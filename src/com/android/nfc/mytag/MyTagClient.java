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

    public MyTagClient(Context context) {
        context.registerReceiver(this, new IntentFilter(NfcAdapter.ACTION_LLCP_LINK_STATE_CHANGED));
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter();
        NdefMessage msg = adapter.getLocalNdefMessage();
        
        if (msg == null) {
            // Nothing to send to the server
            return;
        }

        int linkState = intent.getIntExtra(NfcAdapter.EXTRA_LLCP_LINK_STATE_CHANGED,
                    NfcAdapter.LLCP_LINK_STATE_DEACTIVATED);

        if (linkState != NfcAdapter.LLCP_LINK_STATE_ACTIVATED) {
            return;
        }

        new SendAsync().execute(msg);
    }

    final class SendAsync extends AsyncTask<NdefMessage, Void, Void> {
        @Override
        public Void doInBackground(NdefMessage... msgs) {
            NfcService service = NfcService.getInstance();
            NdefMessage msg = msgs[0];
            try {
                // Connect to the my tag server on the remote side
                LlcpSocket sock = service.createLlcpSocket(0, 128, 1, 1024);
                sock.connect(MyTagServer.SERVICE_NAME);

                // Push the local NDEF message to the server
                sock.send(msg.toByteArray());
                sock.close();

            } catch (IOException e) {
                Log.e(TAG, "couldn't send tag", e);
            } catch (LlcpException e) {
                // Most likely the other side doesn't support the my tag protocol
                Log.e(TAG, "couldn't send tag", e);
            }
            return null;
        }
    }
}
