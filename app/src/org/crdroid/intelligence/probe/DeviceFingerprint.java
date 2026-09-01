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

import android.os.Build;
import android.text.TextUtils;

/**
 * Identifies the hardware and driver combination a probe result is valid for.
 *
 * <p>A GPU driver update can turn a working OpenCL path into a broken one and vice versa, so the
 * cached probe result is keyed on the driver version as well as the SoC. When the fingerprint
 * changes the probe re-runs; that is the whole point of caching it rather than the device name.
 */
public final class DeviceFingerprint {

    private DeviceFingerprint() {}

    public static String compute(String glRenderer, String glVersion, String clPlatform) {
        return TextUtils.join("/", new String[] {
                nz(Build.SOC_MODEL),
                nz(Build.HARDWARE),
                nz(glRenderer),
                nz(glVersion),
                nz(clPlatform),
                String.valueOf(Build.VERSION.SDK_INT),
        });
    }

    private static String nz(String s) {
        return TextUtils.isEmpty(s) ? "?" : s;
    }
}
