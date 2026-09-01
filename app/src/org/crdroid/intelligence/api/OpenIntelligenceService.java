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

package org.crdroid.intelligence.api;

import android.app.Service;
import android.app.ondeviceintelligence.DownloadCallback;
import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.FeatureDetails;
import android.app.ondeviceintelligence.OnDeviceIntelligenceException;
import android.app.ondeviceintelligence.OnDeviceIntelligenceManager;
import android.app.ondeviceintelligence.ProcessingCallback;
import android.app.ondeviceintelligence.StreamingProcessingCallback;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.LongSparseArray;

import org.crdroid.intelligence.client.IOpenIntelligence;
import org.crdroid.intelligence.client.IOpenIntelligenceCallback;
import org.crdroid.intelligence.common.Errors;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Re-exports the platform intelligence API to ordinary apps.
 *
 * <p>Every method on {@code OnDeviceIntelligenceManager} is {@code @SystemApi} guarded by
 * {@code USE_ON_DEVICE_INTELLIGENCE}, which is {@code signature|privileged}. A third-party APK on
 * a stock crDroid build cannot hold that, so "apps on crDroid can use local AI" needs a surface
 * crDroid owns. This service is that surface: it holds the privileged permission, and gates
 * callers on a runtime permission the user grants and revokes per app.
 *
 * <p>Doing it here rather than by relaxing the platform permission is deliberate. Loosening
 * {@code USE_ON_DEVICE_INTELLIGENCE} in {@code frameworks/base} would grant silent access to
 * every installed app, break CTS, and have to be re-landed every release.
 */
public final class OpenIntelligenceService extends Service {

    private OnDeviceIntelligenceManager mManager;
    private Executor mExecutor;

    private final AtomicLong mNextToken = new AtomicLong(1);
    private final LongSparseArray<CancellationSignal> mInFlight = new LongSparseArray<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mManager = getSystemService(OnDeviceIntelligenceManager.class);
        mExecutor = Executors.newCachedThreadPool();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IOpenIntelligence.Stub mBinder = new IOpenIntelligence.Stub() {

        @Override
        public int[] listFeatures() {
            if (mManager == null) {
                return new int[0];
            }
            final Object lock = new Object();
            final int[][] out = new int[1][];
            mManager.listFeatures(mExecutor,
                    new OutcomeReceiver<List<Feature>, OnDeviceIntelligenceException>() {
                        @Override
                        public void onResult(List<Feature> features) {
                            int[] ids = new int[features.size()];
                            for (int i = 0; i < ids.length; i++) {
                                ids[i] = features.get(i).getId();
                            }
                            synchronized (lock) {
                                out[0] = ids;
                                lock.notifyAll();
                            }
                        }

                        @Override
                        public void onError(OnDeviceIntelligenceException e) {
                            synchronized (lock) {
                                out[0] = new int[0];
                                lock.notifyAll();
                            }
                        }
                    });
            return awaitInts(lock, out);
        }

        @Override
        public int getFeatureStatus(int featureId) {
            Feature feature = fetchFeature(featureId);
            if (feature == null || mManager == null) {
                return FeatureDetails.FEATURE_STATUS_UNAVAILABLE;
            }
            final Object lock = new Object();
            final int[] out = new int[] {Integer.MIN_VALUE};
            mManager.getFeatureDetails(feature, mExecutor,
                    new OutcomeReceiver<FeatureDetails, OnDeviceIntelligenceException>() {
                        @Override
                        public void onResult(FeatureDetails details) {
                            synchronized (lock) {
                                out[0] = details.getFeatureStatus();
                                lock.notifyAll();
                            }
                        }

                        @Override
                        public void onError(OnDeviceIntelligenceException e) {
                            synchronized (lock) {
                                out[0] = FeatureDetails.FEATURE_STATUS_UNAVAILABLE;
                                lock.notifyAll();
                            }
                        }
                    });
            synchronized (lock) {
                while (out[0] == Integer.MIN_VALUE) {
                    try {
                        lock.wait(5_000);
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return FeatureDetails.FEATURE_STATUS_UNAVAILABLE;
                    }
                }
                return out[0] == Integer.MIN_VALUE
                        ? FeatureDetails.FEATURE_STATUS_UNAVAILABLE : out[0];
            }
        }

        @Override
        public void requestDownload(int featureId, IOpenIntelligenceCallback callback) {
            Feature feature = fetchFeature(featureId);
            if (feature == null || mManager == null) {
                safeError(callback, Errors.FEATURE_UNAVAILABLE, "id " + featureId);
                return;
            }
            final long[] total = new long[1];
            mManager.requestFeatureDownload(feature, null, mExecutor, new DownloadCallback() {
                @Override
                public void onDownloadStarted(long bytesToDownload) {
                    total[0] = bytesToDownload;
                    safeProgress(callback, 0, bytesToDownload);
                }

                @Override
                public void onDownloadProgress(long totalBytesDownloaded) {
                    safeProgress(callback, totalBytesDownloaded, total[0]);
                }

                @Override
                public void onDownloadCompleted(PersistableBundle bundle) {
                    try {
                        callback.onResult(new Bundle());
                    } catch (RemoteException ignored) {
                        // Caller went away; the download completed regardless.
                    }
                }

                @Override
                public void onDownloadFailed(int failureStatus, String errorMessage,
                        PersistableBundle bundle) {
                    safeError(callback, Errors.MODEL_NOT_DOWNLOADED, errorMessage);
                }
            });
        }

        @Override
        public long process(int featureId, Bundle request, boolean streaming,
                IOpenIntelligenceCallback callback) {
            Feature feature = fetchFeature(featureId);
            if (feature == null || mManager == null) {
                safeError(callback, Errors.FEATURE_UNAVAILABLE, "id " + featureId);
                return 0;
            }
            long token = mNextToken.getAndIncrement();
            CancellationSignal signal = new CancellationSignal();
            synchronized (mInFlight) {
                mInFlight.put(token, signal);
            }

            ProcessingCallback base = new ProcessingCallback() {
                @Override
                public void onResult(Bundle result) {
                    retire(token);
                    try {
                        callback.onResult(result);
                    } catch (RemoteException ignored) {
                        // Nothing to deliver to.
                    }
                }

                @Override
                public void onError(OnDeviceIntelligenceException e) {
                    retire(token);
                    safeError(callback, reasonOf(e), e.getMessage());
                }
            };

            if (streaming) {
                mManager.processRequestStreaming(feature, request,
                        OnDeviceIntelligenceManager.REQUEST_TYPE_INFERENCE, signal, null,
                        mExecutor, new StreamingProcessingCallback() {
                            @Override
                            public void onPartialResult(Bundle partial) {
                                try {
                                    callback.onPartialResult(partial);
                                } catch (RemoteException ignored) {
                                    signal.cancel();
                                }
                            }

                            @Override
                            public void onResult(Bundle result) {
                                base.onResult(result);
                            }

                            @Override
                            public void onError(OnDeviceIntelligenceException e) {
                                base.onError(e);
                            }
                        });
            } else {
                mManager.processRequest(feature, request,
                        OnDeviceIntelligenceManager.REQUEST_TYPE_INFERENCE, signal, null,
                        mExecutor, base);
            }
            return token;
        }

        @Override
        public void cancel(long token) {
            CancellationSignal signal;
            synchronized (mInFlight) {
                signal = mInFlight.get(token);
                mInFlight.remove(token);
            }
            if (signal != null) {
                signal.cancel();
            }
        }
    };

