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

package org.crdroid.intelligence.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.crdroid.intelligence.common.ModelCatalogIds;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * On-disk location and status of downloaded models.
 *
 * <p>Models live in device-protected storage because the broker can be bound by system_server
 * before the user has unlocked, and a feature query that early must still be able to answer
 * truthfully rather than reporting the model missing.
 */
public final class ModelStore {

    private static final String TAG = "OpenAICore.ModelStore";
    private static final String PREFS = "model_store";
    private static final String KEY_ACTIVE_VERSION_PREFIX = "active_version.";

    private final Context mDeContext;
    private final SharedPreferences mPrefs;

    public ModelStore(Context context) {
        mDeContext = context.isDeviceProtectedStorage()
                ? context : context.createDeviceProtectedStorageContext();
        mPrefs = mDeContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public File rootDir() {
        File dir = new File(mDeContext.getFilesDir(), "models");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create model root");
        }
        return dir;
    }

    /**
     * Directory for one version of one model. Versions are kept side by side so a failed update
     * never leaves the device without a working model; the old one is only removed once the new
     * one has loaded successfully.
     */
    public File versionDir(String modelId, int version) {
        return new File(rootDir(), modelId + "/" + version);
    }

    public File modelFile(String modelId, int version) {
        return new File(versionDir(modelId, version), "model.litertlm");
    }

    /** Marker written only after the checksum of the downloaded file has been verified. */
    public File verifiedMarker(String modelId, int version) {
        return new File(versionDir(modelId, version), ".verified");
    }

    public int activeVersion(String modelId) {
        return mPrefs.getInt(KEY_ACTIVE_VERSION_PREFIX + modelId, -1);
    }

    public void setActiveVersion(String modelId, int version) {
        mPrefs.edit().putInt(KEY_ACTIVE_VERSION_PREFIX + modelId, version).apply();
    }

    public void clearActiveVersion(String modelId) {
        mPrefs.edit().remove(KEY_ACTIVE_VERSION_PREFIX + modelId).apply();
    }

    public boolean isAvailable(String modelId) {
        int v = activeVersion(modelId);
        return v >= 0 && verifiedMarker(modelId, v).exists() && modelFile(modelId, v).length() > 0;
    }

    public long sizeOnDisk(String modelId) {
        int v = activeVersion(modelId);
        return v < 0 ? 0 : modelFile(modelId, v).length();
    }

    /**
     * Opens the active model read-only. The returned descriptor is what gets handed to the
     * isolated inference process, which has no way to open the file itself.
     */
    public ParcelFileDescriptor openModel(String modelId) throws FileNotFoundException {
        int v = activeVersion(modelId);
        if (v < 0) {
            throw new FileNotFoundException("no active version for " + modelId);
        }
        return ParcelFileDescriptor.open(
                modelFile(modelId, v), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Removes every version of {@code modelId} except the active one. */
    public void collectGarbage(String modelId) {
        File dir = new File(rootDir(), modelId);
        File[] versions = dir.listFiles();
        if (versions == null) {
            return;
        }
        int active = activeVersion(modelId);
        for (File v : versions) {
            if (v.getName().equals(String.valueOf(active))) {
                continue;
            }
            deleteRecursively(v);
        }
    }

    /** Frees everything. Backs the "delete model" control in Settings. */
    public void deleteAll() {
        for (String id : new String[] {ModelCatalogIds.GEMMA_4_E2B, ModelCatalogIds.GEMMA_4_E4B}) {
            clearActiveVersion(id);
            deleteRecursively(new File(rootDir(), id));
        }
    }

    public long usableSpaceBytes() {
        return rootDir().getUsableSpace();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            Log.w(TAG, "failed to delete " + f);
        }
    }

    /** Best-effort close that never throws into a callback path. */
    public static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return;
        }
        try {
            pfd.close();
        } catch (IOException ignored) {
            // Nothing useful to do; the descriptor is going away with the process anyway.
        }
    }
}
