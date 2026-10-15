package com.sxrong.smsforwarder;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class OutboxTest extends UiTaskTestBase {
    private Context context;
    @Before public void setup() { context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db"); }
    @After public void cleanup() { context.deleteDatabase("outbox.db"); }
    @Test public void upgradesV1WithoutLosingPayloadOrPartialProgress() {
        try (SQLiteDatabase old=context.openOrCreateDatabase("outbox.db",0,null)) {
            old.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL, test INTEGER NOT NULL, offset INTEGER NOT NULL DEFAULT 0)");
            old.execSQL("INSERT INTO messages(body,test,offset) VALUES ('旧短信全文',0,2)"); old.setVersion(1);
        }
        try (Outbox db=new Outbox(context)) {
            Outbox.Entry entry=db.get(1);
            assertEquals("旧短信全文",entry.body); assertEquals("旧短信全文",entry.content);
            assertEquals(2,entry.offset); assertEquals(Outbox.QUEUED,entry.state);
            assertEquals(1,db.count(false)); assertEquals(0,db.count(true));
        }
    }
    @Test public void successfulMessageMovesToHistoryAndSurvivesReopen() {
        long id;
        try (Outbox db=new Outbox(context)) {
            id=db.add("payload",false,"10086","你好",1000,"123");
            db.state(id,Outbox.RETRY,"离线"); db.progress(id,3); db.sent(id,"123");
        }
        try (Outbox db=new Outbox(context)) {
            assertEquals(0,db.count(false)); assertEquals(1,db.count(true));
            Outbox.Entry sent=db.list(true,50).get(0);
            assertEquals("你好",sent.content); assertEquals("10086",sent.sender);
            assertEquals("123",sent.target); assertTrue(sent.sentAt>0); assertEquals("",sent.error);
            assertFalse(db.resumable().contains(id));
        }
    }
    @Test public void clearHistoryPreservesQueueAndLateCompletionCannotRestoreDeletedMessage() {
        try (Outbox db=new Outbox(context)) {
            long sent=db.add("a",false,"a","a",1,"123"); db.sent(sent,"123");
            long queued=db.add("b",false,"b","b",2,"123");
            db.clearSent(); assertNull(db.get(sent)); assertNotNull(db.get(queued));
            db.delete(queued); db.progress(queued,1); db.sent(queued,"123");
            assertNull(db.get(queued)); assertEquals(0,db.count(true));
        }
    }
    @Test public void resumableExcludesPermanentFailuresAndSentMessages() {
        try (Outbox db=new Outbox(context)) {
            long queued=db.add("q",false,"","q",1,"123");
            long paused=db.add("p",false,"","p",1,"123"); db.state(paused,Outbox.PAUSED,"");
            long failed=db.add("f",false,"","f",1,"123"); db.state(failed,Outbox.FAILED,"401");
            assertTrue(db.resumable().contains(queued)); assertTrue(db.resumable().contains(paused));
            assertFalse(db.resumable().contains(failed)); assertEquals(3,db.count(false));
        }
    }
    @Test public void concurrentWorkersCannotBothClaimTheSameMessage() {
        try(Outbox db=new Outbox(context)) {
            long id=db.add("payload",false,"sender","text",1,"123");
            assertTrue(db.claim(id,"","worker-a"));
            assertFalse(db.claim(id,"","worker-b"));
            assertEquals("worker-a",db.get(id).owner);
            db.sent(id,"123"); assertFalse(db.claim(id,"worker-a","worker-b"));
        }
    }

    @Test public void repeatedTestRequestsReusePendingMessageButAllowNewTestAfterSuccess() {
        try(Outbox db=new Outbox(context)) {
            long first=db.addTest("test","123");
            assertEquals(first,db.addTest("test","123")); assertEquals(1,db.count(false));
            db.state(first,Outbox.RETRY,"offline"); assertEquals(first,db.addTest("test","123"));
            db.sent(first,"123"); assertEquals(-1,db.pendingTestId());
            assertNotEquals(first,db.addTest("test","123"));
        }
    }

    @Test public void bulkDeleteUsesHistorySnapshotAcrossPagesAndPreservesQueueAndNewRecords() {
        try(Outbox db=new Outbox(context)) {
            for(int i=0;i<75;i++) { long id=db.add("p",false,"sender","history",1,"123"); db.sent(id,"123"); }
            long queued=db.add("p",false,"sender","pending",1,"123");
            java.util.List<Long> selected=db.sentIds(); assertEquals(75,selected.size());
            long arrivedLater=db.add("p",false,"sender","new",1,"123"); db.sent(arrivedLater,"123");
            selected.add(queued); // Deleting selected history must still reject pending rows.
            assertEquals(75,db.deleteSent(selected)); assertNotNull(db.get(queued)); assertNotNull(db.get(arrivedLater));
            assertEquals(1,db.count(true)); assertEquals(1,db.count(false));
            assertEquals(1,db.deleteSent(java.util.Collections.singleton(arrivedLater))); assertNull(db.get(arrivedLater));
        }
    }

    @Test public void recoveryWhileDisabledOnlySchedulesTestMessages() {
        try(Outbox db=new Outbox(context)) {
            long regular=db.add("sms",false,"sender","sms",1,"123");
            long test=db.addTest("test","123");
            assertFalse(db.resumable(false).contains(regular)); assertTrue(db.resumable(false).contains(test));
            assertTrue(db.resumable(true).contains(regular));
        }
    }

    @Test public void simultaneousFirstOpenCreatesSchemaOnce() throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Long>> tasks=new java.util.ArrayList<>();
        try {
            for(int i=0;i<4;i++) tasks.add(pool.submit(() -> {
                start.await();
                try(Outbox db=new Outbox(context)) { return db.add("payload",false,"sender","text",1,"123"); }
            }));
            start.countDown(); java.util.Set<Long> ids=new java.util.HashSet<>();
            for(java.util.concurrent.Future<Long> task:tasks) ids.add(task.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(4,ids.size());
            try(Outbox db=new Outbox(context)) { assertEquals(4,db.count(false)); }
        } finally { pool.shutdownNow(); }
    }

}
