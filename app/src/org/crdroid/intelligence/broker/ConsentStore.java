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

/**
 * The user-facing kill switches: global, per-package and per-feature.
 *
 * <p>OpenAICore ships off. Nothing runs until the user has been shown the disclosure screen and
 * accepted the model licence, so {@link #isGloballyEnabled} defaults to false and every check
 * short-circuits on it.
 */
public final class ConsentStore {

    private static final String PREFS = "consent";
    private static final String KEY_GLOBAL = "global_enabled";
    private static final String KEY_PACKAGE_PREFIX = "package_disabled.";
    private static final String KEY_FEATURE_PREFIX = "feature_disabled.";
    private static final String KEY_ALLOW_BACKGROUND_PREFIX = "allow_background.";

    private final SharedPreferences mPrefs;

    public ConsentStore(Context context) {
        Context de = context.isDeviceProtectedStorage()
                ? context : context.createDeviceProtectedStorageContext();
        mPrefs = de.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isGloballyEnabled() {
        return mPrefs.getBoolean(KEY_GLOBAL, false);
    }

    public void setGloballyEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_GLOBAL, enabled).apply();
    }

    public boolean isPackageAllowed(String packageName) {
        return isGloballyEnabled()
                && !mPrefs.getBoolean(KEY_PACKAGE_PREFIX + packageName, false);
    }

    public void setPackageDisabled(String packageName, boolean disabled) {
        mPrefs.edit().putBoolean(KEY_PACKAGE_PREFIX + packageName, disabled).apply();
    }

    public boolean isFeatureAllowed(int featureId) {
        return isGloballyEnabled()
                && !mPrefs.getBoolean(KEY_FEATURE_PREFIX + featureId, false);
    }

    public void setFeatureDisabled(int featureId, boolean disabled) {
        mPrefs.edit().putBoolean(KEY_FEATURE_PREFIX + featureId, disabled).apply();
    }

    /**
     * Whether a package may run inference while it is not in the foreground. Off by default,
     * matching AICore. This is a battery protection as much as a privacy one, and the exceptions
     * are system components such as notification summarisation.
     */
    public boolean isBackgroundAllowed(String packageName) {
        return mPrefs.getBoolean(KEY_ALLOW_BACKGROUND_PREFIX + packageName, false);
    }

    public void setBackgroundAllowed(String packageName, boolean allowed) {
        mPrefs.edit().putBoolean(KEY_ALLOW_BACKGROUND_PREFIX + packageName, allowed).apply();
    }
}
