package com.android.nfc.handover;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

public class BluetoothOppHandover {
    static final String TAG = "BluetoothOppHandover";
    static final boolean D = true;
    
    final Context mContext;
    final BluetoothDevice mDevice;
    final Uri mUri;
    final String mMimeType;

    public interface Callback {
        public void onBluetoothOppHandoverComplete();
    }
    
    public BluetoothOppHandover(Context context, BluetoothDevice device, String mimeType,
            Uri uri) {
        mContext = context;
        mDevice = device;
        mMimeType = mimeType;
        mUri = uri;
    }
    
    /**
     * Main entry point. This method is usually called after construction,
     * to begin the BT sequence. Must be called on Main thread.
     */
    public void start() {
        //TODO: Should call setActivity to make sure it goes to Bluetooth
        //TODO: either open up BluetoothOppLauncherActivity to all MIME types
        //      or gracefully handle mime types that can't be sent
        Log.d(TAG, "Sending handover intent for " + mDevice.getAddress());
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mMimeType);
        intent.putExtra(Intent.EXTRA_STREAM, mUri);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }
}
