// ParaRead Android JNI, original implementation. llama.cpp is MIT, pinned in upstream/llama.lock.json.
#include <jni.h>
#include <llama.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

struct Engine {
    std::string architecture;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    std::atomic<bool> cancelled{false};
    ~Engine() { if (context) llama_free(context); if (model) llama_model_free(model); }
};
static std::once_flag backend_once;
static void fail(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
static bool abort_decode(void * data) { return static_cast<Engine *>(data)->cancelled.load(); }
// Constrain JSON syntax and expected rows; exact placeholder checks remain in Kotlin.
static constexpr const char * translation_grammar = R"GBNF(
root ::= ws "{" ws "\"translations\"" ws ":" ws "[" ws item (ws "," ws item)* ws "]" ws "}"
item ::= "{" ws "\"id\"" ws ":" ws "\"s" [1-9] [0-9]* "\"" ws "," ws "\"translation\"" ws ":" ws string ws "}"
string ::= "\"" char+ "\""
char ::= [^"\\\x00-\x1f] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
)GBNF";
// A controlled single-sentence benchmark cannot accidentally generate additional result rows.
static constexpr const char * single_translation_grammar = R"GBNF(
root ::= ws "{" ws "\"translations\"" ws ":" ws "[" ws "{" ws "\"id\"" ws ":" ws "\"s1\"" ws "," ws "\"translation\"" ws ":" ws string ws "}" ws "]" ws "}"
string ::= "\"" char+ "\""
char ::= [^"\\\x00-\x1f] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= [ \t\n\r]*
)GBNF";
static std::string exact_translation_grammar(int count) {
    std::string rows;
    for (int i = 1; i <= count; ++i) {
        if (i > 1) rows += " ws \",\" ws ";
        rows += "\"{\" ws \"\\\"id\\\"\" ws \":\" ws \"\\\"s" + std::to_string(i) + "\\\"\" ws \",\" ws \"\\\"translation\\\"\" ws \":\" ws string ws \"}\"";
    }
    const std::string common = single_translation_grammar;
    return "root ::= ws \"{\" ws \"\\\"translations\\\"\" ws \":\" ws \"[\" ws " + rows + " ws \"]\" ws \"}\"\n" + common.substr(common.find("string ::="));
}
// Java strings use modified UTF-8; convert through String.getBytes("UTF-8") to preserve non-BMP text.
static std::string utf8(JNIEnv * env, jstring value) {
    auto cls = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    auto length = env->GetArrayLength(bytes);
    std::string result(length, '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
extern "C" JNIEXPORT jlong JNICALL Java_org_pararead_llama_NativeBridge_load(JNIEnv * env, jobject, jstring path, jint threads) {
    try {
        std::call_once(backend_once, [] { llama_backend_init(); });
        auto engine = std::make_unique<Engine>();
        auto mp = llama_model_default_params(); mp.load_mode = LLAMA_LOAD_MODE_MMAP; mp.n_gpu_layers = 0;
        engine->model = llama_model_load_from_file(utf8(env, path).c_str(), mp);
        if (!engine->model) throw std::runtime_error("GGUF_LOAD_FAILED");
        char architecture[128]{};
        llama_model_meta_val_str(engine->model, "general.architecture", architecture, sizeof(architecture));
        engine->architecture = architecture;
        if (engine->architecture != "qwen2" && engine->architecture != "qwen3" && engine->architecture != "qwen35" && engine->architecture != "lfm2")
            throw std::runtime_error("MODEL_TEMPLATE_UNSUPPORTED: supported architectures: qwen2 / qwen3 / qwen35 / lfm2");
        auto cp = llama_context_default_params(); cp.n_ctx = 4096; cp.n_batch = 512; cp.n_ubatch = 128;
        cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.abort_callback = abort_decode; cp.abort_callback_data = engine.get();
        engine->context = llama_init_from_model(engine->model, cp);
        if (!engine->context) throw std::runtime_error("CONTEXT_ALLOCATION_FAILED");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jlongArray JNICALL Java_org_pararead_llama_NativeBridge_generate(JNIEnv * env, jobject, jlong handle, jstring input, jobject callback, jint expected_results, jboolean constrain_json) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    if (!engine) { fail(env, "MODEL_NOT_LOADED"); return nullptr; }
    try {
        engine->cancelled.store(false);
        auto cls = env->GetObjectClass(callback);
        auto emit = env->GetMethodID(cls, "onBytes", "([B)Z");
        auto empty = env->NewByteArray(0);
        bool accepted_start = env->CallBooleanMethod(callback, emit, empty);
        env->DeleteLocalRef(empty);
        if (env->ExceptionCheck()) return nullptr;
        if (!accepted_start) throw std::runtime_error("INFERENCE_CANCELLED");
        const auto prefill_start = std::chrono::steady_clock::now();
        llama_memory_clear(llama_get_memory(engine->context), true);
        auto prompt = utf8(env, input);
        auto vocab = llama_model_get_vocab(engine->model);
        auto count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0 || count > 2400) throw std::runtime_error("PROMPT_EXCEEDS_CONTEXT_BUDGET: reduce batch size");
        std::vector<llama_token> tokens(count);
        llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
        for (int offset = 0; offset < count; offset += 512) {
            if (engine->cancelled.load()) throw std::runtime_error("INFERENCE_CANCELLED");
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(512, count - offset));
            if (llama_decode(engine->context, batch) != 0) throw std::runtime_error("PREFILL_FAILED_OR_CANCELLED");
        }
        const auto prefill_end = std::chrono::steady_clock::now();
        auto sp = llama_sampler_chain_default_params();
        auto * sampler = llama_sampler_chain_init(sp);
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> holder(sampler, llama_sampler_free);
        // Check the proposed token first, as llama.cpp common_sampler does. A full vocabulary
        // grammar scan on every token can cost more than decoding on small models.
        const auto output_grammar = expected_results > 0 && expected_results <= 40 ? exact_translation_grammar(expected_results) : std::string(translation_grammar);
        auto * grammar = constrain_json ? llama_sampler_init_grammar(vocab, output_grammar.c_str(), "root") : nullptr;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> grammar_holder(grammar, llama_sampler_free);
        if (constrain_json && !grammar) throw std::runtime_error("TRANSLATION_GRAMMAR_INIT_FAILED");
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.8f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
        std::vector<char> piece(256);
        std::vector<llama_token_data> candidates(llama_vocab_n_tokens(vocab));
        auto sample = [&]() -> llama_token {
            const auto * logits = llama_get_logits_ith(engine->context, -1);
            auto reset_candidates = [&]() {
                for (size_t i = 0; i < candidates.size(); ++i) candidates[i] = {static_cast<llama_token>(i), logits[i], 0.0f};
            };
            reset_candidates();
            llama_token_data_array current = {candidates.data(), candidates.size(), -1, false};
            llama_sampler_apply(sampler, &current);
            auto token = current.data[current.selected].id;
            if (grammar) {
                llama_token_data proposed = {token, 0.0f, 0.0f};
                llama_token_data_array test = {&proposed, 1, -1, false};
                llama_sampler_apply(grammar, &test);
                if (std::isinf(proposed.logit) && proposed.logit < 0) {
                    reset_candidates();
                    current = {candidates.data(), candidates.size(), -1, false};
                    llama_sampler_apply(grammar, &current);
                    llama_sampler_apply(sampler, &current);
                    token = current.data[current.selected].id;
                }
                llama_sampler_accept(grammar, token);
            }
            llama_sampler_accept(sampler, token);
            return token;
        };
        bool ended = false;
        jlong generated_tokens = 0;
        jlong sampling_ns = 0, decode_ns = 0, callback_ns = 0;
        auto nanos_since = [](const auto & start) { return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count(); };
        const auto generation_start = std::chrono::steady_clock::now();
        for (int generated = 0; generated < 1536; ++generated) {
            if (engine->cancelled.load()) throw std::runtime_error("INFERENCE_CANCELLED");
            const auto sampling_start = std::chrono::steady_clock::now();
            auto token = sample();
            sampling_ns += nanos_since(sampling_start);
            if (llama_vocab_is_eog(vocab, token)) { ended = true; break; }
            ++generated_tokens;
            int n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (n < 0) { piece.resize(-n); n = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false); }
            if (n < 0) throw std::runtime_error("TOKEN_DECODE_FAILED");
            const auto callback_start = std::chrono::steady_clock::now();
            auto bytes = env->NewByteArray(n); env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(piece.data()));
            bool accepted = env->CallBooleanMethod(callback, emit, bytes); env->DeleteLocalRef(bytes);
            callback_ns += nanos_since(callback_start);
            if (env->ExceptionCheck()) return nullptr;
            if (!accepted) throw std::runtime_error("INFERENCE_CANCELLED");
            const auto decode_start = std::chrono::steady_clock::now();
            if (llama_decode(engine->context, llama_batch_get_one(&token, 1)) != 0) throw std::runtime_error("DECODE_FAILED_OR_CANCELLED");
            decode_ns += nanos_since(decode_start);
        }
        env->DeleteLocalRef(cls);
        if (!ended) throw std::runtime_error("TRUNCATED_GENERATION");
        const auto generation_end = std::chrono::steady_clock::now();
        const jlong stats[] = {count, generated_tokens,
            std::chrono::duration_cast<std::chrono::nanoseconds>(prefill_end - prefill_start).count(),
            std::chrono::duration_cast<std::chrono::nanoseconds>(generation_end - generation_start).count(), sampling_ns, decode_ns, callback_ns};
        auto result = env->NewLongArray(7);
        if (result) env->SetLongArrayRegion(result, 0, 7, stats);
        return result;
    } catch (const std::exception & e) { if (!env->ExceptionCheck()) fail(env, e.what()); }
    return nullptr;
}
extern "C" JNIEXPORT jstring JNICALL Java_org_pararead_llama_NativeBridge_architecture(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(reinterpret_cast<Engine *>(handle)->architecture.c_str());
}
extern "C" JNIEXPORT jstring JNICALL Java_org_pararead_llama_NativeBridge_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
// Kernel-only pp/tg benchmark: deterministic valid token IDs, no sampler, grammar or per-token JNI callbacks.
extern "C" JNIEXPORT jdoubleArray JNICALL Java_org_pararead_llama_NativeBridge_benchmark(JNIEnv * env, jobject, jlong handle, jint pp, jint tg, jobject callback) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    try {
        if (!engine || pp < 1 || pp > 2048 || tg < 1 || tg > 512) throw std::runtime_error("INVALID_BENCHMARK_ARGUMENTS");
        engine->cancelled.store(false);
        auto cls = env->GetObjectClass(callback);
        auto emit = env->GetMethodID(cls, "onBytes", "([B)Z");
        auto empty = env->NewByteArray(0);
        const bool accepted = env->CallBooleanMethod(callback, emit, empty);
        env->DeleteLocalRef(empty); env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) return nullptr;
        if (!accepted) throw std::runtime_error("INFERENCE_CANCELLED");
        std::vector<llama_token> prompt(pp);
        const auto vocab = llama_model_get_vocab(engine->model);
        const int n_vocab = llama_vocab_n_tokens(vocab);
        for (int i = 0; i < pp; ++i) prompt[i] = (16 + i % 193) % n_vocab;
        llama_memory_clear(llama_get_memory(engine->context), true);
        if (engine->cancelled.load() || llama_decode(engine->context, llama_batch_get_one(prompt.data(), std::min(16, pp))) != 0)
            throw std::runtime_error("BENCHMARK_CANCELLED_OR_FAILED");
        llama_synchronize(engine->context);
        llama_memory_clear(llama_get_memory(engine->context), true);
        const auto prefill_start = std::chrono::steady_clock::now();
        for (int offset = 0; offset < pp; offset += 512) {
            if (engine->cancelled.load() || llama_decode(engine->context, llama_batch_get_one(prompt.data() + offset, std::min(512, pp - offset))) != 0)
                throw std::runtime_error("BENCHMARK_CANCELLED_OR_FAILED");
        }
        llama_synchronize(engine->context);
        const auto prefill_end = std::chrono::steady_clock::now();
        // TG follows the same fixed-size prompt for all models; synthetic IDs are never shown as translations.
        const auto generation_start = std::chrono::steady_clock::now();
        for (int i = 0; i < tg; ++i) {
            llama_token token = (16 + i % 193) % n_vocab;
            if (engine->cancelled.load() || llama_decode(engine->context, llama_batch_get_one(&token, 1)) != 0)
                throw std::runtime_error("BENCHMARK_CANCELLED_OR_FAILED");
            llama_synchronize(engine->context);
        }
        const auto generation_end = std::chrono::steady_clock::now();
        const double prefill_s = std::chrono::duration<double>(prefill_end - prefill_start).count();
        const double generation_s = std::chrono::duration<double>(generation_end - generation_start).count();
        const jdouble stats[] = {pp / prefill_s, tg / generation_s, prefill_s * 1000, generation_s * 1000};
        auto result = env->NewDoubleArray(4);
        if (result) env->SetDoubleArrayRegion(result, 0, 4, stats);
        return result;
    } catch (const std::exception & e) { if (!env->ExceptionCheck()) fail(env, e.what()); }
    return nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_org_pararead_llama_NativeBridge_cancel(JNIEnv *, jobject, jlong handle) {
    if (handle) reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL Java_org_pararead_llama_NativeBridge_free(JNIEnv *, jobject, jlong handle) { delete reinterpret_cast<Engine *>(handle); }
extern "C" JNIEXPORT jstring JNICALL Java_org_pararead_llama_NativeBridge_version(JNIEnv * env, jobject) { return env->NewStringUTF("llama.cpp@2280f0fd163b03d4331d49e08460f66502e35854;cpu;arm64;ctx4096;proposal-exact-grammar-v3"); }
