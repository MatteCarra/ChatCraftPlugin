package app.chatcraft.plugin;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HelpPageTest {

    private static List<String> usages(boolean reports, String... perms) {
        List<String> have = Arrays.asList(perms);
        List<String> out = new java.util.ArrayList<>();
        for (HelpPage.Entry e : HelpPage.visible(HelpPage.entries(reports), have::contains)) out.add(e.usage);
        return out;
    }

    @Test
    public void adminSeesEverything() {
        List<String> u = usages(true, CommandAuth.ADMIN, CommandAuth.STAFF);
        assertEquals(7, u.size());
        assertTrue(u.contains("/chatcraft staff list"));
    }

    @Test
    public void staffSeesOnlyEnrolmentAndReport() {
        assertEquals(Arrays.asList("/chatcraft staff [host[:port]]", "/report <player> [reason]"), usages(true, CommandAuth.STAFF));
    }

    @Test
    public void playerSeesOnlyReportWhenEnabled() {
        assertEquals(Arrays.asList("/report <player> [reason]"), usages(true));
        assertTrue(usages(false).isEmpty());
    }

    @Test
    public void everyEntryOfEachListedPermissionMatchesCommandAuth() {
        for (HelpPage.Entry e : HelpPage.entries(true)) {
            if (e.permission == null) continue;
            String[] args = e.usage.replace("/chatcraft ", "").replaceAll("[\\[<].*", "").trim().split(" ");
            assertEquals(e.usage, e.permission, CommandAuth.required(args));
        }
    }

    @Test
    public void tabCompletionFiltersByPrefixWithoutDuplicates() {
        assertEquals(Arrays.asList("staff", "status"), ChatCraftCommand.startingWith(Arrays.asList("verify", "staff", "status", "staff"), "st"));
        assertEquals(Arrays.asList("Steve"), ChatCraftCommand.startingWith(Arrays.asList("Steve", "Alex"), "ste"));
    }

    @Test
    public void staffListNeedsAdmin() {
        assertEquals(CommandAuth.ADMIN, CommandAuth.required(new String[]{"staff", "list"}));
    }
}
