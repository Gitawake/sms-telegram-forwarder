package com.sxrong.smsforwarder;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Only reports verifiable system state; OEM background settings remain user-reviewed. */
final class PermissionGuide {
    private final AppCompatActivity activity;
    private final com.sxrong.smsforwarder.Settings prefs;
    private final Runnable completed, changed;
    private AlertDialog dialog;
    private int step=-1;
    PermissionGuide(AppCompatActivity activity, com.sxrong.smsforwarder.Settings prefs, Runnable completed, Runnable changed) {
        this.activity=activity; this.prefs=prefs; this.completed=completed; this.changed=changed;
    }
    boolean active() { return step>=0; }
    int step() { return step; }
    boolean smsGranted() { return activity.checkSelfPermission(Manifest.permission.RECEIVE_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED; }
    boolean batteryExempt() {
        PowerManager power=(PowerManager)activity.getSystemService(Context.POWER_SERVICE);
        return power!=null && power.isIgnoringBatteryOptimizations(activity.getPackageName());
    }
    boolean backgroundRestricted() {
        ActivityManager manager=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        return Build.VERSION.SDK_INT>=28 && manager!=null && manager.isBackgroundRestricted();
    }
    boolean recommendedComplete() { return smsGranted() && batteryExempt() && prefs.backgroundReviewed() && !backgroundRestricted(); }
    String summary() {
        return "接收短信："+(smsGranted()?"已允许":"未允许")+"\n电池优化："+(batteryExempt()?"已豁免":"尚未豁免")
                +"\n后台运行："+(backgroundRestricted()?"系统限制中":"未检测到系统限制")
                +"\n厂商后台策略："+(prefs.backgroundReviewed()?"已手动检查":"待手动检查");
    }
    void start() { restore(0); }
    void restore(int savedStep) { step=Math.max(0,Math.min(savedStep,2)); refresh(); }
    void refresh() {
        changed.run();
        if (!active() || activity.isFinishing() || activity.isDestroyed()) return;
        if(dialog!=null) dialog.dismiss();
        String title, message, action;
        if(step==0) {
            title="1 / 3  接收短信";
            message=(smsGranted()?"✓ 已允许接收新短信。":"需要允许接收短信，才能在短信到达时转发。")
                    +"\n\n仅使用接收新短信权限，不扫描历史收件箱。转发内容包含号码、时间与全文，请确认 Telegram 目标会话属于你。";
            action=smsGranted()?"下一步":prefs.smsRequested() && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)?"打开短信权限设置":"允许接收短信";
        } else if(step==1) {
            title="2 / 3  电池优化";
            message=(batteryExempt()?"✓ 已获得电池优化豁免。":"建议允许忽略电池优化，减少熄屏后的任务延迟。")
                    +"\n\n此项可跳过。允许后仍需遵守系统后台任务调度，不保证永久常驻或零延迟。";
            action=batteryExempt()?"下一步":"前往允许";
        } else {
            title="3 / 3  后台运行与自启动";
            message="当前状态："+(backgroundRestricted()?"系统正在限制后台运行":"未检测到系统后台限制")
                    +"\n\n打开应用设置，如有“后台运行 / 电池 / 自启动”选项，请允许后台活动并选择“不限制”。部分手机还可在最近任务中锁定应用。"
                    +"\n\n这些选项因系统而异；没有对应开关可以直接完成。应用只能记录你已检查，无法验证所有厂商开关。强行停止应用后仍需重新打开。";
            action="已检查，完成";
        }
        MaterialAlertDialogBuilder builder=new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
                .setNegativeButton("稍后",(d,w) -> stop()).setPositiveButton(action,null);
        if(step==1 && !batteryExempt()) builder.setNeutralButton("跳过此步",(d,w) -> { step=2; refresh(); });
        if(step==2) builder.setNeutralButton("打开应用设置",null);
        dialog=builder.create();
        dialog.setOnCancelListener(d -> stop());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> advance());
        if(step==2) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> appSettings());
    }
    private void advance() {
        if(step==0) {
            if(smsGranted()) { step=1; refresh(); }
            else if(prefs.smsRequested() && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECEIVE_SMS)) appSettings();
            else { prefs.smsRequested(true); activity.requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS},10); }
        } else if(step==1) {
            if(batteryExempt()) { step=2; refresh(); }
            else open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())));
        } else {
            prefs.backgroundReviewed(true); stop(); completed.run();
        }
    }
    private void appSettings() { open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+activity.getPackageName()))); }
    private void open(Intent intent) {
        try { activity.startActivity(intent); }
        catch(android.content.ActivityNotFoundException | SecurityException e) {
            try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch(android.content.ActivityNotFoundException ignored) { }
        }
    }
    private void stop() { step=-1; if(dialog!=null) dialog.dismiss(); changed.run(); }
    void destroy() { if(dialog!=null) dialog.dismiss(); }
}
