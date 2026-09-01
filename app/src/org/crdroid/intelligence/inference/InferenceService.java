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

import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.InferenceInfo;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.ProcessingCallback;
import android.app.ondeviceintelligence.ProcessingSignal;
import android.app.ondeviceintelligence.StreamingProcessingCallback;
import android.app.ondeviceintelligence.TokenInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.service.ondeviceintelligence.OnDeviceSandboxedInferenceService;
import android.util.Log;

import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.Errors;
import org.crdroid.intelligence.common.Features;
import org.crdroid.intelligence.common.RequestKeys;
import org.crdroid.intelligence.inference.engine.InferenceEngine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sandboxed half of OpenAICore: the only process that ever sees prompt or response content.
 *
 * <p>The platform requires this service to be declared {@code isolatedProcess}, which is what
 * gives it an isolated uid with no network, no ability to open files by path, and no service
 * manager access beyond the {@code isolated_compute_app} allowlist. Everything it needs therefore
 * arrives over binder: model bytes as read-only descriptors from the broker, and device state via
 * {@link #onUpdateProcessingState}, because {@code power_service} is not reachable from here.
 *
 * <p>Nothing in this class logs request or response text. The debug helper that would is compiled
 * out on user builds rather than gated at runtime.
 */
public final class InferenceService extends OnDeviceSandboxedInferenceService {

    private static final String TAG = "OpenAICore.Inference";

    /** Content logging is a build-time decision. Never make this a runtime flag. */
    private static final boolean LOG_CONTENT = false;

    private HandlerThread mThread;
    private Handler mHandler;
    private Executor mCallbackExecutor;
    private ExecutorService mWorker;
    private EngineHolder mEngineHolder;

    private final Object mStateLock = new Object();
    private int mTier = DeviceTier.TIER_E;
    private int mBackend = DeviceTier.BACKEND_CPU;
    private int mContextTokens = 2048;
    private boolean mSpeculative;
    private int mThermalStatus = PowerManager.THERMAL_STATUS_NONE;

    private final QuotaTracker mQuota = new QuotaTracker();
    private final AtomicInteger mInFlight = new AtomicInteger();
    private final AtomicLong mCompleted = new AtomicLong();
    private final AtomicLong mFailed = new AtomicLong();

    private LifecycleListener mLifecycleListener;

    @Override
    public void onCreate() {
        super.onCreate();
        mThread = new HandlerThread("OpenAICore.inference", Process.THREAD_PRIORITY_DEFAULT);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mCallbackExecutor = mHandler::post;
        // One worker: the engine is a single instance and requests are serialised behind it.
        // Queuing here rather than in the engine keeps cancellation cheap for queued requests.
        mWorker = Executors.newSingleThreadExecutor();
        mEngineHolder = new EngineHolder(mHandler);
    }

    @Override
    public void onDestroy() {
        mWorker.shutdownNow();
        mEngineHolder.unloadNow();
        mThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public Executor getCallbackExecutor() {
        return mCallbackExecutor;
    }

    @Override
    public void onRegisterInferenceServiceLifecycleListener(LifecycleListener listener) {
        mLifecycleListener = listener;
    }

    @Override
    public void onUpdateProcessingState(Bundle processingState,
            OutcomeReceiver<PersistableBundle, OnDeviceIntelligenceException> callback) {
        synchronized (mStateLock) {
            if (processingState.containsKey(RequestKeys.STATE_TIER)) {
                mTier = processingState.getInt(RequestKeys.STATE_TIER);
            }
            if (processingState.containsKey(RequestKeys.STATE_BACKEND)) {
                mBackend = processingState.getInt(RequestKeys.STATE_BACKEND);
            }
            if (processingState.containsKey(RequestKeys.STATE_CONTEXT_TOKENS)) {
                mContextTokens = processingState.getInt(RequestKeys.STATE_CONTEXT_TOKENS);
            }
            if (processingState.containsKey(RequestKeys.STATE_SPECULATIVE)) {
                mSpeculative = processingState.getBoolean(RequestKeys.STATE_SPECULATIVE);
            }
            if (processingState.containsKey(RequestKeys.STATE_THERMAL)) {
                mThermalStatus = processingState.getInt(RequestKeys.STATE_THERMAL);
            }
            if (processingState.containsKey(RequestKeys.STATE_IDLE_UNLOAD_MS)) {
                mEngineHolder.setIdleUnloadMs(
                        processingState.getLong(RequestKeys.STATE_IDLE_UNLOAD_MS));
            }
        }
        if (processingState.getBoolean(RequestKeys.STATE_UNLOAD, false)) {
            mEngineHolder.unloadNow();
            notifyLifecycle(LifecycleListener.LIFECYCLE_EVENT_MODEL_UNLOADED, null);
        }
        PersistableBundle result = new PersistableBundle();
        result.putString(RequestKeys.KEY_BACKEND,
                DeviceTier.backendName(mEngineHolder.engine().activeBackend()));
        result.putString("engine_state", mEngineHolder.describeState());
        callback.onResult(result);
    }

    @Override
    public void onProcessRequest(int callerUid, Feature feature, Bundle request, int requestType,
            CancellationSignal cancellationSignal, ProcessingSignal processingSignal,
            ProcessingCallback callback) {
        submit(callerUid, feature, request, requestType, cancellationSignal, callback,
                /* streaming= */ null);
    }

    @Override
    public void onProcessRequestStreaming(int callerUid, Feature feature, Bundle request,
            int requestType, CancellationSignal cancellationSignal,
            ProcessingSignal processingSignal, StreamingProcessingCallback callback) {
        submit(callerUid, feature, request, requestType, cancellationSignal, callback, callback);
    }

    @Override
    public void onTokenInfoRequest(int callerUid, Feature feature, Bundle request,
            CancellationSignal cancellationSignal,
            OutcomeReceiver<TokenInfo, OnDeviceIntelligenceException> callback) {
        mWorker.execute(() -> {
            String text = request.getString(RequestKeys.KEY_TEXT,
                    request.getString(RequestKeys.KEY_PROMPT, ""));
            long count = mEngineHolder.engine().countTokens(text);
            if (count < 0) {
                callback.onError(Errors.of(Errors.BACKEND_INIT_FAILED, "tokenizer unavailable"));
                return;
            }
            PersistableBundle info = new PersistableBundle();
            info.putInt("context_tokens", contextTokens());
            callback.onResult(new TokenInfo(count, info));
        });
    }

    private void submit(int callerUid, Feature feature, Bundle request, int requestType,
            CancellationSignal cancellationSignal, ProcessingCallback callback,
            StreamingProcessingCallback streaming) {
        final long queuedAtMs = System.currentTimeMillis();
        final long queuedAtUptimeMs = SystemClock.uptimeMillis();

        String thermalRejection = checkThermal();
        if (thermalRejection != null) {
            callback.onError(Errors.of(thermalRejection));
            return;
        }
        // Quota is enforced here rather than in the broker because this is the component the
        // platform actually hands callerUid to: processRequest goes from the manager service
        // straight to the sandbox and never passes through the broker.
        String quotaRejection = mQuota.admit(callerUid);
        if (quotaRejection != null) {
            callback.onError(Errors.of(quotaRejection));
            return;
        }

        // A cancellation that arrives before the worker picks the request up has to be honoured
        // without ever loading the engine, which is the common case for a user backing out of a
        // screen while a request is queued.
        final boolean[] cancelledBeforeStart = new boolean[1];
        final InferenceEngine.Session[] activeSession = new InferenceEngine.Session[1];
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(() -> {
                synchronized (cancelledBeforeStart) {
                    cancelledBeforeStart[0] = true;
                    if (activeSession[0] != null) {
                        activeSession[0].cancel();
                    }
                }
            });
        }

        mInFlight.incrementAndGet();
        mWorker.execute(() -> {
            mEngineHolder.beginRequest();
            try {
                synchronized (cancelledBeforeStart) {
                    if (cancelledBeforeStart[0]) {
                        throw new InferenceEngine.EngineException(Errors.CANCELLED, "cancelled");
                    }
                }
                if (requestType == OnDeviceIntelligenceManager.REQUEST_TYPE_PREPARE) {
                    prepareEngine(feature);
                    callback.onResult(new Bundle());
                    return;
                }
                runInference(feature, request, callback, streaming, activeSession,
                        cancelledBeforeStart);
                mCompleted.incrementAndGet();
            } catch (InferenceEngine.EngineException e) {
                mFailed.incrementAndGet();
                callback.onError(Errors.of(EngineHolder.reasonFor(e), e.getMessage()));
            } catch (RuntimeException e) {
                mFailed.incrementAndGet();
                Log.e(TAG, "request failed", e);
                callback.onError(Errors.of(Errors.BACKEND_INIT_FAILED, e.getClass().getName()));
            } finally {
                synchronized (cancelledBeforeStart) {
                    if (activeSession[0] != null) {
                        activeSession[0].close();
                        activeSession[0] = null;
                    }
                }
                mQuota.recordCompute(callerUid, SystemClock.uptimeMillis() - queuedAtUptimeMs);
                mEngineHolder.endRequest();
                mInFlight.decrementAndGet();
                callback.onInferenceInfo(new InferenceInfo.Builder(Process.myUid())
                        .setStartTimeMillis(queuedAtMs)
                        .setEndTimeMillis(System.currentTimeMillis())
                        .setSuspendedTimeMillis(0)
                        .build());
                if (LOG_CONTENT) {
                    Log.d(TAG, "request took "
                            + (SystemClock.uptimeMillis() - queuedAtUptimeMs) + "ms");
                }
            }
        });
    }

    private void prepareEngine(Feature feature) throws InferenceEngine.EngineException {
        notifyLifecycle(LifecycleListener.LIFECYCLE_EVENT_ATTEMPTING_MODEL_LOADING, feature);
        Map<String, ParcelFileDescriptor> files = fetchFilesBlocking(feature);
        int modalities = feature.getFeatureParams()
                .getInt(Features.PARAM_MODALITIES, Features.MODALITY_TEXT);
        synchronized (mStateLock) {
            mEngineHolder.ensureLoaded(files, mBackend, mContextTokens, mSpeculative,
                    (modalities & Features.MODALITY_IMAGE) != 0,
                    (modalities & Features.MODALITY_AUDIO) != 0);
        }
        notifyLifecycle(LifecycleListener.LIFECYCLE_EVENT_MODEL_LOADED, feature);
    }

    private void runInference(Feature feature, Bundle request, ProcessingCallback callback,
            StreamingProcessingCallback streaming, InferenceEngine.Session[] activeSession,
            boolean[] cancelledBeforeStart) throws InferenceEngine.EngineException {
        prepareEngine(feature);

        PromptTemplates.Prepared prepared = PromptTemplates.prepare(feature.getId(), request);

        long inputTokens = mEngineHolder.engine().countTokens(prepared.userTurn);
        int limit = feature.getFeatureParams()
                .getInt(Features.PARAM_MAX_INPUT_TOKENS, Integer.MAX_VALUE);
        if (inputTokens > 0 && inputTokens > limit) {
            throw new InferenceEngine.EngineException(Errors.CONTEXT_LENGTH_EXCEEDED,
                    inputTokens + " > " + limit);
        }

        InferenceEngine.SessionConfig sessionConfig = new InferenceEngine.SessionConfig();
        sessionConfig.systemPrompt = prepared.systemPrompt;
        sessionConfig.temperature = prepared.temperature;
        sessionConfig.maxOutputTokens = prepared.maxOutputTokens;

        InferenceEngine.Session session = mEngineHolder.engine().createSession(sessionConfig);
        synchronized (cancelledBeforeStart) {
            if (cancelledBeforeStart[0]) {
                session.close();
                throw new InferenceEngine.EngineException(Errors.CANCELLED, "cancelled");
            }
            activeSession[0] = session;
        }

        Bitmap image = request.getParcelable(RequestKeys.KEY_IMAGE, Bitmap.class);
        if (image != null) {
            session.addImage(image);
        }
        short[] pcm = readAudio(request);
        if (pcm != null) {
            session.addAudio(pcm, 16_000);
        }

        StringBuilder full = new StringBuilder();
        session.generate(prepared.userTurn, chunk -> {
            full.append(chunk);
            if (streaming != null) {
                Bundle partial = new Bundle();
                partial.putString(RequestKeys.KEY_RESPONSE, chunk);
                streaming.onPartialResult(partial);
            }
            return true;
        });

        Bundle result = new Bundle();
        result.putString(RequestKeys.KEY_RESPONSE, full.toString());
        result.putBoolean(RequestKeys.KEY_DONE, true);
        result.putString(RequestKeys.KEY_BACKEND,
                DeviceTier.backendName(mEngineHolder.engine().activeBackend()));
        callback.onResult(result);
    }

    /**
     * Blocks the worker until the broker has handed over the model descriptors.
     *
     * <p>The platform's file-descriptor provider is asynchronous, but the worker thread is already
     * the serialisation point for the whole process, so waiting here is what "one request at a
     * time" means rather than an extra stall.
     */
    private Map<String, ParcelFileDescriptor> fetchFilesBlocking(Feature feature)
            throws InferenceEngine.EngineException {
        final Object[] holder = new Object[1];
        final CountDownLatch latch = new CountDownLatch(1);
        fetchFeatureFileDescriptorMap(feature, Runnable::run, map -> {
            holder[0] = map;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InferenceEngine.EngineException(Errors.CANCELLED, "interrupted");
        }
        @SuppressWarnings("unchecked")
        Map<String, ParcelFileDescriptor> map = (Map<String, ParcelFileDescriptor>) holder[0];
        if (map == null || map.isEmpty()) {
            throw new InferenceEngine.EngineException(Errors.MODEL_NOT_DOWNLOADED,
                    "broker returned no model descriptors");
        }
        return map;
    }

    private short[] readAudio(Bundle request) throws InferenceEngine.EngineException {
        ParcelFileDescriptor pfd = request.getParcelable(
                RequestKeys.KEY_AUDIO, ParcelFileDescriptor.class);
        if (pfd == null) {
            return null;
        }
        try (ParcelFileDescriptor.AutoCloseInputStream in =
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] bytes = in.readAllBytes();
            short[] pcm = new short[bytes.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                // Little-endian 16-bit PCM, which is what AudioRecord produces.
                pcm[i] = (short) ((bytes[2 * i] & 0xff) | (bytes[2 * i + 1] << 8));
            }
            return pcm;
        } catch (Exception e) {
            throw new InferenceEngine.EngineException(Errors.BAD_REQUEST, "unreadable audio");
        }
    }

    private String checkThermal() {
        synchronized (mStateLock) {
            // Refuse new work from MODERATE upwards. In-flight generation is aborted by the
            // broker dropping a SEVERE state push, which cancels through the same path a client
            // cancellation uses.
            return mThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
                    ? Errors.THERMAL_THROTTLED : null;
        }
    }

    private int contextTokens() {
        synchronized (mStateLock) {
            return mContextTokens;
        }
    }

    private void notifyLifecycle(int event, Feature feature) {
        LifecycleListener listener = mLifecycleListener;
        if (listener != null && feature != null) {
            listener.onLifecycleEvent(event, feature);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("OpenAICore inference sandbox");
        pw.println("  tier=" + DeviceTier.name(mTier)
                + " backend=" + DeviceTier.backendName(mBackend)
                + " ctx=" + mContextTokens
                + " speculative=" + mSpeculative
                + " thermal=" + mThermalStatus);
        pw.println("  inFlight=" + mInFlight.get()
                + " completed=" + mCompleted.get()
                + " failed=" + mFailed.get());
        mEngineHolder.dump(pw);
        mQuota.dump(pw);
    }
}
