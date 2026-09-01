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

package org.crdroid.intelligence.settings;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import org.crdroid.intelligence.broker.ModelClient;
import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.IModelDownloadCallback;
import org.crdroid.intelligence.common.IModelProvider;
import org.crdroid.intelligence.common.ModelInfo;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Settings-side wrapper that keeps the binder calls off the UI thread. */
final class SettingsModelClient {

    private static final String TAG = "OpenAICore.SettingsModels";

    interface ProgressListener {
        void onProgress(long bytesSoFar, long totalBytes);
    }

    private final ModelClient mClient;
    private final Executor mBackground;
    private final Handler mMain;

    SettingsModelClient(Context context, Executor background, Handler main) {
        mClient = new ModelClient(context);
        mBackground = background;
        mMain = main;
    }

    /** The model this device would use, given its tier. */
    void info(Consumer<ModelInfo> callback) {
        mBackground.execute(() -> {
            ModelInfo info = mClient.info(DeviceTier.modelForTier(DeviceTier.TIER_B));
            mMain.post(() -> callback.accept(info));
        });
    }

    void setLicenceAccepted(String modelId, boolean accepted) {
        mBackground.execute(() -> {
            IModelProvider provider = mClient.providerOrNull();
            if (provider == null) {
                return;
            }
            try {
                provider.setLicenceAccepted(modelId, accepted);
            } catch (RemoteException e) {
                Log.w(TAG, "could not record licence acceptance");
            }
        });
    }

    void download(String modelId, ProgressListener progress, Runnable onFinished) {
        mBackground.execute(() -> {
            IModelProvider provider = mClient.providerOrNull();
            if (provider == null) {
                mMain.post(onFinished);
                return;
            }
            try {
                provider.startDownload(modelId, new IModelDownloadCallback.Stub() {
                    private long mTotal;

                    @Override
                    public void onStarted(long totalBytes) {
                        mTotal = totalBytes;
                        mMain.post(() -> progress.onProgress(0, totalBytes));
                    }

                    @Override
                    public void onProgress(long bytesSoFar) {
                        long total = mTotal;
                        mMain.post(() -> progress.onProgress(bytesSoFar, total));
                    }

                    @Override
                    public void onCompleted() {
                        mMain.post(onFinished);
                    }

                    @Override
                    public void onFailed(int status, String reason) {
                        Log.w(TAG, "download failed: " + reason);
                        mMain.post(onFinished);
                    }
                });
            } catch (RemoteException e) {
                mMain.post(onFinished);
            }
        });
    }

    void deleteAll(Runnable onFinished) {
        mBackground.execute(() -> {
            IModelProvider provider = mClient.providerOrNull();
            if (provider != null) {
                try {
                    provider.deleteAll();
                } catch (RemoteException e) {
                    Log.w(TAG, "delete failed");
                }
            }
            mMain.post(onFinished);
        });
    }
}
