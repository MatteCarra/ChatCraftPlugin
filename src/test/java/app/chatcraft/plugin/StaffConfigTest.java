package app.chatcraft.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.*;

public class StaffConfigTest {

    private static ConfigurationSection sectionWithTemplates(Object... kv) {
        YamlConfiguration root = new YamlConfiguration();
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            Map<String, Object> m = new HashMap<>();
            // kv pairs are template index -> map; simpler: caller builds list
        }
        return root;
    }

    private static ConfigurationSection configWith(List<Map<String, Object>> templates) {
        YamlConfiguration root = new YamlConfiguration();
        root.set("reports", true);
        root.set("report-cooldown-seconds", 30);
        root.set("staff-actions", templates);
        return root;
    }

    private static Map<String, Object> tpl(String id, String command, String target, Object confirm) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("label", id + " label");
        m.put("command", command);
        if (target != null) m.put("target", target);
        if (confirm != null) m.put("confirm", confirm);
        return m;
    }

    @Test
    public void validTemplateIsKept() {
        StaffConfig c = new StaffConfig(configWith(java.util.Collections.singletonList(tpl("kick", "kick {player}", "player", true))), Logger.getLogger("t"));
        List<StaffConfig.Template> ts = c.getTemplates();
        assertEquals(1, ts.size());
        assertEquals("kick", ts.get(0).id);
        assertEquals("kick {player}", ts.get(0).command);
        assertEquals("player", ts.get(0).target);
        assertTrue(ts.get(0).confirm);
    }

    @Test
    public void newlineLeadingSlashAndOver100CharsAreDropped() {
        java.util.List<Map<String, Object>> bad = new java.util.ArrayList<>();
        bad.add(tpl("nl", "kick {player}\nban {player}", "player", null));
        bad.add(tpl("slash", "/kick {player}", "player", null));
        bad.add(tpl("long", "mute " + new String(new char[101]).replace('\0', 'x'), "player", null));
        bad.add(tpl("ok", "kick {player}", "player", null));
        StaffConfig c = new StaffConfig(configWith(bad), Logger.getLogger("t"));
        List<StaffConfig.Template> ts = c.getTemplates();
        assertEquals(1, ts.size());
        assertEquals("ok", ts.get(0).id);
    }

    @Test
    public void duplicateIdsAreDropped() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        list.add(tpl("kick", "kick {player}", "player", null));
        list.add(tpl("kick", "ban {player}", "player", null));
        StaffConfig c = new StaffConfig(configWith(list), Logger.getLogger("t"));
        assertEquals(1, c.getTemplates().size());
        assertEquals("kick {player}", c.getTemplates().get(0).command);
    }

    @Test
    public void atMost10TemplatesConfirmDefaultsTrue() {
        java.util.List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) list.add(tpl("t" + i, "cmd" + i + " {player}", "alert", null));
        StaffConfig c = new StaffConfig(configWith(list), Logger.getLogger("t"));
        assertEquals(10, c.getTemplates().size());
        assertTrue(c.getTemplates().get(0).confirm); // default true
        assertEquals("alert", c.getTemplates().get(0).target);
    }

    @Test
    public void reportsDefaults() {
        StaffConfig c = new StaffConfig(new YamlConfiguration(), Logger.getLogger("t"));
        assertTrue(c.reportsEnabled());
        assertEquals(30, c.reportCooldownSeconds());
    }
}
