package com.android.nfc.handover;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import com.android.nfc.handover.HandoverManager.HandoverPowerManager;
import com.android.nfc.R;

import java.util.ArrayList;
import java.util.Arrays;

public class BluetoothOppHandover implements Handler.Callback {
    static final String TAG = "BluetoothOppHandover";
    static final boolean D = true;

    static final int STATE_INIT = 0;
    static final int STATE_TURNING_ON = 1;
    static final int STATE_WAITING = 2; // Need to wait for remote side turning on BT
    static final int STATE_COMPLETE = 3;

    static final int MSG_START_SEND = 0;

    static final int REMOTE_BT_ENABLE_DELAY_MS = 3000;

    static final String ACTION_HANDOVER_SEND =
            "android.btopp.intent.action.HANDOVER_SEND";

    static final String ACTION_HANDOVER_SEND_MULTIPLE =
            "android.btopp.intent.action.HANDOVER_SEND_MULTIPLE";

    final Context mContext;
    final BluetoothDevice mDevice;

    final Uri[] mUris;
    final HandoverPowerManager mHandoverPowerManager;
    final boolean mRemoteActivating;
    final Handler mHandler;

    int mState;
    Long mStartTime;

    public BluetoothOppHandover(Context context, BluetoothDevice device, Uri[] uris,
            HandoverPowerManager powerManager, boolean remoteActivating) {
        mContext = context;
        mDevice = device;
        mUris = uris;
        mHandoverPowerManager = powerManager;
        mRemoteActivating = remoteActivating;

        mHandler = new Handler(context.getMainLooper(),this);
        mState = STATE_INIT;
    }

    public static String getMimeTypeForUri(Context context, Uri uri)  {
        if (uri.getScheme() == null) return null;

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
        mStartTime = SystemClock.elapsedRealtime();

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        if (!mHandoverPowerManager.isBluetoothEnabled()) {
           if (mHandoverPowerManager.enableBluetooth()) {
               mState = STATE_TURNING_ON;
           } else {
               Toast.makeText(mContext, mContext.getString(R.string.beam_failed),
                       Toast.LENGTH_SHORT).show();
               complete();
           }
        } else {
            // BT already enabled
            if (mRemoteActivating) {
                mHandler.sendEmptyMessageDelayed(MSG_START_SEND, REMOTE_BT_ENABLE_DELAY_MS);
            } else {
                // Remote BT enabled too, start send immediately
                sendIntent();
            }
        }
    }

    void complete() {
        mState = STATE_COMPLETE;
        mContext.unregisterReceiver(mReceiver);
    }

    void sendIntent() {
        Intent intent = new Intent();
        intent.setPackage("com.android.bluetooth");
        String mimeType = getMimeTypeForUri(mContext, mUris[0]);
        intent.setType(mimeType);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
        if (mUris.length == 1) {
            intent.setAction(ACTION_HANDOVER_SEND);
            intent.putExtra(Intent.EXTRA_STREAM, mUris[0]);
        } else {
            ArrayList<Uri> uris = new ArrayList<Uri>(Arrays.asList(mUris));
            intent.setAction(ACTION_HANDOVER_SEND_MULTIPLE);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }

        mContext.sendBroadcast(intent);

        complete();
    }

    void handleIntent(Intent intent) {
        String action = intent.getAction();
        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action) && mState == STATE_TURNING_ON) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                // Add additional delay if needed
                Long timeElapsed = SystemClock.elapsedRealtime() - mStartTime;
                if (mRemoteActivating && timeElapsed < REMOTE_BT_ENABLE_DELAY_MS) {
                    mHandler.sendEmptyMessageDelayed(MSG_START_SEND,
                            REMOTE_BT_ENABLE_DELAY_MS - timeElapsed);
                } else {
                    sendIntent();
                }
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

    @Override
    public boolean handleMessage(Message msg) {
        if (msg.what == MSG_START_SEND) {
            sendIntent();
            return true;
        }
        return false;
    }
}
