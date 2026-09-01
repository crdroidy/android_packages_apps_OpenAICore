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

import android.app.ondeviceintelligence.Feature;
import android.app.ondeviceintelligence.FeatureDetails;
import android.os.PersistableBundle;

import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.Features;
import org.crdroid.intelligence.common.ModelInfo;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides which features this device offers, and what state each is in.
 *
 * <p>On Pixel almost everything is {@code AVAILABLE}; across crDroid's matrix
 * {@code UNAVAILABLE} is a normal, common answer, and callers have to handle it. Listing a
 * feature that will later fail is worse than not listing it at all.
 */
final class FeatureRegistry {

    /** Bumped when prompt templates or output shape change in a way callers can observe. */
    private static final int FEATURE_VERSION = 1;

    private final ModelClient mModels;
    private final ConsentStore mConsent;

    private volatile int mTier = DeviceTier.TIER_E;

    FeatureRegistry(ModelClient models, ConsentStore consent) {
        mModels = models;
        mConsent = consent;
    }

    void setTier(int tier) {
        mTier = tier;
    }

    int tier() {
        return mTier;
    }

    String activeModelId() {
        return DeviceTier.modelForTier(mTier);
    }

    List<Feature> listFeatures() {
        int tier = mTier;
        List<Feature> out = new ArrayList<>();
        if (tier == DeviceTier.TIER_E || !mConsent.isGloballyEnabled()) {
            return out;
        }
        String modelId = activeModelId();
        ModelInfo model = mModels.info(modelId);
        int modelModalities = model == null ? Features.MODALITY_TEXT : model.modalities;
        int tierModalities = DeviceTier.modalitiesForTier(tier);
        for (Features.Spec spec : Features.forTier(tier, modelModalities & tierModalities)) {
            if (!mConsent.isFeatureAllowed(spec.id)) {
                continue;
            }
            out.add(Features.toFeature(spec, modelId, FEATURE_VERSION));
        }
        return out;
    }

    Feature getFeature(int id) {
        for (Feature f : listFeatures()) {
            if (f.getId() == id) {
                return f;
            }
        }
        return null;
    }

    FeatureDetails detailsFor(Feature feature) {
        PersistableBundle params = new PersistableBundle();
        params.putString("tier", DeviceTier.name(mTier));
        params.putString("model", activeModelId());
        params.putInt("context_tokens", DeviceTier.defaultContextTokens(mTier));

        if (mTier == DeviceTier.TIER_E) {
            params.putString("reason", "device_tier");
            return new FeatureDetails(FeatureDetails.FEATURE_STATUS_UNAVAILABLE, params);
        }
        if (!mConsent.isGloballyEnabled()) {
            params.putString("reason", "not_enabled_by_user");
            return new FeatureDetails(FeatureDetails.FEATURE_STATUS_UNAVAILABLE, params);
        }
        if (getFeature(feature.getId()) == null) {
            params.putString("reason", "feature_not_offered_on_this_device");
            return new FeatureDetails(FeatureDetails.FEATURE_STATUS_UNAVAILABLE, params);
        }

        ModelInfo model = mModels.info(activeModelId());
        if (model == null) {
            params.putString("reason", "model_provider_unavailable");
            return new FeatureDetails(FeatureDetails.FEATURE_STATUS_SERVICE_UNAVAILABLE, params);
        }
        params.putLong("download_bytes", model.sizeBytes);
        params.putString("licence", model.licenceName);
        switch (model.state) {
            case ModelInfo.STATE_AVAILABLE:
                return new FeatureDetails(FeatureDetails.FEATURE_STATUS_AVAILABLE, params);
            case ModelInfo.STATE_DOWNLOADING:
                return new FeatureDetails(FeatureDetails.FEATURE_STATUS_DOWNLOADING, params);
            case ModelInfo.STATE_DOWNLOADABLE:
                return new FeatureDetails(FeatureDetails.FEATURE_STATUS_DOWNLOADABLE, params);
            default:
                params.putString("reason", "model_unavailable");
                return new FeatureDetails(FeatureDetails.FEATURE_STATUS_UNAVAILABLE, params);
        }
    }

    boolean isModelInstalled() {
        ModelInfo model = mModels.info(activeModelId());
        return model != null && model.state == ModelInfo.STATE_AVAILABLE;
    }

    void dump(PrintWriter pw) {
        pw.println("  features: tier=" + DeviceTier.name(mTier)
                + " model=" + activeModelId()
                + " modelInstalled=" + isModelInstalled()
                + " offered=" + listFeatures().size());
    }
}
