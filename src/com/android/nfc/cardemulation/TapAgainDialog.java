/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.nfc.cardemulation;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulationManager;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

public class TapAgainDialog extends AlertActivity {
    public static final String ACTION_CLOSE = "com.android.nfc.cardmeulation.close_tap_dialog";
    public static final String EXTRA_COMPONENT = "component";
    public static final String EXTRA_CATEGORY = "category";

    // Variables below only accessed on the main thread
    private CardEmulationManager mCardEmuManager;
    private boolean mClosedOnRequest = false;
    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mClosedOnRequest = true;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTheme(R.style.Theme_DeviceDefault_Light_Dialog_Alert);

        final NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        mCardEmuManager = CardEmulationManager.getInstance(adapter);
        Intent intent = getIntent();
        String category = intent.getStringExtra(EXTRA_CATEGORY);
        ComponentName component = (ComponentName) intent.getParcelableExtra(EXTRA_COMPONENT);
        IntentFilter filter = new IntentFilter(ACTION_CLOSE);
        registerReceiver(mReceiver, filter);
        AlertController.AlertParams ap = mAlertParams;

        ap.mTitle = "";
        ap.mView = getLayoutInflater().inflate(com.android.nfc.R.layout.tapagain, null);

        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(component.getPackageName(), 0);
            TextView tv = (TextView) ap.mView.findViewById(com.android.nfc.R.id.textview);
            if (CardEmulationManager.CATEGORY_PAYMENT.equals(category)) {
                tv.setText("Tap again to pay\nwith " + appInfo.loadLabel(pm));
            } else {
                tv.setText("Tap again to complete\nwith " + appInfo.loadLabel(pm));
            }
            setupAlert();
        } catch (NameNotFoundException e) {
            finish();
        }
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!mClosedOnRequest) {
            mCardEmuManager.setDefaultForNextTap(null);
        }
    }
}