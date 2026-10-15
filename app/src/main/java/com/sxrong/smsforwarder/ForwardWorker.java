package com.sxrong.smsforwarder;

import android.content.Context;
import androidx.work.*;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

public class ForwardWorker extends Worker {
    static String workName(long id) { return "forward-" + id; }
    static androidx.work.Operation enqueue(Context context, String text, boolean test) {
        return enqueue(context, text, test, "", text, System.currentTimeMillis());
    }
    static androidx.work.Operation enqueue(Context context, String text, boolean test, String sender, String content, long receivedAt) {
        long id;
        try (Outbox db = new Outbox(context)) {
            id = test ? db.addTest(text,new Settings(context).chat())
                    : db.add(text, false, sender, content, receivedAt, new Settings(context).chat());
        }
        return schedule(context, id);
    }
    static androidx.work.Operation schedule(Context context, long id) { return schedule(context,id,ExistingWorkPolicy.KEEP); }
    static androidx.work.Operation schedule(Context context, long id, ExistingWorkPolicy policy) {
        return WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy,
                new OneTimeWorkRequest.Builder(ForwardWorker.class)
                .setInputData(new Data.Builder().putLong("id", id).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag("forward").build());
    }
    static void resume(Context context) {
        try (Outbox db = new Outbox(context)) {
            for (long id : db.resumable(new Settings(context).enabled())) schedule(context,id);
        }
    }
    public ForwardWorker(Context context, WorkerParameters params) { super(context, params); }
    @Override public Result doWork() {
        Settings settings = new Settings(getApplicationContext());
        long id = getInputData().getLong("id", -1);
        try (Outbox db = new Outbox(getApplicationContext())) {
            Outbox.Entry entry = db.get(id);
            if (entry == null || Outbox.SENT.equals(entry.state)) return Result.success();
            if (!entry.test && !settings.enabled()) {
                db.state(id, Outbox.PAUSED, "转发已关闭，重新开启后继续"); return Result.success();
            }
            String token = settings.token(), chat = settings.chat();
            // Bind a queued message to its original destination, including retries.
            if (!entry.target.isEmpty()) chat = entry.target;
            if (token.isEmpty() || chat.isEmpty()) return failed(db, id, settings, "配置缺失，请重新保存后重试");
            // v1 used unnamed work: its task can coexist with a newly named task after upgrade.
            // Only one live worker may own a message, even when both are scheduled.
            String workerId=getId().toString();
            if (!entry.owner.isEmpty() && !entry.owner.equals(workerId)) {
                WorkInfo owner=WorkManager.getInstance(getApplicationContext())
                        .getWorkInfoById(java.util.UUID.fromString(entry.owner)).get();
                if (owner!=null && owner.getState()==WorkInfo.State.RUNNING) return Result.retry();
            }
            if (!db.claim(id,entry.owner,workerId)) return Result.retry();
            db.state(id, Outbox.SENDING, "");
            int offset = entry.offset;
            while (offset < entry.body.length()) {
                if (isStopped()) { db.state(id, Outbox.RETRY, "等待后台任务恢复"); return Result.retry(); }
                if (db.get(id) == null) return Result.success();
                if (!entry.test && !settings.enabled()) {
                    db.state(id, Outbox.PAUSED, "转发已关闭，重新开启后继续"); return Result.success();
                }
                int end = Math.min(offset + 3500, entry.body.length());
                if (end < entry.body.length() && Character.isHighSurrogate(entry.body.charAt(end - 1))) end--;
                int code = send(token, chat, entry.body.substring(offset, end));
                if (code == 429 || code >= 500) {
                    String reason = code == 429 ? "Telegram 限流，稍后自动重试" : "Telegram 暂时不可用，稍后自动重试";
                    db.state(id, Outbox.RETRY, reason); settings.status(reason); return Result.retry();
                }
                if (code != 200) return failed(db, id, settings, "HTTP " + code + "，请检查 Token、Chat ID 和机器人会话");
                offset = end; db.progress(id, offset);
            }
            db.sent(id, chat);
            settings.status("发送成功 · " + DateFormat.getDateTimeInstance().format(new Date()));
            return Result.success();
        } catch (IOException e) {
            mark(id, Outbox.RETRY, "网络连接失败，等待自动重试");
            settings.status("网络连接失败，等待自动重试"); return Result.retry();
        } catch (Exception e) {
            mark(id, Outbox.FAILED, "请重新保存配置后重试");
            settings.status("发送失败，请重新保存配置后重试"); return Result.failure();
        }
    }
    private Result failed(Outbox db, long id, Settings settings, String reason) {
        db.state(id, Outbox.FAILED, reason); settings.status(reason); return Result.failure();
    }
    private void mark(long id, String state, String reason) {
        try (Outbox db = new Outbox(getApplicationContext())) { db.state(id, state, reason); }
    }
    private int send(String token, String chat, String text) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://api.telegram.org/bot" + token + "/sendMessage").openConnection();
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = new JSONObject().put("chat_id", chat).put("text", text).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(body); }
            int code = connection.getResponseCode();
            if (code == 200) {
                try (InputStream stream = connection.getInputStream()) {
                    java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int n;
                    while ((n = stream.read(buffer)) != -1) result.write(buffer, 0, n);
                    if (!new JSONObject(result.toString("UTF-8")).optBoolean("ok")) return 400;
                }
            }
            return code;
        } finally { connection.disconnect(); }
    }
}
