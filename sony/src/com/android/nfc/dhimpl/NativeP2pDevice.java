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

import com.android.nfc.DeviceHost.NfcDepEndpoint;

/**
 * Native interface to the P2P Initiator functions
 */
public class NativeP2pDevice implements NfcDepEndpoint {

    private int mHandle;

    private int mMode;

    private byte[] mGeneralBytes;

    private boolean mConnectedFlag = false;

    @Override
    public byte[] receive() {
        return null;
    }

    @Override
    public boolean send(byte[] data) {
        return false;
    }

    @Override
    public boolean connect() {
        return true;
    }

    private native boolean nativeDisconnect(boolean connectedFlag);
    @Override
    public boolean disconnect() {
        mConnectedFlag = nativeDisconnect(mConnectedFlag);
        return mConnectedFlag;
    }

    @Override
    public byte[] transceive(byte[] data) {
        return null;
    }

    @Override
    public int getHandle() {
        return mHandle;
    }

    @Override
    public int getMode() {
        return mMode;
    }

    @Override
    public byte[] getGeneralBytes() {
        return mGeneralBytes;
    }

}
