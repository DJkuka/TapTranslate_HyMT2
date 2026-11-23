#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "HyMT2PenaltyJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Must match the private handle structure created by the pinned
// llama-android commit 9f5469fd9fa7ea7bc7b01801923af95dc3b41d52.
struct LlamaCtx {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    int n_threads = 4;
};

namespace {
std::atomic<bool> g_cancel_requested{false};

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

std::vector<llama_token> tokenize(
        const llama_vocab *vocab, const std::string &text, bool add_special) {
    // Match the verified standalone llama-android JNI path exactly.
    int count = -llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        nullptr, 0, add_special, true);
    if (count <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    int written = llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()),
        tokens.data(), static_cast<int32_t>(tokens.size()), add_special, true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string token_piece(const llama_vocab *vocab, llama_token token) {
    char buffer[512];
    // The standalone test uses special=true; keep this identical so token
    // pieces are decoded by llama.cpp in the same way as the known-good path.
    int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
    if (size <= 0) return {};
    return std::string(buffer, static_cast<size_t>(size));
}

// Apply the model's GGUF-embedded chat template exactly as Llama.complete()
// does in the standalone test. Do not hand-assemble Hy-MT2 role markers.
std::string build_prompt(
        const llama_model *model,
        const std::string &system,
        const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (!tmpl) {
        return system.empty() ? user : (system + "\n\n" + user);
    }

    std::vector<llama_chat_message> messages;
    if (!system.empty()) {
        messages.push_back({"system", system.c_str()});
    }
    messages.push_back({"user", user.c_str()});

    std::vector<char> buffer(user.size() + system.size() + 2048);
    int32_t written = llama_chat_apply_template(
        tmpl, messages.data(), messages.size(), true,
        buffer.data(), static_cast<int32_t>(buffer.size()));
    if (written > static_cast<int32_t>(buffer.size())) {
        buffer.resize(static_cast<size_t>(written));
        written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), true,
            buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (written < 0) {
        return system.empty() ? user : (system + "\n\n" + user);
    }
    return std::string(buffer.data(), static_cast<size_t>(written));
}

llama_sampler *make_sampler(
        const llama_vocab *vocab, int32_t context_size,
        float temperature, float top_p, int top_k,
        float repetition_penalty, int seed) {
    llama_sampler *sampler =
        llama_sampler_chain_init(llama_sampler_chain_default_params());

    if (repetition_penalty > 1.0f) {
        const int32_t penalty_last_n = std::max(1, context_size);
        llama_sampler_chain_add(
            sampler, llama_sampler_init_penalties(
                penalty_last_n, repetition_penalty, 0.0f, 0.0f));
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

extern "C" {

JNIEXPORT jint JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeCountPromptTokens(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jstring system) {
    LlamaCtx *context = get_context(handle);
    if (!context) return -1;
    const std::string full_prompt = build_prompt(
        context->model, from_jstring(env, system), from_jstring(env, prompt));
    const llama_vocab *vocab = llama_model_get_vocab(context->model);
    // Match standalone LlamaJNI::nativeComplete: apply template, then add BOS.
    const auto tokens = tokenize(vocab, full_prompt, true);
    if (tokens.empty()) return -1;
    return static_cast<jint>(tokens.size());
}

JNIEXPORT void JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeResetCancellation(
        JNIEnv *, jobject, jlong handle) {
    if (get_context(handle)) {
        g_cancel_requested.store(false, std::memory_order_release);
    }
}

JNIEXPORT void JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeCancel(
        JNIEnv *, jobject, jlong handle) {
    if (get_context(handle)) {
        g_cancel_requested.store(true, std::memory_order_release);
    }
}

JNIEXPORT jstring JNICALL
Java_com_tap_1to_1translate_snap_1translate_hymt2_HyMT2PenaltyNative_nativeComplete(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jstring system,
        jint max_tokens, jfloat temperature, jfloat top_p, jint top_k,
        jfloat repetition_penalty, jint seed) {
    LlamaCtx *context = get_context(handle);
    if (!context) return env->NewStringUTF("{\"text\":\"\",\"error\":\"model handle is invalid\"}");
    if (max_tokens <= 0) return env->NewStringUTF("{\"text\":\"\",\"error\":\"max_tokens must be positive\"}");
    if (g_cancel_requested.load(std::memory_order_acquire)) {
        return env->NewStringUTF("{\"text\":\"\",\"cancelled\":true}");
    }

    const llama_vocab *vocab = llama_model_get_vocab(context->model);
    const llama_token bos_token = llama_vocab_bos(vocab);
    const llama_token eos_token = llama_vocab_eos(vocab);
    const llama_token eot_token = llama_vocab_eot(vocab);
    const std::string full_prompt = build_prompt(
        context->model, from_jstring(env, system), from_jstring(env, prompt));
    // Match standalone LlamaJNI::nativeComplete: apply template, then add BOS.
    const auto tokens = tokenize(vocab, full_prompt, true);
    if (tokens.empty()) return env->NewStringUTF("{\"text\":\"\",\"error\":\"prompt tokenization failed\"}");

    llama_memory_clear(llama_get_memory(context->ctx), true);
    llama_sampler *sampler = make_sampler(
        vocab, static_cast<int32_t>(llama_n_ctx(context->ctx)),
        temperature, top_p, top_k, repetition_penalty, seed);
    if (!sampler) return env->NewStringUTF("{\"text\":\"\",\"error\":\"sampler initialization failed\"}");

    const int64_t start_us = llama_time_us();
    LOGI("NATIVE_ENTER max_tokens=%d prompt_tokens=%zu prompt_bytes=%zu ctx=%u temp=%.3f top_p=%.3f top_k=%d rep_penalty=%.3f seed=%d",
         max_tokens, tokens.size(), full_prompt.size(), llama_n_ctx(context->ctx),
         temperature, top_p, top_k, repetition_penalty, seed);
    LOGI("VOCAB_SPECIALS bos=%d eos=%d eot=%d; is_eog(bos)=%d is_eog(eos)=%d is_eog(eot)=%d",
         bos_token, eos_token, eot_token,
         llama_vocab_is_eog(vocab, bos_token) ? 1 : 0,
         llama_vocab_is_eog(vocab, eos_token) ? 1 : 0,
         llama_vocab_is_eog(vocab, eot_token) ? 1 : 0);

    llama_batch batch = llama_batch_get_one(
        const_cast<llama_token *>(tokens.data()), static_cast<int32_t>(tokens.size()));
    const int64_t prompt_decode_start_us = llama_time_us();
    LOGI("PROMPT_DECODE_START tokens=%zu", tokens.size());
    const int prompt_decode_result = llama_decode(context->ctx, batch);
    const int64_t prompt_end_us = llama_time_us();
    if (prompt_decode_result != 0) {
        LOGE("PROMPT_DECODE_FAILED rc=%d elapsed_ms=%lld",
             prompt_decode_result,
             static_cast<long long>((prompt_end_us - prompt_decode_start_us) / 1000));
        llama_sampler_free(sampler);
        return env->NewStringUTF(R"json({"text":"","error":"prompt decode failed; prompt may exceed context"})json");
    }
    LOGI("PROMPT_DECODE_DONE elapsed_ms=%lld",
         static_cast<long long>((prompt_end_us - prompt_decode_start_us) / 1000));
    if (g_cancel_requested.load(std::memory_order_acquire)) {
        llama_sampler_free(sampler);
        LOGW("GENERATION_CANCELLED during prompt evaluation");
        return env->NewStringUTF("{\"text\":\"\",\"cancelled\":true}");
    }

    std::string output;
    int generated = 0;
    bool cancelled = false;
    int skipped_bos = 0;
    int consecutive_bos = 0;
    bool stopped_on_eog = false;
    bool stopped_on_bos = false;
    for (; generated < max_tokens; ++generated) {
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        const bool trace_token = generated < 4 || ((generated + 1) % 8 == 0);
        const int64_t sample_start_us = llama_time_us();
        if (trace_token) LOGI("SAMPLE_START index=%d", generated + 1);
        const llama_token token = llama_sampler_sample(sampler, context->ctx, -1);
        const int64_t sample_end_us = llama_time_us();
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }
        if (trace_token) {
            LOGI("SAMPLE_DONE index=%d token=%d elapsed_ms=%lld",
                 generated + 1, token,
                 static_cast<long long>((sample_end_us - sample_start_us) / 1000));
        }
        if (llama_vocab_is_eog(vocab, token) ||
            token == eos_token || token == eot_token) {
            stopped_on_eog = true;
            LOGI("GENERATION_EOG index=%d token=%d", generated + 1, token);
            break;
        }
        // A generated BOS is not user-visible text and is not an end marker.
        // Feed it back into the context, but omit its printable special-token
        // spelling. Stop only if the model emits a run of four BOS tokens.
        if (token == bos_token) {
            ++skipped_bos;
            ++consecutive_bos;
            LOGW("GENERATION_BOS_SKIP index=%d token=%d consecutive=%d output_bytes=%zu",
                 generated + 1, token, consecutive_bos, output.size());
            if (consecutive_bos >= 4) {
                stopped_on_bos = true;
                LOGW("GENERATION_BOS_STREAK_ABORT index=%d consecutive=%d",
                     generated + 1, consecutive_bos);
                break;
            }
        } else {
            consecutive_bos = 0;
        }

        llama_sampler_accept(sampler, token);
        const std::string piece = token == bos_token ? std::string() : token_piece(vocab, token);
        output += piece;
        if (g_cancel_requested.load(std::memory_order_acquire)) {
            cancelled = true;
            break;
        }

        llama_batch next = llama_batch_get_one(
            const_cast<llama_token *>(&token), 1);
        const int64_t token_decode_start_us = llama_time_us();
        if (trace_token) LOGI("TOKEN_DECODE_START index=%d token=%d", generated + 1, token);
        const int token_decode_result = llama_decode(context->ctx, next);
        const int64_t token_decode_end_us = llama_time_us();
        if (trace_token) {
            LOGI("TOKEN_DECODE_DONE index=%d rc=%d elapsed_ms=%lld piece_bytes=%zu output_bytes=%zu",
                 generated + 1, token_decode_result,
                 static_cast<long long>((token_decode_end_us - token_decode_start_us) / 1000),
                 piece.size(), output.size());
        }
        if (token_decode_result != 0) {
            LOGE("TOKEN_DECODE_FAILED index=%d rc=%d", generated + 1, token_decode_result);
            ++generated; // Count the token already sampled and appended.
            break;
        }
    }
    const int64_t end_us = llama_time_us();
    if (g_cancel_requested.load(std::memory_order_acquire)) cancelled = true;
    llama_sampler_free(sampler);
    if (cancelled) {
        LOGW("GENERATION_CANCELLED generated=%d max_tokens=%d elapsed_ms=%lld",
             generated, max_tokens,
             static_cast<long long>((end_us - prompt_end_us) / 1000));
        return env->NewStringUTF("{\"text\":\"\",\"cancelled\":true}");
    }
    LOGI("GENERATION_DONE generated=%d max_tokens=%d eog=%d bos_skipped=%d bos_streak_abort=%d output_bytes=%zu prompt_ms=%lld generation_ms=%lld",
         generated, max_tokens, stopped_on_eog ? 1 : 0, skipped_bos, stopped_on_bos ? 1 : 0, output.size(),
         static_cast<long long>((prompt_end_us - start_us) / 1000),
         static_cast<long long>((end_us - prompt_end_us) / 1000));

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
} // extern "C"
