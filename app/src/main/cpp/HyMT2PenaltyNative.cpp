#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

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
    int size = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
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
        const llama_vocab *vocab, int32_t context_size,
        float temperature, float top_p, int top_k,
        float repetition_penalty, int seed) {
    llama_sampler *sampler =
        llama_sampler_chain_init(llama_sampler_chain_default_params());

    // Tencent's Hy-MT2 1.8B recommendation: repetition_penalty=1.05.
    // In this llama.cpp API, negative penalty_last_n is clamped to zero
    // (disabled). Pass the actual active context length instead.
    if (repetition_penalty > 1.0f) {
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        const int32_t penalty_last_n = std::max(1, context_size);
        llama_sampler_chain_add(
            sampler, llama_sampler_init_penalties(
                n_vocab, penalty_last_n, repetition_penalty, 0.0f, 0.0f));
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
        vocab, static_cast<int32_t>(llama_n_ctx(context->ctx)),
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
        // Hy-MT2's BOS token is only valid at the beginning of the prompt.
        // If generation emits it again, stop rather than printing the control
        // token (Tencent's reference decoder uses skip_special_tokens=true).
        if (llama_vocab_is_eog(vocab, token) || token == llama_vocab_bos(vocab)) break;
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
} // extern "C"
