package com.android.nfc.handover;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;

import com.android.nfc.R;

public class ConfirmConnectActivity extends Activity {
    BluetoothDevice mDevice;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK);
        Intent launchIntent = getIntent();
        mDevice = launchIntent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (mDevice == null) finish();
        Resources res = getResources();
        String deviceName = mDevice.getName() != null ? mDevice.getName() : "";
        String confirmString = String.format(res.getString(R.string.confirm_pairing), deviceName);
        builder.setMessage(confirmString)
               .setCancelable(false)
               .setPositiveButton(res.getString(R.string.pair_yes),
                       new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                        Intent allowIntent = new Intent(BluetoothHeadsetHandover.ACTION_ALLOW_CONNECT);
                        allowIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                        sendBroadcast(allowIntent);
                        ConfirmConnectActivity.this.finish();
                   }
               })
               .setNegativeButton(res.getString(R.string.pair_no),
                       new DialogInterface.OnClickListener() {
                   public void onClick(DialogInterface dialog, int id) {
                       Intent denyIntent = new Intent(BluetoothHeadsetHandover.ACTION_DENY_CONNECT);
                       denyIntent.putExtra(BluetoothDevice.EXTRA_DEVICE, mDevice);
                       sendBroadcast(denyIntent);
                       ConfirmConnectActivity.this.finish();
                   }
               });
        AlertDialog alert = builder.create();
        alert.show();
    }
}
