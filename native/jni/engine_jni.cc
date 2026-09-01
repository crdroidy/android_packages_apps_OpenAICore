/*
 * Copyright (C) 2026 The crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// JNI over the OpenAICore engine ABI. The engine implementation is dlopened rather than linked:
// it is a prebuilt built out-of-tree against LiteRT-LM, and a crDroid build that ships without it
// must still boot with the feature reported unavailable.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstring>
#include <mutex>
#include <string>

#include "openaicore_engine.h"

#define LOG_TAG "OpenAICore.jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kEngineSoname = "libopenaicore_litertlm.so";

struct EngineApi {
  int32_t (*abi_version)(void) = nullptr;
  OpenAiCoreStatus (*engine_create)(const OpenAiCoreEngineConfig*, OpenAiCoreEngine**) = nullptr;
  void (*engine_destroy)(OpenAiCoreEngine*) = nullptr;
  OpenAiCoreBackend (*engine_active_backend)(const OpenAiCoreEngine*) = nullptr;
  OpenAiCoreStatus (*engine_count_tokens)(OpenAiCoreEngine*, const char*, int64_t*) = nullptr;
  OpenAiCoreStatus (*session_create)(OpenAiCoreEngine*, const OpenAiCoreSessionConfig*,
                                     OpenAiCoreSession**) = nullptr;
  void (*session_destroy)(OpenAiCoreSession*) = nullptr;
  OpenAiCoreStatus (*session_generate)(OpenAiCoreSession*, const char*, OpenAiCoreTokenCallback,
                                       void*) = nullptr;
  OpenAiCoreStatus (*session_add_image)(OpenAiCoreSession*, const uint8_t*, int32_t,
                                        int32_t) = nullptr;
  OpenAiCoreStatus (*session_add_audio)(OpenAiCoreSession*, const int16_t*, size_t,
                                        int32_t) = nullptr;
  void (*session_cancel)(OpenAiCoreSession*) = nullptr;
  const char* (*last_error)(void) = nullptr;
  bool loaded = false;
};

std::once_flag g_load_once;
EngineApi g_api;

template <typename Fn>
bool Resolve(void* lib, const char* symbol, Fn* out) {
  *out = reinterpret_cast<Fn>(dlsym(lib, symbol));
  if (*out == nullptr) {
    LOGE("engine library is missing %s", symbol);
    return false;
  }
  return true;
}

void LoadEngineApi() {
  void* lib = dlopen(kEngineSoname, RTLD_NOW | RTLD_LOCAL);
  if (lib == nullptr) {
    LOGI("%s not present; local inference is unavailable on this build", kEngineSoname);
    return;
  }
  EngineApi api;
  const bool ok =
      Resolve(lib, "OpenAiCoreEngineAbiVersion", &api.abi_version) &&
      Resolve(lib, "OpenAiCoreEngineCreate", &api.engine_create) &&
      Resolve(lib, "OpenAiCoreEngineDestroy", &api.engine_destroy) &&
      Resolve(lib, "OpenAiCoreEngineActiveBackend", &api.engine_active_backend) &&
      Resolve(lib, "OpenAiCoreEngineCountTokens", &api.engine_count_tokens) &&
      Resolve(lib, "OpenAiCoreSessionCreate", &api.session_create) &&
      Resolve(lib, "OpenAiCoreSessionDestroy", &api.session_destroy) &&
      Resolve(lib, "OpenAiCoreSessionGenerateText", &api.session_generate) &&
      Resolve(lib, "OpenAiCoreSessionAddImage", &api.session_add_image) &&
      Resolve(lib, "OpenAiCoreSessionAddAudio", &api.session_add_audio) &&
      Resolve(lib, "OpenAiCoreSessionCancel", &api.session_cancel) &&
      Resolve(lib, "OpenAiCoreLastErrorMessage", &api.last_error);
  if (!ok) {
    dlclose(lib);
    return;
  }
  const int32_t abi = api.abi_version();
  if (abi != OPENAICORE_ENGINE_ABI_VERSION) {
    LOGE("engine ABI mismatch: library reports %d, expected %d", abi,
         OPENAICORE_ENGINE_ABI_VERSION);
    dlclose(lib);
    return;
  }
  api.loaded = true;
  g_api = api;
  // The handle is deliberately leaked: the engine stays mapped for the life of the process, and
  // unloading it under a live session would be worse than the leak.
}

const EngineApi& Api() {
  std::call_once(g_load_once, LoadEngineApi);
  return g_api;
}

// Bridges engine-thread token callbacks back into Java. The engine calls us on its own thread,
// which is already attached because generate() is invoked from the JNI call itself.
struct CallbackContext {
  JNIEnv* env;
  jobject sink;
  jmethodID on_chunk;
  bool cancelled = false;
};

int OnToken(const char* chunk, size_t length, void* user_data) {
  auto* ctx = static_cast<CallbackContext*>(user_data);
  if (ctx->cancelled) {
    return 1;
  }
  // The chunk is not NUL-terminated in the general case.
  std::string owned(chunk, length);
  jstring jchunk = ctx->env->NewStringUTF(owned.c_str());
  if (jchunk == nullptr) {
    return 1;
  }
  const jboolean keep_going = ctx->env->CallBooleanMethod(ctx->sink, ctx->on_chunk, jchunk);
  ctx->env->DeleteLocalRef(jchunk);
  if (ctx->env->ExceptionCheck()) {
    ctx->env->ExceptionClear();
    ctx->cancelled = true;
    return 1;
  }
  if (keep_going == JNI_FALSE) {
    ctx->cancelled = true;
    return 1;
  }
  return 0;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeIsAvailable(JNIEnv*, jclass) {
  return Api().loaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeCreateEngine(
    JNIEnv*, jclass, jint model_fd, jint adapter_fd, jint backend, jint context_tokens,
    jint cpu_threads, jboolean speculative, jboolean vision, jboolean audio) {
  const EngineApi& api = Api();
  if (!api.loaded) {
    return 0;
  }
  OpenAiCoreEngineConfig config = {};
  config.model_fd = model_fd;
  config.adapter_fd = adapter_fd;
  config.backend = static_cast<OpenAiCoreBackend>(backend);
  config.context_tokens = context_tokens;
  config.cpu_threads = cpu_threads;
  config.enable_speculative_decoding = speculative == JNI_TRUE ? 1 : 0;
  config.enable_vision = vision == JNI_TRUE ? 1 : 0;
  config.enable_audio = audio == JNI_TRUE ? 1 : 0;

  OpenAiCoreEngine* engine = nullptr;
  const OpenAiCoreStatus status = api.engine_create(&config, &engine);
  if (status != OPENAICORE_OK || engine == nullptr) {
    LOGE("engine creation failed: status=%d detail=%s", status, api.last_error());
    return 0;
  }
  return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeDestroyEngine(JNIEnv*, jclass,
                                                                                jlong handle) {
  const EngineApi& api = Api();
  if (api.loaded && handle != 0) {
    api.engine_destroy(reinterpret_cast<OpenAiCoreEngine*>(handle));
  }
}

JNIEXPORT jint JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeActiveBackend(JNIEnv*, jclass,
                                                                                jlong handle) {
  const EngineApi& api = Api();
  if (!api.loaded || handle == 0) {
    return OPENAICORE_BACKEND_CPU;
  }
  return api.engine_active_backend(reinterpret_cast<OpenAiCoreEngine*>(handle));
}

JNIEXPORT jlong JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeCountTokens(JNIEnv* env, jclass,
                                                                              jlong handle,
                                                                              jstring text) {
  const EngineApi& api = Api();
  if (!api.loaded || handle == 0 || text == nullptr) {
    return -1;
  }
  const char* utf8 = env->GetStringUTFChars(text, nullptr);
  int64_t count = -1;
  const OpenAiCoreStatus status =
      api.engine_count_tokens(reinterpret_cast<OpenAiCoreEngine*>(handle), utf8, &count);
  env->ReleaseStringUTFChars(text, utf8);
  return status == OPENAICORE_OK ? count : -1;
}

JNIEXPORT jlong JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeCreateSession(
    JNIEnv* env, jclass, jlong engine_handle, jstring system_prompt, jfloat temperature,
    jfloat top_p, jint top_k, jint max_output_tokens) {
  const EngineApi& api = Api();
  if (!api.loaded || engine_handle == 0) {
    return 0;
  }
  const char* prompt = system_prompt == nullptr
                           ? nullptr
                           : env->GetStringUTFChars(system_prompt, nullptr);
  OpenAiCoreSessionConfig config = {};
  config.temperature = temperature;
  config.top_p = top_p;
  config.top_k = top_k;
  config.max_output_tokens = max_output_tokens;
  config.system_prompt = prompt;

  OpenAiCoreSession* session = nullptr;
  const OpenAiCoreStatus status =
      api.session_create(reinterpret_cast<OpenAiCoreEngine*>(engine_handle), &config, &session);
  if (prompt != nullptr) {
    env->ReleaseStringUTFChars(system_prompt, prompt);
  }
  if (status != OPENAICORE_OK || session == nullptr) {
    LOGE("session creation failed: status=%d", status);
    return 0;
  }
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeDestroySession(JNIEnv*, jclass,
                                                                                 jlong handle) {
  const EngineApi& api = Api();
  if (api.loaded && handle != 0) {
    api.session_destroy(reinterpret_cast<OpenAiCoreSession*>(handle));
  }
}

JNIEXPORT jint JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeGenerate(
    JNIEnv* env, jclass, jlong session_handle, jstring prompt, jobject sink) {
  const EngineApi& api = Api();
  if (!api.loaded || session_handle == 0 || prompt == nullptr || sink == nullptr) {
    return OPENAICORE_ERR_BAD_ARGUMENT;
  }
  jclass sink_class = env->GetObjectClass(sink);
  jmethodID on_chunk = env->GetMethodID(sink_class, "onChunk", "(Ljava/lang/String;)Z");
  if (on_chunk == nullptr) {
    return OPENAICORE_ERR_BAD_ARGUMENT;
  }
  CallbackContext ctx{env, sink, on_chunk, false};
  const char* utf8 = env->GetStringUTFChars(prompt, nullptr);
  const OpenAiCoreStatus status = api.session_generate(
      reinterpret_cast<OpenAiCoreSession*>(session_handle), utf8, &OnToken, &ctx);
  env->ReleaseStringUTFChars(prompt, utf8);
  if (ctx.cancelled && status == OPENAICORE_OK) {
    return OPENAICORE_ERR_CANCELLED;
  }
  return status;
}

JNIEXPORT jint JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeAddImage(
    JNIEnv* env, jclass, jlong session_handle, jbyteArray rgba, jint width, jint height) {
  const EngineApi& api = Api();
  if (!api.loaded || session_handle == 0 || rgba == nullptr) {
    return OPENAICORE_ERR_BAD_ARGUMENT;
  }
  jbyte* pixels = env->GetByteArrayElements(rgba, nullptr);
  const OpenAiCoreStatus status =
      api.session_add_image(reinterpret_cast<OpenAiCoreSession*>(session_handle),
                            reinterpret_cast<const uint8_t*>(pixels), width, height);
  env->ReleaseByteArrayElements(rgba, pixels, JNI_ABORT);
  return status;
}

JNIEXPORT jint JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeAddAudio(
    JNIEnv* env, jclass, jlong session_handle, jshortArray pcm, jint sample_rate_hz) {
  const EngineApi& api = Api();
  if (!api.loaded || session_handle == 0 || pcm == nullptr) {
    return OPENAICORE_ERR_BAD_ARGUMENT;
  }
  const jsize count = env->GetArrayLength(pcm);
  jshort* samples = env->GetShortArrayElements(pcm, nullptr);
  const OpenAiCoreStatus status = api.session_add_audio(
      reinterpret_cast<OpenAiCoreSession*>(session_handle),
      reinterpret_cast<const int16_t*>(samples), static_cast<size_t>(count), sample_rate_hz);
  env->ReleaseShortArrayElements(pcm, samples, JNI_ABORT);
  return status;
}

JNIEXPORT void JNICALL
Java_org_crdroid_intelligence_inference_engine_NativeEngine_nativeCancel(JNIEnv*, jclass,
                                                                         jlong session_handle) {
  const EngineApi& api = Api();
  if (api.loaded && session_handle != 0) {
    api.session_cancel(reinterpret_cast<OpenAiCoreSession*>(session_handle));
  }
}

}  // extern "C"
