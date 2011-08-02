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

package com.android.nfc.ndefpush;

import com.android.nfc.DeviceHost.LlcpSocket;
import com.android.nfc.LlcpException;
import com.android.nfc.NfcService;

import android.nfc.NdefMessage;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;

/**
 * Simple client to push the local NDEF message to a server on the remote side of an
 * LLCP connection, using the Android Ndef Push Protocol.
 */
public class NdefPushClient {
    private static final String TAG = "NdefPushClient";
    private static final int MIU = 128;
    private static final boolean DBG = true;

    public boolean push(NdefMessage msg) {
        NfcService service = NfcService.getInstance();

        // We only handle a single immediate action for now
        NdefPushProtocol proto = new NdefPushProtocol(msg, NdefPushProtocol.ACTION_IMMEDIATE);
        byte[] buffer = proto.toByteArray();
        int offset = 0;
        int remoteMiu;
        LlcpSocket sock = null;
        try {
            if (DBG) Log.d(TAG, "about to create socket");
            // Connect to the my tag server on the remote side
            sock = service.createLlcpSocket(0, MIU, 1, 1024);
            if (sock == null) {
                throw new IOException("Could not connect to socket.");
            }
            if (DBG) Log.d(TAG, "about to connect to service " + NdefPushServer.SERVICE_NAME);
            sock.connectToService(NdefPushServer.SERVICE_NAME);

            remoteMiu = sock.getRemoteMiu();
            if (DBG) Log.d(TAG, "about to send a " + buffer.length + " byte message");
            while (offset < buffer.length) {
                int length = Math.min(buffer.length - offset, remoteMiu);
                byte[] tmpBuffer = Arrays.copyOfRange(buffer, offset, offset+length);
                if (DBG) Log.d(TAG, "about to send a " + length + " byte packet");
                sock.send(tmpBuffer);
                offset += length;
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "couldn't send tag");
            if (DBG) Log.d(TAG, "exception:", e);
        } catch (LlcpException e) {
            // Most likely the other side doesn't support the my tag protocol
            Log.e(TAG, "couldn't send tag");
            if (DBG) Log.d(TAG, "exception:", e);
        } finally {
            if (sock != null) {
                try {
                    if (DBG) Log.d(TAG, "about to close");
                    sock.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
        return false;
    }
}
