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

package com.android.nfc.nxp;

import com.android.nfc.DeviceHost;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;

/**
 * Native interface to the NFC Manager functions
 */
public class NativeNfcManager implements DeviceHost {
    static {
        System.loadLibrary("nfc_jni");
    }

    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.android.nfc.action.INTERNAL_TARGET_DESELECTED";

    /* Native structure */
    private int mNative;

    private final DeviceHostListener mListener;

    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
    }

    public native boolean initializeNativeStructure();

    @Override
    public native boolean initialize();

    @Override
    public native boolean deinitialize();

    @Override
    public native void enableDiscovery();

    @Override
    public native void disableDiscovery();

    @Override
    public native int[] doGetSecureElementList();

    @Override
    public native void doSelectSecureElement();

    @Override
    public native void doDeselectSecureElement();

    @Override
    public native int doGetLastError();

    @Override
    public native NativeLlcpConnectionlessSocket doCreateLlcpConnectionlessSocket(int nSap);

    @Override
    public native NativeLlcpServiceSocket doCreateLlcpServiceSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength);

    @Override
    public native NativeLlcpSocket doCreateLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength);

    @Override
    public native boolean doCheckLlcp();

    @Override
    public native boolean doActivateLlcp();

    private native void doResetTimeouts();

    @Override
    public void resetTimeouts() {
        doResetTimeouts();
    }

    public native void doAbort();

    private native boolean doSetTimeout(int tech, int timeout);
    @Override
    public boolean setTimeout(int tech, int timeout) {
        return doSetTimeout(tech, timeout);
    }


    /**
     * Notifies Ndef Message (TODO: rename into notifyTargetDiscovered)
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
    }

    /**
     * Notifies transaction
     */
    private void notifyTargetDeselected() {
        mListener.onCardEmulationDeselected();
    }

    /**
     * Notifies transaction
     */
    private void notifyTransactionListeners(byte[] aid) {
        mListener.onCardEmulationAidSelected(aid);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkActivation(NativeP2pDevice device) {
        mListener.onLlcpLinkActivated(device);
    }

    /**
     * Notifies P2P Device detected, to activate LLCP link
     */
    private void notifyLlcpLinkDeactivated(NativeP2pDevice device) {
        mListener.onLlcpLinkDeactivated(device);
    }

    private void notifySeFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifySeFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }
}
