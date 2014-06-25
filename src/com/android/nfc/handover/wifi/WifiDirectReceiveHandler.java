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
import java.util.HashSet;

public class WifiDirectReceiveHandler extends WifiHandoverTransferProcessor {
    private static final String TAG = "HandoverReceiver";

    private static final short SUPPORTED_PROTOCOL_MESSAGE_ID = 0x2001;

    private static final short[] SUPPORTED_PROTOCOLS = {};

    private GoogleRawFileTransferProtocol.Receiver mReceiver;

    public WifiDirectReceiveHandler(Context context, String remoteMacAddress,
                             WifiP2pInfo wifiP2pInfo) {
        super(context, remoteMacAddress, wifiP2pInfo);
    }

    @Override
    public void processTransfer(Socket socket) {
        Log.i(TAG, "Started...");

        DataInputStream inputStream;
        DataOutputStream outputStream;
        ProgressReporter progressReporter = new ProgressReporter(mContext, mRemoteMacAddress,
                HandoverService.DIRECTION_INCOMING);;

        try {
            inputStream = new DataInputStream(socket.getInputStream());
            outputStream = new DataOutputStream(socket.getOutputStream());

            int selectedProtocol = negotiateProtocol(inputStream, outputStream);

            Log.i(TAG, "Selected protocol: " + selectedProtocol);

            if (selectedProtocol == GoogleRawFileTransferProtocol.PROTOCOL_ID) {
                mReceiver = new GoogleRawFileTransferProtocol.Receiver(progressReporter);
                mReceiver.receive(inputStream, outputStream);
            }

        } catch (IOException e) {
            progressReporter.reportTransferDone(
                    HandoverService.HANDOVER_TRANSFER_STATUS_FAILURE, null, null);
            e.printStackTrace();
        }

    }

    private int negotiateProtocol(DataInputStream inputStream, DataOutputStream outputStream)
            throws IOException {
        short[] supportedProtocols = readSupportedProtocolsMessage(inputStream);
        if (supportedProtocols == null) {
            return 0;
        }

        HashSet<Short> supportedProtocolsSet = new HashSet<Short>(supportedProtocols.length);

        for (short protocol : supportedProtocols) {
            supportedProtocolsSet.add(protocol);
        }

        short selectedProtocol = 0;
        for (short protocol : SUPPORTED_PROTOCOLS) {
            if (supportedProtocolsSet.contains(protocol)) {
                selectedProtocol = protocol;
                break;
            }
        }
        outputStream.write(buildSelectProtocolMessage(selectedProtocol));
        return selectedProtocol;
    }


    private short[] readSupportedProtocolsMessage(DataInputStream inputStream)
            throws IOException {
        short messageId = inputStream.readShort();
        if (messageId == SUPPORTED_PROTOCOL_MESSAGE_ID) {
            short payloadSize = inputStream.readShort();
            if ((payloadSize & (~0x01)) != 0) {
                // expecting even payload size
                return null;
            }

            byte[] buffer = new byte[payloadSize];
            int read = inputStream.read(buffer);
            if (read != payloadSize) {
                return null;
            }
            return toShortArray(buffer);
        }

        return null;
    }

    private short[] toShortArray(byte[] buffer) {
        ByteBuffer converter = ByteBuffer.wrap(buffer);
        converter.order(ByteOrder.BIG_ENDIAN);
        short[] supportedProtocols = new short[buffer.length / 2];
        converter.asShortBuffer().get(supportedProtocols);
        return supportedProtocols;
    }

    private byte[] buildSelectProtocolMessage(short selectedProtocol) {
        ByteBuffer buffer = ByteBuffer.allocate(SELECT_PROTOCOL_MESSAGE_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.putShort(SELECT_PROTOCOL_MESSAGE_ID);
        buffer.putShort(selectedProtocol);
        buffer.flip();
        return buffer.array();
    }

    @Override
    public void cancelTransfer() {
        mReceiver.cancel();
    }

}

