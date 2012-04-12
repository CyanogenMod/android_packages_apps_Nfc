package com.android.nfc.handover;

import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.util.ArrayList;
import java.util.Arrays;

public class BluetoothOppHandover {
    static final String TAG = "BluetoothOppHandover";
    static final boolean D = true;

    final Context mContext;
    final BluetoothDevice mDevice;
    final Uri[] mUris;

    public interface Callback {
        public void onBluetoothOppHandoverComplete();
    }

    public BluetoothOppHandover(Context context, BluetoothDevice device,
            Uri[] uris) {
        mContext = context;
        mDevice = device;
        mUris = uris;
    }

    public String getMimeTypeForUri(Uri uri) {
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            ContentResolver cr = mContext.getContentResolver();
            return cr.getType(uri);
        } else if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.getPath());
            if (extension != null) {
                return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            } else {
                return null;
            }
        } else {
            Log.d(TAG, "Could not determine mime type for Uri " + uri);
            return null;
        }
    }

    /**
     * Main entry point. This method is usually called after construction,
     * to begin the BT sequence. Must be called on Main thread.
     */
    public void start() {
        //TODO: either open up BluetoothOppLauncherActivity to all MIME types
        //      or gracefully handle mime types that can't be sent
        Log.d(TAG, "Sending handover intent for " + mDevice.getAddress());
        Intent intent = new Intent();
        intent.setPackage("com.android.bluetooth");
        String mimeType = getMimeTypeForUri(mUris[0]);
        Log.d(TAG, "Determined mime type as " + mimeType);
        intent.setType(mimeType);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        if (mUris.length == 1) {
            intent.setAction(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, mUris[0]);
        } else {
            ArrayList<Uri> uris = (ArrayList<Uri>)Arrays.asList(mUris);
            intent.setAction(Intent.ACTION_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
}
