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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import org.crdroid.intelligence.common.Errors;

import java.io.PrintWriter;
import java.util.function.IntConsumer;

/**
 * Thermal, battery and memory admission control.
 *
 * <p>All of it lives in the broker rather than the sandbox, because an isolated process cannot
 * reach {@code power_service}: {@code isolated_compute_app} only gets the services on the
 * {@code isolated_compute_allowed_service} list, and the power manager is not one of them.
 * Thermal state therefore has to be observed here and pushed down through
 * {@code updateProcessingState}.
 */
final class ResourceGovernor {

    private static final int HARD_BATTERY_FLOOR_PERCENT = 15;

    private final Context mContext;
    private final PowerManager mPowerManager;
    private final ActivityManager mActivityManager;
    private final IntConsumer mThermalSink;

    private volatile int mThermalStatus = PowerManager.THERMAL_STATUS_NONE;

    private final PowerManager.OnThermalStatusChangedListener mThermalListener = status -> {
        mThermalStatus = status;
        mThermalSink.accept(status);
    };

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Nothing to cache: the level is read on demand in admit(). The registration exists
            // so the broker is woken to re-evaluate a queued request when power state changes.
        }
    };

    ResourceGovernor(Context context, IntConsumer thermalSink) {
        mContext = context;
        mThermalSink = thermalSink;
        mPowerManager = context.getSystemService(PowerManager.class);
        mActivityManager = context.getSystemService(ActivityManager.class);
    }

    void start() {
        if (mPowerManager != null) {
            mThermalStatus = mPowerManager.getCurrentThermalStatus();
            mPowerManager.addThermalStatusListener(mThermalListener);
        }
        mContext.registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    void stop() {
        if (mPowerManager != null) {
            mPowerManager.removeThermalStatusListener(mThermalListener);
        }
        mContext.unregisterReceiver(mBatteryReceiver);
    }

    int thermalStatus() {
        return mThermalStatus;
    }

    /** @return null when a request may run, otherwise the {@link Errors} reason to refuse with. */
    String admit() {
        if (mThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
            // Sustained decode falls off badly once the device is already warm, and pushing on
            // makes every other thing on the phone worse. Refuse rather than deliver a slow answer.
            return Errors.THERMAL_THROTTLED;
        }
        if (batteryPercent() < HARD_BATTERY_FLOOR_PERCENT) {
            return Errors.BATTERY_QUOTA_EXCEEDED;
        }
        if (mPowerManager != null && mPowerManager.isPowerSaveMode()) {
            return Errors.BATTERY_QUOTA_EXCEEDED;
        }
        if (isLowMemory()) {
            return Errors.LOW_MEMORY;
        }
        return null;
    }

    private int batteryPercent() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        if (bm == null) {
            return 100;
        }
        int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return level <= 0 ? 100 : level;
    }

    private boolean isLowMemory() {
        if (mActivityManager == null) {
            return false;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        mActivityManager.getMemoryInfo(info);
        return info.lowMemory;
    }

    long totalRamBytes() {
        if (mActivityManager == null) {
            return 0;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        mActivityManager.getMemoryInfo(info);
        return info.totalMem;
    }

    void dump(PrintWriter pw) {
        pw.println("  resources: thermal=" + mThermalStatus
                + " battery=" + batteryPercent() + "%"
                + " powerSave=" + (mPowerManager != null && mPowerManager.isPowerSaveMode())
                + " lowMemory=" + isLowMemory()
                + " totalRamMb=" + (totalRamBytes() >> 20));
    }
}
