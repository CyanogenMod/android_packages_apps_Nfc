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

package com.android.nfc.handover.wifi;

import android.content.Context;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
*
*/
public abstract class WifiHandoverTransferProcessor extends Thread {
    private static final String TAG = WifiHandoverTransferProcessor.class.getSimpleName();

    protected static final short[] SUPPORTED_PROTOCOLS = new short[] {
            GoogleRawFileTransferProtocol.PROTOCOL_ID
    };

    private static final int MSG_RETRY_CONNECTION = 1;
    private static final int RETRY_DELAY_MILLIS = 500;
    private static final int NUM_RETRIES = 2;
    private static final int WIFI_DIRECT_BEAM_PORT = 8888;

    private static final int SUPPORTED_PROTOCOL_ID_WIDTH = 2;
    private static final int PAYLOAD_SIZE_WIDTH = 2;
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final int MESSAGE_ID_WIDTH = 2;

    protected static final short SUPPORTED_PROTOCOL_MESSAGE_ID = 0x2001;

    protected static final short SELECT_PROTOCOL_MESSAGE_ID = 0x2002;
    protected static final int SUPPORTED_PROTOCOLS_MESSAGE_SIZE =
            MESSAGE_ID_WIDTH + PAYLOAD_SIZE_WIDTH
                    + SUPPORTED_PROTOCOLS.length * SUPPORTED_PROTOCOL_ID_WIDTH;
    protected static final int SELECT_PROTOCOL_MESSAGE_SIZE =
            MESSAGE_ID_WIDTH + SUPPORTED_PROTOCOL_ID_WIDTH;

    private final WifiP2pInfo mWifiP2pInfo;

    protected final Context mContext;
    protected final String mRemoteMacAddress;

    private Handler mHandler;
    private boolean mCancelled;

    private class MessageHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_RETRY_CONNECTION:
                    startTransfer(msg.arg1);
                    break;
            }
        }
    };

    WifiHandoverTransferProcessor(Context context,
                                  String remoteMacAddress,
                                  WifiP2pInfo wifiP2pInfo) {
        if (remoteMacAddress == null || wifiP2pInfo == null) {
            throw new RuntimeException("macAddress and wifiP2pInfo must be non-null");
        }

        mContext = context;
        mRemoteMacAddress = remoteMacAddress;
        mWifiP2pInfo = wifiP2pInfo;
    }

    @Override
    public void run() {
        Looper.prepare();
        mHandler = new MessageHandler();
        if (!startTransfer(NUM_RETRIES)) {
            // wait for retry message
            Looper.loop();
        }
    }

    boolean startTransfer(int retryCount) {
        Log.i(TAG, "Starting transfer...");
        Socket socket = getSocket(retryCount);

        if (socket == null) {
            return false;
        }

        processTransfer(socket);

        try {
            socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to close socket.");
        }

        return true;
    }

    private Socket getSocket(int retryCount) {
        try {
            return doGetSocket();
        } catch (IOException e) {
            try {
                Log.e(TAG, "Failed to connect. Retrying...", e);
                return doGetSocket();
            } catch (IOException e1) {
                Log.e(TAG, "Failed to connect. Retrying in " + RETRY_DELAY_MILLIS + "...", e1);
                if (retryCount > 0 && !mCancelled) {
                    Message msg = mHandler.obtainMessage(MSG_RETRY_CONNECTION);
                    msg.arg1 = --retryCount;

                    mHandler.sendMessageDelayed(msg, RETRY_DELAY_MILLIS);
                } else {
                    // We've run out of retries :(
                    Looper.myLooper().quit();
                }
            }
            return null;
        }
    }

    private Socket doGetSocket() throws IOException {
        if (mWifiP2pInfo.isGroupOwner) {
            Log.i(TAG, "Group owner. Opening server socket...");
            // Don't have remote IP, must wait for incoming connection
            ServerSocket serverSocket = new ServerSocket(WIFI_DIRECT_BEAM_PORT);
            return serverSocket.accept();
        } else {
            Log.i(TAG, "Not group owner. Connecting to " + mWifiP2pInfo.groupOwnerAddress + "...");
            return new Socket(mWifiP2pInfo.groupOwnerAddress, WIFI_DIRECT_BEAM_PORT);
        }
    }

    public void cancelTransfer() {
        mCancelled = true;
    }

    abstract void processTransfer(Socket socket);

    protected static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

}
