package com.ashkanrafiee.libresmsbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.provider.Telephony;

/** Empty delivery endpoint required by Android before it can offer the SMS role to this app. */
public final class MmsDeliverReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, android.content.Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) return;
        // Archive/restore uses the provider directly; this endpoint only exists for temporary
        // SMS-role eligibility and must not consume incoming MMS broadcasts.
    }
}
