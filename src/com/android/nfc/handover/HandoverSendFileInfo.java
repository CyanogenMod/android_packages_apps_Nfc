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

package com.android.nfc.handover;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

//TODO: find a way to merge this with BluetoohOppSendFileInfo
public class HandoverSendFileInfo {
    private static final String TAG = "HandoverSendFileInfo";
    private static final boolean DBG = false;

    public static final String CONTENT_SCHEME = "content";

    /** readable media file name */
    public final String mFileName;

    /** media file input stream */
    public final FileInputStream mInputStream;

    public final int mStatus;

    public final String mMimetype;

    public final long mLength;

    /** for media file */
    public HandoverSendFileInfo(String fileName, String type, long length,
                                    FileInputStream inputStream, int status) {
        mFileName = fileName;
        mMimetype = type;
        mLength = length;
        mInputStream = inputStream;
        mStatus = status;
    }

    public static HandoverSendFileInfo generateFileInfo(Context context, Uri uri,
                                                            String type) {
        ContentResolver contentResolver = context.getContentResolver();
        String scheme = uri.getScheme();
        String fileName = null;
        String contentType;
        long length = 0;
        // Support all Uri with "content" scheme
        // This will allow more 3rd party applications to share files via
        // wifi
        if (CONTENT_SCHEME.equals(scheme)) {
            contentType = contentResolver.getType(uri);
            Cursor metadataCursor;
            try {
                metadataCursor = contentResolver.query(uri, new String[] {
                        OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
                }, null, null, null);
            } catch (SQLiteException e) {
                // some content providers don't support the DISPLAY_NAME or SIZE columns
                metadataCursor = null;
            }
            if (metadataCursor != null) {
                try {
                    if (metadataCursor.moveToFirst()) {
                        fileName = metadataCursor.getString(0);
                        length = metadataCursor.getInt(1);
                        if (DBG) Log.d(TAG, "fileName = " + fileName + " length = " + length);
                    }
                } finally {
                    metadataCursor.close();
                }
            }
            if (fileName == null) {
                // use last segment of URI if DISPLAY_NAME query fails
                fileName = uri.getLastPathSegment();
            }
        } else if ("file".equals(scheme)) {
            fileName = uri.getLastPathSegment();
            contentType = type;
            File f = new File(uri.getPath());
            length = f.length();
        } else {
            // currently don't accept other scheme
            return null;
        }

        FileInputStream is = null;
        if (CONTENT_SCHEME.equals(scheme)) {
            try {
                // We've found that content providers don't always have the
                // right size in _OpenableColumns.SIZE
                // As a second source of getting the correct file length,
                AssetFileDescriptor fd = contentResolver.openAssetFileDescriptor(uri, "r");
                long statLength = fd.getLength();
                if (length != statLength && statLength > 0) {
                    Log.e(TAG, "Content provider length is wrong (" + Long.toString(length) +
                            "), using stat length (" + Long.toString(statLength) + ")");
                    length = statLength;
                }
                try {
                    // This creates an auto-closing input-stream, so
                    // the file descriptor will be closed whenever the InputStream
                    // is closed.
                    is = fd.createInputStream();
                } catch (IOException e) {
                    try {
                        fd.close();
                    } catch (IOException e2) {
                        // Ignore
                    }
                }
            } catch (FileNotFoundException e) {
                // Ignore
            }
        }
        // get a file descriptor and get the stat length
        if (is == null) {
            try {
                is = (FileInputStream) contentResolver.openInputStream(uri);
            } catch (FileNotFoundException e) {
                return null;
            }
        }
        // If we can not get file length from content provider, we can try to
        // get the length via the opened stream.
        if (length == 0) {
            try {
                length = is.available();
                if (DBG) Log.d(TAG, "file length is " + length);
            } catch (IOException e) {
                Log.e(TAG, "Read stream exception: ", e);
                return null;
            }
        }

        return new HandoverSendFileInfo(fileName, contentType, length, is, 0);
    }

}
