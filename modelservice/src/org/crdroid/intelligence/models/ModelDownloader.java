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

import android.app.ondeviceintelligence.DownloadCallback;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resumable download and verification of one model revision.
 *
 * <p>Nearly three gigabytes over a phone connection will be interrupted, so this resumes by byte
 * range rather than restarting, and it only marks a revision usable after the digest matches.
 * An unverified file is never installed: the alternative is a corrupt mmap and a native crash in
 * the sandbox with no useful diagnosis.
 */
final class ModelDownloader {

    private static final String TAG = "OpenAICore.Downloader";
    private static final int BUFFER_BYTES = 256 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Free space required beyond the model itself, so verification and swap have room. */
    private static final float HEADROOM_FACTOR = 1.5f;

    interface Progress {
        void onStarted(long totalBytes);

        void onProgress(long bytesSoFar);

        void onCompleted();

        void onFailed(int status, String reason);
    }

    private final ModelStore mStore;
    private final AtomicBoolean mCancelled = new AtomicBoolean();

    ModelDownloader(ModelStore store) {
        mStore = store;
    }

    void cancel() {
        mCancelled.set(true);
    }

    void run(ModelCatalog.Entry entry, Progress progress) {
        if (entry.sha256 == null || entry.sha256.isEmpty()) {
            // A catalog revision without a digest cannot be verified, so it is not shippable.
            // Refusing here is the difference between a broken build and a corrupted device.
            progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNAVAILABLE,
                    "catalog entry has no sha256");
            return;
        }
        if (mStore.usableSpaceBytes() < entry.sizeBytes * HEADROOM_FACTOR) {
            progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_NOT_ENOUGH_DISK_SPACE,
                    "need " + (long) (entry.sizeBytes * HEADROOM_FACTOR) + " bytes");
            return;
        }

        File dir = mStore.versionDir(entry.id, entry.version);
        if (!dir.exists() && !dir.mkdirs()) {
            progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNKNOWN,
                    "cannot create " + dir);
            return;
        }
        File target = mStore.modelFile(entry.id, entry.version);
        long existing = target.exists() ? target.length() : 0;
        if (existing > entry.sizeBytes) {
            // A previous attempt against a different revision left a longer file behind.
            existing = 0;
            if (!target.delete()) {
                Log.w(TAG, "could not remove stale partial download");
            }
        }

        progress.onStarted(entry.sizeBytes);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(entry.url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            if (existing > 0) {
                connection.setRequestProperty("Range", "bytes=" + existing + "-");
            }
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                // Server ignored the range request; start over rather than append to a prefix.
                existing = 0;
            } else if (code != HttpURLConnection.HTTP_PARTIAL) {
                progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_NETWORK_FAILURE,
                        "http " + code);
                return;
            }

            long written = existing;
            try (InputStream in = connection.getInputStream();
                 RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                out.setLength(Math.max(existing, 0));
                out.seek(existing);
                byte[] buffer = new byte[BUFFER_BYTES];
                long lastReport = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (mCancelled.get()) {
                        progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_DOWNLOADING,
                                "cancelled");
                        return;
                    }
                    out.write(buffer, 0, read);
                    written += read;
                    // Report at most every 8 MB: the callback crosses a binder, and a progress
                    // update per 256 KB chunk costs more than the download.
                    if (written - lastReport >= 8L * 1024 * 1024) {
                        lastReport = written;
                        progress.onProgress(written);
                    }
                }
            }
            progress.onProgress(written);

            if (!verify(target, entry.sha256)) {
                if (!target.delete()) {
                    Log.w(TAG, "could not remove file that failed verification");
                }
                progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNKNOWN,
                        "checksum mismatch");
                return;
            }

            if (!mStore.verifiedMarker(entry.id, entry.version).createNewFile()
                    && !mStore.verifiedMarker(entry.id, entry.version).exists()) {
                progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_UNKNOWN,
                        "cannot mark verified");
                return;
            }
            mStore.setActiveVersion(entry.id, entry.version);
            // Only now is the previous revision safe to remove.
            mStore.collectGarbage(entry.id);
            progress.onCompleted();
        } catch (IOException e) {
            progress.onFailed(DownloadCallback.DOWNLOAD_FAILURE_STATUS_NETWORK_FAILURE,
                    e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean verify(File file, String expectedHex) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_BYTES];
            try (InputStream in = new DigestInputStream(
                    new java.io.FileInputStream(file), digest)) {
                while (in.read(buffer) > 0) {
                    // Reading is the point; DigestInputStream accumulates as it goes.
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString().equalsIgnoreCase(expectedHex.trim());
        } catch (NoSuchAlgorithmException | IOException e) {
            Log.e(TAG, "verification failed", e);
            return false;
        }
    }
}
