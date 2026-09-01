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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.crdroid.intelligence.common.DeviceTier;

import java.util.function.Consumer;

/**
 * Runs the backend probe in its own short-lived process and collects the answer.
 *
 * <p>Probing means constructing a real GPU engine on a driver that may be broken. Doing that
 * in-process would mean a driver fault takes the broker with it, so the probe gets a throwaway
 * process and a timeout: a hang has to cost one probe, not the service.
 */
public final class BackendProbeClient {

    private static final String TAG = "OpenAICore.ProbeClient";
    private static final String PROBE_CLASS = "org.crdroid.intelligence.probe.BackendProbeService";

    /** Engine construction plus a short generation. A driver that has not answered by now is stuck. */
    private static final long PROBE_TIMEOUT_MS = 90_000L;

    public static final int MSG_PROBE = 1;
    public static final int MSG_RESULT = 2;

    private BackendProbeClient() {}

    public static void run(Context context, Handler handler,
            Consumer<BackendProbeResult> onResult) {
        String fingerprint = DeviceFingerprint.compute(
                NativeProbe.glRenderer(), NativeProbe.glVersion(),
                NativeProbe.openClPlatformName());

        // Cheap disqualifiers first, so a device with no OpenCL at all never starts a process.
        if (!NativeProbe.hasOpenCl()) {
            onResult.accept(new BackendProbeResult(DeviceTier.BACKEND_GLES, false, fingerprint,
                    0f, "no_opencl"));
            return;
        }
        if (NativeProbe.isAngleOpenCl()) {
            // Known to fail at engine construction rather than degrade. Step down now.
            onResult.accept(new BackendProbeResult(DeviceTier.BACKEND_GLES, false, fingerprint,
                    0f, "angle_cl"));
            return;
        }

        final boolean[] answered = new boolean[1];
        final ServiceConnection[] connectionHolder = new ServiceConnection[1];

        Messenger replyTo = new Messenger(new Handler(handler.getLooper(), msg -> {
            if (msg.what != MSG_RESULT) {
                return false;
            }
            synchronized (answered) {
                if (answered[0]) {
                    return true;
                }
                answered[0] = true;
            }
            Bundle data = msg.getData();
            onResult.accept(BackendProbeResult.fromBundle(data));
            unbind(context, connectionHolder[0]);
            return true;
        }));

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Message msg = Message.obtain(null, MSG_PROBE);
                msg.replyTo = replyTo;
                Bundle args = new Bundle();
                args.putString(BackendProbeResult.KEY_FINGERPRINT, fingerprint);
                msg.setData(args);
                try {
                    new Messenger(service).send(msg);
                } catch (RemoteException e) {
                    finishWithFailure(context, this, answered, onResult, fingerprint, "send_failed");
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // The probe process died, which is exactly the outcome running it out of process
                // is meant to survive. Treat it as a failed rung of the ladder.
                finishWithFailure(context, this, answered, onResult, fingerprint, "probe_crashed");
            }
        };
        connectionHolder[0] = connection;

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(context.getPackageName(), PROBE_CLASS));
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            onResult.accept(new BackendProbeResult(DeviceTier.BACKEND_CPU, false, fingerprint,
                    0f, "probe_bind_failed"));
            return;
        }

        handler.postDelayed(() -> finishWithFailure(context, connection, answered, onResult,
                fingerprint, "probe_timeout"), PROBE_TIMEOUT_MS);
    }

    private static void finishWithFailure(Context context, ServiceConnection connection,
            boolean[] answered, Consumer<BackendProbeResult> onResult, String fingerprint,
            String reason) {
        synchronized (answered) {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
        }
        Log.w(TAG, "probe did not complete: " + reason);
        onResult.accept(new BackendProbeResult(DeviceTier.BACKEND_CPU, false, fingerprint,
                0f, reason));
        unbind(context, connection);
    }

    private static void unbind(Context context, ServiceConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException e) {
            // Already unbound, which happens when the timeout and the reply race.
        }
    }
}
