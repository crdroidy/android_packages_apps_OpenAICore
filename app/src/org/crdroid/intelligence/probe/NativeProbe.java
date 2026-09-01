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

package org.crdroid.intelligence.probe;

/**
 * JNI shims for querying the OpenCL and GLES stacks without linking against them.
 *
 * <p>Everything here dlopens; a device with no {@code libOpenCL.so} must get a clean negative
 * answer, not a link failure at process start.
 */
public final class NativeProbe {

    static {
        System.loadLibrary("openaicore_jni");
    }

    private NativeProbe() {}

    /** {@code true} when a {@code libOpenCL.so} could be dlopened and reports at least one platform. */
    public static native boolean hasOpenCl();

    /** {@code CL_PLATFORM_NAME} of the first platform, or null. */
    public static native String openClPlatformName();

    /** {@code CL_PLATFORM_VERSION} of the first platform, or null. */
    public static native String openClPlatformVersion();

    /**
     * Whether the device's {@code libOpenCL.so} is ANGLE's CL-on-Vulkan translator.
     *
     * <p>ML Drift's generated kernels are rejected by Clspv on ANGLE-CL over an implicit
     * {@code __global} to {@code __constant} address-space conversion, and engine construction
     * fails outright instead of degrading. Detecting it up front is the difference between a
     * clean step down the ladder and a hang at first inference.
     */
    public static native boolean isAngleOpenCl();

    /** {@code GL_RENDERER} from a throwaway offscreen GLES context, or null. */
    public static native String glRenderer();

    /** {@code GL_VERSION} from a throwaway offscreen GLES context, or null. */
    public static native String glVersion();
}
