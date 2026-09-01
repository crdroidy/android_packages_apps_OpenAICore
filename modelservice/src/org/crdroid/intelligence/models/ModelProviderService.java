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

package org.crdroid.intelligence.models;

import android.app.Service;
import android.app.ondeviceintelligence.DownloadCallback;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

import org.crdroid.intelligence.common.IModelDownloadCallback;
import org.crdroid.intelligence.common.IModelProvider;
import org.crdroid.intelligence.common.ModelInfo;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The only OpenAICore component with network access.
 *
 * <p>It lives in its own package for one reason: the intelligence roles that gate the AppFunctions
 * registry require their holder not to request INTERNET, and to reach the network only through a
 * separate open-source component. Splitting the downloader out is what keeps that role reachable
 * for the broker, and it is the same shape AICore uses with Private Compute Services.
 */
public final class ModelProviderService extends Service {

    private static final String TAG = "OpenAICore.ModelProvider";
    private static final String PREFS = "licences";

    private ModelStore mStore;
    private ModelCatalog mCatalog;
    private SharedPreferences mLicences;
    private ExecutorService mExecutor;

    private final Map<String, ModelDownloader> mActive = new ArrayMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        mStore = new ModelStore(this);
        mCatalog = new ModelCatalog(this);
        Context de = createDeviceProtectedStorageContext();
        mLicences = de.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IModelProvider.Stub mBinder = new IModelProvider.Stub() {

        @Override
        public ModelInfo getModelInfo(String modelId) {
            return infoFor(modelId);
        }

        @Override
        public List<ModelInfo> listModels() {
            return allModelInfos();
        }

        @Override
        public ParcelFileDescriptor openModel(String modelId) {
            try {
                return mStore.openModel(modelId);
            } catch (FileNotFoundException e) {
                return null;
            }
        }

        @Override
        public void startDownload(String modelId, IModelDownloadCallback callback) {
            ModelCatalog.Entry entry = mCatalog.latest(modelId);
            if (entry == null) {
                fail(callback, DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        "unknown model " + modelId);
                return;
            }
            if (!mLicences.getBoolean(modelId, false)) {
                fail(callback, DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                        "LICENCE_NOT_ACCEPTED");
                return;
            }
            if (mStore.isAvailable(modelId)) {
                try {
                    callback.onCompleted();
                } catch (RemoteException ignored) {
                    // The requester went away; the model is installed either way.
                }
                return;
            }
            synchronized (mActive) {
                if (mActive.containsKey(modelId)) {
                    fail(callback, DownloadCallback.DOWNLOAD_FAILURE_STATUS_DOWNLOADING,
                            "already downloading");
                    return;
                }
                ModelDownloader downloader = new ModelDownloader(mStore);
                mActive.put(modelId, downloader);
                mExecutor.execute(() -> {
                    try {
                        downloader.run(entry, new ModelDownloader.Progress() {
                            @Override
                            public void onStarted(long totalBytes) {
                                post(() -> callback.onStarted(totalBytes));
                            }

                            @Override
                            public void onProgress(long bytesSoFar) {
                                post(() -> callback.onProgress(bytesSoFar));
                            }

                            @Override
                            public void onCompleted() {
                                post(callback::onCompleted);
                            }

                            @Override
                            public void onFailed(int status, String reason) {
                                post(() -> callback.onFailed(status, reason));
                            }
                        });
                    } finally {
                        synchronized (mActive) {
                            mActive.remove(modelId);
                        }
                    }
                });
            }
        }

        @Override
        public void cancelDownload(String modelId) {
            synchronized (mActive) {
                ModelDownloader downloader = mActive.get(modelId);
                if (downloader != null) {
                    downloader.cancel();
                }
            }
        }

        @Override
        public void deleteAll() {
            synchronized (mActive) {
                for (ModelDownloader downloader : mActive.values()) {
                    downloader.cancel();
                }
            }
            mStore.deleteAll();
        }

        @Override
        public void setLicenceAccepted(String modelId, boolean accepted) {
            mLicences.edit().putBoolean(modelId, accepted).apply();
        }
    };

    private List<ModelInfo> allModelInfos() {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelCatalog.Entry entry : mCatalog.all()) {
            ModelInfo info = infoFor(entry.id);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    private ModelInfo infoFor(String modelId) {
        ModelCatalog.Entry entry = mCatalog.latest(modelId);
        if (entry == null) {
            return null;
        }
        int state;
        long downloaded = 0;
        synchronized (mActive) {
            if (mStore.isAvailable(modelId)) {
                state = ModelInfo.STATE_AVAILABLE;
                downloaded = mStore.sizeOnDisk(modelId);
            } else if (mActive.containsKey(modelId)) {
                state = ModelInfo.STATE_DOWNLOADING;
                downloaded = mStore.modelFile(entry.id, entry.version).length();
            } else {
                state = ModelInfo.STATE_DOWNLOADABLE;
                downloaded = mStore.modelFile(entry.id, entry.version).length();
            }
        }
        return new ModelInfo(entry.id, entry.displayName, state, entry.sizeBytes, downloaded,
                entry.modalities, entry.maxTokens, entry.licenceName, entry.licenceUrl,
                mLicences.getBoolean(modelId, false));
    }

    private interface RemoteAction {
        void run() throws RemoteException;
    }

    private static void post(RemoteAction action) {
        try {
            action.run();
        } catch (RemoteException e) {
            // The broker died mid-download. The download itself carries on and the result is
            // picked up from the store on the next query.
            Log.d(TAG, "download callback recipient is gone");
        }
    }

    private static void fail(IModelDownloadCallback callback, int status, String reason) {
        post(() -> callback.onFailed(status, reason));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("OpenAICore model provider");
        pw.println("  usableSpaceMb=" + (mStore.usableSpaceBytes() >> 20));
        for (ModelInfo info : allModelInfos()) {
            pw.printf("  %s state=%d bytes=%d/%d licenceAccepted=%b%n",
                    info.id, info.state, info.bytesDownloaded, info.sizeBytes,
                    info.licenceAccepted);
        }
        synchronized (mActive) {
            pw.println("  activeDownloads=" + mActive.keySet());
        }
    }
}
