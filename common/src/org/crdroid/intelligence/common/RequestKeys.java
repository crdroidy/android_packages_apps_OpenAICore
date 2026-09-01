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

package org.crdroid.intelligence.common;

/**
 * Keys for the request and response {@link android.os.Bundle}s crossing
 * {@code OnDeviceIntelligenceManager#processRequest}.
 *
 * <p>The platform deliberately leaves the payload schema to the implementation, so this class is
 * the whole contract between OpenAICore and its callers. Everything here must survive
 * {@code BundleUtil.sanitizeInferenceParams}, i.e. primitives, Strings, nested Bundles,
 * read-only ParcelFileDescriptors, Bitmaps and SharedMemory only.
 */
public final class RequestKeys {

    private static final String P = "org.crdroid.intelligence.";

    // ---- request ----

    /** {@code String}. Free-form instruction. Required for {@link Features#ID_PROMPT}. */
    public static final String KEY_PROMPT = P + "prompt";
    /** {@code String}. The text a task operates on (summarize / proofread / rewrite input). */
    public static final String KEY_TEXT = P + "text";
    /** {@code String}. One of {@link #TONE_FORMAL} .. {@link #TONE_ELABORATE}. */
    public static final String KEY_TONE = P + "tone";
    /** {@code android.graphics.Bitmap}. Single image for DESCRIBE_IMAGE / multimodal PROMPT. */
    public static final String KEY_IMAGE = P + "image";
    /** Read-only {@code ParcelFileDescriptor} to a PCM/opus chunk for TRANSCRIBE. */
    public static final String KEY_AUDIO = P + "audio";
    /** {@code int}. Caller's ceiling on generated tokens. Clamped to the feature limit. */
    public static final String KEY_MAX_OUTPUT_TOKENS = P + "max_output_tokens";
    /** {@code float}. 0..2. Defaults to the per-feature value. */
    public static final String KEY_TEMPERATURE = P + "temperature";
    /** {@code String}. Opaque session id; when present, conversation state is retained. */
    public static final String KEY_SESSION_ID = P + "session_id";

    public static final String TONE_FORMAL = "formal";
    public static final String TONE_CASUAL = "casual";
    public static final String TONE_CONCISE = "concise";
    public static final String TONE_ELABORATE = "elaborate";

    // ---- response ----

    /** {@code String}. The generated text. Present on every successful text response. */
    public static final String KEY_RESPONSE = P + "response";
    /** {@code boolean}. Set on the final streaming chunk. */
    public static final String KEY_DONE = P + "done";
    /** {@code int}. Tokens actually generated. */
    public static final String KEY_OUTPUT_TOKENS = P + "output_tokens";
    /** {@code String}. Backend that served the request, see {@link DeviceTier#backendName}. */
    public static final String KEY_BACKEND = P + "backend";

    // ---- processing-state keys, broker -> inference ----

    /** {@code int}. {@link DeviceTier} ordinal. */
    public static final String STATE_TIER = P + "state.tier";
    /** {@code int}. Backend the probe selected. */
    public static final String STATE_BACKEND = P + "state.backend";
    /** {@code int}. Context window in tokens. */
    public static final String STATE_CONTEXT_TOKENS = P + "state.context_tokens";
    /** {@code boolean}. Enable speculative decoding. */
    public static final String STATE_SPECULATIVE = P + "state.speculative";
    /** {@code String}. Model variant id, see {@link ModelCatalogIds}. */
    public static final String STATE_MODEL_ID = P + "state.model_id";
    /**
     * {@code int}. Latest {@code PowerManager.THERMAL_STATUS_*}. The inference process is isolated
     * and cannot reach {@code power_service}, so thermal state has to be pushed to it.
     */
    public static final String STATE_THERMAL = P + "state.thermal";
    /** {@code boolean}. Unload the engine now and free the model mapping. */
    public static final String STATE_UNLOAD = P + "state.unload";
    /** {@code long}. Idle milliseconds after which the engine self-unloads. */
    public static final String STATE_IDLE_UNLOAD_MS = P + "state.idle_unload_ms";

    /** Key in the file-descriptor map handed to the sandbox: the packaged model. */
    public static final String FD_MODEL = "model.litertlm";
    /** Key in the file-descriptor map: optional per-feature LoRA adapter. */
    public static final String FD_ADAPTER = "adapter.litertlm";

    private RequestKeys() {}
}
