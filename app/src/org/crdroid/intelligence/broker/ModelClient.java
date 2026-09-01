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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.crdroid.intelligence.common.IModelProvider;
import org.crdroid.intelligence.common.ModelInfo;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The broker's handle on the separate downloader package.
 *
 * <p>The connection is short-lived on purpose: the provider only matters while a model is being
 * queried, opened or fetched, and leaving it bound would keep a process with network access alive
 * for no reason.
 */
public final class ModelClient {

    private static final String TAG = "OpenAICore.ModelClient";
    private static final String PROVIDER_PACKAGE = "org.crdroid.intelligence.models";
    private static final String PROVIDER_CLASS =
            "org.crdroid.intelligence.models.ModelProviderService";
    private static final long BIND_TIMEOUT_SECONDS = 10;

    private final Context mContext;

    private final Object mLock = new Object();
    private IModelProvider mProvider;
    private CountDownLatch mConnectLatch;
    private boolean mBindRequested;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mProvider = IModelProvider.Stub.asInterface(service);
                if (mConnectLatch != null) {
                    mConnectLatch.countDown();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mProvider = null;
            }
        }
    };

    public ModelClient(Context context) {
        mContext = context;
    }

    /** Blocks briefly for the provider. Callers are already on a background thread. */
    private IModelProvider provider() {
        CountDownLatch latch;
        synchronized (mLock) {
            if (mProvider != null) {
                return mProvider;
            }
            if (!mBindRequested) {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(PROVIDER_PACKAGE, PROVIDER_CLASS));
                mBindRequested = mContext.bindService(
                        intent, mConnection, Context.BIND_AUTO_CREATE);
                if (!mBindRequested) {
                    Log.w(TAG, "model provider package is not installed on this build");
                    return null;
                }
            }
            if (mConnectLatch == null) {
                mConnectLatch = new CountDownLatch(1);
            }
            latch = mConnectLatch;
        }
        try {
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "timed out binding the model provider");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        synchronized (mLock) {
            return mProvider;
        }
    }

    public ModelInfo info(String modelId) {
        IModelProvider p = provider();
        if (p == null) {
            return null;
        }
        try {
            return p.getModelInfo(modelId);
        } catch (RemoteException e) {
            return null;
        }
    }

    public List<ModelInfo> list() {
        IModelProvider p = provider();
        if (p == null) {
            return new ArrayList<>();
        }
        try {
            return p.listModels();
        } catch (RemoteException e) {
            return new ArrayList<>();
        }
    }

    /**
     * A read-only descriptor for the model, relayed straight to the sandbox. The broker never
     * reads it: it only moves the descriptor from the process that has the file to the process
     * that is not allowed to open one.
     */
    public ParcelFileDescriptor openModel(String modelId) {
        IModelProvider p = provider();
        if (p == null) {
            return null;
        }
        try {
            return p.openModel(modelId);
        } catch (RemoteException e) {
            return null;
        }
    }

    public IModelProvider providerOrNull() {
        return provider();
    }

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  model provider: " + (mProvider == null ? "unbound" : "bound"));
        }
        for (ModelInfo info : list()) {
            pw.printf("    %s state=%d bytes=%d/%d licenceAccepted=%b%n",
                    info.id, info.state, info.bytesDownloaded, info.sizeBytes,
                    info.licenceAccepted);
        }
    }
}
