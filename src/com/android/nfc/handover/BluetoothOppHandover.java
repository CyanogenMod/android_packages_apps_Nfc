package com.android.nfc.handover;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.android.nfc.handover.HandoverManager.HandoverPowerManager;

import java.util.ArrayList;
import java.util.Arrays;

public class BluetoothOppHandover {
    static final String TAG = "BluetoothOppHandover";
    static final boolean D = true;

    static final int STATE_INIT = 0;
    static final int STATE_TURNING_ON = 1;
    static final int STATE_COMPLETE = 2;

    public static final String EXTRA_CONNECTION_HANDOVER =
            "com.android.intent.extra.CONNECTION_HANDOVER";

    final Context mContext;
    final BluetoothDevice mDevice;
    final Uri[] mUris;
    final HandoverPowerManager mHandoverPowerManager;

    int mState;

    public BluetoothOppHandover(Context context, BluetoothDevice device, Uri[] uris,
            HandoverPowerManager powerManager) {
        mContext = context;
        mDevice = device;
        mUris = uris;
        mHandoverPowerManager = powerManager;

        mState = STATE_INIT;
    }

    public static String getMimeTypeForUri(Context context, Uri uri) {
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            ContentResolver cr = context.getContentResolver();
            return cr.getType(uri);
        } else if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.getPath()).toLowerCase();
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
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        if (!mHandoverPowerManager.isBluetoothEnabled()) {
           if (mHandoverPowerManager.enableBluetooth()) {
               mState = STATE_TURNING_ON;
           } else {
               // TODO deal with this: toast or tie in to Beam failure?
           }
        } else {
            // BT already enabled
            sendIntent();
        }
    }

    void complete() {
        mState = STATE_COMPLETE;
        mContext.unregisterReceiver(mReceiver);
    }

    void sendIntent() {
        //TODO: either open up BluetoothOppLauncherActivity to all MIME types
        //      or gracefully handle mime types that can't be sent
        Log.d(TAG, "Sending handover intent for " + mDevice.getAddress());
        Intent intent = new Intent();
        intent.setPackage("com.android.bluetooth");
        String mimeType = getMimeTypeForUri(mContext, mUris[0]);
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
        intent.putExtra(EXTRA_CONNECTION_HANDOVER, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        complete();
    }

    void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) && mState == STATE_TURNING_ON) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                sendIntent();
            } else if (state == BluetoothAdapter.STATE_OFF) {
                complete();
            }
            return;
        }
    }

    final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleIntent(intent);
        }
    };

}
