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
import android.os.Environment;
import android.util.Log;
import com.android.nfc.handover.HandoverSendFileInfo;
import com.android.nfc.handover.HandoverService;
import com.android.nfc.handover.MimeTypeUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Implementation of a Wi-Fi Direct File transfer protocol
 * for sending files over Android Beam.
 */
public class GoogleRawFileTransferProtocol  {
    public static final short PROTOCOL_ID = 0x3001;

    // Standard sizes
    private static final int TLV_INT_FIELD_SIZE = 8;
    private static final int HEADER_SIZE = 4;
    private static final int JAVA_SIZE_OF_SHORT = 2;
    private static final int JAVA_SIZE_OF_INT = 4;
    private static final int JAVA_SIZE_OF_LONG = 8;

    // Session Metadata
    private static final short SESSION_METADATA_ID = 0x2003;
    private static final short SESSION_METADATA_FILE_COUNT_ID = 0x6001;

    // File Metadata
    private static final short FILE_METADATA_ID = 0x2003;
    private static final short FILE_NAME_ID = 0x1001;
    private static final short FILE_TYPE_ID = 0x1002;
    private static final short FILE_SIZE_ID = 0x1003;

    // Responses
    private static final short RESPONSE_OK = 0x2005;
    private static final short RESPONSE_FAIL_ID = 0x2006;
    private static final short ACTION_RETRY = 0x4001;
    private static final short ACTION_ABORT = 0x4002;

    public static class Sender {

        private static final String TAG = Sender.class.getName();
        private static final int SEND_CHUNK_SIZE = 64 * 1024;
        private final Uri[] mUris;

        private boolean mCancelled;

        private final ProgressReporter mProgressReporter;
        private final Context mContext;

        public Sender(Context context, Uri[] uris, ProgressReporter progressReporter) {
            mContext = context;
            mUris = uris;
            mProgressReporter = progressReporter;
        }

        public boolean send(InputStream inputStream, OutputStream outputStream)
                throws IOException {
            byte[] sessionMetadata= buildSessionMetadata();
            outputStream.write(sessionMetadata);
            int res = readResponse(inputStream);

            if (res == ACTION_ABORT) {
                return false;
            } else while (res == ACTION_RETRY) {
                // TODO: bound # of retries? its bounded by the globat xfer timeout anyway...
                outputStream.write(sessionMetadata);
                res = readResponse(inputStream);
            }

            for (int i = 0; i < mUris.length; i++) {
                String mimeType = MimeTypeUtil.getMimeTypeForUri(mContext, mUris[i]);
                HandoverSendFileInfo fileInfo =
                        HandoverSendFileInfo.generateFileInfo(mContext, mUris[i], mimeType);
                byte[] fileMetadata = buildFileMetadata(fileInfo);
                outputStream.write(fileMetadata);
                res = readResponse(inputStream);

                if (res == ACTION_ABORT) {
                    return false;
                } else while (res == ACTION_RETRY) {
                    // TODO: bound # of retries? its bounded by the globat xfer timeout anyway...
                    outputStream.write(fileMetadata);
                    res = readResponse(inputStream);
                }

                if (!writePayload(outputStream, fileInfo)) {
                    return false;
                }

                res = readResponse(inputStream);
                if (res == ACTION_ABORT) {
                    return false;
                } else while (res == ACTION_RETRY) {
                    if (!writePayload(outputStream, fileInfo)) {
                        return false;
                    }
                    res = readResponse(inputStream);
                }

                synchronized (this) {
                    if (mCancelled) {
                        mProgressReporter.reportTransferDone(
                                HandoverService.HANDOVER_TRANSFER_STATUS_FAILURE, mUris[i],
                                mimeType);
                        return true;
                    }
                }

                mProgressReporter.reportTransferDone(
                        HandoverService.HANDOVER_TRANSFER_STATUS_SUCCESS, mUris[i], mimeType);
            }


            return true;
        }

