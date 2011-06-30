/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.nfc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.util.Log;

public class BluetoothMeProfileReceiver extends BroadcastReceiver {
    private static final String TAG = "BluetoothMeProfileReceiver";

    public static final String EXTRA_PROFILE = "profile";
    public static final String EXTRA_TARGET = "target";

    @Override
    public void onReceive(Context context, Intent intent) {
        NdefMessage target = intent.getParcelableExtra(EXTRA_TARGET);
        NdefMessage profile = intent.getParcelableExtra(EXTRA_PROFILE);
        if (profile == null || target == null) {
            Log.d(TAG, "missing profile or target extra");
            return;
        }

        NfcService service = NfcService.getInstance();
        service.sendMeProfile(target, profile);
    }
}
