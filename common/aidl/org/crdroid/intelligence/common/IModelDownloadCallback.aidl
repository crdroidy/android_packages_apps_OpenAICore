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

package org.crdroid.intelligence.common;

/** Mirrors android.app.ondeviceintelligence.DownloadCallback so the broker can relay verbatim. */
oneway interface IModelDownloadCallback {
    void onStarted(long totalBytes);
    void onProgress(long bytesSoFar);
    void onCompleted();
    /** status uses the DownloadCallback.DOWNLOAD_FAILURE_STATUS_* values. */
    void onFailed(int status, String reason);
}
