package com.ashkanrafiee.libresmsbackup;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;

import java.util.ArrayList;

/** Endpoint required by Android's default SMS role contract. */
public final class RespondViaMessageService extends Service {
    private static final String ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            Uri target = intent.getData();
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (target != null && text != null && text.length() > 0) {
                String address = target.getSchemeSpecificPart();
                int query = address == null ? -1 : address.indexOf('?');
                if (query >= 0) address = address.substring(0, query);
                if (address != null && !address.isEmpty()) {
                    try {
                        SmsManager manager = SmsManager.getDefault();
                        String body = text.toString();
                        ArrayList<String> parts = manager.divideMessage(body);
                        if (parts.size() == 1) manager.sendTextMessage(address, null, body, null, null);
                        else manager.sendMultipartTextMessage(address, null, parts, null, null);
                    } catch (SecurityException ignored) { }
                }
            }
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
