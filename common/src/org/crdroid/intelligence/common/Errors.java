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

import android.app.ondeviceintelligence.OnDeviceIntelligenceException;

/**
 * The OpenAICore error taxonomy, mapped onto the platform's smaller set of error codes.
 *
 * <p>The platform codes are coarse, so the reason string carries the specific cause. Callers that
 * only understand platform codes still behave correctly; callers that parse the reason get the
 * detail. Reasons are stable identifiers and are never localised or content-bearing.
 */
public final class Errors {

    public static final String BUSY = "BUSY";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String BATTERY_QUOTA_EXCEEDED = "BATTERY_QUOTA_EXCEEDED";
    public static final String THERMAL_THROTTLED = "THERMAL_THROTTLED";
    public static final String LOW_MEMORY = "LOW_MEMORY";
    public static final String MODEL_NOT_DOWNLOADED = "MODEL_NOT_DOWNLOADED";
    public static final String BACKEND_INIT_FAILED = "BACKEND_INIT_FAILED";
    public static final String CONTEXT_LENGTH_EXCEEDED = "CONTEXT_LENGTH_EXCEEDED";
    public static final String CANCELLED = "CANCELLED";
    public static final String SAFETY_BLOCKED = "SAFETY_BLOCKED";
    public static final String NOT_CONSENTED = "NOT_CONSENTED";
    public static final String FEATURE_UNAVAILABLE = "FEATURE_UNAVAILABLE";
    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String FOREGROUND_REQUIRED = "FOREGROUND_REQUIRED";

    private Errors() {}

    public static OnDeviceIntelligenceException of(String reason) {
        return new OnDeviceIntelligenceException(platformCode(reason), reason);
    }

    public static OnDeviceIntelligenceException of(String reason, String detail) {
        return new OnDeviceIntelligenceException(platformCode(reason), reason + ": " + detail);
    }

    private static int platformCode(String reason) {
        switch (reason) {
            case BUSY:
            case QUOTA_EXCEEDED:
            case BATTERY_QUOTA_EXCEEDED:
            case THERMAL_THROTTLED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_BUSY;
            case LOW_MEMORY:
            case BACKEND_INIT_FAILED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_COMPUTE_ERROR;
            case MODEL_NOT_DOWNLOADED:
            case FEATURE_UNAVAILABLE:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_NOT_AVAILABLE;
            case CONTEXT_LENGTH_EXCEEDED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_REQUEST_TOO_LARGE;
            case CANCELLED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_CANCELLED;
            case SAFETY_BLOCKED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_REQUEST_NOT_SAFE;
            case NOT_CONSENTED:
            case FOREGROUND_REQUIRED:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_SUSPENDED;
            case BAD_REQUEST:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_BAD_REQUEST;
            default:
                return OnDeviceIntelligenceException.PROCESSING_ERROR_UNKNOWN;
        }
    }
}
