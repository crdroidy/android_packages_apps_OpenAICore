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

import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.Errors;
import org.crdroid.intelligence.common.RequestKeys;

import java.nio.ByteBuffer;

/** {@link InferenceEngine} backed by the out-of-tree LiteRT-LM adapter. */
public final class NativeEngine implements InferenceEngine {

    // Mirrors OpenAiCoreStatus in native/include/openaicore_engine.h.
    private static final int STATUS_OK = 0;
    private static final int STATUS_MODEL_LOAD_FAILED = 3;
    private static final int STATUS_BACKEND_INIT_FAILED = 4;
    private static final int STATUS_OUT_OF_MEMORY = 5;
    private static final int STATUS_CONTEXT_LENGTH_EXCEEDED = 6;
    private static final int STATUS_CANCELLED = 7;
    private static final int STATUS_BUSY = 8;

    // Mirrors OpenAiCoreBackend.
    private static final int NATIVE_BACKEND_NPU = 1;
    private static final int NATIVE_BACKEND_OPENCL = 2;
    private static final int NATIVE_BACKEND_GLES = 3;
    private static final int NATIVE_BACKEND_CPU = 4;

    static {
        System.loadLibrary("openaicore_jni");
    }

    private final Object mLock = new Object();
    private long mEngineHandle;

    @Override
    public boolean isAvailable() {
        return nativeIsAvailable();
    }

    @Override
    public void load(Config config) throws EngineException {
        synchronized (mLock) {
            if (mEngineHandle != 0) {
                return;
            }
            ParcelFileDescriptor model = config.files.get(RequestKeys.FD_MODEL);
            if (model == null) {
                throw new EngineException(Errors.MODEL_NOT_DOWNLOADED, "no model descriptor");
            }
            ParcelFileDescriptor adapter = config.files.get(RequestKeys.FD_ADAPTER);
            long handle = nativeCreateEngine(
                    model.getFd(),
                    adapter == null ? -1 : adapter.getFd(),
                    toNativeBackend(config.backend),
                    config.contextTokens,
                    config.cpuThreads,
                    config.speculativeDecoding,
                    config.vision,
                    config.audio);
            if (handle == 0) {
                throw new EngineException(Errors.BACKEND_INIT_FAILED,
                        "engine construction failed on backend "
                                + DeviceTier.backendName(config.backend));
            }
            mEngineHandle = handle;
        }
    }

    @Override
    public void unload() {
        synchronized (mLock) {
            if (mEngineHandle != 0) {
                nativeDestroyEngine(mEngineHandle);
                mEngineHandle = 0;
            }
        }
    }

    @Override
    public boolean isLoaded() {
        synchronized (mLock) {
            return mEngineHandle != 0;
        }
    }

    @Override
    public int activeBackend() {
        synchronized (mLock) {
            if (mEngineHandle == 0) {
                return DeviceTier.BACKEND_NONE;
            }
            switch (nativeActiveBackend(mEngineHandle)) {
                case NATIVE_BACKEND_NPU:
                case NATIVE_BACKEND_OPENCL:
                    return DeviceTier.BACKEND_OPENCL;
                case NATIVE_BACKEND_GLES:
                    return DeviceTier.BACKEND_GLES;
                default:
                    return DeviceTier.BACKEND_CPU;
            }
        }
    }

    @Override
    public long countTokens(String text) {
        synchronized (mLock) {
            return mEngineHandle == 0 ? -1 : nativeCountTokens(mEngineHandle, text);
        }
    }

    @Override
    public Session createSession(SessionConfig config) throws EngineException {
        final long engine;
        synchronized (mLock) {
            engine = mEngineHandle;
        }
        if (engine == 0) {
            throw new EngineException(Errors.BACKEND_INIT_FAILED, "engine not loaded");
        }
        long session = nativeCreateSession(engine, config.systemPrompt, config.temperature,
                config.topP, config.topK, config.maxOutputTokens);
        if (session == 0) {
            throw new EngineException(Errors.BACKEND_INIT_FAILED, "session creation failed");
        }
        return new NativeSession(session);
    }

