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

package com.android.nfc;

import java.util.ArrayList;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.nfc.BeamShareData;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.os.Bundle;
import android.util.Log;
import android.webkit.URLUtil;

/**
 * This class is registered by NfcService to handle
 * ACTION_SHARE intents. It tries to parse data contained
 * in ACTION_SHARE intents in either a content/file Uri,
 * which can be sent using NFC handover, or alternatively
 * it tries to parse texts and URLs to store them in a simple
 * Text or Uri NdefRecord. The data is then passed on into
 * NfcService to transmit on NFC tap.
 *
 */
public class BeamShareActivity extends Activity {
    static final String TAG ="BeamShareActivity";
    static final boolean DBG = true; // STOPSHIP set to false

    ArrayList<Uri> mUris;
    NdefMessage mNdefMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUris = new ArrayList<Uri>();
        mNdefMessage = null;
        parseShareIntent(getIntent());

        finish();
    }

    void tryUri(Uri uri) {
        if (uri.getScheme().equalsIgnoreCase("content") ||
                uri.getScheme().equalsIgnoreCase("file")) {
            // Typically larger data, this can be shared using NFC handover
            mUris.add(uri);
        } else {
            // Just put this Uri in an NDEF message
            mNdefMessage = new NdefMessage(NdefRecord.createUri(uri));
        }
    }

    void tryText(String text) {
        if (URLUtil.isValidUrl(text)) {
            Uri parsedUri = Uri.parse(text);
            tryUri(parsedUri);
        } else {
            mNdefMessage = new NdefMessage(NdefRecord.createTextRecord(null, text));
        }
    }

    public void parseShareIntent(Intent intent) {
        if (intent == null || (!intent.getAction().equalsIgnoreCase(Intent.ACTION_SEND) &&
                !intent.getAction().equalsIgnoreCase(Intent.ACTION_SEND_MULTIPLE))) return;

        // First, see if the intent contains clip-data, and if so get data from there
        ClipData clipData = intent.getClipData();
        if (clipData != null && clipData.getItemCount() > 0) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                ClipData.Item item = clipData.getItemAt(i);
                // First try to get an Uri
                Uri uri = item.getUri();
                String plainText = item.coerceToText(this).toString();
                if (uri != null) {
                    if (DBG) Log.d(TAG, "Found uri in ClipData.");
                    tryUri(uri);
                } else if (plainText != null) {
                    if (DBG) Log.d(TAG, "Found text in ClipData.");
                    tryText(plainText);
                } else {
                    if (DBG) Log.d(TAG, "Did not find any shareable data in ClipData.");
                }
            }
        } else {
            if (intent.getAction().equalsIgnoreCase(Intent.ACTION_SEND)) {
                final Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                final CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (uri != null) {
                    if (DBG) Log.d(TAG, "Found uri in ACTION_SEND intent.");
                    tryUri(uri);
                } else if (text != null) {
                    if (DBG) Log.d(TAG, "Found EXTRA_TEXT in ACTION_SEND intent.");
                    tryText(text.toString());
                } else {
                    if (DBG) Log.d(TAG, "Did not find any shareable data in ACTION_SEND intent.");
                }
            } else {
                final ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                final ArrayList<CharSequence> texts = intent.getCharSequenceArrayListExtra(
                        Intent.EXTRA_TEXT);

                if (uris != null && uris.size() > 0) {
                    for (Uri uri : uris) {
                        if (DBG) Log.d(TAG, "Found uri in ACTION_SEND_MULTIPLE intent.");
                        tryUri(uri);
                    }
                } else if (texts != null && texts.size() > 0) {
                    // Try EXTRA_TEXT, but just for the first record
                    if (DBG) Log.d(TAG, "Found text in ACTION_SEND_MULTIPLE intent.");
                    tryText(texts.get(0).toString());
                } else {
                    if (DBG) Log.d(TAG, "Did not find any shareable data in " +
                            "ACTION_SEND_MULTIPLE intent.");
                }
            }
        }

        BeamShareData shareData = null;
        if (mUris.size() > 0) {
            // Uris have our first preference for sharing
            Uri[] uriArray = new Uri[mUris.size()];
            int numValidUris = 0;
            for (Uri uri : mUris) {
                try {
                    grantUriPermission("com.android.nfc", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    uriArray[numValidUris++] = uri;
                    if (DBG) Log.d(TAG, "Found uri: " + uri);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security exception granting uri permission to NFC process.");
                    numValidUris = 0;
                    break;
                }
            }
            if (numValidUris > 0) {
                shareData = new BeamShareData(null, uriArray, 0);
            } else {
                // No uris left
                shareData = new BeamShareData(null, null, 0);
            }
        } else if (mNdefMessage != null) {
            shareData = new BeamShareData(mNdefMessage, null, 0);
            if (DBG) Log.d(TAG, "Created NDEF message:" + mNdefMessage.toString());
        } else {
            if (DBG) Log.d(TAG, "Could not find any data to parse.");
            // Activity may have set something to share over NFC, so pass on anyway
            shareData = new BeamShareData(null, null, 0);
        }

        NfcService.getInstance().invokeBeam(shareData);
    }
}
