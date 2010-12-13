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
import com.android.internal.nfc.LlcpSocket;
import com.android.nfc.NfcService;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.util.Log;

import java.io.IOException;

/**
 * Simple client to push the local NDEF message to a server on the remote side of an
 * LLCP connection. The message is set via {@link NfcAdapter#setLocalNdefMessage}.
 */
public class MyTagClient extends BroadcastReceiver {
    private static final String TAG = "MyTagClient";
    private static final int MIU = 128;
    private static final boolean DBG = true;

    public MyTagClient(Context context) {
        context.registerReceiver(this, new IntentFilter(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int linkState = intent.getIntExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);
        if (linkState != NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
            // The link was torn down, ignore
            return;
        }

        if (DBG) Log.d(TAG, "LLCP connection up and running");
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        NdefMessage msg = adapter.getLocalNdefMessage();
        if (msg == null) {
            if (DBG) Log.d(TAG, "No MyTag set, exiting");
            // Nothing to send to the server
            return;
        }

        new SendAsync().execute(msg);
    }

    final class SendAsync extends AsyncTask<NdefMessage, Void, Void> {
        private void trace(String msg) {
            if (DBG) Log.d(TAG, Thread.currentThread().getId() + ": " + msg);
        }
        private void error(String msg, Throwable e) {
            if (DBG) Log.e(TAG, Thread.currentThread().getId() + ": " + msg, e);
        }

        @Override
        public Void doInBackground(NdefMessage... msgs) {
            NfcService service = NfcService.getInstance();
            NdefMessage msg = msgs[0];
            byte[] buffer = msg.toByteArray();
            int offset = 0;
            LlcpSocket sock = null;
            try {
                trace("about to create socket");
                // Connect to the my tag server on the remote side
                sock = service.createLlcpSocket(0, MIU, 1, 1024);
                trace("about to connect");
//                sock.connect(MyTagServer.SERVICE_NAME);
                sock.connect(0x20);

                trace("about to send a " + buffer.length + "-bytes message");
                while (offset < buffer.length) {
                    int length = buffer.length - offset;
                    if (length > MIU) {
                        length = MIU;
                    }
                    byte[] tmpBuffer = new byte[length];
                    System.arraycopy(buffer, offset, tmpBuffer, 0, length);
                    trace("about to send a " + length + "-bytes packet");
                    sock.send(tmpBuffer);
                    offset += length;
                }
            } catch (IOException e) {
                error("couldn't send tag", e);
            } catch (LlcpException e) {
                // Most likely the other side doesn't support the my tag protocol
                error("couldn't send tag", e);
            } finally {
                if (sock != null) {
                    try {
                        trace("about to close");
                        sock.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            }
            return null;
        }
    }
}
