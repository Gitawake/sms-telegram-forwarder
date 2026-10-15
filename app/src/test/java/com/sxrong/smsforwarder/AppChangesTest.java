package com.sxrong.smsforwarder;

import android.os.Looper;
import androidx.lifecycle.Observer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class AppChangesTest extends UiTaskTestBase {
    @Test public void burstChangesCoalesceAndUnchangedSettingsDoNotEmit() {
        shadowOf(Looper.getMainLooper()).idle();
        AtomicInteger notifications=new AtomicInteger(); Observer<Long> listener=value -> notifications.incrementAndGet();
        AppChanges.observe().observeForever(listener);
        try {
            int initial=notifications.get();
            for(int i=0;i<100;i++) AppChanges.publish();
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(initial+1,notifications.get());
            Settings settings=new Settings(RuntimeEnvironment.getApplication());
            long revision=AppChanges.revision(); settings.enabled(settings.enabled()); settings.status(settings.status());
            assertEquals(revision,AppChanges.revision());
        } finally { AppChanges.observe().removeObserver(listener); }
    }
}
