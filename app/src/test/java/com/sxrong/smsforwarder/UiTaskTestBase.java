package com.sxrong.smsforwarder;

import android.os.Handler;
import android.os.Looper;
import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import org.junit.Before;
import org.junit.After;

/** Bind Architecture Components to each Robolectric test's new main looper. */
abstract class UiTaskTestBase {
    @Before public void bindArchitectureExecutor() {
        AppChanges.resetForTests();
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override public void executeOnDiskIO(Runnable task) { new Thread(task,"test-arch-io").start(); }
            @Override public void postToMainThread(Runnable task) { new Handler(Looper.getMainLooper()).post(task); }
            @Override public boolean isMainThread() { return Looper.myLooper()==Looper.getMainLooper(); }
        });
    }
    @After public void resetArchitectureExecutor() { ArchTaskExecutor.getInstance().setDelegate(null); }
}
