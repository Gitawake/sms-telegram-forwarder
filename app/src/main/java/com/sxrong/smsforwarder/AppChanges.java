package com.sxrong.smsforwarder;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.concurrent.atomic.AtomicLong;

/** Process-local invalidation only: no timers, broadcasts, services or wake locks. */
final class AppChanges {
    private static final AtomicLong revision=new AtomicLong();
    private static MutableLiveData<Long> changes=new MutableLiveData<>(0L);
    static LiveData<Long> observe() { return changes; }
    static long revision() { return revision.get(); }
    static void publish() { changes.postValue(revision.incrementAndGet()); }
    @androidx.annotation.VisibleForTesting
    static void resetForTests() { revision.set(0); changes=new MutableLiveData<>(0L); }
    private AppChanges() { }
}
