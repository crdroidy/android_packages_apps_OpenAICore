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

package org.crdroid.intelligence.broker;

import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.FeatureDetails;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.OutcomeReceiver;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.service.ondeviceintelligence.OnDeviceIntelligenceService;
import android.util.Log;

import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.Errors;
import org.crdroid.intelligence.common.IModelDownloadCallback;
import org.crdroid.intelligence.common.IModelProvider;
import org.crdroid.intelligence.common.ModelInfo;
import org.crdroid.intelligence.common.RequestKeys;
import org.crdroid.intelligence.probe.BackendProbeClient;
import org.crdroid.intelligence.probe.DeviceFingerprint;
import org.crdroid.intelligence.probe.NativeProbe;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * The privileged half of OpenAICore, and the only part that decides anything.
 *
 * <p>It owns the feature registry, consent and tiering, and it is the sole source of file
 * descriptors for the sandbox — which has no way to open a file for itself. It also observes
 * device state the sandbox cannot see and pushes it down. What it does not do is touch request or
 * response content: that never enters this process.
 *
 * <p>Note what the broker does <em>not</em> sit in front of. The platform routes
 * {@code processRequest} from the manager service straight to the sandbox; it never passes
 * through here. So per-caller admission cannot live in this class:
 *
 * <ul>
 *   <li>Per-uid quota lives in the sandbox, which is the component that receives {@code callerUid}
 *       on every request.
 *   <li>Per-package consent is enforced where a package name is actually available — on the
 *       feature-listing calls below, and in {@code OpenIntelligenceService} for third parties.
 *   <li>The global kill switch is enforced by refusing to hand over the model descriptor, which
 *       is the one thing every request needs and the one thing only this process can supply.
 *       A caller replaying a stale {@link Feature} therefore still fails.
 * </ul>
 */
public final class IntelligenceBrokerService extends OnDeviceIntelligenceService {

    private static final String TAG = "OpenAICore.Broker";

    /** Bumped whenever the request or response contract changes. Exposed via getVersion(). */
    private static final long SERVICE_VERSION = 1L;

    private HandlerThread mThread;
    private Handler mHandler;

    private ModelClient mModels;
    private ConsentStore mConsent;
    private FeatureRegistry mRegistry;
    private ResourceGovernor mGovernor;
    private TierStore mTierStore;

    private volatile boolean mInferenceConnected;

    @Override
    public void onCreate() {
        super.onCreate();
        mThread = new HandlerThread("OpenAICore.broker", Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());

        mModels = new ModelClient(this);
        mConsent = new ConsentStore(this);
        mRegistry = new FeatureRegistry(mModels, mConsent);
        mTierStore = new TierStore(this);
        mGovernor = new ResourceGovernor(this, this::onThermalChanged);
        mGovernor.start();
    }

