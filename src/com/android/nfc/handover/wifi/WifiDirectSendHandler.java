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
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pInfo;
import android.util.Log;
import com.android.nfc.handover.HandoverService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class WifiDirectSendHandler extends WifiHandoverTransferProcessor {
    private static final String TAG = "HandoverSender";

    private final Uri[] mUris;
    private boolean mCancelled;
    private GoogleRawFileTransferProtocol.Sender mSender;

    public WifiDirectSendHandler(Context context, String remoteMacAddress,
                                 WifiP2pInfo wifiP2pInfo, Uri[] uris) {
        super(context, remoteMacAddress, wifiP2pInfo);

        mUris = uris;
        mCancelled = false;
    }

    @Override
    public void processTransfer(Socket socket) {
        Log.i(TAG, "Started...");

        DataOutputStream outputStream = null;
        DataInputStream inputStream = null;
        ProgressReporter progressReporter = new ProgressReporter(mContext, mRemoteMacAddress,
                HandoverService.DIRECTION_OUTGOING);

        try {
            outputStream = new DataOutputStream(socket.getOutputStream());
            inputStream = new DataInputStream(socket.getInputStream());

            int selectedProtocol = negotiateProtocol(outputStream, inputStream);

            // Currently only support Raw File Transfer Protocol
            if (selectedProtocol == GoogleRawFileTransferProtocol.PROTOCOL_ID) {
                mSender = new GoogleRawFileTransferProtocol.Sender(mContext, mUris,
                        progressReporter);
                mSender.send(inputStream, outputStream);
            }

        } catch (IOException e) {
            progressReporter.reportTransferDone(
                    HandoverService.HANDOVER_TRANSFER_STATUS_FAILURE, null, null);
            e.printStackTrace();
        }
    }

    private int negotiateProtocol(OutputStream outputStream, InputStream inputStream)
            throws IOException  {
        byte[] buffer = buildSupportedProtocolsMessage();
        Log.i(TAG, "Sending " + bytesToHex(buffer));
        outputStream.write(buffer);
        buffer = new byte[SELECT_PROTOCOL_MESSAGE_SIZE];
        int read = inputStream.read(buffer);
        if (read < SELECT_PROTOCOL_MESSAGE_SIZE) {
            return 0;
        }
        int selectedProtocol = getSelectedProtocol(buffer);
        Log.i(TAG, "selected protocol: " + selectedProtocol);
        return selectedProtocol;
    }

    private int getSelectedProtocol(byte[] buffer) {
        if (buffer.length != SELECT_PROTOCOL_MESSAGE_SIZE) {
            return 0;
        }

        ByteBuffer converter = ByteBuffer.allocate(SELECT_PROTOCOL_MESSAGE_SIZE);
        converter.order(ByteOrder.BIG_ENDIAN);

        converter.put(buffer);
        converter.flip();
        short messageId = converter.getShort();

        if (messageId == SELECT_PROTOCOL_MESSAGE_ID) {
            return converter.getShort();
        }

        return 0;
    }

    @Override
    public void cancelTransfer() {
        if (mSender != null) {
            mSender.cancel();
        }
    }

    private byte[] buildSupportedProtocolsMessage() {
        ByteBuffer buffer = ByteBuffer.allocate(SUPPORTED_PROTOCOLS_MESSAGE_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        
        buffer.putShort(SUPPORTED_PROTOCOL_MESSAGE_ID);
        buffer.putShort((short) (SUPPORTED_PROTOCOLS.length * 2));

        for (short protocol : SUPPORTED_PROTOCOLS) {
            buffer.putShort(protocol);
        }

        buffer.flip();
        return buffer.array();
    }



}
