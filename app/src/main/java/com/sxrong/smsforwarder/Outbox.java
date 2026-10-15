package com.sxrong.smsforwarder;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class Outbox extends SQLiteOpenHelper {
    private static final Object OPEN_LOCK=new Object();
    static final String QUEUED = "queued", SENDING = "sending", RETRY = "retry", FAILED = "failed", PAUSED = "paused", SENT = "sent";
    Outbox(Context context) {
        super(context,"outbox.db",null,2);
        // Serialize schema initialization only. Normal transaction reads must not hold this lock.
        synchronized(OPEN_LOCK) { getWritableDatabase(); }
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, test INTEGER NOT NULL, offset INTEGER NOT NULL DEFAULT 0, sender TEXT NOT NULL DEFAULT '', content TEXT NOT NULL DEFAULT '', received_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, sent_at INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL DEFAULT 'queued', error TEXT NOT NULL DEFAULT '', target TEXT NOT NULL DEFAULT '', owner TEXT NOT NULL DEFAULT '')");
        index(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN sender TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN content TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN received_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN sent_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE messages ADD COLUMN state TEXT NOT NULL DEFAULT 'queued'");
            db.execSQL("ALTER TABLE messages ADD COLUMN error TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN target TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE messages ADD COLUMN owner TEXT NOT NULL DEFAULT ''");
            // Retain the original payload and partial-send offset of v1 queue entries.
            db.execSQL("UPDATE messages SET content=body");
            index(db);
        }
    }
    private void index(SQLiteDatabase db) { db.execSQL("CREATE INDEX IF NOT EXISTS messages_state_id ON messages(state,id)"); }
    long add(String body, boolean test, String sender, String content, long receivedAt, String target) {
        ContentValues values = new ContentValues(); values.put("body", body); values.put("test", test ? 1 : 0);
        values.put("sender", sender); values.put("content", content); values.put("received_at", receivedAt);
        values.put("created_at", System.currentTimeMillis()); values.put("target", target);
        SQLiteDatabase db=getWritableDatabase();
        long id=db.insertOrThrow("messages",null,values);
        if(!db.inTransaction()) AppChanges.publish();
        return id;
    }
    long pendingTestId() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM messages WHERE test=1 AND state NOT IN ('sent','failed') ORDER BY id DESC LIMIT 1",null)) {
            return c.moveToFirst()?c.getLong(0):-1;
        }
    }
    long addTest(String body, String target) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction(); boolean inserted=false;
        try {
            long id=pendingTestId();
            if(id==-1) { id=add(body,true,"",body,System.currentTimeMillis(),target); inserted=true; }
            db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); if(inserted) AppChanges.publish(); }
    }
    Entry get(long id) {
        try (Cursor c = getReadableDatabase().query("messages", null, "id=?", new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? entry(c) : null;
        }
    }
    List<Entry> list(boolean sent, int limit) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("messages", null, sent ? "state='sent'" : "state!='sent'", null, null, null,
                sent ? "sent_at DESC,id DESC" : "id DESC", Integer.toString(limit))) {
            while (c.moveToNext()) rows.add(entry(c));
        }
        return rows;
    }
    int count(boolean sent) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM messages WHERE state" + (sent ? "='sent'" : "!='sent'"), null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    boolean claim(long id, String previousOwner, String workerId) {
        ContentValues values=new ContentValues(); values.put("owner",workerId);
        return getWritableDatabase().update("messages",values,"id=? AND owner=? AND state!='sent'",
                new String[]{Long.toString(id),previousOwner})==1;
    }
    void progress(long id, int offset) { ContentValues v = new ContentValues(); v.put("offset", offset); update(id, v); }
    void state(long id, String state, String error) {
        ContentValues v = new ContentValues(); v.put("state", state); v.put("error", error); update(id, v);
    }
    void sent(long id, String target) {
        ContentValues v = new ContentValues(); v.put("state", SENT); v.put("error", "");
        v.put("sent_at", System.currentTimeMillis()); v.put("target", target); update(id, v);
    }
    private void update(long id, ContentValues values) {
        // A cancelled/deleted record must never be resurrected by an in-flight request.
        if(getWritableDatabase().update("messages", values, "id=?", new String[]{Long.toString(id)})>0) AppChanges.publish();
    }
    void delete(long id) { if(getWritableDatabase().delete("messages", "id=?", new String[]{Long.toString(id)})>0) AppChanges.publish(); }
    List<Long> sentIds() {
        List<Long> ids=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM messages WHERE state='sent' ORDER BY sent_at DESC,id DESC",null)) {
            while(c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }
    int deleteSent(java.util.Collection<Long> ids) {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction(); int deleted=0;
        try(android.database.sqlite.SQLiteStatement statement=db.compileStatement("DELETE FROM messages WHERE id=? AND state='sent'")) {
            for(long id:ids) { statement.bindLong(1,id); deleted+=statement.executeUpdateDelete(); }
            db.setTransactionSuccessful(); return deleted;
        } finally { db.endTransaction(); if(deleted>0) AppChanges.publish(); }
    }
    void clearSent() { if(getWritableDatabase().delete("messages", "state='sent'", null)>0) AppChanges.publish(); }
    List<Long> resumable() { return resumable(true); }
    List<Long> resumable(boolean forwardingEnabled) {
        List<Long> ids = new ArrayList<>();
        String query="SELECT id FROM messages WHERE state IN ('queued','retry','sending','paused')";
        if(!forwardingEnabled) query+=" AND test=1";
        try (Cursor c = getReadableDatabase().rawQuery(query, null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }
    private Entry entry(Cursor c) {
        return new Entry(c.getLong(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("body")),
                c.getInt(c.getColumnIndexOrThrow("test")) != 0, c.getInt(c.getColumnIndexOrThrow("offset")),
                c.getString(c.getColumnIndexOrThrow("sender")), c.getString(c.getColumnIndexOrThrow("content")),
                c.getLong(c.getColumnIndexOrThrow("received_at")), c.getLong(c.getColumnIndexOrThrow("created_at")),
                c.getLong(c.getColumnIndexOrThrow("sent_at")), c.getString(c.getColumnIndexOrThrow("state")),
                c.getString(c.getColumnIndexOrThrow("error")), c.getString(c.getColumnIndexOrThrow("target")), c.getString(c.getColumnIndexOrThrow("owner")));
    }
    static final class Entry {
        final long id, receivedAt, createdAt, sentAt;
        final String body, sender, content, state, error, target, owner;
        final boolean test; final int offset;
        Entry(long id, String body, boolean test, int offset, String sender, String content, long receivedAt,
              long createdAt, long sentAt, String state, String error, String target, String owner) {
            this.id=id; this.body=body; this.test=test; this.offset=offset; this.sender=sender; this.content=content;
            this.receivedAt=receivedAt; this.createdAt=createdAt; this.sentAt=sentAt; this.state=state; this.error=error; this.target=target; this.owner=owner;
        }
        String title() { return test ? "连接测试" : sender.isEmpty() ? "短信" : sender; }
        String stateLabel() {
            switch (state) {
                case SENT: return "已转发";
                case SENDING: return "正在发送";
                case RETRY: return "等待重试";
                case FAILED: return "发送失败";
                case PAUSED: return "已暂停";
                default: return "等待发送";
            }
        }
    }
}