    @Override
    public void onDestroy() {
        mGovernor.stop();
        mThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public void onReady() {
        mHandler.post(this::resolveTier);
    }

    @Override
    public void onInferenceServiceConnected() {
        mInferenceConnected = true;
        mHandler.post(this::pushProcessingState);
    }

    @Override
    public void onInferenceServiceDisconnected() {
        mInferenceConnected = false;
    }

    @Override
    public void onGetVersion(LongConsumer versionConsumer) {
        versionConsumer.accept(SERVICE_VERSION);
    }

    @Override
    public void onListFeatures(int callerUid,
            OutcomeReceiver<List<Feature>, OnDeviceIntelligenceException> callback) {
        mHandler.post(() -> {
            String denial = admit(callerUid);
            if (denial != null) {
                callback.onError(Errors.of(denial));
                return;
            }
            callback.onResult(mRegistry.listFeatures());
        });
    }

    @Override
    public void onListFeatures(int callerUid, PersistableBundle filter,
            OutcomeReceiver<List<Feature>, OnDeviceIntelligenceException> callback) {
        // No filter dimensions are defined yet; ignoring the bundle is the documented behaviour
        // for an implementation that recognises none of its keys.
        onListFeatures(callerUid, callback);
    }

    @Override
    public void onGetFeature(int callerUid, int featureId,
            OutcomeReceiver<Feature, OnDeviceIntelligenceException> callback) {
        mHandler.post(() -> {
            String denial = admit(callerUid);
            if (denial != null) {
                callback.onError(Errors.of(denial));
                return;
            }
            Feature feature = mRegistry.getFeature(featureId);
            if (feature == null) {
                callback.onError(Errors.of(Errors.FEATURE_UNAVAILABLE, "id " + featureId));
                return;
            }
            callback.onResult(feature);
        });
    }

    @Override
    public void onGetFeatureDetails(int callerUid, Feature feature,
            OutcomeReceiver<FeatureDetails, OnDeviceIntelligenceException> callback) {
        mHandler.post(() -> callback.onResult(mRegistry.detailsFor(feature)));
    }

    @Override
    public void onGetFeatureMetadata(Feature feature, Consumer<Bundle> metadataConsumer) {
        Bundle metadata = new Bundle();
        metadata.putString("tier", DeviceTier.name(mRegistry.tier()));
        metadata.putString("model", mRegistry.activeModelId());
        metadata.putString("backend", DeviceTier.backendName(mTierStore.backend()));
        metadataConsumer.accept(metadata);
    }

    /**
     * Hands the sandbox read-only descriptors for everything it needs.
     *
     * <p>This is the whole reason the isolated process can work at all: it may read and map a
     * descriptor it is given, but SELinux forbids it opening an app data file itself. The
     * descriptors are closed by the platform once they have been sent.
     */
    @Override
    public void onGetReadOnlyFeatureFileDescriptorMap(Feature feature,
            Consumer<Map<String, ParcelFileDescriptor>> fileDescriptorMapConsumer) {
        mHandler.post(() -> {
            Map<String, ParcelFileDescriptor> map = new HashMap<>();
            if (!mConsent.isGloballyEnabled()) {
                // The enforcement point for the master switch. The platform sends inference
                // requests straight to the sandbox, so refusing the descriptor is what actually
                // stops a caller holding a Feature from before the user turned this off.
                fileDescriptorMapConsumer.accept(map);
                return;
            }
            String modelId = mRegistry.activeModelId();
            ParcelFileDescriptor model = mModels.openModel(modelId);
            if (model != null) {
                map.put(RequestKeys.FD_MODEL, model);
            } else {
                Log.w(TAG, "model not present for " + modelId);
            }
            fileDescriptorMapConsumer.accept(map);
        });
    }

    @Override
    public void onDownloadFeature(int callerUid, Feature feature,
            CancellationSignal cancellationSignal, DownloadCallback downloadCallback) {
        mHandler.post(() -> {
            String modelId = mRegistry.activeModelId();
            if (!mConsent.isGloballyEnabled()) {
                downloadCallback.onDownloadFailed(
                        DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        Errors.NOT_CONSENTED, new PersistableBundle());
                return;
            }
            ModelInfo info = mModels.info(modelId);
            if (info == null) {
                downloadCallback.onDownloadFailed(
                        DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        "MODEL_PROVIDER_UNAVAILABLE", new PersistableBundle());
                return;
            }
            if (!info.licenceAccepted) {
                // The Gemma weights are governed by their own terms rather than an OSI licence.
                // Nothing is fetched until the user has seen and accepted them in Settings, which
                // is also why crDroid does not mirror the files.
                downloadCallback.onDownloadFailed(
                        DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        "LICENCE_NOT_ACCEPTED", new PersistableBundle());
                return;
            }
            IModelProvider provider = mModels.providerOrNull();
            if (provider == null) {
                downloadCallback.onDownloadFailed(
                        DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        "MODEL_PROVIDER_UNAVAILABLE", new PersistableBundle());
                return;
            }
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(() -> {
                    try {
                        provider.cancelDownload(modelId);
                    } catch (RemoteException e) {
                        Log.d(TAG, "cancel could not reach the model provider");
                    }
                });
            }
            try {
                provider.startDownload(modelId, new IModelDownloadCallback.Stub() {
                    @Override
                    public void onStarted(long totalBytes) {
                        downloadCallback.onDownloadStarted(totalBytes);
                    }

                    @Override
                    public void onProgress(long bytesSoFar) {
                        downloadCallback.onDownloadProgress(bytesSoFar);
                    }

                    @Override
                    public void onCompleted() {
                        mHandler.post(IntelligenceBrokerService.this::resolveTier);
                        downloadCallback.onDownloadCompleted(new PersistableBundle());
                    }

                    @Override
                    public void onFailed(int status, String reason) {
                        downloadCallback.onDownloadFailed(status, reason, new PersistableBundle());
                    }
                });
            } catch (RemoteException e) {
                downloadCallback.onDownloadFailed(
                        DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNKNOWN,
                        "PROVIDER_DIED", new PersistableBundle());
            }
        });
    }

