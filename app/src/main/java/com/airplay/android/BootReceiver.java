package com.airplay.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Brings the receiver up when the TV boots (or the app is updated), so the device is on the
 * AirPlay list without anyone having to open the app first.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        Log.i("BootReceiver", "Starting AirPlay receiver after " + action);
        AirPlayService.start(context);
    }
}