    private static int toNativeBackend(int backend) {
        switch (backend) {
            case DeviceTier.BACKEND_OPENCL: return NATIVE_BACKEND_OPENCL;
            case DeviceTier.BACKEND_GLES: return NATIVE_BACKEND_GLES;
            default: return NATIVE_BACKEND_CPU;
        }
    }

    private static EngineException toException(int status, String what) {
        switch (status) {
            case STATUS_CANCELLED:
                return new EngineException(Errors.CANCELLED, what);
            case STATUS_CONTEXT_LENGTH_EXCEEDED:
                return new EngineException(Errors.CONTEXT_LENGTH_EXCEEDED, what);
            case STATUS_OUT_OF_MEMORY:
                return new EngineException(Errors.LOW_MEMORY, what);
            case STATUS_BUSY:
                return new EngineException(Errors.BUSY, what);
            case STATUS_MODEL_LOAD_FAILED:
            case STATUS_BACKEND_INIT_FAILED:
                return new EngineException(Errors.BACKEND_INIT_FAILED, what);
            default:
                return new EngineException(Errors.BACKEND_INIT_FAILED, what + " (status " + status + ")");
        }
    }

    private static final class NativeSession implements Session {
        private final Object mSessionLock = new Object();
        private long mHandle;

        NativeSession(long handle) {
            mHandle = handle;
        }

        @Override
        public void addImage(Bitmap image) throws EngineException {
            byte[] rgba = toRgba(image);
            int status;
            synchronized (mSessionLock) {
                if (mHandle == 0) {
                    throw new EngineException(Errors.CANCELLED, "session closed");
                }
                status = nativeAddImage(mHandle, rgba, image.getWidth(), image.getHeight());
            }
            if (status != STATUS_OK) {
                throw toException(status, "addImage");
            }
        }

        @Override
        public void addAudio(short[] pcm, int sampleRateHz) throws EngineException {
            int status;
            synchronized (mSessionLock) {
                if (mHandle == 0) {
                    throw new EngineException(Errors.CANCELLED, "session closed");
                }
                status = nativeAddAudio(mHandle, pcm, sampleRateHz);
            }
            if (status != STATUS_OK) {
                throw toException(status, "addAudio");
            }
        }

        @Override
        public void generate(String prompt, TokenSink sink) throws EngineException {
            // Read the handle under the lock but call out without it: generate blocks for the
            // whole decode, and cancel() has to be able to run while it does.
            final long handle;
            synchronized (mSessionLock) {
                handle = mHandle;
            }
            if (handle == 0) {
                throw new EngineException(Errors.CANCELLED, "session closed");
            }
            int status = nativeGenerate(handle, prompt, sink);
            if (status != STATUS_OK) {
                throw toException(status, "generate");
            }
        }

        @Override
        public void cancel() {
            synchronized (mSessionLock) {
                if (mHandle != 0) {
                    nativeCancel(mHandle);
                }
            }
        }

        @Override
        public void close() {
            synchronized (mSessionLock) {
                if (mHandle != 0) {
                    nativeDestroySession(mHandle);
                    mHandle = 0;
                }
            }
        }

        private static byte[] toRgba(Bitmap bitmap) {
            Bitmap src = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                    ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
            ByteBuffer buffer = ByteBuffer.allocate(src.getByteCount());
            src.copyPixelsToBuffer(buffer);
            if (src != bitmap) {
                src.recycle();
            }
            return buffer.array();
        }
    }

    private static native boolean nativeIsAvailable();

    private static native long nativeCreateEngine(int modelFd, int adapterFd, int backend,
            int contextTokens, int cpuThreads, boolean speculative, boolean vision, boolean audio);

    private static native void nativeDestroyEngine(long handle);

    private static native int nativeActiveBackend(long handle);

    private static native long nativeCountTokens(long handle, String text);

    private static native long nativeCreateSession(long engineHandle, String systemPrompt,
            float temperature, float topP, int topK, int maxOutputTokens);

    private static native void nativeDestroySession(long handle);

    private static native int nativeGenerate(long sessionHandle, String prompt, TokenSink sink);

    private static native int nativeAddImage(long sessionHandle, byte[] rgba, int width, int height);

    private static native int nativeAddAudio(long sessionHandle, short[] pcm, int sampleRateHz);

    private static native void nativeCancel(long sessionHandle);
}
