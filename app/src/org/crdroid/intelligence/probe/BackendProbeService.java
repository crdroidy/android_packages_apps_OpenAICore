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

package org.crdroid.intelligence.probe;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import org.crdroid.intelligence.broker.ModelClient;
import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.RequestKeys;
import org.crdroid.intelligence.inference.engine.InferenceEngine;
import org.crdroid.intelligence.inference.engine.NativeEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Walks the backend ladder in a throwaway process and reports the first rung that works.
 *
 * <p>Runs in {@code :probe}, which is the whole point: a GPU driver that faults during engine
 * construction kills this process and nothing else. The client treats that death as a failed rung
 * and steps down.
 *
 * <p>This process is not the inference sandbox and never sees user content. It generates from a
 * fixed, non-content prompt purely to time the decode.
 */
public final class BackendProbeService extends Service {

    private static final String TAG = "OpenAICore.Probe";

    /** Fixed and content-free: this exists to time a decode, not to produce anything. */
    private static final String PROBE_PROMPT = "Count from one to ten.";
    private static final int PROBE_TOKENS = 10;

    private HandlerThread mThread;
    private Messenger mMessenger;
    private ModelClient mModels;

    @Override
    public void onCreate() {
        super.onCreate();
        mThread = new HandlerThread("OpenAICore.probe", Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mMessenger = new Messenger(new Handler(mThread.getLooper(), this::handleMessage));
        mModels = new ModelClient(this);
    }

    @Override
    public void onDestroy() {
        mThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private boolean handleMessage(Message msg) {
        if (msg.what != BackendProbeClient.MSG_PROBE) {
            return false;
        }
        String fingerprint = msg.getData().getString(BackendProbeResult.KEY_FINGERPRINT);
        Messenger replyTo = msg.replyTo;
        BackendProbeResult result = probe(fingerprint);
        if (replyTo != null) {
            Message reply = Message.obtain(null, BackendProbeClient.MSG_RESULT);
            reply.setData(result.toBundle());
            try {
                replyTo.send(reply);
            } catch (RemoteException e) {
                Log.w(TAG, "probe requester is gone");
            }
        }
        return true;
    }

    /**
     * Tries OpenCL, then GLES, then CPU. Each rung constructs a real engine and generates a few
     * tokens, because construction succeeding tells you very little on its own — the ANGLE-CL
     * failure mode in particular shows up at kernel compile time, not at engine open.
     */
    private BackendProbeResult probe(String fingerprint) {
        for (int backend : new int[] {DeviceTier.BACKEND_OPENCL, DeviceTier.BACKEND_GLES,
                DeviceTier.BACKEND_CPU}) {
            Attempt attempt = attempt(backend, /* speculative= */ false);
            if (!attempt.ok) {
                Log.i(TAG, "backend " + DeviceTier.backendName(backend) + " failed: "
                        + attempt.failure);
                continue;
            }
            boolean speculative = false;
            if (backend != DeviceTier.BACKEND_CPU) {
                // Speculative decoding is a decode-throughput win but not universally stable, so
                // it is probed separately and only enabled where it actually ran.
                speculative = attempt(backend, /* speculative= */ true).ok;
            }
            return new BackendProbeResult(backend, speculative, fingerprint,
                    attempt.tokensPerSecond, null);
        }
        return new BackendProbeResult(DeviceTier.BACKEND_NONE, false, fingerprint, 0f,
                "all_backends_failed");
    }

    private static final class Attempt {
        boolean ok;
        float tokensPerSecond;
        String failure;
    }

    private Attempt attempt(int backend, boolean speculative) {
        Attempt attempt = new Attempt();
        NativeEngine engine = new NativeEngine();
        if (!engine.isAvailable()) {
            // No adapter on this build, so there is nothing to accelerate. CPU is the honest
            // answer rather than a failure.
            attempt.ok = backend == DeviceTier.BACKEND_CPU;
            attempt.failure = "no_native_engine";
            return attempt;
        }

        ParcelFileDescriptor model = mModels.openModel(
                DeviceTier.modelForTier(DeviceTier.TIER_B));
        if (model == null) {
            attempt.failure = "no_model";
            return attempt;
        }

        InferenceEngine.Session session = null;
        try {
            Map<String, ParcelFileDescriptor> files = new HashMap<>();
            files.put(RequestKeys.FD_MODEL, model);

            InferenceEngine.Config config = new InferenceEngine.Config();
            config.files = files;
            config.backend = backend;
            // A short context keeps the probe's KV cache small; this measures the backend, not
            // the model's behaviour at length.
            config.contextTokens = 512;
            config.speculativeDecoding = speculative;
            engine.load(config);

            if (engine.activeBackend() != backend) {
                // The adapter silently fell back, which for probing purposes is a failure of this
                // rung: recording it as a success would cache a backend that is not in use.
                attempt.failure = "fell_back_to_"
                        + DeviceTier.backendName(engine.activeBackend());
                return attempt;
            }

            InferenceEngine.SessionConfig sessionConfig = new InferenceEngine.SessionConfig();
            sessionConfig.maxOutputTokens = PROBE_TOKENS;
            sessionConfig.temperature = 0f;
            session = engine.createSession(sessionConfig);

            final int[] chunks = new int[1];
            long started = SystemClock.elapsedRealtime();
            session.generate(PROBE_PROMPT, chunk -> {
                chunks[0]++;
                return chunks[0] < PROBE_TOKENS;
            });
            long elapsedMs = SystemClock.elapsedRealtime() - started;

            if (chunks[0] == 0) {
                attempt.failure = "no_tokens";
                return attempt;
            }
            attempt.ok = true;
            attempt.tokensPerSecond = elapsedMs == 0 ? 0f : (chunks[0] * 1000f) / elapsedMs;
            return attempt;
        } catch (InferenceEngine.EngineException e) {
            attempt.failure = e.reason;
            return attempt;
        } finally {
            if (session != null) {
                session.close();
            }
            engine.unload();
            try {
                model.close();
            } catch (java.io.IOException ignored) {
                // The process is about to go away regardless.
            }
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // Nothing survives this process, which is the point. Stopping releases the GPU driver
        // mapping immediately rather than at the next lmkd pass.
        stopSelf();
        return false;
    }
}
