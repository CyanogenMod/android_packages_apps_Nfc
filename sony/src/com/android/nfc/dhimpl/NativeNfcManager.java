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

package com.android.nfc.dhimpl;

import com.android.nfc.DeviceHost;
import com.android.nfc.LlcpException;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.SharedPreferences;
import android.nfc.ErrorCodes;
import android.nfc.tech.Ndef;
import android.nfc.tech.TagTechnology;
import android.util.Log;
import com.android.nfc.NfcDiscoveryParameters;

import java.io.File;

/**
 * Native interface to the NFC Manager functions
 */
public class NativeNfcManager implements DeviceHost {
    private static final String TAG = "NativeNfcManager";
    private static final boolean DBG = true;

    static final String PREF = "NxpDeviceHost";


    static final String DRIVER_NAME = "sony";

    public static final String INTERNAL_TARGET_DESELECTED_ACTION = "com.android.nfc.action.INTERNAL_TARGET_DESELECTED";

    static final int DEFAULT_LLCP_MIU = 128;
    static final int DEFAULT_LLCP_RWSIZE = 1;

    //TODO: dont hardcode this
    private static final byte[][] EE_WIPE_APDUS = {
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00,
                (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x20, (byte)0x10, (byte)0x00},
        {(byte)0x80, (byte)0xe2, (byte)0x01, (byte)0x03, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x07, (byte)0xa0, (byte)0x00,
                (byte)0x00, (byte)0x04, (byte)0x76, (byte)0x30, (byte)0x30, (byte)0x00},
        {(byte)0x80, (byte)0xb4, (byte)0x00, (byte)0x00, (byte)0x00},
        {(byte)0x00, (byte)0xa4, (byte)0x04, (byte)0x00, (byte)0x00},
    };

    static {
        System.loadLibrary("nfc_sony_jni");
    }

    /* Native structure */
    private int mNative;

    private final DeviceHostListener mListener;
    private final Context mContext;

    private native int nativeInitializeDriver();

    private native boolean nativeInitializeDriver_llcp();

    public NativeNfcManager(Context context, DeviceHostListener listener) {
        mListener = listener;
        nativeInitializeDriver();
        nativeInitializeDriver_llcp();
        mContext = context;
    }

    private native int nativeGetLastError();

    public int doGetLastError() {
        return nativeGetLastError();
    }

    @Override
    public void checkFirmware() {
    }

    private native int nativeInitialize();

    @Override
    public boolean initialize() {
        boolean result;
        if (nativeInitialize() != 0) {
            result = false;
        } else {
            result = true;
        }
        return result;
    }

    private native int nativeDeinitialize();

    @Override
    public boolean deinitialize() {
        boolean result;
        if (nativeDeinitialize() != 0) {
            result = false;
        } else {
            result = true;
        }
        return result;
    }

    @Override
    public String getName() {
        return DRIVER_NAME;
    }

    @Override
    public boolean sendRawFrame(byte[] data)
    {
        return false;
    }

    @Override
    public boolean routeAid(byte[] aid, int route)
    {
        return false;
    }

    @Override
    public boolean unrouteAid(byte[] aid)
    {
       return false;
    }

    private native void doCommitRouting();

    @Override
    public boolean commitRouting()
    {
        doCommitRouting();
        return true;
    }

    private native int nativeStartDiscover(byte b1, byte b2);

    @Override
    public void enableDiscovery(NfcDiscoveryParameters params, boolean restart) {
        nativeStartDiscover((byte)0, (byte)0);
    }

    private native int nativeStopReaderAction();

    @Override
    public void disableDiscovery() {
        nativeStopReaderAction();
    }

    private native NativeLlcpConnectionlessSocket doCreateLlcpConnectionlessSocket(int nSap,
            String sn);

    @Override
    public LlcpConnectionlessSocket createLlcpConnectionlessSocket(int nSap, String sn)
            throws LlcpException {
        LlcpConnectionlessSocket socket = doCreateLlcpConnectionlessSocket(nSap, sn);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    private native NativeLlcpServiceSocket doCreateLlcpServiceSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength);
    @Override
    public LlcpServerSocket createLlcpServerSocket(int nSap, String sn, int miu,
            int rw, int linearBufferLength) throws LlcpException {
        LlcpServerSocket socket = doCreateLlcpServiceSocket(nSap, sn, miu, rw, linearBufferLength);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    private native NativeLlcpSocket doCreateLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength);
    @Override
    public LlcpSocket createLlcpSocket(int sap, int miu, int rw,
            int linearBufferLength) throws LlcpException {
        LlcpSocket socket = doCreateLlcpSocket(sap, miu, rw, linearBufferLength);
        if (socket != null) {
            return socket;
        } else {
            /* Get Error Status */
            int error = doGetLastError();

            Log.d(TAG, "failed to create llcp socket: " + ErrorCodes.asString(error));

            switch (error) {
                case ErrorCodes.ERROR_BUFFER_TO_SMALL:
                case ErrorCodes.ERROR_INSUFFICIENT_RESOURCES:
                    throw new LlcpException(error);
                default:
                    throw new LlcpException(ErrorCodes.ERROR_SOCKET_CREATION);
            }
        }
    }

