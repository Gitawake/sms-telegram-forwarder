package com.sxrong.smsforwarder;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import org.robolectric.RuntimeEnvironment;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w411dp-h891dp")
public class MainActivityTest extends UiTaskTestBase {
    @Test public void launchesAndRoutesMissingConfigurationToSettings() {
        Context context=RuntimeEnvironment.getApplication();
        context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().commit();
        try (ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            View root=activity.findViewById(android.R.id.content);
            BottomNavigationView navigation=find(root,BottomNavigationView.class);
            assertNotNull(navigation); assertEquals(3,navigation.getMenu().size());
            navigation.setSelectedItemId(2); assertEquals(2,navigation.getSelectedItemId());
            navigation.setSelectedItemId(1);
            MaterialSwitch toggle=find(root,MaterialSwitch.class); toggle.setChecked(true);
            assertFalse(toggle.isChecked()); assertEquals(3,navigation.getSelectedItemId());
            assertNotNull(activity.findViewById(com.sxrong.smsforwarder.R.id.token_input));
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void renderRepresentativeScreens() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().putString("chat","123456789").commit();
        long now=System.currentTimeMillis();
        try(Outbox db=new Outbox(context)) {
            db.add("示例短信",false,"10086","【中国移动】您本月套餐剩余流量 12.8GB，请及时关注使用情况。",now,"123456789");
            long failed=db.add("示例短信",false,"95588","【示例通知】账户服务提醒，请查看您的最新消息。",now-180000,"123456789");
            db.state(failed,Outbox.FAILED,"HTTP 401，请检查机器人配置");
            long sent=db.add("示例短信",false,"快递服务","您的包裹已到达快递站，请凭取件码 8-1206 领取。",now-360000,"123456789"); db.sent(sent,"123456789");
        }
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); View root=activity.findViewById(android.R.id.content);
            BottomNavigationView navigation=find(root,BottomNavigationView.class);
            for(int page=1;page<=3;page++) {
                navigation.setSelectedItemId(page);
                // Allow the single database executor to deliver its next list snapshot.
                for(int i=0;i<15;i++) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idle(); }
                root.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));
                root.layout(0,0,411,891);
                android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(411,891,android.graphics.Bitmap.Config.ARGB_8888);
                root.draw(new android.graphics.Canvas(bitmap));
                java.io.File output=new java.io.File("build/ui-previews"); output.mkdirs();
                try(java.io.FileOutputStream stream=new java.io.FileOutputStream(new java.io.File(output,"page-"+page+((activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES?"-dark":"")+".png"))) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);
                }
            }
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(sdk=28,qualifiers="w411dp-h891dp-night")
    public void renderDarkScreens() throws Exception { renderRepresentativeScreens(); }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void systemBottomInsetIsConsumedOnceAndNavigationLabelsDoNotOverlapIcons() {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().commit();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get();
            android.view.ViewGroup content=activity.findViewById(android.R.id.content);
            View appRoot=content.getChildAt(0);
            BottomNavigationView navigation=find(appRoot,BottomNavigationView.class);
            androidx.core.view.WindowInsetsCompat insets=new androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),androidx.core.graphics.Insets.of(0,24,0,24)).build();
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(appRoot,insets);
            appRoot.measure(View.MeasureSpec.makeMeasureSpec(411,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(891,View.MeasureSpec.EXACTLY));
            appRoot.layout(0,0,411,891);
            assertEquals(24,appRoot.getPaddingBottom()); assertEquals(0,navigation.getPaddingBottom());
            assertTrue(navigation.getHeight()>=80);
            View icon=navigation.findViewById(com.google.android.material.R.id.navigation_bar_item_icon_view);
            View label=navigation.findViewById(com.google.android.material.R.id.navigation_bar_item_large_label_view);
            android.graphics.Rect iconRect=new android.graphics.Rect(), labelRect=new android.graphics.Rect();
            assertTrue(icon.getGlobalVisibleRect(iconRect)); assertTrue(label.getGlobalVisibleRect(labelRect));
            assertTrue("Navigation label must remain below the icon: label="+labelRect+", icon="+iconRect+", nav="+navigation.getHeight()+", labelScale="+label.getScaleY(),labelRect.top>=iconRect.bottom);
        }
    }
    @Test public void apiFieldsAreLockedUntilExplicitEditAndCancelRestoresSavedValues() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().putString("chat","123456789").commit();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); View root=activity.findViewById(android.R.id.content);
            find(root,BottomNavigationView.class).setSelectedItemId(3); settle();
            android.widget.EditText token=activity.findViewById(R.id.token_input), chat=activity.findViewById(R.id.chat_input);
            assertFalse(token.isEnabled()); assertFalse(chat.isEnabled());
            assertEquals(View.GONE,findButton(root,"自动获取 Chat ID").getVisibility());
            findButton(root,"编辑配置").performClick(); assertTrue(token.isEnabled()); assertTrue(chat.isEnabled());
            assertEquals(View.VISIBLE,findButton(root,"自动获取 Chat ID").getVisibility());
            token.setText("123:unsaved"); chat.setText("999");
            findButton(root,"取消编辑").performClick(); assertFalse(token.isEnabled());
            assertEquals("123456789",chat.getText().toString()); assertEquals("",token.getText().toString());
            assertEquals("123456789",new Settings(context).chat());
        }
    }
    @Test public void selectAllHistoryIncludesRecordsBeyondFirstPageAndDeleteNeedsConfirmation() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().commit();
        try(Outbox db=new Outbox(context)) {
            for(int i=0;i<55;i++) { long id=db.add("p",false,"sender","text",1,"123"); db.sent(id,"123"); }
        }
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); View root=activity.findViewById(android.R.id.content);
            find(root,BottomNavigationView.class).setSelectedItemId(2); settle();
            com.google.android.material.appbar.MaterialToolbar toolbar=find(root,com.google.android.material.appbar.MaterialToolbar.class);
            toolbar.getMenu().performIdentifierAction(11,0); findButton(root,"全选").performClick(); settle();
            assertEquals("已选择 55 条",toolbar.getTitle().toString());
            findButton(root,"删除").performClick();
            android.app.Dialog dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog(); assertTrue(dialog.isShowing());
            try(Outbox db=new Outbox(context)) { assertEquals(55,db.count(true)); }
            ((androidx.appcompat.app.AlertDialog)dialog).getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            try(Outbox db=new Outbox(context)) { assertEquals(55,db.count(true)); }
        }
    }
    @Test public void permissionGuideExplainsAndAdvancesThroughOptionalBackgroundSteps() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().commit();
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECEIVE_SMS);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); View root=activity.findViewById(android.R.id.content);
            find(root,BottomNavigationView.class).setSelectedItemId(3); findButton(root,"开始权限引导").performClick();
            androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals("下一步",dialog.getButton(-1).getText().toString()); dialog.getButton(-1).performClick();
            dialog=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals("跳过此步",dialog.getButton(-3).getText().toString()); dialog.getButton(-3).performClick(); shadowOf(Looper.getMainLooper()).idle();
            dialog=(androidx.appcompat.app.AlertDialog)org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertEquals("已检查，完成",dialog.getButton(-1).getText().toString()); dialog.getButton(-1).performClick();
            assertTrue(new Settings(context).backgroundReviewed()); assertFalse(new Settings(context).enabled());
        }
    }
    @Test public void databaseChangesRefreshVisibleListAndStoppedActivityCatchesUpOnReturn() throws Exception {
        Context context=RuntimeEnvironment.getApplication(); context.deleteDatabase("outbox.db");
        context.getSharedPreferences("settings",0).edit().clear().commit();
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity=controller.get(); View root=activity.findViewById(android.R.id.content);
            androidx.recyclerview.widget.RecyclerView list=find(root,androidx.recyclerview.widget.RecyclerView.class);
            settle(); assertEquals(0,list.getAdapter().getItemCount());
            assertTrue("Lifecycle="+activity.getLifecycle().getCurrentState(),AppChanges.observe().hasActiveObservers());
            long id, before=AppChanges.revision();
            try(Outbox db=new Outbox(context)) { id=db.add("payload",false,"sender","new SMS",1,"123"); }
            assertTrue("Missing database invalidation",AppChanges.revision()>before);
            settle();
            assertEquals(1,list.getAdapter().getItemCount());
            controller.pause().stop(); assertFalse(AppChanges.observe().hasActiveObservers());
            try(Outbox db=new Outbox(context)) { db.sent(id,"123"); }
            settle(); assertEquals(1,list.getAdapter().getItemCount());
            controller.restart().start().resume().visible(); settle(); assertEquals(0,list.getAdapter().getItemCount());
            find(root,BottomNavigationView.class).setSelectedItemId(2); settle(); assertEquals(1,list.getAdapter().getItemCount());
        }
    }
    private void settle() throws Exception { for(int i=0;i<15;i++) { Thread.sleep(30); shadowOf(Looper.getMainLooper()).idle(); } }
    private com.google.android.material.button.MaterialButton findButton(View view,String text) {
        if(view instanceof com.google.android.material.button.MaterialButton && ((android.widget.TextView)view).getText().toString().equals(text)) return (com.google.android.material.button.MaterialButton)view;
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) { com.google.android.material.button.MaterialButton found=findButton(group.getChildAt(i),text); if(found!=null)return found; }
        }
        return null;
    }
    private <T extends View> T find(View view,Class<T> type) {
        if(type.isInstance(view)) return type.cast(view);
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) { T found=find(group.getChildAt(i),type); if(found!=null)return found; }
        }
        return null;
    }
}