    // ---- device state ----

    private void onThermalChanged(int status) {
        mHandler.post(this::pushProcessingState);
    }

    /**
     * Recomputes the tier, running the backend probe when the cached answer no longer applies.
     *
     * <p>The probe runs in a separate short-lived process so a GPU driver crash costs a probe
     * rather than the broker.
     */
    private void resolveTier() {
        int seeded = mTierStore.seededBackend();
        if (seeded == DeviceTier.BACKEND_CPU || seeded == DeviceTier.BACKEND_NONE) {
            applyTier(seeded, /* speculative= */ false);
            return;
        }
        String fingerprint = DeviceFingerprint.compute(
                NativeProbe.glRenderer(), NativeProbe.glVersion(), NativeProbe.openClPlatformName());
        if (!mTierStore.needsProbe(fingerprint)) {
            applyTier(mTierStore.backend(), mTierStore.speculative());
            return;
        }
        if (!mRegistry.isModelInstalled()) {
            // A full probe constructs a real engine, so it cannot run before the model exists.
            // Until then, advertise conservatively from the seed.
            applyTier(seeded >= 0 ? seeded : DeviceTier.BACKEND_CPU, false);
            return;
        }
        BackendProbeClient.run(this, mHandler, result -> {
            mTierStore.record(result);
            applyTier(result.backend, result.speculative);
        });
    }

    private void applyTier(int backend, boolean speculative) {
        int tier = DeviceTier.classify(mGovernor.totalRamBytes(), backend, speculative);
        mRegistry.setTier(tier);
        Log.i(TAG, "tier=" + DeviceTier.name(tier)
                + " backend=" + DeviceTier.backendName(backend)
                + " speculative=" + speculative);
        pushProcessingState();
    }

    /** Pushes everything the sandbox cannot observe for itself. */
    private void pushProcessingState() {
        if (!mInferenceConnected) {
            return;
        }
        int tier = mRegistry.tier();
        Bundle state = new Bundle();
        state.putInt(RequestKeys.STATE_TIER, tier);
        state.putInt(RequestKeys.STATE_BACKEND, mTierStore.backend());
        state.putInt(RequestKeys.STATE_CONTEXT_TOKENS, DeviceTier.defaultContextTokens(tier));
        state.putBoolean(RequestKeys.STATE_SPECULATIVE, mTierStore.speculative());
        state.putString(RequestKeys.STATE_MODEL_ID, mRegistry.activeModelId());
        state.putInt(RequestKeys.STATE_THERMAL, mGovernor.thermalStatus());
        state.putLong(RequestKeys.STATE_IDLE_UNLOAD_MS, 120_000L);
        try {
            updateProcessingState(state, mHandler::post,
                    new OutcomeReceiver<PersistableBundle, OnDeviceIntelligenceException>() {
                        @Override
                        public void onResult(PersistableBundle result) {}

                        @Override
                        public void onError(OnDeviceIntelligenceException e) {
                            Log.w(TAG, "state push rejected: " + e.getMessage());
                        }
                    });
        } catch (IllegalStateException e) {
            // The inference service went away between the connected callback and here. It will be
            // brought back up on the next request, and onInferenceServiceConnected pushes again.
            Log.d(TAG, "inference service unavailable for state push");
        }
    }

    /** Consent and resource admission for the calls that do reach this process. */
    private String admit(int callerUid) {
        if (!mConsent.isGloballyEnabled()) {
            return Errors.NOT_CONSENTED;
        }
        String[] packages = getPackageManager().getPackagesForUid(callerUid);
        if (packages != null) {
            boolean anyAllowed = false;
            for (String pkg : packages) {
                if (mConsent.isPackageAllowed(pkg)) {
                    anyAllowed = true;
                    break;
                }
            }
            if (!anyAllowed) {
                return Errors.NOT_CONSENTED;
            }
        }
        return null;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("OpenAICore broker");
        pw.println("  version=" + SERVICE_VERSION
                + " inferenceConnected=" + mInferenceConnected
                + " consent=" + mConsent.isGloballyEnabled());
        mRegistry.dump(pw);
        mTierStore.dump(pw);
        mGovernor.dump(pw);
        mModels.dump(pw);
    }
}
