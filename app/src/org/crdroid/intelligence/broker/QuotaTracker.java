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

import android.os.SystemClock;
import android.util.SparseArray;

import org.crdroid.intelligence.common.Errors;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/**
 * Per-uid request admission.
 *
 * <p>Two independent limits, because they protect different things: a short token bucket keeps one
 * app from monopolising the single engine instance, and a rolling daily compute budget keeps a
 * well-behaved app from flattening the battery over a day. Neither is a security boundary — the
 * SELinux sandbox and the isolated process are — they exist so a buggy caller degrades instead of
 * ruining the device.
 */
public final class QuotaTracker {

    private static final int BURST_REQUESTS = 8;
    private static final long BURST_REFILL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final long DAILY_COMPUTE_BUDGET_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

    private static final class Bucket {
        int tokens = BURST_REQUESTS;
        long lastRefillMs = SystemClock.elapsedRealtime();
        long computeUsedMs;
        long windowStartMs = SystemClock.elapsedRealtime();
        long totalRequests;
        long totalRejections;
    }

    private final Object mLock = new Object();
    private final SparseArray<Bucket> mBuckets = new SparseArray<>();

    /**
     * @return null when the request may proceed, otherwise the {@link Errors} reason to fail with.
     */
    public String admit(int uid) {
        synchronized (mLock) {
            Bucket b = bucketLocked(uid);
            long now = SystemClock.elapsedRealtime();

            long elapsed = now - b.lastRefillMs;
            if (elapsed >= BURST_REFILL_MS) {
                int refill = (int) (elapsed / BURST_REFILL_MS);
                b.tokens = Math.min(BURST_REQUESTS, b.tokens + refill);
                b.lastRefillMs = now;
            }
            if (now - b.windowStartMs >= DAY_MS) {
                b.windowStartMs = now;
                b.computeUsedMs = 0;
            }

            b.totalRequests++;
            if (b.computeUsedMs >= DAILY_COMPUTE_BUDGET_MS) {
                b.totalRejections++;
                return Errors.BATTERY_QUOTA_EXCEEDED;
            }
            if (b.tokens <= 0) {
                b.totalRejections++;
                return Errors.QUOTA_EXCEEDED;
            }
            b.tokens--;
            return null;
        }
    }

    /** Charges the compute a completed request actually used against the daily budget. */
    public void recordCompute(int uid, long durationMs) {
        synchronized (mLock) {
            bucketLocked(uid).computeUsedMs += durationMs;
        }
    }

    public void forget(int uid) {
        synchronized (mLock) {
            mBuckets.remove(uid);
        }
    }

    /** Dump carries counters and quota state only — never request or response content. */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  quota:");
            for (int i = 0; i < mBuckets.size(); i++) {
                Bucket b = mBuckets.valueAt(i);
                pw.printf("    uid=%d requests=%d rejected=%d tokens=%d computeMs=%d%n",
                        mBuckets.keyAt(i), b.totalRequests, b.totalRejections, b.tokens,
                        b.computeUsedMs);
            }
        }
    }

    private Bucket bucketLocked(int uid) {
        Bucket b = mBuckets.get(uid);
        if (b == null) {
            b = new Bucket();
            mBuckets.put(uid, b);
        }
        return b;
    }
}
