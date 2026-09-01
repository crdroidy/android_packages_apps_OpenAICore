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

package org.crdroid.intelligence.inference;

import android.os.Bundle;

import org.crdroid.intelligence.common.Errors;
import org.crdroid.intelligence.common.Features;
import org.crdroid.intelligence.common.RequestKeys;
import org.crdroid.intelligence.inference.engine.InferenceEngine;

/**
 * Turns a feature plus a request bundle into the system prompt and user turn the engine sees.
 *
 * <p>These are task prompts, not a chatbot persona. A 2–4B model is good at summarising,
 * classifying, extracting and short rewriting, and poor at open-ended conversation; shipping
 * tasks is what keeps the quality bar reachable.
 */
final class PromptTemplates {

    private PromptTemplates() {}

    static final class Prepared {
        final String systemPrompt;
        final String userTurn;
        final float temperature;
        final int maxOutputTokens;

        Prepared(String systemPrompt, String userTurn, float temperature, int maxOutputTokens) {
            this.systemPrompt = systemPrompt;
            this.userTurn = userTurn;
            this.temperature = temperature;
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    static Prepared prepare(int featureId, Bundle request)
            throws InferenceEngine.EngineException {
        switch (featureId) {
            case Features.ID_PROMPT:
                return new Prepared(
                        "You are a concise on-device assistant. Answer directly. If you do not "
                                + "know, say so.",
                        require(request, RequestKeys.KEY_PROMPT),
                        clampTemperature(request, 0.7f),
                        clampTokens(request, 512));

            case Features.ID_SUMMARIZE:
                return new Prepared(
                        "Summarise the user's text as three to five short bullet points. Use only "
                                + "information present in the text. Output bullets and nothing else.",
                        require(request, RequestKeys.KEY_TEXT),
                        clampTemperature(request, 0.3f),
                        clampTokens(request, 256));

            case Features.ID_PROOFREAD:
                return new Prepared(
                        "Correct spelling, grammar and punctuation in the user's text. Preserve "
                                + "meaning, tone and formatting. Output only the corrected text.",
                        require(request, RequestKeys.KEY_TEXT),
                        clampTemperature(request, 0.1f),
                        clampTokens(request, 320));

            case Features.ID_REWRITE:
                return new Prepared(
                        "Rewrite the user's text in a " + tone(request) + " register. Preserve "
                                + "meaning. Output only the rewritten text.",
                        require(request, RequestKeys.KEY_TEXT),
                        clampTemperature(request, 0.6f),
                        clampTokens(request, 320));

            case Features.ID_DESCRIBE_IMAGE:
                return new Prepared(
                        "Describe the attached image in one or two sentences, suitable as alt "
                                + "text. Be specific and factual. Do not speculate about people.",
                        optional(request, RequestKeys.KEY_PROMPT, "Describe this image."),
                        clampTemperature(request, 0.3f),
                        clampTokens(request, 128));

            case Features.ID_TRANSCRIBE:
                return new Prepared(
                        "Transcribe the attached audio verbatim. Output only the transcript.",
                        optional(request, RequestKeys.KEY_PROMPT, "Transcribe this audio."),
                        clampTemperature(request, 0.0f),
                        clampTokens(request, 1024));

            default:
                throw new InferenceEngine.EngineException(
                        Errors.FEATURE_UNAVAILABLE, "unknown feature " + featureId);
        }
    }

    private static String tone(Bundle request) {
        String tone = request.getString(RequestKeys.KEY_TONE, RequestKeys.TONE_CONCISE);
        switch (tone) {
            case RequestKeys.TONE_FORMAL:
            case RequestKeys.TONE_CASUAL:
            case RequestKeys.TONE_CONCISE:
            case RequestKeys.TONE_ELABORATE:
                return tone;
            default:
                // An unrecognised tone is a caller bug, but failing the whole request over it
                // would be worse than quietly using the default.
                return RequestKeys.TONE_CONCISE;
        }
    }

    private static String require(Bundle request, String key)
            throws InferenceEngine.EngineException {
        String value = request.getString(key);
        if (value == null || value.isEmpty()) {
            throw new InferenceEngine.EngineException(Errors.BAD_REQUEST, "missing " + key);
        }
        return value;
    }

    private static String optional(Bundle request, String key, String fallback) {
        String value = request.getString(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static float clampTemperature(Bundle request, float fallback) {
        if (!request.containsKey(RequestKeys.KEY_TEMPERATURE)) {
            return fallback;
        }
        return Math.max(0f, Math.min(2f, request.getFloat(RequestKeys.KEY_TEMPERATURE, fallback)));
    }

    private static int clampTokens(Bundle request, int featureMax) {
        int requested = request.getInt(RequestKeys.KEY_MAX_OUTPUT_TOKENS, featureMax);
        return Math.max(1, Math.min(featureMax, requested));
    }
}