    @Override
    public native boolean doCheckLlcp();

    @Override
    public native boolean doActivateLlcp();

    private native void nativeResetTimeout();

    @Override
    public void resetTimeouts() {
        nativeResetTimeout();
    }

    public native void nativeAbort();

    @Override
    public void doAbort() {
        nativeAbort();
    }

    private native boolean nativeSetTimeout(int tech, int timeout);
    @Override
    public boolean setTimeout(int tech, int timeout) {
        return nativeSetTimeout(tech, timeout);
    }

    private native int nativeGetTimeout(int tech);
    @Override
    public int getTimeout(int tech) {
        return nativeGetTimeout(tech);
    }


    @Override
    public boolean canMakeReadOnly(int ndefType) {
        // TODO check defined value
        return (ndefType == Ndef.TYPE_1/* 1 */ || ndefType == Ndef.TYPE_2/* 2 */ ||
                ndefType == Ndef.TYPE_MIFARE_CLASSIC/* 0x65 */);
    }


    private native int nativeGetTransceiveSendMaxSize(int technology);
    @Override
    public int getMaxTransceiveLength(int technology) {
        return nativeGetTransceiveSendMaxSize(technology);
    }

    @Override
    public void setP2pInitiatorModes(int modes) {
    }

    @Override
    public void setP2pTargetModes(int modes) {
    }

    @Override
    public boolean enableScreenOffSuspend() {
        // Snooze mode not supported on NXP silicon
        Log.i(TAG, "Snooze mode is not supported on NXP NFCC");
        return false;
    }

    @Override
    public boolean disableScreenOffSuspend() {
        // Snooze mode not supported on NXP silicon
        Log.i(TAG, "Snooze mode is not supported on NXP NFCC");
        return true;
    }

    @Override
    public boolean getExtendedLengthApdusSupported() {
        // Not supported on the PN544
        return false;
    }

    @Override
    public int getDefaultLlcpMiu() {
        return DEFAULT_LLCP_MIU;
    }

    @Override
    public int getDefaultLlcpRwSize() {
        return DEFAULT_LLCP_RWSIZE;
    }

    private native String nativeDump();
    @Override
    public String dump() {
        return nativeDump();
    }

    /**
     * Notifies Ndef Message (TODO: rename into notifyTargetDiscovered)
     */
    private void notifyNdefMessageListeners(NativeNfcTag tag) {
        mListener.onRemoteEndpointDiscovered(tag);
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

    private void notifyRfFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifyRfFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

    private native void nativeEnableReaderMode(int technologies);
    public boolean enableReaderMode(int technologies) {
        Log.i(TAG, "call: enableReaderMode technologies=" + technologies);
        nativeEnableReaderMode(technologies);
        return true;
    }

    private native void nativeDisableReaderMode();
    public boolean disableReaderMode() {
        Log.i(TAG, "call: disableReaderMode");
        nativeDisableReaderMode();
        return true;
    }

    private void notifyConnectivityListeners() {
        //mListener.onConnectivityEvent();
    }

    private void notifySeApduReceived(byte[] apdu) {
        //mListener.onSeApduReceived(apdu);
    }

    private void notifySeEmvCardRemoval() {
        //mListener.onSeEmvCardRemoval();
    }

    private void notifySeFieldActivated() {
        mListener.onRemoteFieldActivated();
    }

    private void notifySeFieldDeactivated() {
        mListener.onRemoteFieldDeactivated();
    }

    private void notifySeMifareAccess(byte[] block) {
        //mListener.onSeMifareAccess(block);
    }

    private void notifyTargetDeselected() {
        //mListener.onCardEmulationDeselected();
    }

    private void notifyTransactionListeners(byte[] aid) {
    }

    private void notifyTransactionListeners(byte[] aid, byte[] data) {
        //mListener.onCardEmulationAidSelected(aid, data);
    }

    private void notifyUartAbnormal() {
        //mListener.onUartAbnormal();
    }

    private void notifyUimTransactionListeners(byte[] aid, byte[] parameter) {
        Log.e(TAG, "notifyUimTransactionListeners aid=" + aid + ", parameter=" + parameter);
        byte[][] data = { aid, parameter };
        //mListener.onHciEvtTransaction(data);
    }

    public native int[] doGetSecureElementList();

    public native int doGetSecureElementTechList();

    public native void doPrbsOff();

    public native void doPrbsOn(int i, int j);

    public native void doSelectSecureElement(int i);

    public native void doSetEEPROM(byte[] array);

    public native void doSetSEPowerOffState(int i, boolean z);

    public native void doDeselectSecureElement(int i);

    public native int SWPSelfTest(int i);

    public int swpTest() {
        return SWPSelfTest(0);
    }
}
