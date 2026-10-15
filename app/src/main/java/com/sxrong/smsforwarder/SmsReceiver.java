package com.sxrong.smsforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.text.DateFormat;
import java.util.Date;

public class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction()) || !new Settings(context).enabled()) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) body.append(part.getMessageBody());
        String text = "📩 收到短信\n发件人：" + parts[0].getDisplayOriginatingAddress()
                + "\n时间：" + DateFormat.getDateTimeInstance().format(new Date(parts[0].getTimestampMillis()))
                + "\n\n" + body;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { ForwardWorker.enqueue(app, text, false, parts[0].getDisplayOriginatingAddress() == null ? "未知号码" : parts[0].getDisplayOriginatingAddress(), body.toString(), parts[0].getTimestampMillis()).getResult().get(8, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception e) { new Settings(app).status("短信入队失败，请打开应用检查后台限制"); }
            finally { pending.finish(); }
        }, "sms-enqueue").start();
    }
}