        private byte[] buildFileMetadata(HandoverSendFileInfo fileInfo) {
            // Block headers + filename + mime type + file size
            int payloadSize = (3 * HEADER_SIZE)
                    + fileInfo.mFileName.length() + fileInfo.mMimetype.length() + JAVA_SIZE_OF_LONG;
            ByteBuffer fileMetadata = ByteBuffer.allocate(HEADER_SIZE + payloadSize);
            fileMetadata.order(ByteOrder.BIG_ENDIAN);

            fileMetadata.putShort(FILE_METADATA_ID);
            fileMetadata.putShort((short) payloadSize);
            fileMetadata.putShort(FILE_NAME_ID);
            fileMetadata.putShort((short) fileInfo.mFileName.length());
            fileMetadata.put(fileInfo.mFileName.getBytes());
            fileMetadata.putShort(FILE_TYPE_ID);
            fileMetadata.putShort((short) fileInfo.mMimetype.length());
            fileMetadata.put(fileInfo.mMimetype.getBytes());
            fileMetadata.putShort(FILE_SIZE_ID);
            fileMetadata.putShort((short) JAVA_SIZE_OF_LONG);
            fileMetadata.putLong(fileInfo.mLength);

            byte[] bytes = new byte[HEADER_SIZE + payloadSize];
            fileMetadata.get(bytes);
            return bytes;
        }

        private short readResponse(InputStream inputStream) throws IOException {
            byte[] buffer = new byte[2];
            int read = inputStream.read(buffer);

            if (read < 2) {
                // unable to read response, aborting
                return ACTION_ABORT;
            }

            ByteBuffer converter = ByteBuffer.wrap(buffer);
            converter.order(ByteOrder.BIG_ENDIAN);
            short responseId = converter.getShort();

            if (responseId == RESPONSE_OK) {
              return responseId;
            }

            // Read fail response code
            read = inputStream.read(buffer);
            if (read < 2) {
                // unable to read response, aborting
                return ACTION_ABORT;
            }

            converter.flip();

            return converter.getShort();
        }

        private byte[] buildSessionMetadata() {
            ByteBuffer sessionMetadata = ByteBuffer.allocate(HEADER_SIZE + TLV_INT_FIELD_SIZE);
            sessionMetadata.order(ByteOrder.BIG_ENDIAN);
            sessionMetadata.putShort(SESSION_METADATA_ID);
            // only one field to send
            sessionMetadata.putShort((short) TLV_INT_FIELD_SIZE);
            sessionMetadata.putShort(SESSION_METADATA_FILE_COUNT_ID);
            sessionMetadata.putShort((short) JAVA_SIZE_OF_INT);
            sessionMetadata.putInt(mUris.length);
            byte[] sessionMetadataArray = new byte[HEADER_SIZE + TLV_INT_FIELD_SIZE];
            sessionMetadata.get(sessionMetadataArray);
            return sessionMetadataArray;
        }


        private boolean writePayload(OutputStream outputStream, HandoverSendFileInfo fileInfo)
                throws IOException {
            Log.i(TAG, "Preparing to send " + fileInfo.mLength + " bytes");

            byte[] outBuffer;
            if (fileInfo.mLength > SEND_CHUNK_SIZE) {
                outBuffer = new byte[SEND_CHUNK_SIZE];
            } else {
                outBuffer = new byte[(int) fileInfo.mLength];
            }

            int bytesSent = 0;
            while (bytesSent < fileInfo.mLength) {

                int ret = fileInfo.mInputStream.read(outBuffer);

                if (ret < 0) {
                    return false;
                }

                outputStream.write(outBuffer, 0, ret);
                bytesSent += ret;
                mProgressReporter.reportProgress(bytesSent, fileInfo.mLength);
            }

            fileInfo.mInputStream.close();
            return true;
        }

        public synchronized void cancel() {
            mCancelled = true;
        }


    }

    public static class Receiver {
        private static final String TAG = Receiver.class.getName();
        private static final int BUFFER_SIZE = 64 * 1024;
        private static final String WIFI_DIRECTORY_NAME = "wifi";

