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
import android.content.res.XmlResourceParser;
import android.util.Log;

import org.crdroid.intelligence.common.Features;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The set of models OpenAICore knows how to fetch, parsed from {@code res/xml/model_catalog.xml}.
 *
 * <p>The catalog is data rather than code so a model revision or a licence-driven change of
 * download host does not require an app update to every branch.
 */
public final class ModelCatalog {

    private static final String TAG = "OpenAICore.ModelCatalog";

    /** One model revision. */
    public static final class Entry {
        public final String id;
        public final int version;
        public final String displayName;
        public final String url;
        public final String sha256;
        public final long sizeBytes;
        public final int modalities;
        public final long maxTokens;
        /**
         * Licence the user has to accept before the download starts. Gemma weights are governed
         * by the Gemma Terms of Use rather than an OSI licence, which is why this is mandatory
         * and why crDroid does not mirror the files.
         */
        public final String licenceUrl;
        public final String licenceName;

        Entry(String id, int version, String displayName, String url, String sha256,
                long sizeBytes, int modalities, long maxTokens,
                String licenceName, String licenceUrl) {
            this.id = id;
            this.version = version;
            this.displayName = displayName;
            this.url = url;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.modalities = modalities;
            this.maxTokens = maxTokens;
            this.licenceName = licenceName;
            this.licenceUrl = licenceUrl;
        }
    }

    private final List<Entry> mEntries = new ArrayList<>();

    public ModelCatalog(Context context) {
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.model_catalog)) {
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"model".equals(parser.getName())) {
                    continue;
                }
                mEntries.add(new Entry(
                        parser.getAttributeValue(null, "id"),
                        parser.getAttributeIntValue(null, "version", 1),
                        parser.getAttributeValue(null, "displayName"),
                        parser.getAttributeValue(null, "url"),
                        parser.getAttributeValue(null, "sha256"),
                        parseLong(parser.getAttributeValue(null, "sizeBytes")),
                        parseModalities(parser.getAttributeValue(null, "modalities")),
                        parseLong(parser.getAttributeValue(null, "maxTokens")),
                        parser.getAttributeValue(null, "licenceName"),
                        parser.getAttributeValue(null, "licenceUrl")));
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "malformed model catalog", e);
        }
    }

    public Entry latest(String modelId) {
        Entry best = null;
        for (Entry e : mEntries) {
            if (e.id.equals(modelId) && (best == null || e.version > best.version)) {
                best = e;
            }
        }
        return best;
    }

    public Entry get(String modelId, int version) {
        for (Entry e : mEntries) {
            if (e.id.equals(modelId) && e.version == version) {
                return e;
            }
        }
        return null;
    }

    public List<Entry> all() {
        return mEntries;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseModalities(String s) {
        if (s == null) {
            return Features.MODALITY_TEXT;
        }
        int m = 0;
        for (String part : s.split("\\|")) {
            switch (part.trim()) {
                case "text": m |= Features.MODALITY_TEXT; break;
                case "image": m |= Features.MODALITY_IMAGE; break;
                case "audio": m |= Features.MODALITY_AUDIO; break;
                default: break;
            }
        }
        return m == 0 ? Features.MODALITY_TEXT : m;
    }
}
