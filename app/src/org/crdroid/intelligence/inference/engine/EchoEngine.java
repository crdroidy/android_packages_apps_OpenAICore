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
import android.os.SystemClock;

import org.crdroid.intelligence.common.DeviceTier;

/**
 * A model-free engine that streams a fixed acknowledgement.
 *
 * <p>It exists for the first milestone — proving the platform binder round trip from a client all
 * the way into the isolated process before LiteRT-LM is involved at all — and it stays afterwards
 * as the engine for builds that ship without the prebuilt adapter, so those builds boot and
 * report the feature honestly rather than crashing.
 *
 * <p>It never appears on a user build with a model installed: {@code EngineHolder} only selects
 * it when {@link NativeEngine#isAvailable()} is false.
 */
public final class EchoEngine implements InferenceEngine {

    private static final String NOTICE =
            "OpenAICore is running without a model. This build has no inference engine "
                    + "installed, so no text was generated.";

    private volatile boolean mLoaded;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void load(Config config) {
        mLoaded = true;
    }

    @Override
    public void unload() {
        mLoaded = false;
    }

    @Override
    public boolean isLoaded() {
        return mLoaded;
    }

    @Override
    public int activeBackend() {
        return DeviceTier.BACKEND_CPU;
    }

    @Override
    public long countTokens(String text) {
        // Roughly four characters per token; close enough for a caller sizing a request against
        // the advertised limit, and this engine never actually runs one.
        return text == null ? 0 : (text.length() + 3) / 4;
    }

    @Override
    public Session createSession(SessionConfig config) {
        return new EchoSession();
    }

    private static final class EchoSession implements Session {
        private volatile boolean mCancelled;

        @Override
        public void addImage(Bitmap image) {}

        @Override
        public void addAudio(short[] pcm, int sampleRateHz) {}

        @Override
        public void generate(String prompt, TokenSink sink) {
            for (String word : NOTICE.split(" ")) {
                if (mCancelled || !sink.onChunk(word + " ")) {
                    return;
                }
                // Paced so streaming callers exercise their partial-result path the way they
                // would against a real decoder.
                SystemClock.sleep(20);
            }
        }

        @Override
        public void cancel() {
            mCancelled = true;
        }

        @Override
        public void close() {
            mCancelled = true;
        }
    }
}
