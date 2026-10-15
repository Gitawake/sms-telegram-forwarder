package com.sxrong.smsforwarder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class TelegramChatsTest {
    @Test public void deduplicatesChatsKeepsRecentNameAndPreservesLargeNegativeIds() throws Exception {
        String json="{\"ok\":true,\"result\":["
            +"{\"message\":{\"chat\":{\"id\":123,\"type\":\"private\",\"first_name\":\"Old\"}}},"
            +"{\"channel_post\":{\"chat\":{\"id\":-1001234567890,\"type\":\"channel\",\"title\":\"My channel\"}}},"
            +"{\"message\":{\"chat\":{\"id\":123,\"type\":\"private\",\"first_name\":\"New\",\"last_name\":\"Surname\"}}}]}";
        List<TelegramChats.Chat> chats=TelegramChats.parse(200,json);
        assertEquals(2,chats.size()); assertEquals("123",chats.get(0).id); assertEquals("New Surname",chats.get(0).name); assertEquals("New",chats.get(0).displayName);
        assertEquals("-1001234567890",chats.get(1).id); assertTrue(chats.get(1).label().contains("频道"));
    }
    @Test public void ignoresUnknownEventsAndInvalidChatsButReadsMembershipEvents() throws Exception {
        List<TelegramChats.Chat> chats=TelegramChats.parse(200,"{\"ok\":true,\"result\":[{},null,{\"message\":{\"chat\":{\"id\":\"invalid\"}}},{\"my_chat_member\":{\"chat\":{\"id\":-123,\"type\":\"group\",\"title\":\"Friends\"}}}]}");
        assertEquals(1,chats.size()); assertEquals("-123",chats.get(0).id); assertTrue(chats.get(0).label().contains("群组"));
    }
    @Test public void emptyUpdatesReturnEmptyChoices() throws Exception {
        assertTrue(TelegramChats.parse(200,"{\"ok\":true,\"result\":[]}").isEmpty());
    }
    @Test public void webhookConflictProducesActionableMessageWithoutRawServerText() throws Exception {
        try { TelegramChats.parse(409,"{\"ok\":false,\"error_code\":409,\"description\":\"Conflict: can't use getUpdates method while webhook is active\"}"); fail(); }
        catch(TelegramChats.ApiException e) { assertTrue(e.getMessage().contains("Webhook")); assertTrue(e.getMessage().contains("手动填写")); }
    }
    @Test public void invalidTokenAndMalformedResponsesAreHandled() throws Exception {
        try { TelegramChats.parse(401,"{\"ok\":false,\"error_code\":401}"); fail(); }
        catch(TelegramChats.ApiException e) { assertTrue(e.getMessage().contains("Token 无效")); }
        try { TelegramChats.parse(200,"not json"); fail(); }
        catch(TelegramChats.ApiException e) { assertTrue(e.getMessage().contains("格式异常")); }
    }
}
