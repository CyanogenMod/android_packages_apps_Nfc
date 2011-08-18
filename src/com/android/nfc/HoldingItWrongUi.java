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
 * limitations under the License.
 */

package com.android.nfc;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.WindowManager;

import com.android.nfc3.R;

public class HoldingItWrongUi implements DialogInterface.OnDismissListener {

    AlertDialog mDialog;

    /** Must call from UI thread */
    public void show(Context context) {
        if (mDialog != null) {
            return;
        }

        View v = View.inflate(context, R.layout.holding_it_wrong, null);

        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setCancelable(false);
        b.setView(v);

        AlertDialog d = b.create();
        d.setOnDismissListener(this);
        d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        d.show();
        mDialog = d;
    }

    /** Must call from UI thread */
    public void dismiss() {
        if (mDialog != null) {
            mDialog.dismiss();
            mDialog = null;
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        mDialog = null;
    }
}
