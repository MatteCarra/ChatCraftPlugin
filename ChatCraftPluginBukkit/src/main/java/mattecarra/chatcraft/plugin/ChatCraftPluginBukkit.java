package mattecarra.chatcraft.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mattecarra.chatcraft.plugin.listener.PlayerListener;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

import java.io.*;
import java.util.logging.Level;

public class ChatCraftPluginBukkit extends JavaPlugin {
    public static final String CHANNEL = "chatcraft:features";

    public static final Gson GSON_NON_PRETTY = new GsonBuilder().enableComplexMapKeySerialization().disableHtmlEscaping().create();
    public static final Gson GSON_PRETTY = new GsonBuilder().enableComplexMapKeySerialization().disableHtmlEscaping().setPrettyPrinting().create();

    private Conf conf;

    @Override
    public void onEnable() {

        if (!this.getDataFolder().exists()) {
            if (!this.getDataFolder().mkdir()) {
                this.getLogger().log(Level.SEVERE, "Failed to create plugin directory.");
            }
        }

        try {
            this.conf = loadConf(new File(this.getDataFolder(), "config.json"));

            // Register channel
            this.getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);

            // Only register the listener if the config loads successfully
            this.getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

            this.getLogger().log(Level.INFO, "Successfully setup ChatCraftPluginBukkit plugin.");
        } catch (IOException e) {
            this.getLogger().log(Level.SEVERE, "Error with config for ChatCraftPluginBukkit plugin.");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {

    }

    public Conf loadConf(File file) throws IOException {
        try {
            Reader reader = new BufferedReader(new FileReader(file));
            return ChatCraftPluginBukkit.GSON_NON_PRETTY.fromJson(reader, Conf.class);
        } catch (FileNotFoundException ex) {
            this.getLogger().log(Level.INFO,"No Config Found: Saving default...");
            Conf conf = new Conf();
            this.saveConf(conf, new File(this.getDataFolder(), "config.json"));
            return conf;
        }
    }

    private void saveConf(Conf conf, File file) {
        try {
            FileWriter writer = new FileWriter(file);
            ChatCraftPluginBukkit.GSON_PRETTY.toJson(conf, writer);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public Conf getConf() {
        return this.conf;
    }

}