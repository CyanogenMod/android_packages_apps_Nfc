/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.nfc.sony;

//import android.nfc.INfcUtilityCallback;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;

public class NativeNfcUtility {
    private static final String TAG = "NativeNfcUtility";

    private static final boolean DBG = false;
    private static final int MSG_WAIT_SIM_BOOT = 1;
    private static boolean mIsLock = false;
    //private static INfcUtilityCallback mStaticCallback;
    final NativeNfcUtilityHandler mHandler = new NativeNfcUtilityHandler();
    private native int nativeWaitSimBoot(boolean paramBoolean);
  
    void startWaitSimBoot() {
        nativeWaitSimBoot(mIsLock);
//      if (mStaticCallback != null) {
//      try {
//          mStaticCallback.SimBootComplete();
//      } catch (RemoteException localRemoteException) {
//          e.printStackTrace();
//      }
    }
  
//  public boolean waitSimBoot(INfcUtilityCallback callback, boolean isLock) {
//        mIsLock = isLock;
//        mStaticCallback = callback;
//        this.mHandler.sendEmptyMessage(1);
//        return true;
//  }
  
    final class NativeNfcUtilityHandler extends Handler {
        NativeNfcUtilityHandler() {
        }

        public void handleMessage(Message paramMessage) {
            NativeNfcUtility.this.startWaitSimBoot();
        }
    }
}

