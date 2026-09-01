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

package org.crdroid.intelligence.inference;

import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.Errors;
import org.crdroid.intelligence.inference.engine.EchoEngine;
import org.crdroid.intelligence.inference.engine.InferenceEngine;
import org.crdroid.intelligence.inference.engine.NativeEngine;

import java.io.PrintWriter;
import java.util.Map;

/**
 * Owns the one engine instance in the process.
 *
 * <p>One instance, process-wide, shared by every caller. Two resident copies of a multi-gigabyte
 * model is an immediate out-of-memory kill, so requests queue behind a single engine rather than
 * running concurrently. An idle timer unloads it again, because the success criterion for this
 * component is zero resident bytes when nobody has asked for anything in a couple of minutes.
 */
final class EngineHolder {

    private static final String TAG = "OpenAICore.EngineHolder";
    private static final long DEFAULT_IDLE_UNLOAD_MS = 120_000L;

    private final Handler mHandler;
    private final InferenceEngine mEngine;

    private final Object mLock = new Object();
    private long mIdleUnloadMs = DEFAULT_IDLE_UNLOAD_MS;
    private long mLastUseElapsedMs;
    private int mActiveRequests;
    private long mLoadDurationMs;
    private long mLoadCount;

    private final Runnable mIdleCheck = this::onIdleCheck;

    EngineHolder(Handler handler) {
        mHandler = handler;
        NativeEngine native_ = new NativeEngine();
        if (native_.isAvailable()) {
            mEngine = native_;
        } else {
            Log.w(TAG, "no native engine on this build; falling back to the model-free engine");
            mEngine = new EchoEngine();
        }
    }

    boolean hasRealEngine() {
        return mEngine instanceof NativeEngine;
    }

    void setIdleUnloadMs(long idleUnloadMs) {
        synchronized (mLock) {
            mIdleUnloadMs = idleUnloadMs;
        }
    }

    /**
     * Loads the engine if it is not already up. Callers must hold the request slot: this both
     * blocks and can take seconds on first construction.
     */
    void ensureLoaded(Map<String, ParcelFileDescriptor> files, int backend, int contextTokens,
            boolean speculative, boolean vision, boolean audio)
            throws InferenceEngine.EngineException {
        synchronized (mLock) {
            if (mEngine.isLoaded()) {
                return;
            }
            InferenceEngine.Config config = new InferenceEngine.Config();
            config.files = files;
            config.backend = backend;
            config.contextTokens = contextTokens;
            config.speculativeDecoding = speculative;
            config.vision = vision;
            config.audio = audio;

            long started = SystemClock.uptimeMillis();
            mEngine.load(config);
            mLoadDurationMs = SystemClock.uptimeMillis() - started;
            mLoadCount++;
            Log.i(TAG, "engine loaded in " + mLoadDurationMs + "ms on "
                    + DeviceTier.backendName(mEngine.activeBackend()));
        }
    }

    InferenceEngine engine() {
        return mEngine;
    }

    /** Marks the start of a request. Suppresses the idle timer until it finishes. */
    void beginRequest() {
        synchronized (mLock) {
            mActiveRequests++;
        }
        mHandler.removeCallbacks(mIdleCheck);
    }

    void endRequest() {
        final long idleMs;
        synchronized (mLock) {
            mActiveRequests--;
            mLastUseElapsedMs = SystemClock.elapsedRealtime();
            idleMs = mIdleUnloadMs;
        }
        if (idleMs > 0) {
            mHandler.postDelayed(mIdleCheck, idleMs);
        }
    }

    void unloadNow() {
        mHandler.removeCallbacks(mIdleCheck);
        synchronized (mLock) {
            if (mEngine.isLoaded()) {
                mEngine.unload();
                Log.i(TAG, "engine unloaded");
            }
        }
    }

    private void onIdleCheck() {
        synchronized (mLock) {
            if (mActiveRequests > 0) {
                return;
            }
            if (SystemClock.elapsedRealtime() - mLastUseElapsedMs < mIdleUnloadMs) {
                // A request landed and finished between the post and now; the endRequest that
                // handled it has already scheduled a fresh check.
                return;
            }
            if (mEngine.isLoaded()) {
                mEngine.unload();
                Log.i(TAG, "engine unloaded after idle timeout");
            }
        }
    }

    String describeState() {
        synchronized (mLock) {
            return mEngine.isLoaded() ? "loaded" : "unloaded";
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  engine: " + (hasRealEngine() ? "native" : "model-free")
                    + " state=" + (mEngine.isLoaded() ? "loaded" : "unloaded")
                    + " backend=" + DeviceTier.backendName(mEngine.activeBackend())
                    + " loads=" + mLoadCount
                    + " lastLoadMs=" + mLoadDurationMs
                    + " active=" + mActiveRequests
                    + " idleUnloadMs=" + mIdleUnloadMs);
        }
    }

    static String reasonFor(InferenceEngine.EngineException e) {
        return e.reason == null ? Errors.BACKEND_INIT_FAILED : e.reason;
    }
}
