package com.ashkanrafiee.libresmsbackup;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.provider.Telephony;

/**
 * Minimal SMS-delivery endpoint required while the app is temporarily the SMS role holder.
 * It keeps an incoming SMS in the system inbox instead of silently dropping it.
 */
public final class SmsDeliverReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, android.content.Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        android.telephony.SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages == null || messages.length == 0) return;

        StringBuilder body = new StringBuilder();
        String address = messages[0].getDisplayOriginatingAddress();
        long date = messages[0].getTimestampMillis();
        for (android.telephony.SmsMessage message : messages) {
            if (message != null && message.getMessageBody() != null) body.append(message.getMessageBody());
        }
        ContentValues values = new ContentValues();
        if (address != null) values.put("address", address);
        values.put("date", date);
        values.put("body", body.toString());
        values.put("read", 0);
        values.put("seen", 0);
        values.put("type", Telephony.Sms.MESSAGE_TYPE_INBOX);
        try { context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values); }
        catch (SecurityException ignored) { }
    }
}