        private boolean mCancelled;

        private static final class FileMetadata {
            String fileName;
            String mimeType;
            long fileSize;
        }

        private final ProgressReporter mProgressReporter;

        public Receiver(ProgressReporter progressReporter) {
            mProgressReporter = progressReporter;
        }

        public boolean receive(InputStream inputStream, OutputStream outputStream)
                throws IOException {
            int fileCount = readSessionMetadata(inputStream);


            if (fileCount == 0) {
                sendFailResponse(outputStream, ACTION_RETRY);
                fileCount = readSessionMetadata(inputStream);

                if (fileCount == 0) {
                    sendFailResponse(outputStream, ACTION_ABORT);
                    return false;
                }
            }

            Log.i(TAG, "File Count: " + fileCount);

            sendOKResponse(outputStream);

            mProgressReporter.reportIncomingObjectCount(fileCount);

            for (int i = 0; i < fileCount; i++) {
                FileMetadata fileMetadata = readFileMetadata(inputStream);
                Log.i(TAG, "File: " + fileMetadata.fileName);
                Uri uri = readPayload(inputStream, fileMetadata);

                if (uri == null) {
                    mProgressReporter.reportTransferDone(
                            HandoverService.HANDOVER_TRANSFER_STATUS_FAILURE, uri,
                            fileMetadata.mimeType);
                    return false;
                }

                synchronized (this) {
                    if (mCancelled) {
                        maybeDeleteFile(uri);
                        mProgressReporter.reportTransferDone(
                                HandoverService.HANDOVER_TRANSFER_STATUS_FAILURE, uri,
                                fileMetadata.mimeType);
                        return true;
                    }
                }

                mProgressReporter.reportTransferDone(
                        HandoverService.HANDOVER_TRANSFER_STATUS_SUCCESS, uri,
                        fileMetadata.mimeType);
            }

            return true;
        }

        private void maybeDeleteFile(Uri uri) {
            File file = new File(uri.getPath());
            if (file.exists()) file.delete();
        }

        private FileMetadata readFileMetadata(InputStream inputStream) throws IOException {
            byte[] idAndPayloadSize = new byte[4];
            int read = inputStream.read(idAndPayloadSize);
            if (read < idAndPayloadSize.length) {
                return null;
            }
            ByteBuffer converter = ByteBuffer.wrap(idAndPayloadSize);
            short messageId = converter.getShort();
            short payloadSize = converter.getShort();

            if (messageId != FILE_METADATA_ID) {
                return null;
            }

            int bytesRead = 0;

            FileMetadata fileMetadata = new FileMetadata();
            while (bytesRead < payloadSize) {
                read = inputStream.read(idAndPayloadSize);
                if (read < idAndPayloadSize.length) {
                    return null;
                }
                bytesRead += read;
                converter.flip();
                messageId = converter.getShort();
                int messageSize = converter.getShort();

                switch (messageId) {
                    case FILE_NAME_ID:
                        byte[] filenameBytes = new byte[messageSize];
                        read = inputStream.read(filenameBytes);
                        if (read <= 0) {
                            return null;
                        }
                        bytesRead += read;
                        fileMetadata.fileName = new String(filenameBytes, "UTF-8");
                        break;
                    case FILE_TYPE_ID:
                        byte[] fileTypeBytes = new byte[messageSize];
                        read = inputStream.read(fileTypeBytes);
                        if (read <= 0) {
                            return null;
                        }
                        bytesRead += read;
                        fileMetadata.mimeType = new String(fileTypeBytes, "UTF-8");
                    case FILE_SIZE_ID:
                        if (messageSize != 8) {
                            return null;
                        }
                        byte[] fileSizeBytes = new byte[messageSize];
                        read = inputStream.read(fileSizeBytes);
                        if (read < messageSize) {
                            return null;
                        }
                        bytesRead += read;
                        ByteBuffer longConverter = ByteBuffer.wrap(fileSizeBytes);
                        fileMetadata.fileSize = longConverter.getLong();
                        break;
                }
            }

            if (fileMetadata.fileName != null && fileMetadata.mimeType != null
                    && fileMetadata.fileSize > 0) {
                return fileMetadata;
            }

            return null;
        }

