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

package com.android.nfc.mytag;

import com.android.internal.nfc.LlcpException;
import com.android.internal.nfc.LlcpServiceSocket;
import com.android.internal.nfc.LlcpSocket;
import com.android.nfc.NfcService;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A simple server that accepts NDEF messages pushed to it over an LLCP connection. Those messages
 * are typically set on the client side by using {@link NfcAdapter#setLocalNdefMessage}.
 */
public class MyTagServer {
    private static final String TAG = "MyTagServer";

    static final String SERVICE_NAME = "com.android.mytag";
    static final int SERVICE_SAP = 0x20;

    NfcService mService = NfcService.getInstance();
    /** Protected by 'this', null when stopped, non-null when running */
    ServerThread mServerThread;

    /** Connection class, used to handle incoming connections */
    private class ConnectionThread extends Thread {
        private LlcpSocket mSock;

        ConnectionThread(LlcpSocket sock) {
            mSock = sock;
        }

        @Override
        public void run() {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
                byte[] partial = new byte[1024];
                int size;
                boolean connectionBroken = false;

                // Get raw data from remote server
                while(!connectionBroken) {
                    try {
                        size = mSock.receive(partial);
                        buffer.write(partial, 0, size);
                    } catch (IOException e) {
                        // Connection broken
                        connectionBroken = true;
                    }
                }

                // Build NDEF message from the stream
                NdefMessage msg = new NdefMessage(buffer.toByteArray());

                // Send the intent for the fake tag
                mService.sendMockNdefTag(msg);
            } catch (FormatException e) {
                Log.e(TAG, "badly formatted NDEF message, ignoring", e);
            }
        }
    };

    /** Server class, used to listen for incoming connection request */
    class ServerThread extends Thread {
        boolean mRunning = true;
        LlcpServiceSocket mServerSocket;

        @Override
        public void run() {
            while(mRunning) {
                mServerSocket = mService.createLlcpServiceSocket(SERVICE_SAP, SERVICE_NAME,
                        128, 1, 1024);
                try {
                    LlcpSocket communicationSocket = mServerSocket.accept();
                    new ConnectionThread(communicationSocket).start();
                } catch (LlcpException e) {
                    Log.e(TAG, "llcp error", e);
                } catch (IOException e) {
                    Log.e(TAG, "IO error", e);
                } finally {
                    if (mServerSocket != null) mServerSocket.close();
                }
            }
        }

        public void shutdown() {
            mRunning = false;
            mServerSocket.close();
        }
    };

    public void start() {
        synchronized (this) {
            mServerThread = new ServerThread();
            mServerThread.start();
        }
    }

    public void stop() {
        synchronized (this) {
            if (mServerThread != null) {
                mServerThread.shutdown();
                mServerThread = null;
            }
        }
    }
}
