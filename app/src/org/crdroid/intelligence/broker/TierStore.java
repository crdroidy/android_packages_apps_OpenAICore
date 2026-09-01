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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import org.crdroid.intelligence.R;
import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.probe.BackendProbeResult;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Caches the backend the probe chose, keyed on a hardware and driver fingerprint.
 *
 * <p>The in-tree seed file only supplies a blocklist and a handful of known-good entries. Tier is
 * otherwise decided by running the probe, because crDroid ships to a device matrix far too large
 * and too varied to maintain a hand-written table for, and a stale table is worse than no table:
 * it silently hides a working feature or promises one that hangs.
 */
final class TierStore {

    private static final String TAG = "OpenAICore.TierStore";
    private static final String PREFS = "tier";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_SPECULATIVE = "speculative";
    private static final String KEY_FAILURES = "consecutive_failures";

    /** After this many failed probes we stop trying and stay on CPU until the driver changes. */
    private static final int MAX_PROBE_FAILURES = 2;

    private final Context mContext;
    private final SharedPreferences mPrefs;

    TierStore(Context context) {
        mContext = context;
        Context de = context.isDeviceProtectedStorage()
                ? context : context.createDeviceProtectedStorageContext();
        mPrefs = de.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** True when the cached result no longer applies and the probe has to run again. */
    boolean needsProbe(String currentFingerprint) {
        if (mPrefs.getInt(KEY_FAILURES, 0) >= MAX_PROBE_FAILURES) {
            return !currentFingerprint.equals(mPrefs.getString(KEY_FINGERPRINT, null));
        }
        return !currentFingerprint.equals(mPrefs.getString(KEY_FINGERPRINT, null));
    }

    void record(BackendProbeResult result) {
        SharedPreferences.Editor editor = mPrefs.edit()
                .putString(KEY_FINGERPRINT, result.fingerprint)
                .putInt(KEY_BACKEND, result.backend)
                .putBoolean(KEY_SPECULATIVE, result.speculative);
        if (result.failure != null) {
            editor.putInt(KEY_FAILURES, mPrefs.getInt(KEY_FAILURES, 0) + 1);
        } else {
            editor.putInt(KEY_FAILURES, 0);
        }
        editor.apply();
        Log.i(TAG, "probe result backend=" + DeviceTier.backendName(result.backend)
                + " speculative=" + result.speculative
                + (result.failure == null ? "" : " failure=" + result.failure));
    }

    int backend() {
        return mPrefs.getInt(KEY_BACKEND, DeviceTier.BACKEND_CPU);
    }

    boolean speculative() {
        return mPrefs.getBoolean(KEY_SPECULATIVE, false);
    }

    /**
     * The seeded verdict for this SoC, or -1 when the seed says nothing. A seed entry of
     * {@code cpu} is the blocklist: it means the probe is known to fail or hang here and must not
     * be run at all.
     */
    int seededBackend() {
        String soc = TextUtils.isEmpty(Build.SOC_MODEL) ? Build.HARDWARE : Build.SOC_MODEL;
        if (TextUtils.isEmpty(soc)) {
            return -1;
        }
        try (XmlResourceParser parser = mContext.getResources().getXml(R.xml.backend_seed)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"soc".equals(parser.getName())) {
                    continue;
                }
                String match = parser.getAttributeValue(null, "match");
                if (match == null || !soc.toLowerCase().contains(match.toLowerCase())) {
                    continue;
                }
                String backend = parser.getAttributeValue(null, "backend");
                if ("opencl".equals(backend)) {
                    return DeviceTier.BACKEND_OPENCL;
                }
                if ("gles".equals(backend)) {
                    return DeviceTier.BACKEND_GLES;
                }
                if ("cpu".equals(backend)) {
                    return DeviceTier.BACKEND_CPU;
                }
                if ("none".equals(backend)) {
                    return DeviceTier.BACKEND_NONE;
                }
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "malformed backend seed", e);
        }
        return -1;
    }

    void dump(PrintWriter pw) {
        pw.println("  tier store: backend=" + DeviceTier.backendName(backend())
                + " speculative=" + speculative()
                + " probeFailures=" + mPrefs.getInt(KEY_FAILURES, 0)
                + " fingerprint=" + mPrefs.getString(KEY_FINGERPRINT, "<none>"));
    }
}
