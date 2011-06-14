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

package com.android.nfc.snep;

import java.io.IOException;

import com.android.internal.nfc.LlcpException;
import com.android.internal.nfc.LlcpSocket;
import com.android.nfc.NfcService;

import android.nfc.NdefMessage;
import android.util.Log;

public final class SnepClient {
    private static final String TAG = "SnepClient";
    private static final boolean DBG = false;
    private static final int DEFAULT_ACCEPTABLE_LENGTH = 100*1024;
    private static final int MIU = 128;
    SnepMessenger mMessenger = null;

    private final String mServiceName;
    private final int mPort;
    private LlcpSocket mSocket = null;
    private boolean mConnected = false;
    private final int mAcceptableLength;
    private final int mFragmentLength;

    public SnepClient() {
        mServiceName = SnepServer.DEFAULT_SERVICE_NAME;
        mPort = SnepServer.DEFAULT_PORT;
        mAcceptableLength = DEFAULT_ACCEPTABLE_LENGTH;
        mFragmentLength = -1;
    }

    public SnepClient(String serviceName) {
        mServiceName = serviceName;
        mPort = -1;
        mAcceptableLength = DEFAULT_ACCEPTABLE_LENGTH;
        mFragmentLength = -1;
    }

    SnepClient(String serviceName, int fragmentLength) {
        mServiceName = serviceName;
        mPort = -1;
        mAcceptableLength = DEFAULT_ACCEPTABLE_LENGTH;
        mFragmentLength = fragmentLength;
    }

    SnepClient(String serviceName, int acceptableLength, int fragmentLength) {
        mServiceName = serviceName;
        mPort = -1;
        mAcceptableLength = acceptableLength;
        mFragmentLength = fragmentLength;
    }

    public void put(NdefMessage msg) throws IOException {
        synchronized (this) {
            if (!mConnected) {
                throw new IOException("Socket not connected.");
            }

            try {
                mMessenger.sendMessage(SnepMessage.getPutRequest(msg));
                mMessenger.getMessage();
            } catch (SnepException e) {
                throw new IOException(e);
            }
        }
    }

    public SnepMessage get(NdefMessage msg) throws IOException {
        synchronized (this) {
            if (!mConnected) {
                throw new IOException("Socket not connected.");
            }

            try {
                mMessenger.sendMessage(SnepMessage.getGetRequest(mAcceptableLength, msg));
                return mMessenger.getMessage();
            } catch (SnepException e) {
                throw new IOException(e);
            }
        }
    }

    public void connect() throws IOException {
        synchronized (this) {
            try {
                if (mConnected) {
                    throw new IOException("Socket already connected.");
                }
                if (DBG) Log.d(TAG, "about to create socket");
                // Connect to the snep server on the remote side
                mSocket = NfcService.getInstance().createLlcpSocket(0, MIU, 1, 1024);
                if (mSocket == null) {
                    throw new IOException("Could not connect to socket.");
                }
                if (mPort == -1) {
                    if (DBG) Log.d(TAG, "about to connect to service " + mServiceName);
                    mSocket.connect(mServiceName);
                } else {
                    if (DBG) Log.d(TAG, "about to connect to port " + mPort);
                    mSocket.connect(mPort);
                }

                int miu = mSocket.getRemoteSocketMiu();
                int fragmentLength = (mFragmentLength == -1) ?  miu : Math.min(miu, mFragmentLength);
                mMessenger = new SnepMessenger(true, mSocket, fragmentLength);
            } catch (LlcpException e) {
                throw new IOException("Could not connect to socket");
            }
        }
    }

    public void close() {
        synchronized (this) {
            if (mSocket != null) {
               try {
                   mSocket.close();
               } catch (IOException e) {
                   // ignore
               } finally {
                   mConnected = false;
                   mSocket = null;
                   mMessenger = null;
               }
            }
        }
    }
}
