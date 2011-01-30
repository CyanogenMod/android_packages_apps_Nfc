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

package com.android.nfc.ndefpush;

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
import java.util.Arrays;

/**
 * Simple client to push the local NDEF message to a server on the remote side of an
 * LLCP connection. The message is set via {@link NfcAdapter#setLocalNdefMessage}.
 */
public class NdefPushClient extends BroadcastReceiver {
    private static final String TAG = "NdefPushClient";
    private static final int MIU = 128;
    private static final boolean DBG = true;

    /** Locked on MyTagClient.class */
    NdefMessage mForegroundMsg;

    public NdefPushClient(Context context) {
        context.registerReceiver(this, new IntentFilter(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED));
    }

    public boolean setForegroundMessage(NdefMessage msg) {
        synchronized (this) {
            boolean set = mForegroundMsg != null;
            mForegroundMsg = msg;
            return set;
        }
    }

    public NdefMessage getForegroundMessage() {
        synchronized (this) {
            return mForegroundMsg;
        }
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

        NdefMessage foregroundMsg;
        synchronized (this) {
            foregroundMsg = mForegroundMsg;
        }

        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        NdefMessage myTag = adapter.getLocalNdefMessage();

        if (foregroundMsg != null && myTag != null) {
            if (DBG) Log.d(TAG, "sending foreground and my tag");
            new SendAsync().execute(foregroundMsg, myTag);
        } else if (myTag != null) {
            if (DBG) Log.d(TAG, "sending my tag");
            new SendAsync().execute(myTag);
        } else if (foregroundMsg != null) {
            if (DBG) Log.d(TAG, "sending foreground");
            new SendAsync().execute(foregroundMsg);
        } else {
            if (DBG) Log.d(TAG, "no tags set, bailing");
            return;
        }
    }

    final class SendAsync extends AsyncTask<NdefMessage, Void, Void> {
        @Override
        public Void doInBackground(NdefMessage... msgs) {
            NfcService service = NfcService.getInstance();

            // We only handle a single immediate action for now
            NdefPushProtocol msg = new NdefPushProtocol(msgs[0], NdefPushProtocol.ACTION_IMMEDIATE);
            byte[] buffer = msg.toByteArray();
            int offset = 0;
            int remoteMiu;
            LlcpSocket sock = null;
            try {
                if (DBG) Log.d(TAG, "about to create socket");
                // Connect to the my tag server on the remote side
                sock = service.createLlcpSocket(0, MIU, 1, 1024);
                if (DBG) Log.d(TAG, "about to connect to service " + NdefPushServer.SERVICE_NAME);
                sock.connect(NdefPushServer.SERVICE_NAME);

                remoteMiu = sock.getRemoteSocketMiu();
                if (DBG) Log.d(TAG, "about to send a " + buffer.length + " byte message");
                while (offset < buffer.length) {
                    int length = Math.min(buffer.length - offset, remoteMiu);
                    byte[] tmpBuffer = Arrays.copyOfRange(buffer, offset, offset+length);
                    if (DBG) Log.d(TAG, "about to send a " + length + " byte packet");
                    sock.send(tmpBuffer);
                    offset += length;
                }
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
            return null;
        }
    }
}
