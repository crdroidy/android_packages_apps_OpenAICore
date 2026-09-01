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

package org.crdroid.intelligence.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import org.crdroid.intelligence.R;
import org.crdroid.intelligence.broker.ConsentStore;
import org.crdroid.intelligence.common.DeviceTier;
import org.crdroid.intelligence.common.ModelInfo;

import java.util.concurrent.Executors;

/**
 * Settings > System > On-device intelligence.
 *
 * <p>Everything here is a real control, not a description: the master switch actually stops the
 * broker answering, and "delete model" actually frees the several gigabytes on disk. A user who
 * turns this off should be able to verify with {@code df} that it is off.
 *
 * <p>A plain view controller rather than a Fragment. There is one screen and no back stack, so a
 * fragment buys nothing and {@code android.app.Fragment} is deprecated.
 */
final class IntelligenceSettingsPanel {

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Activity mActivity;
    private final ConsentStore mConsent;
    private final SettingsModelClient mModels;

    private Switch mMasterSwitch;
    private TextView mStatus;
    private TextView mCapability;
    private ProgressBar mProgress;
    private Button mDownloadButton;
    private Button mDeleteButton;

    IntelligenceSettingsPanel(Activity activity) {
        mActivity = activity;
        mConsent = new ConsentStore(activity);
        mModels = new SettingsModelClient(activity, Executors.newSingleThreadExecutor(), mMain);

        activity.setContentView(R.layout.intelligence_settings);
        mMasterSwitch = activity.findViewById(R.id.master_switch);
        mStatus = activity.findViewById(R.id.status);
        mCapability = activity.findViewById(R.id.capability);
        mProgress = activity.findViewById(R.id.download_progress);
        mDownloadButton = activity.findViewById(R.id.download_button);
        mDeleteButton = activity.findViewById(R.id.delete_button);

        mMasterSwitch.setChecked(mConsent.isGloballyEnabled());
        mMasterSwitch.setOnCheckedChangeListener(this::onMasterToggled);
        mDownloadButton.setOnClickListener(v -> onDownloadClicked());
        mDeleteButton.setOnClickListener(v -> onDeleteClicked());
    }

    private void onMasterToggled(CompoundButton button, boolean checked) {
        if (!checked) {
            mConsent.setGloballyEnabled(false);
            refresh();
            return;
        }
        // Turning it on is the disclosure point. Nothing has been downloaded or run before here,
        // which is what "ships off by default" has to mean to be worth anything.
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.consent_title)
                .setMessage(R.string.consent_body)
                .setPositiveButton(R.string.consent_accept, (d, w) -> {
                    mConsent.setGloballyEnabled(true);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    mMasterSwitch.setChecked(false);
                })
                .setOnCancelListener(d -> mMasterSwitch.setChecked(false))
                .show();
    }

    private void onDownloadClicked() {
        mModels.info(info -> {
            if (info == null) {
                return;
            }
            if (info.licenceAccepted) {
                startDownload(info);
                return;
            }
            new AlertDialog.Builder(mActivity)
                    .setTitle(mActivity.getString(R.string.licence_title, info.licenceName))
                    .setMessage(mActivity.getString(R.string.licence_body, info.displayName,
                            info.licenceName,
                            Formatter.formatShortFileSize(mActivity, info.sizeBytes)))
                    .setPositiveButton(R.string.licence_view, (d, w) -> openLicence(info.licenceUrl))
                    .setNeutralButton(R.string.licence_accept, (d, w) -> {
                        mModels.setLicenceAccepted(info.id, true);
                        startDownload(info);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void startDownload(ModelInfo info) {
        mProgress.setVisibility(View.VISIBLE);
        mProgress.setIndeterminate(true);
        mModels.download(info.id, (soFar, total) -> {
            if (total > 0) {
                mProgress.setIndeterminate(false);
                mProgress.setMax(100);
                mProgress.setProgress((int) (100 * soFar / total));
            }
            mStatus.setText(mActivity.getString(R.string.status_downloading,
                    Formatter.formatShortFileSize(mActivity, soFar),
                    Formatter.formatShortFileSize(mActivity, total)));
        }, this::refresh);
    }

    private void onDeleteClicked() {
        mModels.info(info -> {
            long bytes = info == null ? 0 : info.bytesDownloaded;
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.delete_title)
                    .setMessage(mActivity.getString(R.string.delete_body,
                            Formatter.formatShortFileSize(mActivity, bytes)))
                    .setPositiveButton(R.string.delete_confirm, (d, w) -> {
                        mModels.deleteAll(this::refresh);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void openLicence(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    void refresh() {
        boolean enabled = mConsent.isGloballyEnabled();
        mMasterSwitch.setChecked(enabled);
        mDownloadButton.setEnabled(enabled);

        int tier = DeviceTier.TIER_E;
        mCapability.setText(mActivity.getString(R.string.capability_summary, DeviceTier.name(tier)));

        mModels.info(info -> {
            if (info == null) {
                mStatus.setText(R.string.status_no_provider);
                mDownloadButton.setEnabled(false);
                mDeleteButton.setEnabled(false);
                mProgress.setVisibility(View.GONE);
                return;
            }
            mDeleteButton.setEnabled(info.bytesDownloaded > 0);
            switch (info.state) {
                case ModelInfo.STATE_AVAILABLE:
                    mStatus.setText(mActivity.getString(R.string.status_ready, info.displayName,
                            Formatter.formatShortFileSize(mActivity, info.bytesDownloaded)));
                    mDownloadButton.setEnabled(false);
                    mProgress.setVisibility(View.GONE);
                    break;
                case ModelInfo.STATE_DOWNLOADING:
                    mProgress.setVisibility(View.VISIBLE);
                    break;
                default:
                    mStatus.setText(mActivity.getString(R.string.status_downloadable, info.displayName,
                            Formatter.formatShortFileSize(mActivity, info.sizeBytes)));
                    mProgress.setVisibility(View.GONE);
                    break;
            }
        });
    }
}
