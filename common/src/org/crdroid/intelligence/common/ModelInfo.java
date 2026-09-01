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

import android.os.Parcel;
import android.os.Parcelable;

/** Catalog entry plus installation state for one model, as seen across the provider binder. */
public final class ModelInfo implements Parcelable {

    public static final int STATE_UNAVAILABLE = 0;
    public static final int STATE_DOWNLOADABLE = 1;
    public static final int STATE_DOWNLOADING = 2;
    public static final int STATE_AVAILABLE = 3;

    public final String id;
    public final String displayName;
    public final int state;
    public final long sizeBytes;
    public final long bytesDownloaded;
    public final int modalities;
    public final long maxTokens;
    public final String licenceName;
    public final String licenceUrl;
    public final boolean licenceAccepted;

    public ModelInfo(String id, String displayName, int state, long sizeBytes,
            long bytesDownloaded, int modalities, long maxTokens, String licenceName,
            String licenceUrl, boolean licenceAccepted) {
        this.id = id;
        this.displayName = displayName;
        this.state = state;
        this.sizeBytes = sizeBytes;
        this.bytesDownloaded = bytesDownloaded;
        this.modalities = modalities;
        this.maxTokens = maxTokens;
        this.licenceName = licenceName;
        this.licenceUrl = licenceUrl;
        this.licenceAccepted = licenceAccepted;
    }

    private ModelInfo(Parcel in) {
        id = in.readString();
        displayName = in.readString();
        state = in.readInt();
        sizeBytes = in.readLong();
        bytesDownloaded = in.readLong();
        modalities = in.readInt();
        maxTokens = in.readLong();
        licenceName = in.readString();
        licenceUrl = in.readString();
        licenceAccepted = in.readBoolean();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(id);
        dest.writeString(displayName);
        dest.writeInt(state);
        dest.writeLong(sizeBytes);
        dest.writeLong(bytesDownloaded);
        dest.writeInt(modalities);
        dest.writeLong(maxTokens);
        dest.writeString(licenceName);
        dest.writeString(licenceUrl);
        dest.writeBoolean(licenceAccepted);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelInfo> CREATOR = new Creator<>() {
        @Override
        public ModelInfo createFromParcel(Parcel in) {
            return new ModelInfo(in);
        }

        @Override
        public ModelInfo[] newArray(int size) {
            return new ModelInfo[size];
        }
    };
}
