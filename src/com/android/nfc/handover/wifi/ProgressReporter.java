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
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import com.android.nfc.handover.HandoverService;
import com.android.nfc.handover.HandoverTransfer;

/**
 * Abstraction for reporting the progress of a Wi-Fi Beam Transaction
 */
class ProgressReporter {

    private final Context mContext;
    private final String mRemoteMacAddress;
    private final int mDirection;

    ProgressReporter(Context context, String remoteMacAddress, int direction) {
        mContext = context;
        mRemoteMacAddress = remoteMacAddress;
        mDirection = direction;
    }

    /**
     * Reports the number of bytes successfully sent/received.
     */
    void reportProgress(int bytesWritten, long size) {
        Intent intent = new Intent(HandoverService.ACTION_TRANSFER_PROGRESS);
        intent.putExtra(HandoverService.EXTRA_HANDOVER_DEVICE_TYPE,
                HandoverTransfer.DEVICE_TYPE_WIFI);
        intent.putExtra(HandoverService.EXTRA_ADDRESS, mRemoteMacAddress);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_PROGRESS, ((float) bytesWritten) / size);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_DIRECTION, mDirection);
        mContext.sendBroadcast(intent, HandoverService.HANDOVER_STATUS_PERMISSION);
    }

    /**
     * Reports a completed transfer
     *
     * @param status One of:
     * {@link com.android.nfc.handover.HandoverService}.HANDOVER_TRANSFER_STATUS_SUCESS
     * {@link com.android.nfc.handover.HandoverService}.HANDOVER_TRANSFER_STATUS_FAILURE
     */
    void reportTransferDone(int status, Uri uri, String mimeType) {
        Intent intent = new Intent(HandoverService.ACTION_TRANSFER_DONE);
        intent.putExtra(HandoverService.EXTRA_HANDOVER_DEVICE_TYPE,
                HandoverTransfer.DEVICE_TYPE_WIFI);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_URI, uri != null ? uri.toString() : null);
        intent.putExtra(HandoverService.EXTRA_ADDRESS, mRemoteMacAddress);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_MIMETYPE, mimeType);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_STATUS, status);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_DIRECTION, mDirection);
        mContext.sendBroadcast(intent, HandoverService.HANDOVER_STATUS_PERMISSION);
    }

    /**
     * Reports the number of files to be received.
     */
    void reportIncomingObjectCount(int fileCount) {
        Intent intent = new Intent(HandoverService.ACTION_HANDOVER_STARTED);
        intent.putExtra(HandoverService.EXTRA_HANDOVER_DEVICE_TYPE,
                HandoverTransfer.DEVICE_TYPE_WIFI);
        intent.putExtra(HandoverService.EXTRA_ADDRESS, mRemoteMacAddress);
        intent.putExtra(HandoverService.EXTRA_OBJECT_COUNT, fileCount);
        intent.putExtra(HandoverService.EXTRA_TRANSFER_DIRECTION,
                HandoverService.DIRECTION_INCOMING);
        mContext.sendBroadcast(intent, HandoverService.HANDOVER_STATUS_PERMISSION);
    }

}