        private void sendOKResponse(OutputStream outputStream) throws  IOException {
            outputStream.write(RESPONSE_OK);
        }

        private void sendFailResponse(OutputStream outputStream, short action) throws IOException {
            outputStream.write(RESPONSE_FAIL_ID);
            outputStream.write(action);
        }

        private int readSessionMetadata(InputStream inputStream) throws IOException {
            byte[] shortBytes = new byte[JAVA_SIZE_OF_SHORT];
            ByteBuffer converter = ByteBuffer.wrap(shortBytes);
            converter.order(ByteOrder.BIG_ENDIAN);
            int read = inputStream.read(shortBytes);
            if (read < 2) {
                return 0;
            }
            short messageId = converter.getShort();

            if (messageId == SESSION_METADATA_ID) {
                read = inputStream.read(shortBytes);
                if (read < 2) {
                    return 0;
                }
                converter.flip();
                short payloadSize = converter.getShort();
                short bytesRead = 0;

                while (bytesRead < payloadSize) {
                    read = inputStream.read(shortBytes);
                    if (read < 2) {
                        return 0;
                    }
                    bytesRead += read;
                    converter.flip();
                    short fieldId = converter.getShort();
                    read = inputStream.read(shortBytes);
                    if (read < 2) {
                        return 0;
                    }
                    bytesRead += read;
                    converter.flip();
                    short fieldSize = converter.getShort();

                    if (fieldId == SESSION_METADATA_FILE_COUNT_ID) {
                        byte[] intBytes = new byte[4];
                        converter.get(intBytes);
                        ByteBuffer intConverter = ByteBuffer.wrap(intBytes);
                        intConverter.order(ByteOrder.BIG_ENDIAN);
                        return intConverter.getInt();
                    } else {
                        long skipped = inputStream.skip((long) fieldSize);
                        if (skipped != fieldSize) {
                            // unable to skip to next tag, aborting
                            return 0;
                        }
                        bytesRead += skipped;
                    }
                }
            }

            return 0;
        }

        private Uri readPayload(InputStream inputStream, FileMetadata fileMetadata)
                throws IOException {
            int bytesRead = 0;
            String path = getFilePath(fileMetadata.fileName);

            if (path == null) {
                return null;
            }

            FileOutputStream outputStream = new FileOutputStream(path);

            Log.i(TAG, "Preparing to read " + fileMetadata.fileSize + " bytes");

            byte[] buf;
            if (fileMetadata.fileSize > BUFFER_SIZE) {
                buf = new byte[BUFFER_SIZE];
            } else {
                // OK to cast since we know its a small value
                buf = new byte[(int) fileMetadata.fileSize];
            }

            while(bytesRead < fileMetadata.fileSize) {
                int ret = inputStream.read(buf);
                if (ret < 0) {
                    outputStream.close();
                    maybeDeleteFile(Uri.parse(path));
                    return null;
                }
                bytesRead += ret;
                outputStream.write(buf, 0, ret);
                mProgressReporter.reportProgress(bytesRead, fileMetadata.fileSize);
            }

            outputStream.close();
            return Uri.parse(path);
        }

        private String getFilePath(String filename) {
            if (filename.charAt(0) == '.' || filename.contains("/")) {
                // invalid filename
                return null;
            }

            String extRoot = Environment.getExternalStorageDirectory().getPath();
            File base = new File(extRoot + "/" + WIFI_DIRECTORY_NAME);

            if (!base.isDirectory() && !base.mkdir()) {
                return null;
            }

            return base.getPath() + "/" + filename;
        }

        public synchronized void cancel() {
            mCancelled = true;
        }
    }


}
