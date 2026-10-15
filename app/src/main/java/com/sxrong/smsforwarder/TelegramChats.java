package com.sxrong.smsforwarder;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** One explicit short-poll request: never advances offset or changes webhook/update subscriptions. */
final class TelegramChats {
    static final class Chat {
        final String id, name, type, displayName;
        Chat(String id,String name,String type,String displayName) { this.id=id; this.name=name; this.type=type; this.displayName=displayName; }
        String label() {
            String kind="private".equals(type)?"私聊":"channel".equals(type)?"频道":"群组";
            return name+" · "+kind+"\nChat ID: "+id;
        }
    }
    static final class ApiException extends Exception { ApiException(String message) { super(message); } }
    static List<Chat> fetch(String token) throws IOException, ApiException {
        if(!token.matches("[0-9]+:[A-Za-z0-9_-]+")) throw new ApiException("请输入有效的 Bot Token");
        HttpsURLConnection connection=(HttpsURLConnection)new URL("https://api.telegram.org/bot"+token+"/getUpdates").openConnection();
        try {
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            byte[] data="{\"limit\":100,\"timeout\":0}".getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try(java.io.OutputStream output=connection.getOutputStream()) { output.write(data); }
            int code=connection.getResponseCode();
            InputStream input=code>=200 && code<300?connection.getInputStream():connection.getErrorStream();
            if(input==null) throw new ApiException(errorMessage(code,""));
            try(InputStream stream=input; ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096]; int size;
                while((size=stream.read(buffer))!=-1) {
                    if(bytes.size()+size>2*1024*1024) throw new ApiException("返回数据过大，请手动填写 Chat ID");
                    bytes.write(buffer,0,size);
                }
                return parse(code,bytes.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    static List<Chat> parse(int code,String body) throws ApiException {
        try {
            JSONObject response=new JSONObject(body);
            if(code!=200 || !response.optBoolean("ok")) throw new ApiException(errorMessage(response.optInt("error_code",code),response.optString("description")));
            JSONArray updates=response.optJSONArray("result");
            if(updates==null) throw new ApiException("Telegram 返回格式异常，请稍后重试");
            LinkedHashMap<String,Chat> chats=new LinkedHashMap<>();
            String[] fields={"message","edited_message","channel_post","edited_channel_post","my_chat_member","chat_member","chat_join_request"};
            for(int i=0;i<updates.length();i++) {
                JSONObject update=updates.optJSONObject(i); if(update==null) continue;
                for(String field:fields) {
                    JSONObject event=update.optJSONObject(field); if(event==null) continue;
                    add(chats,event.optJSONObject("chat"));
                }
                JSONObject callback=update.optJSONObject("callback_query");
                if(callback!=null && callback.optJSONObject("message")!=null) add(chats,callback.optJSONObject("message").optJSONObject("chat"));
            }
            List<Chat> result=new ArrayList<>(chats.values()); Collections.reverse(result); return result;
        } catch(org.json.JSONException e) { throw new ApiException(code!=200?errorMessage(code,""):"Telegram 返回格式异常，请稍后重试"); }
    }
    private static void add(LinkedHashMap<String,Chat> chats,JSONObject value) {
        if(value==null) return;
        String id=value.optString("id",""); if(!id.matches("-?[0-9]+")) return;
        String name=value.optString("title","");
        if(name.isEmpty()) name=(value.optString("first_name","")+" "+value.optString("last_name","")).trim();
        if(name.isEmpty()) name=value.optString("username","");
        if(name.isEmpty()) name="未命名会话";
        String type=value.optString("type","private");
        String firstName=value.optString("first_name","").trim();
        String displayName="private".equals(type) && !firstName.isEmpty()?firstName:name;
        chats.remove(id); chats.put(id,new Chat(id,name,type,displayName));
    }
    private static String errorMessage(int code,String description) {
        if(code==401 || code==404) return "Bot Token 无效，请检查后重试";
        if(code==409) return description.toLowerCase(Locale.ROOT).contains("webhook")
                ?"机器人已设置 Webhook，暂时无法自动获取。请手动填写 Chat ID，或在你自己的机器人服务中调整 Webhook 设置。"
                :"机器人正被其他程序轮询，请暂停其 getUpdates 请求后重试，或手动填写 Chat ID。";
        if(code==429) return "Telegram 请求过于频繁，请稍后再试";
        if(code>=500) return "Telegram 服务暂时不可用，请稍后再试";
        return "获取失败（HTTP "+code+"），请检查配置后重试";
    }
}
