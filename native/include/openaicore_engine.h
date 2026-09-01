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

// The ABI between OpenAICore's JNI layer and whatever engine actually runs the model.
//
// This exists because LiteRT-LM builds with Bazel and does not integrate with Soong, so the
// engine cannot be built inside the AOSP tree. The adapter in native/litertlm is built
// out-of-tree against a pinned LiteRT-LM tag and imported as a prebuilt .so; the JNI layer
// dlopens it by soname and degrades cleanly when it is absent. Owning the ABI rather than
// binding LiteRT-LM's own headers is also what keeps a second engine (or a version bump)
// from reaching into the service code.
//
// ABI rules: C linkage, no C++ types across the boundary, additive changes only, and
// OPENAICORE_ENGINE_ABI_VERSION bumped on any incompatible change. The loader refuses a
// library whose reported version does not match.

#ifndef OPENAICORE_ENGINE_H_
#define OPENAICORE_ENGINE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define OPENAICORE_ENGINE_ABI_VERSION 1

typedef enum {
  OPENAICORE_OK = 0,
  OPENAICORE_ERR_UNKNOWN = 1,
  OPENAICORE_ERR_BAD_ARGUMENT = 2,
  OPENAICORE_ERR_MODEL_LOAD_FAILED = 3,
  OPENAICORE_ERR_BACKEND_INIT_FAILED = 4,
  OPENAICORE_ERR_OUT_OF_MEMORY = 5,
  OPENAICORE_ERR_CONTEXT_LENGTH_EXCEEDED = 6,
  OPENAICORE_ERR_CANCELLED = 7,
  OPENAICORE_ERR_BUSY = 8,
} OpenAiCoreStatus;

typedef enum {
  // Order matches the ladder: NPU, then ML Drift's OpenCL path, then GLES, then XNNPACK.
  OPENAICORE_BACKEND_AUTO = 0,
  OPENAICORE_BACKEND_NPU = 1,
  OPENAICORE_BACKEND_OPENCL = 2,
  OPENAICORE_BACKEND_GLES = 3,
  OPENAICORE_BACKEND_CPU = 4,
} OpenAiCoreBackend;

typedef struct OpenAiCoreEngine OpenAiCoreEngine;
typedef struct OpenAiCoreSession OpenAiCoreSession;

typedef struct {
  // Descriptor for the packaged model, already opened read-only by the broker. The engine mmaps
  // it; it must not read it into anonymous memory, because roughly 1.1 GB of the E2B footprint is
  // embedding parameters that are only ever read.
  int model_fd;
  // Optional per-feature adapter, or -1.
  int adapter_fd;
  OpenAiCoreBackend backend;
  int32_t context_tokens;
  int32_t cpu_threads;
  // Multi-token prediction. Materially raises decode throughput on GPU and is safe to leave on
  // where the probe found it working.
  int32_t enable_speculative_decoding;
  // Vision and audio towers are loaded on demand; paying for them unconditionally costs
  // resident memory on every text-only request.
  int32_t enable_vision;
  int32_t enable_audio;
} OpenAiCoreEngineConfig;

typedef struct {
  float temperature;
  float top_p;
  int32_t top_k;
  int32_t max_output_tokens;
  // Optional; NULL for the feature default.
  const char* system_prompt;
} OpenAiCoreSessionConfig;

// Invoked on the engine's own thread for each decoded chunk. Returning a non-zero value cancels
// generation, which is how a client CancellationSignal reaches the decoder.
typedef int (*OpenAiCoreTokenCallback)(const char* utf8_chunk, size_t length, void* user_data);

// Reported ABI version of the loaded library. Checked before anything else is called.
int32_t OpenAiCoreEngineAbiVersion(void);

OpenAiCoreStatus OpenAiCoreEngineCreate(const OpenAiCoreEngineConfig* config,
                                        OpenAiCoreEngine** out_engine);
void OpenAiCoreEngineDestroy(OpenAiCoreEngine* engine);

// Backend the engine actually settled on, which may be lower than requested.
OpenAiCoreBackend OpenAiCoreEngineActiveBackend(const OpenAiCoreEngine* engine);

OpenAiCoreStatus OpenAiCoreEngineCountTokens(OpenAiCoreEngine* engine, const char* utf8_text,
                                             int64_t* out_count);

OpenAiCoreStatus OpenAiCoreSessionCreate(OpenAiCoreEngine* engine,
                                         const OpenAiCoreSessionConfig* config,
                                         OpenAiCoreSession** out_session);
void OpenAiCoreSessionDestroy(OpenAiCoreSession* session);

// Text generation. Blocks until generation ends, the callback cancels, or an error occurs.
OpenAiCoreStatus OpenAiCoreSessionGenerateText(OpenAiCoreSession* session, const char* utf8_prompt,
                                               OpenAiCoreTokenCallback callback, void* user_data);

// Adds an image to the next turn. `pixels` is tightly packed RGBA8888.
OpenAiCoreStatus OpenAiCoreSessionAddImage(OpenAiCoreSession* session, const uint8_t* pixels,
                                           int32_t width, int32_t height);

// Adds an audio chunk to the next turn. `samples` is mono 16-bit PCM at `sample_rate_hz`.
OpenAiCoreStatus OpenAiCoreSessionAddAudio(OpenAiCoreSession* session, const int16_t* samples,
                                           size_t sample_count, int32_t sample_rate_hz);

// Asks a running generate call to stop. Safe to call from another thread.
void OpenAiCoreSessionCancel(OpenAiCoreSession* session);

// Human-readable detail for the last failure on this thread. Never contains prompt or output
// text: it is written to logs, and logs must not carry content.
const char* OpenAiCoreLastErrorMessage(void);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // OPENAICORE_ENGINE_H_
