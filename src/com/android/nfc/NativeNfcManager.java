/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;

/**
 * Native interface to the NFC Manager functions
 */
public class NativeNfcManager {
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_LLCP_LINK_STATE_CHANGED_ACTION = "com.android.nfc.action.INTERNAL_LLCP_LINK_STATE_CHANGED";

    public static final String INTERNAL_LLCP_LINK_STATE_CHANGED_EXTRA = "com.android.nfc.extra.INTERNAL_LLCP_LINK_STATE";

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.android.nfc.action.INTERNAL_TARGET_DESELECTED";

    private static final String NFC_PERM = android.Manifest.permission.NFC;

    /* Native structure */
    private int mNative;

    private final Context mContext;

    private final NfcService mNfcService;

    private static final String TAG = "NativeNfcManager";

    public NativeNfcManager(Context context, NfcService service) {
        mContext = context;
        mNfcService = service;
    }

    /**
     * Initializes Native structure
     */
    public native boolean initializeNativeStructure();

    /**
     * Initializes NFC stack.
     */
    public native boolean initialize();

    /**
     * Deinitializes NFC stack.
     */
    public native boolean deinitialize();

    /**
     * Enable discory for the NdefMessage and Transaction notification
     */
    public native void enableDiscovery(int mode);

    public native void disableDiscovery();

    public native int[] doGetSecureElementList();

    public native void doSelectSecureElement(int seID);

    public native void doDeselectSecureElement(int seID);

    public native int doGetLastError();

    public native void doSetProperties(int param, int value);

    public native NativeLlcpConnectionlessSocket doCreateLlcpConnectionlessSocket(int nSap);

    public native NativeLlcpServiceSocket doCreateLlcpServiceSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength);

    public native NativeLlcpSocket doCreateLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength);

    public native boolean doCheckLlcp();

    public native boolean doActivateLlcp();


    public native void doResetIsoDepTimeout();
    public void resetIsoDepTimeout() {
        doResetIsoDepTimeout();
    }

    public native void doSetIsoDepTimeout(int timeout);
    public void setIsoDepTimeout(int timeout) {
        doSetIsoDepTimeout(timeout);
    }

    /**
     * Notifies Ndef Message (TODO: rename into notifyTargetDiscovered)
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mNfcService.sendMessage(NfcService.MSG_NDEF_TAG, tag);
    }

    /**
     * Notifies transaction
     */
    private void notifyTargetDeselected() {
        mNfcService.sendMessage(NfcService.MSG_TARGET_DESELECTED, null);
    }

    /**
     * Notifies transaction
     */
    private void notifyTransactionListeners(byte[] aid) {
        mNfcService.sendMessage(NfcService.MSG_CARD_EMULATION, aid);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkActivation(NativeP2pDevice device) {
        mNfcService.sendMessage(NfcService.MSG_LLCP_LINK_ACTIVATION, device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkDeactivated(NativeP2pDevice device) {
        mNfcService.sendMessage(NfcService.MSG_LLCP_LINK_DEACTIVATED, device);
    }

    private void notifySeFieldActivated() {
        mNfcService.sendMessage(NfcService.MSG_SE_FIELD_ACTIVATED, null);
    }

    private void notifySeFieldDeactivated() {
        mNfcService.sendMessage(NfcService.MSG_SE_FIELD_DEACTIVATED, null);
    }
}
