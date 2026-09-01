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

package org.crdroid.intelligence.inference.engine;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

import java.util.Map;

/**
 * What the inference service needs from a model runtime.
 *
 * <p>Deliberately small and free of any {@code android.app.ondeviceintelligence} type. The
 * platform contract lives in the broker and the SDK; if a platform symbol ever appears below this
 * interface, the Android 16 backport stops being a module swap and becomes a rewrite.
 */
public interface InferenceEngine {

    /** Engine construction parameters, all resolved by the broker before the sandbox sees them. */
    final class Config {
        public Map<String, ParcelFileDescriptor> files;
        public int backend;
        public int contextTokens;
        public int cpuThreads = 4;
        public boolean speculativeDecoding;
        public boolean vision;
        public boolean audio;
    }

    final class SessionConfig {
        public String systemPrompt;
        public float temperature = 0.7f;
        public float topP = 0.95f;
        public int topK = 64;
        public int maxOutputTokens = 512;
    }

    /** Receives decoded text. Returning false asks the engine to stop. */
    interface TokenSink {
        boolean onChunk(String chunk);
    }

    final class EngineException extends Exception {
        /** One of {@link org.crdroid.intelligence.common.Errors}. */
        public final String reason;

        public EngineException(String reason, String detail) {
            super(detail);
            this.reason = reason;
        }
    }

    /** A single conversation. Closing it destroys all retained state. */
    interface Session extends AutoCloseable {
        void addImage(Bitmap image) throws EngineException;

        void addAudio(short[] pcm, int sampleRateHz) throws EngineException;

        /** Blocks until generation finishes, is cancelled, or fails. */
        void generate(String prompt, TokenSink sink) throws EngineException;

        /** Safe to call from another thread while {@link #generate} is running. */
        void cancel();

        @Override
        void close();
    }

    boolean isAvailable();

    void load(Config config) throws EngineException;

    void unload();

    boolean isLoaded();

    /** The backend actually in use, which may be lower than the one requested. */
    int activeBackend();

    long countTokens(String text);

    Session createSession(SessionConfig config) throws EngineException;
}
