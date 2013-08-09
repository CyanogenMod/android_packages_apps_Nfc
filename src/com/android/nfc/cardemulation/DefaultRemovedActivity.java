package com.android.nfc.cardemulation;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import com.android.internal.R;

import com.android.internal.app.AlertActivity;
import com.android.internal.app.AlertController;

public class DefaultRemovedActivity extends AlertActivity implements
        DialogInterface.OnClickListener {
    public static final String EXTRA_DEFAULT_NAME = "default";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_DeviceDefault_Light_Dialog_Alert);
        super.onCreate(savedInstanceState);
        CharSequence appName = getIntent().getCharSequenceExtra(EXTRA_DEFAULT_NAME);

        AlertController.AlertParams ap = mAlertParams;

        ap.mMessage = appName + " was your preferred option for tap and pay. Choose another?";
        ap.mNegativeButtonText = getString(R.string.no);
        ap.mPositiveButtonText = getString(R.string.yes);
        ap.mPositiveButtonListener = this;
        setupAlert();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        // Launch into Settings
        Intent intent = new Intent(Settings.ACTION_NFC_PAYMENT_SETTINGS);
        startActivity(intent);
    }
}