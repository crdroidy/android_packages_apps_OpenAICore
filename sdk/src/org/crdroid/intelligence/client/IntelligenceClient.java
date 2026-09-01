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

package org.crdroid.intelligence.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Task-shaped wrapper over OpenAICore, for apps on a crDroid device.
 *
 * <p>The method shapes mirror ML Kit's GenAI API on purpose. OpenAICore is not binary compatible
 * with it and never will be — ML Kit's client libraries bind Google's AICore through Play
 * Services and are documented as unsupported on an unlocked bootloader, which every crDroid
 * install has — but matching the shape makes porting app code a matter of changing an import,
 * and it matches what developers already expect.
 *
 * <p>{@code UNAVAILABLE} is a routine answer here in a way it is not on Pixel. Across crDroid's
 * device matrix a large fraction of installs have no usable GPU path or not enough RAM, and this
 * client will tell you so rather than failing at generation time. Handle it.
 */
public final class IntelligenceClient {

    /** Mirrors {@code android.app.ondeviceintelligence.FeatureDetails}. */
    public static final int STATUS_UNAVAILABLE = 0;
    public static final int STATUS_DOWNLOADABLE = 1;
    public static final int STATUS_DOWNLOADING = 2;
    public static final int STATUS_AVAILABLE = 3;
    public static final int STATUS_SERVICE_UNAVAILABLE = 4;

    public static final int FEATURE_PROMPT = 1;
    public static final int FEATURE_SUMMARIZE = 2;
    public static final int FEATURE_PROOFREAD = 3;
    public static final int FEATURE_REWRITE = 4;
    public static final int FEATURE_DESCRIBE_IMAGE = 5;
    public static final int FEATURE_TRANSCRIBE = 6;

    public static final String TONE_FORMAL = "formal";
    public static final String TONE_CASUAL = "casual";
    public static final String TONE_CONCISE = "concise";
    public static final String TONE_ELABORATE = "elaborate";

    private static final String SERVICE_PACKAGE = "org.crdroid.intelligence";
    private static final String SERVICE_CLASS =
            "org.crdroid.intelligence.api.OpenIntelligenceService";
    private static final String KEY_PREFIX = "org.crdroid.intelligence.";

    /** Receives generation results. All methods are delivered on the supplied executor. */
    public interface ResultCallback {
        /** Streaming only. Each call carries the next fragment, not the accumulated text. */
        default void onPartial(String fragment) {}

        void onComplete(String text);

        /** {@code reason} is a stable identifier such as {@code BUSY} or {@code THERMAL_THROTTLED}. */
        void onError(String reason, String detail);
    }

    public interface DownloadCallback {
        void onProgress(long bytesSoFar, long totalBytes);

        void onComplete();

        void onError(String reason, String detail);
    }

    public interface ConnectionCallback {
        void onConnected(IntelligenceClient client);

        /** Called when OpenAICore is not present on this build at all. */
        void onUnavailable();
    }

    private final Context mContext;
    private final Executor mExecutor;

    private IOpenIntelligence mService;
    private ServiceConnection mConnection;

    private IntelligenceClient(Context context, Executor executor) {
        mContext = context.getApplicationContext();
        mExecutor = executor;
    }

