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

import android.os.Bundle;

/** Outcome of one backend probe run, and the device fingerprint it applies to. */
public final class BackendProbeResult {

    public static final String KEY_BACKEND = "backend";
    public static final String KEY_SPECULATIVE = "speculative";
    public static final String KEY_FINGERPRINT = "fingerprint";
    public static final String KEY_DECODE_TOKENS_PER_SEC = "decode_tps";
    public static final String KEY_FAILURE = "failure";

    public final int backend;
    public final boolean speculative;
    public final String fingerprint;
    public final float decodeTokensPerSec;
    /** Non-null when a rung of the ladder failed; carried for dumpsys, never for the user. */
    public final String failure;

    public BackendProbeResult(int backend, boolean speculative, String fingerprint,
            float decodeTokensPerSec, String failure) {
        this.backend = backend;
        this.speculative = speculative;
        this.fingerprint = fingerprint;
        this.decodeTokensPerSec = decodeTokensPerSec;
        this.failure = failure;
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putInt(KEY_BACKEND, backend);
        b.putBoolean(KEY_SPECULATIVE, speculative);
        b.putString(KEY_FINGERPRINT, fingerprint);
        b.putFloat(KEY_DECODE_TOKENS_PER_SEC, decodeTokensPerSec);
        b.putString(KEY_FAILURE, failure);
        return b;
    }

    public static BackendProbeResult fromBundle(Bundle b) {
        return new BackendProbeResult(
                b.getInt(KEY_BACKEND),
                b.getBoolean(KEY_SPECULATIVE),
                b.getString(KEY_FINGERPRINT),
                b.getFloat(KEY_DECODE_TOKENS_PER_SEC),
                b.getString(KEY_FAILURE));
    }
}
