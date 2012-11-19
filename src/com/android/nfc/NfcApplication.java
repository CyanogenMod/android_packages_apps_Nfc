package com.android.nfc;

import android.app.Application;
import android.os.UserHandle;
import android.util.Log;

public class NfcApplication extends Application {

    public static final String TAG = "NfcApplication";
    NfcService mNfcService;

    public NfcApplication() {

    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (UserHandle.myUserId() == 0) {
            mNfcService = new NfcService(this);
        }
    }
}