    /**
     * Connects to OpenAICore. The caller must already hold
     * {@code org.crdroid.intelligence.permission.USE_INTELLIGENCE}; without it the bind is
     * refused and {@link ConnectionCallback#onUnavailable} is called.
     */
    public static void connect(Context context, Executor executor, ConnectionCallback callback) {
        IntelligenceClient client = new IntelligenceClient(context, executor);
        client.mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                client.mService = IOpenIntelligence.Stub.asInterface(service);
                executor.execute(() -> callback.onConnected(client));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                client.mService = null;
            }
        };
        Intent intent = new Intent("org.crdroid.intelligence.OpenIntelligenceService");
        intent.setComponent(new ComponentName(SERVICE_PACKAGE, SERVICE_CLASS));
        if (!context.bindService(intent, client.mConnection, Context.BIND_AUTO_CREATE)) {
            executor.execute(callback::onUnavailable);
        }
    }

    public void close() {
        if (mConnection != null) {
            mContext.unbindService(mConnection);
            mConnection = null;
            mService = null;
        }
    }

    public List<Integer> listFeatures() {
        List<Integer> out = new ArrayList<>();
        if (mService == null) {
            return out;
        }
        try {
            for (int id : mService.listFeatures()) {
                out.add(id);
            }
        } catch (RemoteException e) {
            return out;
        }
        return out;
    }

    public int checkFeatureStatus(int featureId) {
        if (mService == null) {
            return STATUS_SERVICE_UNAVAILABLE;
        }
        try {
            return mService.getFeatureStatus(featureId);
        } catch (RemoteException e) {
            return STATUS_SERVICE_UNAVAILABLE;
        }
    }

    public void downloadFeature(int featureId, DownloadCallback callback) {
        if (mService == null) {
            mExecutor.execute(() -> callback.onError("SERVICE_UNAVAILABLE", null));
            return;
        }
        try {
            mService.requestDownload(featureId, new CallbackAdapter(mExecutor, null, callback));
        } catch (RemoteException e) {
            mExecutor.execute(() -> callback.onError("SERVICE_UNAVAILABLE", null));
        }
    }

    // ---- task helpers ----

    public long prompt(String prompt, boolean streaming, ResultCallback callback) {
        Bundle request = new Bundle();
        request.putString(KEY_PREFIX + "prompt", prompt);
        return process(FEATURE_PROMPT, request, streaming, callback);
    }

    public long summarize(String text, ResultCallback callback) {
        Bundle request = new Bundle();
        request.putString(KEY_PREFIX + "text", text);
        return process(FEATURE_SUMMARIZE, request, false, callback);
    }

    public long proofread(String text, ResultCallback callback) {
        Bundle request = new Bundle();
        request.putString(KEY_PREFIX + "text", text);
        return process(FEATURE_PROOFREAD, request, false, callback);
    }

    public long rewrite(String text, String tone, ResultCallback callback) {
        Bundle request = new Bundle();
        request.putString(KEY_PREFIX + "text", text);
        request.putString(KEY_PREFIX + "tone", tone);
        return process(FEATURE_REWRITE, request, false, callback);
    }

    public long describeImage(Bitmap image, ResultCallback callback) {
        Bundle request = new Bundle();
        request.putParcelable(KEY_PREFIX + "image", image);
        return process(FEATURE_DESCRIBE_IMAGE, request, false, callback);
    }

    public long process(int featureId, Bundle request, boolean streaming,
            ResultCallback callback) {
        if (mService == null) {
            mExecutor.execute(() -> callback.onError("SERVICE_UNAVAILABLE", null));
            return 0;
        }
        try {
            return mService.process(featureId, request, streaming,
                    new CallbackAdapter(mExecutor, callback, null));
        } catch (RemoteException e) {
            mExecutor.execute(() -> callback.onError("SERVICE_UNAVAILABLE", null));
            return 0;
        }
    }

    public void cancel(long token) {
        if (mService == null || token == 0) {
            return;
        }
        try {
            mService.cancel(token);
        } catch (RemoteException e) {
            // Nothing to cancel: the service is gone and the request with it.
        }
    }

    private static final class CallbackAdapter extends IOpenIntelligenceCallback.Stub {
        private final Executor mExecutor;
        private final ResultCallback mResult;
        private final DownloadCallback mDownload;
        private long mTotalBytes;

        CallbackAdapter(Executor executor, ResultCallback result, DownloadCallback download) {
            mExecutor = executor;
            mResult = result;
            mDownload = download;
        }

        @Override
        public void onPartialResult(Bundle partial) {
            if (mResult == null) {
                return;
            }
            String fragment = partial.getString(KEY_PREFIX + "response", "");
            mExecutor.execute(() -> mResult.onPartial(fragment));
        }

        @Override
        public void onResult(Bundle result) {
            if (mDownload != null) {
                mExecutor.execute(mDownload::onComplete);
                return;
            }
            String text = result.getString(KEY_PREFIX + "response", "");
            mExecutor.execute(() -> mResult.onComplete(text));
        }

        @Override
        public void onError(String reason, String detail) {
            if (mDownload != null) {
                mExecutor.execute(() -> mDownload.onError(reason, detail));
                return;
            }
            mExecutor.execute(() -> mResult.onError(reason, detail));
        }

        @Override
        public void onDownloadProgress(long bytesSoFar, long totalBytes) {
            if (mDownload == null) {
                return;
            }
            if (totalBytes > 0) {
                mTotalBytes = totalBytes;
            }
            long total = mTotalBytes;
            mExecutor.execute(() -> mDownload.onProgress(bytesSoFar, total));
        }
    }
}
