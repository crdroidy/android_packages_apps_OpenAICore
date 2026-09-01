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

/**
 * Device capability tiers. Lower ordinal means a more capable device, so a feature declaring
 * {@code minTier = TIER_B} is offered on tiers A and B only.
 *
 * <p>Tier is derived at runtime from total RAM plus the result of the backend probe. crDroid's
 * device matrix is far too large and varied to maintain a hand-written per-device table, so the
 * in-tree data is only a seed and a blocklist, never the answer.
 */
public final class DeviceTier {

    public static final int TIER_A = 0;
    public static final int TIER_B = 1;
    public static final int TIER_C = 2;
    public static final int TIER_D = 3;
    public static final int TIER_E = 4;

    /** GPU is usable through ML Drift's OpenCL path. */
    public static final int BACKEND_OPENCL = 0;
    /** OpenCL unusable; GLES fallback works. Text only, batch only. */
    public static final int BACKEND_GLES = 1;
    /** No usable GPU path; XNNPACK on CPU. */
    public static final int BACKEND_CPU = 2;
    /** Nothing works, or the probe crashed twice. */
    public static final int BACKEND_NONE = 3;

    private static final long GB = 1024L * 1024L * 1024L;

    private DeviceTier() {}

    /**
     * @param totalRamBytes {@code ActivityManager.MemoryInfo.totalMem}
     * @param backend       result of {@link org.crdroid.intelligence.probe.BackendProbe}
     * @param speculative   whether the probe found speculative decoding usable
     */
    public static int classify(long totalRamBytes, int backend, boolean speculative) {
        if (totalRamBytes < 6 * GB || backend == BACKEND_NONE) {
            return TIER_E;
        }
        if (totalRamBytes < 8 * GB) {
            // GPU on these parts is generally present but the KV cache plus a foreground app is
            // what actually kills us, not the backend. Keep them on CPU and batch-only.
            return TIER_D;
        }
        if (backend == BACKEND_CPU) {
            return TIER_D;
        }
        if (backend == BACKEND_GLES) {
            return TIER_C;
        }
        if (totalRamBytes >= 12 * GB && speculative) {
            return TIER_A;
        }
        return TIER_B;
    }

    public static String name(int tier) {
        switch (tier) {
            case TIER_A: return "A";
            case TIER_B: return "B";
            case TIER_C: return "C";
            case TIER_D: return "D";
            default: return "E";
        }
    }

    public static String backendName(int backend) {
        switch (backend) {
            case BACKEND_OPENCL: return "opencl";
            case BACKEND_GLES: return "gles";
            case BACKEND_CPU: return "cpu";
            default: return "none";
        }
    }

    /** The model variant a tier should load. */
    public static String modelForTier(int tier) {
        return tier == TIER_A ? ModelCatalogIds.GEMMA_4_E4B : ModelCatalogIds.GEMMA_4_E2B;
    }

    /** Default context window. KV cache is the dominant memory variable, so this stays small. */
    public static int defaultContextTokens(int tier) {
        switch (tier) {
            case TIER_A: return 8192;
            case TIER_B: return 4096;
            case TIER_C: return 4096;
            case TIER_D: return 2048;
            default: return 0;
        }
    }

    /** Modalities a tier is allowed to pay for. */
    public static int modalitiesForTier(int tier) {
        switch (tier) {
            case TIER_A:
                return Features.MODALITY_TEXT | Features.MODALITY_IMAGE | Features.MODALITY_AUDIO;
            case TIER_B:
                return Features.MODALITY_TEXT | Features.MODALITY_IMAGE | Features.MODALITY_AUDIO;
            case TIER_C:
            case TIER_D:
                return Features.MODALITY_TEXT;
            default:
                return 0;
        }
    }
}
