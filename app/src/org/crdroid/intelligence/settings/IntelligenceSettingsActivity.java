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
import android.os.Bundle;

/** Settings &gt; System &gt; On-device intelligence. */
public final class IntelligenceSettingsActivity extends Activity {

    private IntelligenceSettingsPanel mPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPanel = new IntelligenceSettingsPanel(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Download state changes underneath this screen, so re-read it rather than trusting what
        // was rendered when the activity was created.
        mPanel.refresh();
    }
}
