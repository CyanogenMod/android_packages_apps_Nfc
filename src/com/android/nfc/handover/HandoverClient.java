/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.nfc.handover;

import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.util.Log;

import com.android.nfc.LlcpException;
import com.android.nfc.NfcService;
import com.android.nfc.DeviceHost.LlcpSocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class HandoverClient {
    private static final String TAG = "HandoverClient";
    private static final int MIU = 128;
    // Max NDEF length to receive for Hr/Hs messages
    private static final boolean DBG = false;

    public NdefMessage sendHandoverRequest(NdefMessage msg) {
        if (msg == null) return null;

        NfcService service = NfcService.getInstance();

        LlcpSocket sock = null;
        int offset = 0;
        byte[] buffer = msg.toByteArray();
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

        try {
            sock = service.createLlcpSocket(0, MIU, 1, 1024);
            if (sock == null) {
                throw new IOException("Could not connect to socket.");
            }
            if (DBG) Log.d(TAG, "about to connect to service " +
                    HandoverServer.HANDOVER_SERVICE_NAME);
            sock.connectToService(HandoverServer.HANDOVER_SERVICE_NAME);

            int remoteMiu = sock.getRemoteMiu();
            if (DBG) Log.d(TAG, "about to send a " + buffer.length + " byte message");
            while (offset < buffer.length) {
                int length = Math.min(buffer.length - offset, remoteMiu);
                byte[] tmpBuffer = Arrays.copyOfRange(buffer, offset, offset+length);
                if (DBG) Log.d(TAG, "about to send a " + length + " byte packet");
                sock.send(tmpBuffer);
                offset += length;
            }

            // Now, try to read back the SNEP response
            byte[] partial = new byte[sock.getLocalMiu()];
            NdefMessage handoverSelectMsg = null;
            while (true) {
                int size = sock.receive(partial);
                if (size < 0) {
                    break;
                }
                byteStream.write(partial, 0, size);
                try {
                    handoverSelectMsg = new NdefMessage(byteStream.toByteArray());
                    // If we get here, message is complete
                    break;
                } catch (FormatException e) {
                    // Ignore, and try to fetch more bytes
                }
            }
            return handoverSelectMsg;
        } catch (IOException e) {
            if (DBG) Log.d(TAG, "couldn't connect to handover service");
        } catch (LlcpException e) {
            if (DBG) Log.d(TAG, "couldn't connect to handover service");
        } finally {
            if (sock != null) {
                try {
                    if (DBG) Log.d(TAG, "about to close");
                    sock.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
            try {
                byteStream.close();
            } catch (IOException e) {
                // Ignore
            }
        }
        return null;
    }
}
