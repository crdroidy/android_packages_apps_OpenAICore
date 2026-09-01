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

import android.os.ParcelFileDescriptor;
import org.crdroid.intelligence.common.IModelDownloadCallback;
import org.crdroid.intelligence.common.ModelInfo;

/**
 * The model store, exposed by the separate downloader package.
 *
 * The split exists because the intelligence roles that gate the AppFunctions registry require
 * the holder not to request INTERNET at all: it must reach the network only through a separate,
 * open-source component. Keeping the downloader in its own package is what leaves that role
 * reachable, and it mirrors how AICore defers to Private Compute Services.
 *
 * Guarded by org.crdroid.intelligence.permission.MANAGE_MODELS, a signature permission, so only
 * the broker and Settings can call it.
 */
interface IModelProvider {

    /** Catalog entry plus on-disk state for one model id. */
    ModelInfo getModelInfo(String modelId);

    /** All known catalog entries. */
    List<ModelInfo> listModels();

    /**
     * A read-only descriptor for the active revision, or null when it is not installed.
     * The caller relays this straight to the inference sandbox, which cannot open files itself.
     */
    ParcelFileDescriptor openModel(String modelId);

    /** Starts or resumes a download. No-op when the model is already installed and verified. */
    void startDownload(String modelId, IModelDownloadCallback callback);

    void cancelDownload(String modelId);

    /** Deletes every revision of every model and frees the space. */
    void deleteAll();

    /** Records the user's acceptance of a model's licence terms. */
    void setLicenceAccepted(String modelId, boolean accepted);
}
