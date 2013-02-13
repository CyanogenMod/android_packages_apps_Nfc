package com.android.nfc;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.os.Process;
import android.os.UserHandle;
import java.util.Iterator;
import java.util.List;

public class NfcApplication extends Application {

    static final String TAG = "NfcApplication";
    static final String NFC_PROCESS = "com.android.nfc";

    NfcService mNfcService;

    public NfcApplication() {

    }

    @Override
    public void onCreate() {
        super.onCreate();

        boolean isMainProcess = false;
        // We start a service in a separate process to do
        // handover transfer. We don't want to instantiate an NfcService
        // object in those cases, hence check the name of the process
        // to determine whether we're the main NFC service, or the
        // handover process
        ActivityManager am = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);
        List processes = am.getRunningAppProcesses();
        Iterator i = processes.iterator();
        while (i.hasNext()) {
            RunningAppProcessInfo appInfo = (RunningAppProcessInfo)(i.next());
            if (appInfo.pid == Process.myPid()) {
                isMainProcess =  (NFC_PROCESS.equals(appInfo.processName));
                break;
            }
        }
        if (UserHandle.myUserId() == 0 && isMainProcess) {
            mNfcService = new NfcService(this);
        }
    }
}
