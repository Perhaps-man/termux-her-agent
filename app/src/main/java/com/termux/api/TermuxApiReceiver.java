package com.termux.api;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Minimal stub used for in-process invocation of termux-api API classes.
 * The real TermuxApiReceiver lives in the termux-api app; this stub allows the API
 * static methods to compile and run without the broadcast-receiver lifecycle.
 */
public class TermuxApiReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // no-op — only used as a typed parameter for ResultReturner
    }
}