    private void retire(long token) {
        synchronized (mInFlight) {
            mInFlight.remove(token);
        }
    }

    private Feature fetchFeature(int featureId) {
        if (mManager == null) {
            return null;
        }
        final Object lock = new Object();
        final Feature[] out = new Feature[1];
        final boolean[] done = new boolean[1];
        mManager.getFeature(featureId, mExecutor,
                new OutcomeReceiver<Feature, OnDeviceIntelligenceException>() {
                    @Override
                    public void onResult(Feature feature) {
                        synchronized (lock) {
                            out[0] = feature;
                            done[0] = true;
                            lock.notifyAll();
                        }
                    }

                    @Override
                    public void onError(OnDeviceIntelligenceException e) {
                        synchronized (lock) {
                            done[0] = true;
                            lock.notifyAll();
                        }
                    }
                });
        synchronized (lock) {
            while (!done[0]) {
                try {
                    lock.wait(5_000);
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return out[0];
        }
    }

    private static int[] awaitInts(Object lock, int[][] out) {
        synchronized (lock) {
            while (out[0] == null) {
                try {
                    lock.wait(5_000);
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new int[0];
                }
            }
            return out[0] == null ? new int[0] : out[0];
        }
    }

    /**
     * The broker encodes its taxonomy in the exception message, because the platform's own code
     * set is coarser than the one callers need. Recover it, falling back to the generic reason.
     */
    private static String reasonOf(OnDeviceIntelligenceException e) {
        String message = e.getMessage();
        if (message == null) {
            return Errors.BAD_REQUEST;
        }
        int colon = message.indexOf(':');
        return colon > 0 ? message.substring(0, colon) : message;
    }

    private static void safeError(IOpenIntelligenceCallback callback, String reason,
            String detail) {
        try {
            callback.onError(reason, detail);
        } catch (RemoteException ignored) {
            // Caller went away.
        }
    }

    private static void safeProgress(IOpenIntelligenceCallback callback, long soFar, long total) {
        try {
            callback.onDownloadProgress(soFar, total);
        } catch (RemoteException ignored) {
            // Caller went away.
        }
    }
}
