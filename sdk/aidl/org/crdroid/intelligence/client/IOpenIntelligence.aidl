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

import android.os.Bundle;
import org.crdroid.intelligence.client.IOpenIntelligenceCallback;

/**
 * OpenAICore's task API for ordinary apps.
 *
 * This exists because the platform's own surface is not reachable from a normal APK: every method
 * on android.app.ondeviceintelligence.OnDeviceIntelligenceManager is @SystemApi and guarded by
 * USE_ON_DEVICE_INTELLIGENCE, which is signature|privileged. A third-party app on a stock crDroid
 * build cannot hold it, so a ROM-level API for third parties has to be ours.
 *
 * Guarded by org.crdroid.intelligence.permission.USE_INTELLIGENCE, a runtime permission, so the
 * user grants access per app and can revoke it in Settings like any other.
 *
 * Bundle keys are the ones in org.crdroid.intelligence.common.RequestKeys.
 */
interface IOpenIntelligence {

    /** Feature ids currently offered on this device. May be empty; callers must handle that. */
    int[] listFeatures();

    /** One of the FeatureDetails.FEATURE_STATUS_* values. */
    int getFeatureStatus(int featureId);

    /** Starts the model download, if the user has accepted the licence. */
    void requestDownload(int featureId, IOpenIntelligenceCallback callback);

    /**
     * Runs one request. Results arrive on the callback. Returns a token usable with
     * {@link #cancel}.
     */
    long process(int featureId, in Bundle request, boolean streaming,
            IOpenIntelligenceCallback callback);

    void cancel(long token);
}
