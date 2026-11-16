package app.chatcraft.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CommandAuthTest {

    @Test
    public void bareAndStaffNeedChatcraftStaff() {
        assertEquals("chatcraft.staff", CommandAuth.required(new String[]{}));
        assertEquals("chatcraft.staff", CommandAuth.required(new String[]{"staff"}));
        assertEquals("chatcraft.staff", CommandAuth.required(new String[]{"staff", "play.example.com"}));
    }

    @Test
    public void otherSubcommandsNeedChatcraftAdmin() {
        assertEquals("chatcraft.admin", CommandAuth.required(new String[]{"verify"}));
        assertEquals("chatcraft.admin", CommandAuth.required(new String[]{"status"}));
        assertEquals("chatcraft.admin", CommandAuth.required(new String[]{"alert", "hello"}));
        assertEquals("chatcraft.admin", CommandAuth.required(new String[]{"staff", "remove", "Steve"}));
    }
}
