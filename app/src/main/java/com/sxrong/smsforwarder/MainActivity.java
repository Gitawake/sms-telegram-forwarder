package com.sxrong.smsforwarder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.DiffUtil;
import androidx.work.WorkManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int QUEUE=1, HISTORY=2, SETTINGS=3;
    private Settings settings;
    private LinearLayout root, messagePage;
    private ScrollView settingsPage;
    private MaterialToolbar toolbar;
    private BottomNavigationView navigation;
    private MaterialSwitch enabled;
    private TextView serviceTitle, serviceDescription, heading, countLabel, emptyTitle, emptyDescription, lastStatus;
    private LinearLayout empty;
    private TextInputEditText token, chat;
    private TextInputLayout tokenLayout, chatLayout;
    private MaterialButton more, saveButton, testButton, editButton, cancelEditButton, selectAllButton, deleteSelectedButton, fetchChatsButton;
    private TextView permissionSummary, selectedLabel;
    private LinearLayout selectionBar;
    private PermissionGuide permissionGuide;
    private boolean editingConfig, selectionMode, enableAfterGuide, fetchingChats;
    private String savedToken="";
    private String selectedChatName="", selectedChatId="", selectedChatToken="";
    private final Set<Long> selectedIds=new HashSet<>();
    private RecyclerView list;
    private final MessagesAdapter adapter = new MessagesAdapter();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int tab=QUEUE, limit=50, historyCount;
    private boolean loading, syncingSwitch, saving;
    private String rendered="";
    @Override public void onCreate(Bundle state) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(state);
        settings = new Settings(this);
        if (state != null) { tab=state.getInt("tab", QUEUE); limit=state.getInt("limit",50); }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        root = column(); root.setBackgroundColor(color(com.google.android.material.R.attr.colorSurface));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int bottom=Math.max(bars.bottom, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
            view.setPadding(bars.left,bars.top,bars.right,bottom);
            // This root owns system/IME insets. Material navigation must not apply them a second time.
            return WindowInsetsCompat.CONSUMED;
        });
        toolbar = new MaterialToolbar(this); toolbar.setTitle("短信转发"); root.addView(toolbar, lp(-1,56));
        toolbar.getMenu().add(0,10,0,"清空当前列表");
        toolbar.getMenu().add(0,11,1,"选择消息");
        toolbar.setOnMenuItemClickListener(item -> {
            if(item.getItemId()==11) setSelectionMode(!selectionMode); else confirmClear(); return true;
        });
        buildServiceCard();
        FrameLayout content = new FrameLayout(this); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        buildMessages(); content.addView(messagePage,new FrameLayout.LayoutParams(-1,-1));
        buildSettings(); content.addView(settingsPage,new FrameLayout.LayoutParams(-1,-1));
        permissionGuide=new PermissionGuide(this,settings,() -> {
            if(enableAfterGuide) { enableAfterGuide=false; if(hasSmsPermission()) activateForwarding(); else toast("需要短信权限才能开启转发"); }
            refreshPermissionSummary();
        },this::refreshPermissionSummary);
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if(selectionMode) setSelectionMode(false);
                else if(editingConfig) new MaterialAlertDialogBuilder(MainActivity.this).setTitle("放弃尚未保存的修改？")
                    .setNegativeButton("继续编辑",null).setPositiveButton("放弃修改",(d,w) -> cancelConfigurationEdit()).show();
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        navigation = new BottomNavigationView(this);
        navigation.getMenu().add(0,QUEUE,0,"待发送").setIcon(R.drawable.ic_queue);
        navigation.getMenu().add(0,HISTORY,1,"已转发").setIcon(R.drawable.ic_done);
        navigation.getMenu().add(0,SETTINGS,2,"设置").setIcon(R.drawable.ic_settings);
        navigation.setOnItemSelectedListener(item -> { select(item.getItemId()); return true; });
        navigation.setMinimumHeight(dp(80));
        navigation.setLabelVisibilityMode(com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED);
        navigation.setItemHorizontalTranslationEnabled(false);
        root.addView(navigation,lp(-1,-2)); navigation.setSelectedItemId(tab); select(tab);
        ViewCompat.requestApplyInsets(root);
        AppChanges.observe().observe(this,revision -> loadMessages());
        io.execute(() -> {
            String savedToken="";
            try { savedToken=settings.token(); } catch (Exception e) { settings.enabled(false); }
            final String value=savedToken;
            runOnUiThread(() -> { if (isDestroyed()) return; this.savedToken=value; if(!editingConfig) token.setText(value); updateService(); });
            ForwardWorker.resume(getApplicationContext());
        });
        if(state!=null && state.getInt("guide_step",-1)>=0) {
            enableAfterGuide=state.getBoolean("enable_after_guide",false);
            root.post(() -> permissionGuide.restore(state.getInt("guide_step",0)));
        }
    }
    private void buildServiceCard() {
        MaterialCardView card=card(true); LinearLayout inside=row(); inside.setPadding(dp(20),dp(16),dp(16),dp(16));
        LinearLayout text=column();
        serviceTitle=text(text,"",20,false); serviceTitle.setTypeface(null,Typeface.BOLD);
        serviceDescription=text(text,"",13,true); serviceDescription.setPadding(0,dp(4),dp(8),0);
        inside.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        enabled=new MaterialSwitch(this); enabled.setContentDescription("自动转发短信");
        inside.addView(enabled,lp(-2,48)); card.addView(inside);
        LinearLayout.LayoutParams params=lp(-1,-2); params.setMargins(dp(16),dp(4),dp(16),dp(12)); root.addView(card,params);
        enabled.setOnCheckedChangeListener((button,checked) -> {
            if (syncingSwitch) return;
            if (!checked) { settings.enabled(false); updateService(); loadMessages(); return; }
            setSwitch(false);
            withSavedConfiguration(() -> {
                if(!permissionGuide.recommendedComplete()) { enableAfterGuide=true; permissionGuide.start(); }
                else activateForwarding();
            });
        });
        updateService();
    }
    private void buildMessages() {
        messagePage=column();
        LinearLayout titles=column(); titles.setPadding(dp(24),dp(8),dp(24),dp(12));
        heading=text(titles,"",24,false); heading.setTypeface(null,Typeface.BOLD);
        countLabel=text(titles,"",13,true); countLabel.setPadding(0,dp(4),0,0);
        messagePage.addView(titles);
        selectionBar=row(); selectionBar.setPadding(dp(20),0,dp(20),dp(8));
        selectedLabel=new TextView(this); selectedLabel.setTextSize(14); selectedLabel.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
        selectionBar.addView(selectedLabel,new LinearLayout.LayoutParams(0,-2,1));
        selectAllButton=button("全选",false,v -> selectAllHistory()); selectionBar.addView(selectAllButton,lp(-2,48));
        deleteSelectedButton=button("删除",true,v -> confirmDeleteHistory(new HashSet<>(selectedIds))); selectionBar.addView(deleteSelectedButton,lp(-2,48));
        messagePage.addView(selectionBar); selectionBar.setVisibility(View.GONE);
        FrameLayout body=new FrameLayout(this); messagePage.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        list=new RecyclerView(this); list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter);
        list.setClipToPadding(false); list.setPadding(dp(16),dp(2),dp(16),dp(12));
        body.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=column(); empty.setGravity(android.view.Gravity.CENTER); empty.setPadding(dp(32),dp(24),dp(32),dp(24));
        TextView symbol=text(empty,"○",56,true); symbol.setGravity(17);
        emptyTitle=text(empty,"",20,false); emptyTitle.setGravity(17); emptyTitle.setTypeface(null,Typeface.BOLD);
        emptyDescription=text(empty,"",14,true); emptyDescription.setGravity(17); emptyDescription.setPadding(0,dp(12),0,0);
        body.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        more=button("加载更多",false,v -> { limit+=50; rendered=""; loadMessages(); });
        LinearLayout.LayoutParams p=lp(-1,48); p.setMargins(dp(20),0,dp(20),dp(8)); messagePage.addView(more,p); more.setVisibility(View.GONE);
    }
    private void buildSettings() {
        settingsPage=new ScrollView(this); settingsPage.setFillViewport(true);
        LinearLayout page=column(); page.setPadding(dp(24),dp(8),dp(24),dp(24)); settingsPage.addView(page);
        section(page,"Telegram 连接","消息只会发送到你配置的目标会话。");
        tokenLayout=input(page,"Bot Token",true); token=(TextInputEditText)tokenLayout.getEditText();
        chatLayout=input(page,"Chat ID",false); chat=(TextInputEditText)chatLayout.getEditText(); chat.setText(settings.chat());
        chatLayout.setHelperText("个人会话为数字；群组通常为负数");
        fetchChatsButton=button("自动获取 Chat ID",false,v -> discoverChats()); page.addView(fetchChatsButton,lp(-1,56));
        editButton=button("编辑配置",false,v -> setConfigurationEditing(true)); page.addView(editButton,lp(-1,56));
        saveButton=button("保存配置",true,v -> saveConfiguration(() -> { savedToken=token.getText().toString().trim(); setConfigurationEditing(false); toast("配置已保存"); })); page.addView(saveButton,lp(-1,56));
        cancelEditButton=button("取消编辑",false,v -> cancelConfigurationEdit()); page.addView(cancelEditButton,lp(-1,56));
        testButton=button("发送测试消息",false,v -> withSavedConfiguration(() -> {
            io.execute(() -> {
                try { ForwardWorker.enqueue(getApplicationContext(),"✅ 短信转发测试成功\n这台 Android 设备已连接 Telegram。",true).getResult().get();
                    settings.status("测试消息已加入队列"); runOnUiThread(() -> { toast("测试消息已加入待发送列表"); loadMessages(); });
                } catch(Exception e) { runOnUiThread(() -> toast("测试消息入队失败，请重试")); }
            });
        })); page.addView(testButton,lp(-1,56));
        lastStatus=text(page,settings.status(),13,true); lastStatus.setPadding(0,dp(8),0,dp(12));
        section(page,"权限与后台运行","按步骤完成短信授权、电池优化和后台设置。返回应用后会重新检测系统状态。");
        permissionSummary=text(page,"正在检测…",14,true); permissionSummary.setPadding(0,0,0,dp(12));
        page.addView(button("开始权限引导",true,v -> { enableAfterGuide=false; permissionGuide.start(); }),lp(-1,56));
        text(page,"手机需能访问 api.telegram.org。接收新短信不需要读取历史收件箱。厂商自启动和最近任务锁定为手动设置，无法保证永久保活。",14,true);
        section(page,"如何获取 Chat ID","1. 在 @BotFather 用 /newbot 创建机器人。\n2. 打开你的机器人私聊，点击 Start 并发消息。\n3. 点击编辑配置，填写 Bot Token，然后点击“自动获取 Chat ID”选择自己的会话。\n4. 保存配置，发送测试消息后再开启转发。");
        page.addView(button("查看 Telegram 官方说明",false,v -> startActivity(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://core.telegram.org/bots/api#getupdates")))),lp(-1,56));
        section(page,"消息记录","已转发记录保存在本机，可在已转发页面清空。卸载应用会删除记录。旧版已成功发送的短信无法补回。网络超时重试可能产生重复消息。");
        text(page,"短信转发 1.4.1 · Material 3",12,true).setPadding(0,dp(20),0,0);
        setConfigurationEditing(false);
    }
    private void select(int next) {
        if(tab!=next && selectionMode) setSelectionMode(false);
        if (tab!=next) { limit=50; rendered=""; adapter.set(Collections.emptyList()); list.scrollToPosition(0); list.setVisibility(View.GONE); empty.setVisibility(View.GONE); countLabel.setText("正在加载…"); }
        tab=next;
        messagePage.setVisibility(tab==SETTINGS?View.GONE:View.VISIBLE); settingsPage.setVisibility(tab==SETTINGS?View.VISIBLE:View.GONE);
        updateSelectionUi();
        heading.setText(tab==HISTORY?"已转发消息":"转发队列");
        emptyTitle.setText(tab==HISTORY?"还没有转发记录":"队列为空");
        emptyDescription.setText(tab==HISTORY?"成功转发的消息会显示在这里。\n旧版发送记录无法恢复。":"收到新短信后会在这里显示进度。\n发送成功后会自动移入已转发列表。");
        root.post(() -> {
            androidx.core.view.WindowInsetsControllerCompat controller=ViewCompat.getWindowInsetsController(root);
            if (controller!=null) controller.hide(WindowInsetsCompat.Type.ime());
        });
        loadMessages();
    }
    private void loadMessages() {
        if (loading || isDestroyed()) return;
        loading=true; final int selected=tab, requestedLimit=limit;
        final long revision=AppChanges.revision();
        io.execute(() -> {
            try (Outbox db=new Outbox(getApplicationContext())) {
                int queued=db.count(false), sent=db.count(true);
                boolean testPending=db.pendingTestId()!=-1;
                List<Outbox.Entry> rows=selected==SETTINGS?Collections.emptyList():db.list(selected==HISTORY,requestedLimit);
                runOnUiThread(() -> {
                    loading=false;
                    if (isDestroyed() || !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return;
                    if (selected!=tab || requestedLimit!=limit || revision!=AppChanges.revision()) { loadMessages(); return; }
                    historyCount=sent; updateSelectionUi(); updateService(); lastStatus.setText(settings.status());
                    if (!saving) { testButton.setEnabled(!testPending && !editingConfig); testButton.setText(testPending?"测试消息发送中…":"发送测试消息"); }
                    if (tab==SETTINGS) return;
                    int total=tab==HISTORY?sent:queued;
                    countLabel.setText(tab==HISTORY?"共 "+sent+" 条 · 按发送时间排列":"共 "+queued+" 条 · 含等待、重试和失败消息");
                    StringBuilder key=new StringBuilder().append(tab).append(settings.enabled()).append(selectionMode).append(selectedIds.hashCode());
                    for (Outbox.Entry e:rows) key.append('|').append(e.id).append(e.state).append(e.offset).append(e.error).append(e.sentAt);
                    if (!key.toString().equals(rendered)) { rendered=key.toString(); adapter.set(rows); }
                    list.setVisibility(total==0?View.GONE:View.VISIBLE); empty.setVisibility(total==0?View.VISIBLE:View.GONE);
                    more.setVisibility(total>rows.size()?View.VISIBLE:View.GONE);
                    more.setText(getString(R.string.load_more,rows.size(),total));
                });
            } catch(Exception e) { runOnUiThread(() -> { loading=false; countLabel.setText("读取失败，请稍后重试"); }); }
        });
    }
    private void updateService() {
        boolean running=settings.enabled() && hasSmsPermission(); setSwitch(running);
        serviceTitle.setText(running?"自动转发已开启":"自动转发已关闭");
        String target=settings.chatDisplay();
        serviceDescription.setText(running?"发送到 "+target:target.isEmpty()?"先在设置中连接 Telegram":"开启后接收并转发新短信");
    }
    private void setSwitch(boolean checked) { syncingSwitch=true; enabled.setChecked(checked); syncingSwitch=false; }
    private void activateForwarding() {
        settings.enabled(true); updateService();
        io.execute(() -> { ForwardWorker.resume(getApplicationContext()); runOnUiThread(this::loadMessages); });
        toast("已开启短信转发");
    }
    private boolean hasSmsPermission() { return checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED; }
    private void discoverChats() {
        if(!editingConfig || fetchingChats || saving) return;
        String lookupToken=token.getText().toString().trim(); tokenLayout.setError(null);
        if(!lookupToken.matches("[0-9]+:[A-Za-z0-9_-]+")) { tokenLayout.setError("请先填写有效的 Bot Token"); token.requestFocus(); return; }
        new MaterialAlertDialogBuilder(this).setTitle("获取机器人会话")
                .setMessage("请先在 Telegram 打开你的机器人，发送 /start 或一条消息，再点击获取。随后选择接收短信的会话，填入后点击保存才会生效。")
                .setNegativeButton("取消",null).setPositiveButton("获取会话",(d,w) -> fetchChats(lookupToken)).show();
    }
    private void fetchChats(String lookupToken) {
        if(!editingConfig || fetchingChats || !lookupToken.equals(token.getText().toString().trim())) return;
        fetchingChats=true; fetchChatsButton.setEnabled(false); fetchChatsButton.setText("正在获取…"); saveButton.setEnabled(false);
        tokenLayout.setEnabled(false); chatLayout.setEnabled(false);
        io.execute(() -> {
            List<TelegramChats.Chat> results=null; String error=null;
            try { results=TelegramChats.fetch(lookupToken); }
            catch(TelegramChats.ApiException e) { error=e.getMessage(); }
            catch(java.io.IOException e) { error="连接失败，请确认手机能访问 api.telegram.org 后重试"; }
            final List<TelegramChats.Chat> chats=results; final String failure=error;
            runOnUiThread(() -> {
                fetchingChats=false; if(isDestroyed()) return;
                fetchChatsButton.setText("自动获取 Chat ID"); fetchChatsButton.setEnabled(true); saveButton.setEnabled(true);
                tokenLayout.setEnabled(editingConfig); chatLayout.setEnabled(editingConfig);
                if(!editingConfig || !lookupToken.equals(token.getText().toString().trim())) return;
                if(failure!=null) { new MaterialAlertDialogBuilder(this).setTitle("获取未完成").setMessage(failure).setPositiveButton("知道了",null).show(); return; }
                if(chats==null || chats.isEmpty()) {
                    new MaterialAlertDialogBuilder(this).setTitle("暂未找到会话")
                        .setMessage("请给机器人发送一条新消息，再点击自动获取。机器人只能提供最近尚未确认的更新；其他机器人程序可能已取走这些消息，也可以手动填写 Chat ID。")
                        .setPositiveButton("知道了",null).show(); return;
                }
                String[] labels=new String[chats.size()]; for(int i=0;i<chats.size();i++) labels[i]=chats.get(i).label();
                new MaterialAlertDialogBuilder(this).setTitle("选择接收短信的会话")
                    .setItems(labels,(dialog,index) -> {
                        if(editingConfig && lookupToken.equals(token.getText().toString().trim())) {
                            TelegramChats.Chat chosen=chats.get(index);
                            selectedChatName=chosen.displayName; selectedChatId=chosen.id; selectedChatToken=lookupToken;
                            chat.setText(chosen.id); chatLayout.setError(null); toast("已填入 Chat ID，点击保存配置后生效");
                        }
                    }).setNegativeButton("取消",null).show();
            });
        });
    }
    private void withSavedConfiguration(Runnable success) {
        if(editingConfig) { navigation.setSelectedItemId(SETTINGS); toast("请先保存或取消配置编辑"); return; }
        if(!settings.configured()) { navigation.setSelectedItemId(SETTINGS); toast("请点击编辑配置，填写并保存 Telegram 信息"); return; }
        success.run();
    }
    private void setConfigurationEditing(boolean editing) {
        if(!editing) { selectedChatName=""; selectedChatId=""; selectedChatToken=""; }
        editingConfig=editing; tokenLayout.setEnabled(editing); chatLayout.setEnabled(editing);
        tokenLayout.setEndIconMode(editing?TextInputLayout.END_ICON_PASSWORD_TOGGLE:TextInputLayout.END_ICON_NONE);
        token.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        editButton.setVisibility(editing?View.GONE:View.VISIBLE);
        fetchChatsButton.setVisibility(editing?View.VISIBLE:View.GONE);
        fetchChatsButton.setEnabled(!fetchingChats && !saving);
        saveButton.setEnabled(!fetchingChats && !saving);
        saveButton.setVisibility(editing?View.VISIBLE:View.GONE); cancelEditButton.setVisibility(editing?View.VISIBLE:View.GONE);
        testButton.setEnabled(!editing && !saving);
        if(editing) token.requestFocus();
    }
    private void cancelConfigurationEdit() {
        token.setText(savedToken); chat.setText(settings.chat()); tokenLayout.setError(null); chatLayout.setError(null); setConfigurationEditing(false);
    }
    private void refreshPermissionSummary() {
        if(permissionSummary!=null && permissionGuide!=null) permissionSummary.setText(permissionGuide.summary());
    }
    private void saveConfiguration(Runnable success) {
        if (saving || fetchingChats || !editingConfig) return;
        String t=token.getText().toString().trim(), c=chat.getText().toString().trim();
        tokenLayout.setError(null); chatLayout.setError(null);
        if (!t.matches("[0-9]+:[A-Za-z0-9_-]+")) { navigation.setSelectedItemId(SETTINGS); tokenLayout.setError("请输入有效的 Bot Token"); token.requestFocus(); return; }
        if (!c.matches("-?[0-9]+|@[A-Za-z0-9_]+")) { navigation.setSelectedItemId(SETTINGS); chatLayout.setError("请输入有效的 Chat ID"); chat.requestFocus(); return; }
        String destinationName=c.equals(selectedChatId) && t.equals(selectedChatToken)?selectedChatName
                :c.equals(settings.chat()) && t.equals(savedToken)?settings.chatName():"";
        saving=true; fetchChatsButton.setEnabled(false); saveButton.setEnabled(false); cancelEditButton.setEnabled(false); tokenLayout.setEnabled(false); chatLayout.setEnabled(false); testButton.setEnabled(false); enabled.setEnabled(false);
        io.execute(() -> {
            boolean ok;
            try { settings.save(t,c,destinationName); ok=true; } catch(Exception e) { ok=false; }
            final boolean saved=ok;
            runOnUiThread(() -> {
                saving=false; fetchChatsButton.setEnabled(true); saveButton.setEnabled(true); cancelEditButton.setEnabled(true); tokenLayout.setEnabled(true); chatLayout.setEnabled(true); testButton.setEnabled(false); enabled.setEnabled(true);
                if (isDestroyed()) return;
                if (saved) { updateService(); success.run(); } else toast("保存失败，请重试");
            });
        });
    }
    private void setSelectionMode(boolean value) {
        selectionMode=value; selectedIds.clear(); updateSelectionUi(); rendered=""; adapter.set(adapter.rows);
    }
    private void updateSelectionUi() {
        toolbar.setTitle(selectionMode?"已选择 "+selectedIds.size()+" 条":"短信转发");
        toolbar.getMenu().findItem(10).setVisible(tab!=SETTINGS && !selectionMode);
        toolbar.getMenu().findItem(11).setVisible(tab==HISTORY);
        toolbar.getMenu().findItem(11).setTitle(selectionMode?"完成选择":"选择消息");
        selectionBar.setVisibility(selectionMode?View.VISIBLE:View.GONE);
        selectedLabel.setText(getString(R.string.selected_count,selectedIds.size())); deleteSelectedButton.setEnabled(!selectedIds.isEmpty());
        selectAllButton.setText(!selectedIds.isEmpty() && selectedIds.size()==historyCount?"取消全选":"全选");
    }
    private void toggleSelection(long id) {
        if(!selectedIds.remove(id)) selectedIds.add(id); updateSelectionUi(); rendered=""; adapter.set(adapter.rows);
    }
    private void selectAllHistory() {
        selectAllButton.setEnabled(false);
        io.execute(() -> {
            try(Outbox db=new Outbox(getApplicationContext())) {
                List<Long> all=db.sentIds();
                runOnUiThread(() -> {
                    selectAllButton.setEnabled(true); if(!selectionMode || tab!=HISTORY) return;
                    if(!all.isEmpty() && selectedIds.containsAll(all)) { selectedIds.clear(); selectAllButton.setText("全选"); }
                    else { selectedIds.clear(); selectedIds.addAll(all); selectAllButton.setText("取消全选"); }
                    updateSelectionUi(); rendered=""; adapter.set(adapter.rows);
                });
            }
        });
    }
    private void confirmDeleteHistory(Set<Long> ids) {
        if(ids.isEmpty()) return;
        new MaterialAlertDialogBuilder(this).setTitle("删除 "+ids.size()+" 条转发记录？")
                .setMessage("仅删除选中的本机记录，不会删除 Telegram 中的消息。此操作无法撤销。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w) -> io.execute(() -> {
                    try(Outbox db=new Outbox(getApplicationContext())) {
                        int count=db.deleteSent(ids);
                        runOnUiThread(() -> { setSelectionMode(false); rendered=""; loadMessages(); toast("已删除 "+count+" 条记录"); });
                    }
                })).show();
    }
    private void confirmClear() {
        final boolean history=tab==HISTORY;
        new MaterialAlertDialogBuilder(this).setTitle(history?"清空转发记录？":"清空待发送队列？")
                .setMessage(history?"仅删除本机记录，不会删除 Telegram 中的消息。":"将取消此刻队列中的消息；已发出的内容无法撤回。")
                .setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which) -> io.execute(() -> {
                    try (Outbox db=new Outbox(getApplicationContext())) {
                        if (history) db.clearSent();
                        else {
                            for (Outbox.Entry entry:db.list(false,Integer.MAX_VALUE)) {
                                db.delete(entry.id); WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(ForwardWorker.workName(entry.id));
                            }
                        }
                        runOnUiThread(() -> { rendered=""; loadMessages(); toast(history?"已清空转发记录":"已清空队列"); });
                    }
                })).show();
    }
    private void showDetails(Outbox.Entry item) {
        String timestamp=date(item.receivedAt>0?item.receivedAt:item.createdAt);
        String state=displayState(item);
        String info="状态："+state+"\n接收时间："+timestamp+"\n目标："+(item.target.isEmpty()?"未记录":item.target)
                +(item.sentAt>0?"\n发送时间："+date(item.sentAt):"")
                +(item.error.isEmpty()?"":"\n说明："+item.error)+"\n\n"+item.content;
        TextView detail=new TextView(this); detail.setText(info); detail.setTextSize(15); detail.setTextIsSelectable(true);
        detail.setPadding(dp(24),dp(8),dp(24),dp(16)); detail.setLineSpacing(dp(4),1);
        ScrollView scroll=new ScrollView(this); scroll.addView(detail);
        MaterialAlertDialogBuilder dialog=new MaterialAlertDialogBuilder(this).setTitle(item.title()).setView(scroll).setPositiveButton("关闭",null);
        if (Outbox.SENT.equals(item.state)) {
            dialog.setNegativeButton("删除记录",(d,w) -> confirmDeleteHistory(Collections.singleton(item.id)));
        } else {
            dialog.setNeutralButton("移除",(d,w) -> new MaterialAlertDialogBuilder(this).setTitle("移除此消息？")
                    .setMessage("将取消后续发送。已发出的部分无法撤回。")
                    .setNegativeButton("取消",null).setPositiveButton("移除",(confirm,which) -> io.execute(() -> {
                        try (Outbox db=new Outbox(getApplicationContext())) { db.delete(item.id); }
                        WorkManager.getInstance(getApplicationContext()).cancelUniqueWork(ForwardWorker.workName(item.id));
                        runOnUiThread(() -> { rendered=""; loadMessages(); });
                    })).show());
            if (Outbox.FAILED.equals(item.state) || Outbox.PAUSED.equals(item.state)) {
                dialog.setNegativeButton("重试",(d,w) -> retry(item));
            }
        }
        dialog.show();
    }
    private void retry(Outbox.Entry item) {
        if (!item.test && !settings.enabled()) { toast("请先开启自动转发"); return; }
        io.execute(() -> {
            try (Outbox db=new Outbox(getApplicationContext())) {
                Outbox.Entry latest=db.get(item.id);
                if (latest==null || Outbox.SENT.equals(latest.state)) return;
                db.state(item.id,Outbox.QUEUED,""); ForwardWorker.schedule(getApplicationContext(),item.id,androidx.work.ExistingWorkPolicy.REPLACE);
            }
            runOnUiThread(() -> { rendered=""; loadMessages(); toast("已加入重试队列"); });
        });
    }
    private String displayState(Outbox.Entry item) {
        if (!item.test && !settings.enabled() && (Outbox.QUEUED.equals(item.state)||Outbox.RETRY.equals(item.state))) return "已暂停";
        return item.stateLabel();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code==10) { if(!hasSmsPermission()) { settings.enabled(false); updateService(); } permissionGuide.refresh(); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (!hasSmsPermission()) settings.enabled(false);
        updateService(); refreshPermissionSummary(); if(permissionGuide.active()) permissionGuide.refresh(); loadMessages();
    }
    @Override protected void onDestroy() { if(permissionGuide!=null) permissionGuide.destroy(); io.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putInt("tab",tab); out.putInt("limit",limit); out.putInt("guide_step",permissionGuide.step()); out.putBoolean("enable_after_guide",enableAfterGuide); super.onSaveInstanceState(out); }
    private String date(long timestamp) { return timestamp==0?"旧版消息，时间未记录":DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(timestamp)); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private int color(int attr) { return MaterialColors.getColor(this,attr,MainActivity.class.getSimpleName()); }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(android.view.Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout.LayoutParams lp(int width,int height) { return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height)); }
    private TextView text(LinearLayout parent,String value,int size,boolean muted) {
        TextView view=new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(color(muted?com.google.android.material.R.attr.colorOnSurfaceVariant:com.google.android.material.R.attr.colorOnSurface));
        view.setLineSpacing(dp(2),1); parent.addView(view,lp(-1,-2)); return view;
    }
    private MaterialCardView card(boolean tonal) {
        MaterialCardView card=new MaterialCardView(this); card.setRadius(dp(20)); card.setCardElevation(0);
        card.setStrokeWidth(tonal?0:dp(1)); card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        card.setCardBackgroundColor(color(tonal?com.google.android.material.R.attr.colorSurfaceContainer:com.google.android.material.R.attr.colorSurface));
        return card;
    }
    private void section(LinearLayout parent,String title,String description) {
        TextView label=text(parent,title,20,false); label.setTypeface(null,Typeface.BOLD); label.setPadding(0,dp(20),0,dp(8));
        text(parent,description,14,true).setPadding(0,0,0,dp(16));
    }
    private TextInputLayout input(LinearLayout parent,String hint,boolean password) {
        TextInputLayout layout=new TextInputLayout(this,null,com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint(hint); layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        if (password) layout.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        TextInputEditText field=new TextInputEditText(layout.getContext()); field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (password?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); field.setSaveEnabled(false); field.setId(password?R.id.token_input:R.id.chat_input);
        layout.addView(field,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams p=lp(-1,-2); p.setMargins(0,dp(4),0,dp(12)); parent.addView(layout,p); return layout;
    }
    private MaterialButton button(String title,boolean filled,View.OnClickListener action) {
        MaterialButton view=new MaterialButton(this,null,filled?com.google.android.material.R.attr.materialButtonStyle:com.google.android.material.R.attr.materialButtonOutlinedStyle);
        view.setText(title); view.setOnClickListener(action); view.setMinHeight(dp(48)); return view;
    }
    private void toast(String message) { if (!isFinishing() && !isDestroyed()) Snackbar.make(root,message,Snackbar.LENGTH_LONG).show(); }
    private class MessagesAdapter extends RecyclerView.Adapter<MessageHolder> {
        private List<Outbox.Entry> rows=new ArrayList<>();
        MessagesAdapter() { setHasStableIds(true); }
        private boolean forwardingEnabled, previousSelectionMode;
        private Set<Long> previousSelection=new HashSet<>();
        void set(List<Outbox.Entry> items) {
            List<Outbox.Entry> previous=rows;
            boolean enabledChanged=forwardingEnabled!=settings.enabled();
            DiffUtil.DiffResult changes=DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override public int getOldListSize() { return previous.size(); }
                @Override public int getNewListSize() { return items.size(); }
                @Override public boolean areItemsTheSame(int oldPosition,int newPosition) { return previous.get(oldPosition).id==items.get(newPosition).id; }
                @Override public boolean areContentsTheSame(int oldPosition,int newPosition) {
                    Outbox.Entry a=previous.get(oldPosition), b=items.get(newPosition);
                    return !enabledChanged && previousSelectionMode==selectionMode && previousSelection.contains(a.id)==selectedIds.contains(b.id) && a.state.equals(b.state) && a.offset==b.offset && a.error.equals(b.error) && a.sentAt==b.sentAt;
                }
            });
            rows=items; forwardingEnabled=settings.enabled(); previousSelectionMode=selectionMode; previousSelection=new HashSet<>(selectedIds); changes.dispatchUpdatesTo(this);
        }
        @Override public long getItemId(int position) { return rows.get(position).id; }
        @Override public int getItemCount() { return rows.size(); }
        @Override public MessageHolder onCreateViewHolder(ViewGroup parent,int viewType) {
            MaterialCardView card=card(false);
            RecyclerView.LayoutParams p=new RecyclerView.LayoutParams(-1,-2); p.bottomMargin=dp(10); card.setLayoutParams(p);
            LinearLayout content=column(); content.setPadding(dp(16),dp(16),dp(16),dp(16)); card.addView(content);
            LinearLayout header=row(); content.addView(header);
            com.google.android.material.checkbox.MaterialCheckBox check=new com.google.android.material.checkbox.MaterialCheckBox(MainActivity.this);
            check.setContentDescription("选择此条记录"); header.addView(check,lp(48,48));
            TextView sender=new TextView(MainActivity.this); sender.setTextSize(16); sender.setTypeface(null,Typeface.BOLD);
            sender.setTextColor(color(com.google.android.material.R.attr.colorOnSurface)); sender.setMaxLines(1); sender.setEllipsize(TextUtils.TruncateAt.END);
            header.addView(sender,new LinearLayout.LayoutParams(0,-2,1));
            TextView state=new TextView(MainActivity.this); state.setTextSize(12); state.setPadding(dp(10),dp(5),dp(10),dp(5));
            header.addView(state,lp(-2,-2));
            TextView body=text(content,"",15,false); body.setMaxLines(3); body.setEllipsize(TextUtils.TruncateAt.END); body.setPadding(0,dp(12),0,dp(12));
            TextView time=text(content,"",12,true);
            card.setClickable(true); card.setFocusable(true);
            return new MessageHolder(card,sender,state,body,time,check);
        }
        @Override public void onBindViewHolder(MessageHolder holder,int position) {
            Outbox.Entry item=rows.get(position); holder.sender.setText(item.title()); holder.body.setText(item.content);
            holder.state.setText(displayState(item));
            boolean failure=Outbox.FAILED.equals(item.state);
            int bg=color(failure?com.google.android.material.R.attr.colorErrorContainer:Outbox.SENT.equals(item.state)?com.google.android.material.R.attr.colorPrimaryContainer:com.google.android.material.R.attr.colorSecondaryContainer);
            holder.state.setTextColor(color(failure?com.google.android.material.R.attr.colorOnErrorContainer:Outbox.SENT.equals(item.state)?com.google.android.material.R.attr.colorOnPrimaryContainer:com.google.android.material.R.attr.colorOnSecondaryContainer));
            android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable(); shape.setColor(bg); shape.setCornerRadius(dp(16)); holder.state.setBackground(shape);
            holder.time.setText(getString(R.string.message_time,Outbox.SENT.equals(item.state)?"发送于":"接收于",
                    date(item.sentAt>0?item.sentAt:item.receivedAt>0?item.receivedAt:item.createdAt),item.error.isEmpty()?"":"\n"+item.error));
            holder.check.setOnCheckedChangeListener(null); holder.check.setVisibility(selectionMode && tab==HISTORY?View.VISIBLE:View.GONE);
            holder.check.setChecked(selectedIds.contains(item.id));
            holder.check.setOnCheckedChangeListener((button,checked) -> { if(checked!=selectedIds.contains(item.id)) toggleSelection(item.id); });
            holder.itemView.setOnClickListener(v -> { if(selectionMode && tab==HISTORY) toggleSelection(item.id); else showDetails(item); });
            holder.itemView.setOnLongClickListener(v -> {
                if(tab!=HISTORY) return false;
                if(!selectionMode) setSelectionMode(true); toggleSelection(item.id); return true;
            });
        }
    }
    private static class MessageHolder extends RecyclerView.ViewHolder {
        final TextView sender,state,body,time;
        final com.google.android.material.checkbox.MaterialCheckBox check;
        MessageHolder(View view,TextView sender,TextView state,TextView body,TextView time,com.google.android.material.checkbox.MaterialCheckBox check) { super(view); this.sender=sender; this.state=state; this.body=body; this.time=time; this.check=check; }
    }
}
