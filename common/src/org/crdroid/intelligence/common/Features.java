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

import android.app.ondeviceintelligence.Feature;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of features OpenAICore can expose, and the metadata the broker needs to decide
 * whether a given device may use each one.
 *
 * <p>Feature ids are part of the on-device API contract: once shipped they must not be reused for
 * a different task, because third-party callers persist them.
 */
public final class Features {

    public static final int ID_PROMPT = 1;
    public static final int ID_SUMMARIZE = 2;
    public static final int ID_PROOFREAD = 3;
    public static final int ID_REWRITE = 4;
    public static final int ID_DESCRIBE_IMAGE = 5;
    public static final int ID_TRANSCRIBE = 6;

    /** Feature is a batch task: latency is not user-perceived, so any backend will do. */
    public static final int TYPE_BATCH = 1;
    /** Feature is interactive: only worth exposing where time-to-first-token is low. */
    public static final int TYPE_INTERACTIVE = 2;

    /** Modality bits, used to decide which model towers have to be resident. */
    public static final int MODALITY_TEXT = 1;
    public static final int MODALITY_IMAGE = 1 << 1;
    public static final int MODALITY_AUDIO = 1 << 2;

    /** Bundle key carrying {@link #MODALITY_TEXT} etc. in {@link Feature#getFeatureParams()}. */
    public static final String PARAM_MODALITIES = "modalities";
    /** Bundle key carrying the input token cap the broker advertises for a feature. */
    public static final String PARAM_MAX_INPUT_TOKENS = "max_input_tokens";
    /** Bundle key carrying the tier at or above which this feature is offered. */
    public static final String PARAM_MIN_TIER = "min_tier";

    /** Static description of one feature, before any device-specific filtering. */
    public static final class Spec {
        public final int id;
        public final String name;
        public final int type;
        public final int modalities;
        public final int maxInputTokens;
        /** Lowest {@link DeviceTier} ordinal on which this feature is offered. */
        public final int minTier;

        Spec(int id, String name, int type, int modalities, int maxInputTokens, int minTier) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.modalities = modalities;
            this.maxInputTokens = maxInputTokens;
            this.minTier = minTier;
        }
    }

    private static final Spec[] SPECS = new Spec[] {
        new Spec(ID_PROMPT, "PROMPT", TYPE_INTERACTIVE, MODALITY_TEXT | MODALITY_IMAGE,
                4096, DeviceTier.TIER_D),
        new Spec(ID_SUMMARIZE, "SUMMARIZE", TYPE_BATCH, MODALITY_TEXT,
                4000, DeviceTier.TIER_D),
        new Spec(ID_PROOFREAD, "PROOFREAD", TYPE_INTERACTIVE, MODALITY_TEXT,
                256, DeviceTier.TIER_B),
        new Spec(ID_REWRITE, "REWRITE", TYPE_INTERACTIVE, MODALITY_TEXT,
                256, DeviceTier.TIER_B),
        new Spec(ID_DESCRIBE_IMAGE, "DESCRIBE_IMAGE", TYPE_BATCH, MODALITY_IMAGE,
                1024, DeviceTier.TIER_B),
        new Spec(ID_TRANSCRIBE, "TRANSCRIBE", TYPE_BATCH, MODALITY_AUDIO,
                4096, DeviceTier.TIER_B),
    };

    private Features() {}

    public static Spec[] all() {
        return SPECS;
    }

    public static Spec byId(int id) {
        for (Spec s : SPECS) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    /**
     * Builds the platform {@link Feature} for a spec. {@code modelName} is the model variant the
     * broker has actually selected for this device, so callers can tell E2B from E4B results.
     */
    public static Feature toFeature(Spec spec, String modelName, int version) {
        PersistableBundle params = new PersistableBundle();
        params.putInt(PARAM_MODALITIES, spec.modalities);
        params.putInt(PARAM_MAX_INPUT_TOKENS, spec.maxInputTokens);
        params.putInt(PARAM_MIN_TIER, spec.minTier);
        return new Feature.Builder(spec.id)
                .setName(spec.name)
                .setModelName(modelName)
                .setType(spec.type)
                .setVariant(0)
                .setVersion(version)
                .setFeatureParams(params)
                .build();
    }

    /** The subset of features offered at {@code tier}, given the modalities the model provides. */
    public static List<Spec> forTier(int tier, int availableModalities) {
        List<Spec> out = new ArrayList<>();
        for (Spec s : SPECS) {
            if (tier > s.minTier) {
                // Tier ordinals increase as the device gets weaker.
                continue;
            }
            if ((s.modalities & availableModalities) != s.modalities) {
                continue;
            }
            out.add(s);
        }
        return out;
    }
}
