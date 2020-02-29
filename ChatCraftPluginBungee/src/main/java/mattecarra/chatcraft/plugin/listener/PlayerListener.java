package mattecarra.chatcraft.plugin.listener;

import mattecarra.chatcraft.plugin.ChatCraftPluginBungee;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.protocol.packet.PluginMessage;

public class PlayerListener implements Listener {

    private ChatCraftPluginBungee plugin;

    public PlayerListener(ChatCraftPluginBungee plugin) {
        this.plugin = plugin;
    }


    @EventHandler
    public void onLogin(PostLoginEvent event) {
        ProxiedPlayer player = event.getPlayer();
        player.unsafe().sendPacket(new PluginMessage(ChatCraftPluginBungee.CHANNEL, ChatCraftPluginBungee.GSON_NON_PRETTY.toJson(this.plugin.getConf().getDisallowedFeatures()).getBytes(), false));
    }
}