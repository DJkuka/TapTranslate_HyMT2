#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>
#include <sstream>
#include <mutex>
#include <unordered_map>
#include <fstream>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "ggml-cpu.h"

#include "llama.h"

#define LOG_TAG "HyMT2PenaltyJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Must match the private handle structure created by the pinned
// llama-android commit 9f5469fd9fa7ea7bc7b01801923af95dc3b41d52.
struct LlamaCtx {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_threads = 4;
};

namespace {
std::string from_jstring(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string json_escape(const std::string &input) {
    std::string output;
    output.reserve(input.size() + 8);
    for (char c : input) {
        switch (c) {
            case '"': output += "\\\""; break;
            case '\\': output += "\\\\"; break;
            case '\n': output += "\\n"; break;
            case '\r': output += "\\r"; break;
            case '\t': output += "\\t"; break;
            default: output += c; break;
        }
    }
    return output;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    int count = -llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        nullptr, 0, true, true);
    if (count <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    int written = llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string token_piece(const llama_vocab *vocab, llama_token token) {
    char buffer[512];
    int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (size <= 0) return {};
    return std::string(buffer, static_cast<size_t>(size));
}

// Match the template path used by upstream Llama.complete(). The official
// Tencent instruction is passed as a single user message; no system message
// is added for Hy-MT2.
std::string make_prompt(
        const llama_model *model,
        const std::string &system,
        const std::string &user) {
    const char *chat_template = llama_model_chat_template(model, nullptr);
    if (!chat_template) return system.empty() ? user : system + "\n\n" + user;

    std::vector<llama_chat_message> messages;
    if (!system.empty()) messages.push_back({"system", system.c_str()});
    messages.push_back({"user", user.c_str()});

    std::vector<char> buffer(user.size() + system.size() + 2048);
    int32_t size = llama_chat_apply_template(
        chat_template, messages.data(), messages.size(), true,
        buffer.data(), static_cast<int32_t>(buffer.size()));
    if (size > static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(size));
        size = llama_chat_apply_template(
            chat_template, messages.data(), messages.size(), true,
            buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (size < 0) return system.empty() ? user : system + "\n\n" + user;
    return std::string(buffer.data(), static_cast<size_t>(size));
}

llama_sampler *make_sampler(
        float temperature, float top_p, int top_k,
        float repetition_penalty, int seed) {
    llama_sampler *sampler =
        llama_sampler_chain_init(llama_sampler_chain_default_params());

    // Tencent's Hy-MT2 1.8B recommendation: repetition_penalty=1.05.
    // -1 applies the penalty to the current context window.
    if (repetition_penalty > 1.0f) {
        llama_sampler_chain_add(
            sampler, llama_sampler_init_penalties(-1, repetition_penalty, 0.0f, 0.0f));
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::max(1, top_k)));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(
            sampler, llama_sampler_init_dist(
                seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }
    return sampler;
}

LlamaCtx *get_context(jlong handle) {
    auto *context = reinterpret_cast<LlamaCtx *>(handle);
    if (!context || !context->model || !context->ctx) return nullptr;
    return context;
}

} // namespace

namespace {
struct CpuAffinityPool {
    ggml_threadpool_t pool = nullptr;
    uint64_t mask = 0;
    int threads = 0;
};

struct SavedCallerAffinity {
    pid_t tid = -1;
    cpu_set_t mask{};
};

std::mutex g_cpu_affinity_mutex;
std::unordered_map<LlamaCtx *, CpuAffinityPool> g_cpu_affinity_pools;
std::unordered_map<LlamaCtx *, SavedCallerAffinity> g_saved_caller_affinities;

pid_t current_tid() {
    return static_cast<pid_t>(syscall(__NR_gettid));
}

long read_cpu_value(const std::string &path) {
    std::ifstream input(path);
    long value = 0;
    if (input.good()) input >> value;
    return value;
}

bool is_cpu_online(int id) {
    if (id == 0) return access("/sys/devices/system/cpu/cpu0", F_OK) == 0;
    const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(id);
    if (access(base.c_str(), F_OK) != 0) return false;
    std::ifstream input(base + "/online");
    if (!input.good()) return true;
    int online = 0;
    input >> online;
    return online == 1;
}

uint64_t cpu_set_to_mask(const cpu_set_t &set) {
    uint64_t mask = 0;
    for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
        if (CPU_ISSET(cpu, &set)) mask |= (1ULL << cpu);
    }
    return mask;
}

std::string mask_to_hex(uint64_t mask) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "0x%llx",
                  static_cast<unsigned long long>(mask));
    return buffer;
}

uint64_t online_cpu_mask() {
    const long configured = sysconf(_SC_NPROCESSORS_CONF);
    const int count = static_cast<int>(
        std::max(1L, std::min(64L, configured > 0 ? configured : 1L)));
    uint64_t mask = 0;
    for (int id = 0; id < count; ++id) {
        if (is_cpu_online(id)) mask |= (1ULL << id);
    }
    return mask;
}

std::string cpu_topology_json() {
    const long configured = sysconf(_SC_NPROCESSORS_CONF);
    const int count = static_cast<int>(
        std::max(1L, std::min(64L, configured > 0 ? configured : 1L)));

    cpu_set_t allowed_set;
    CPU_ZERO(&allowed_set);
    const bool have_allowed =
        sched_getaffinity(0, sizeof(allowed_set), &allowed_set) == 0;

    struct CpuInfo {
        int id;
        bool online;
        bool allowed;
        long max_freq;
        long capacity;
    };

    std::vector<CpuInfo> cpus;
    cpus.reserve(static_cast<size_t>(count));
    long max_freq = 0;
    long max_capacity = 0;

    for (int id = 0; id < count; ++id) {
        const std::string base =
            "/sys/devices/system/cpu/cpu" + std::to_string(id) + "/";
        long freq = read_cpu_value(base + "cpufreq/cpuinfo_max_freq");
        if (freq <= 0) {
            freq = read_cpu_value(base + "cpufreq/scaling_max_freq");
        }
        const long capacity = read_cpu_value(base + "cpu_capacity");
        const bool online = is_cpu_online(id);
        const bool allowed = online && (!have_allowed || CPU_ISSET(id, &allowed_set));
        cpus.push_back({id, online, allowed, freq, capacity});
        if (online) {
            max_freq = std::max(max_freq, freq);
            max_capacity = std::max(max_capacity, capacity);
        }
    }

    const char *classification_source =
        max_capacity > 0 ? "cpu_capacity" : (max_freq > 0 ? "max_frequency" : "unknown");

    std::ostringstream out;
    out << "{\"affinitySupported\":" << (have_allowed ? "true" : "false")
        << ",\"classificationSource\":\"" << classification_source
        << "\",\"onlineMask\":\"" << mask_to_hex(online_cpu_mask())
        << "\",\"cpus\":[";
    for (size_t i = 0; i < cpus.size(); ++i) {
        const CpuInfo &cpu = cpus[i];
        if (i != 0) out << ",";
        const bool performance = cpu.online && (
            max_capacity > 0 ? cpu.capacity == max_capacity :
            (max_freq > 0 && cpu.max_freq == max_freq));
        out << "{\"id\":" << cpu.id
            << ",\"online\":" << (cpu.online ? "true" : "false")
            << ",\"allowed\":" << (cpu.allowed ? "true" : "false")
            << ",\"maxFreqKhz\":" << cpu.max_freq
            << ",\"capacity\":" << cpu.capacity
            << ",\"performance\":" << (performance ? "true" : "false")
            << "}";
    }
    out << "]}";
    return out.str();
}

void restore_saved_caller_locked(LlamaCtx *context, std::ostringstream *report) {
    auto saved = g_saved_caller_affinities.find(context);
    if (saved == g_saved_caller_affinities.end()) return;

    errno = 0;
    const int rc = sched_setaffinity(
        saved->second.tid, sizeof(saved->second.mask), &saved->second.mask);
    const int saved_errno = rc == 0 ? 0 : errno;
    if (report) {
        *report << ",\"callerRestoreRc\":" << rc
                << ",\"callerRestoreErrno\":" << saved_errno;
    }
    g_saved_caller_affinities.erase(saved);
}

std::string begin_cpu_affinity(
        LlamaCtx *context, uint64_t requested_mask, int requested_threads) {
    if (!context) return "{\"ok\":false,\"error\":\"invalid model handle\"}";

    const int threads = std::max(1, std::min(8, requested_threads));

    if (requested_mask == 0) {
        std::lock_guard<std::mutex> lock(g_cpu_affinity_mutex);
        auto existing = g_cpu_affinity_pools.find(context);
        if (existing != g_cpu_affinity_pools.end()) {
            llama_detach_threadpool(context->ctx);
            ggml_threadpool_free(existing->second.pool);
            g_cpu_affinity_pools.erase(existing);
        }
        std::ostringstream out;
        out << "{\"ok\":true,\"mode\":\"system\",\"requestedMask\":\"system\","
            << "\"effectiveMask\":\"system\",\"threads\":" << threads
            << ",\"pool\":\"default\"";
        restore_saved_caller_locked(context, &out);
        out << "}";
        return out.str();
    }

    cpu_set_t caller_allowed;
    CPU_ZERO(&caller_allowed);
    if (sched_getaffinity(0, sizeof(caller_allowed), &caller_allowed) != 0) {
        const int error = errno;
        return "{\"ok\":false,\"error\":\"sched_getaffinity failed\",\"errno\":" +
               std::to_string(error) + "}";
    }

    const uint64_t allowed_mask = cpu_set_to_mask(caller_allowed);
    const uint64_t online_mask = online_cpu_mask();
    const uint64_t effective_mask = requested_mask & allowed_mask & online_mask;
    if (effective_mask == 0) {
        return "{\"ok\":false,\"error\":\"no requested CPU is both online and allowed\","
               "\"requestedMask\":\"" + mask_to_hex(requested_mask) +
               "\",\"allowedMask\":\"" + mask_to_hex(allowed_mask) +
               "\",\"onlineMask\":\"" + mask_to_hex(online_mask) + "\"}";
    }

    std::lock_guard<std::mutex> lock(g_cpu_affinity_mutex);

    if (g_saved_caller_affinities.find(context) == g_saved_caller_affinities.end()) {
        SavedCallerAffinity saved;
        saved.tid = current_tid();
        saved.mask = caller_allowed;
        g_saved_caller_affinities.emplace(context, saved);
    }

    auto existing = g_cpu_affinity_pools.find(context);
    if (existing != g_cpu_affinity_pools.end() &&
        existing->second.mask == effective_mask &&
        existing->second.threads == threads) {
        std::ostringstream out;
        out << "{\"ok\":true,\"mode\":\"custom\",\"requestedMask\":\""
            << mask_to_hex(requested_mask) << "\",\"effectiveMask\":\""
            << mask_to_hex(effective_mask) << "\",\"threads\":" << threads
            << ",\"pool\":\"reused\"}";
        return out.str();
    }

    if (existing != g_cpu_affinity_pools.end()) {
        llama_detach_threadpool(context->ctx);
        ggml_threadpool_free(existing->second.pool);
        g_cpu_affinity_pools.erase(existing);
    }

    ggml_threadpool_params params = ggml_threadpool_params_default(threads);
    params.paused = true;
    params.strict_cpu = false;
    for (int cpu = 0; cpu < GGML_MAX_N_THREADS; ++cpu) {
        params.cpumask[cpu] =
            cpu < 64 && (effective_mask & (1ULL << cpu)) != 0;
    }

    ggml_threadpool_t pool = ggml_threadpool_new(&params);
    if (!pool) {
        restore_saved_caller_locked(context, nullptr);
        return "{\"ok\":false,\"error\":\"ggml_threadpool_new returned null\"}";
    }

    llama_attach_threadpool(context->ctx, pool, pool);
    g_cpu_affinity_pools.emplace(
        context, CpuAffinityPool{pool, effective_mask, threads});

    std::ostringstream out;
    out << "{\"ok\":true,\"mode\":\"custom\",\"requestedMask\":\""
        << mask_to_hex(requested_mask) << "\",\"effectiveMask\":\""
        << mask_to_hex(effective_mask) << "\",\"threads\":" << threads
        << ",\"pool\":\"created\",\"strictCpu\":false}";
    return out.str();
}

std::string end_cpu_affinity(LlamaCtx *context) {
    if (!context) return "{\"ok\":false,\"error\":\"invalid model handle\"}";
    std::lock_guard<std::mutex> lock(g_cpu_affinity_mutex);
    auto existing = g_cpu_affinity_pools.find(context);
    if (existing != g_cpu_affinity_pools.end()) {
        ggml_threadpool_pause(existing->second.pool);
    }
    std::ostringstream out;
    out << "{\"ok\":true,\"poolPaused\":"
        << (existing != g_cpu_affinity_pools.end() ? "true" : "false");
    restore_saved_caller_locked(context, &out);
    out << "}";
    return out.str();
}

std::string release_cpu_affinity(LlamaCtx *context) {
    if (!context) return "{\"ok\":false,\"error\":\"invalid model handle\"}";
    std::lock_guard<std::mutex> lock(g_cpu_affinity_mutex);
    auto existing = g_cpu_affinity_pools.find(context);
    if (existing != g_cpu_affinity_pools.end()) {
        llama_detach_threadpool(context->ctx);
        ggml_threadpool_free(existing->second.pool);
        g_cpu_affinity_pools.erase(existing);
    }
    std::ostringstream out;
    out << "{\"ok\":true,\"poolReleased\":true";
    restore_saved_caller_locked(context, &out);
    out << "}";
    return out.str();
}
} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeCountPromptTokens(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jstring system) {
    LlamaCtx *context = get_context(handle);
    if (!context) return -1;
    const std::string full_prompt = make_prompt(
        context->model, from_jstring(env, system), from_jstring(env, prompt));
    const llama_vocab *vocab = llama_model_get_vocab(context->model);
    const auto tokens = tokenize(vocab, full_prompt);
    if (tokens.empty()) return -1;
    return static_cast<jint>(tokens.size());
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeComplete(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jstring system,
        jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
        jfloat repetition_penalty, jint seed) {
    LlamaCtx *context = get_context(handle);
    if (!context) return env->NewStringUTF("{\"text\":\"\",\"error\":\"model handle is invalid\"}");
    if (max_tokens <= 0) return env->NewStringUTF("{\"text\":\"\",\"error\":\"max_tokens must be positive\"}");

    const llama_vocab *vocab = llama_model_get_vocab(context->model);
    const std::string full_prompt = make_prompt(
        context->model, from_jstring(env, system), from_jstring(env, prompt));
    const auto tokens = tokenize(vocab, full_prompt);
    if (tokens.empty()) return env->NewStringUTF("{\"text\":\"\",\"error\":\"prompt tokenization failed\"}");

    llama_memory_clear(llama_get_memory(context->ctx), true);
    llama_sampler *sampler = make_sampler(
        temperature, top_p, top_k, repetition_penalty, seed);
    if (!sampler) return env->NewStringUTF("{\"text\":\"\",\"error\":\"sampler initialization failed\"}");

    const int64_t start_us = llama_time_us();
    llama_batch batch = llama_batch_get_one(
        const_cast<llama_token *>(tokens.data()), static_cast<int32_t>(tokens.size()));
    if (llama_decode(context->ctx, batch) != 0) {
        llama_sampler_free(sampler);
        return env->NewStringUTF("{\"text\":\"\",\"error\":\"prompt decode failed; prompt may exceed context\"}");
    }
    const int64_t prompt_end_us = llama_time_us();

    std::string output;
    int generated = 0;
    for (; generated < max_tokens; ++generated) {
        const llama_token token = llama_sampler_sample(sampler, context->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        llama_sampler_accept(sampler, token);
        output += token_piece(vocab, token);
        llama_batch next = llama_batch_get_one(
            const_cast<llama_token *>(&token), 1);
        if (llama_decode(context->ctx, next) != 0) break;
    }
    const int64_t end_us = llama_time_us();
    llama_sampler_free(sampler);

    const long prompt_ms = static_cast<long>((prompt_end_us - start_us) / 1000);
    const long generation_ms = static_cast<long>((end_us - prompt_end_us) / 1000);
    const float tokens_per_second = generation_ms > 0
        ? (generated * 1000.0f / generation_ms) : 0.0f;

    const std::string json =
        "{\"text\":\"" + json_escape(output) +
        "\",\"tokensGenerated\":" + std::to_string(generated) +
        ",\"promptTokens\":" + std::to_string(tokens.size()) +
        ",\"tokensPerSecond\":" + std::to_string(tokens_per_second) +
        ",\"promptEvalTimeMs\":" + std::to_string(prompt_ms) +
        ",\"generateTimeMs\":" + std::to_string(generation_ms) + "}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeCpuTopologyJson(
        JNIEnv *env, jobject) {
    const std::string json = cpu_topology_json();
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeSetCpuAffinity(
        JNIEnv *env, jobject, jlong handle, jlong requested_mask, jint threads) {
    LlamaCtx *context = get_context(handle);
    const std::string json = begin_cpu_affinity(
        context, static_cast<uint64_t>(requested_mask), static_cast<int>(threads));
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeEndCpuAffinity(
        JNIEnv *env, jobject, jlong handle) {
    const std::string json = end_cpu_affinity(get_context(handle));
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeReleaseCpuAffinity(
        JNIEnv *env, jobject, jlong handle) {
    const std::string json = release_cpu_affinity(get_context(handle));
    return env->NewStringUTF(json.c_str());
}

} // extern "C"
