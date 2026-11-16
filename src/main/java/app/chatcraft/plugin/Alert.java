package app.chatcraft.plugin;

import com.google.gson.JsonObject;

/**
 * One staff alert (owner program stage 2): a player's /report or a console `chatcraft alert`.
 * `aid` is a random 8-byte hex id that dedupes the in-session and the push copy in the app.
 * `target` is empty for console alerts, otherwise the reported player's name.
 */
public class Alert {
    public final String aid;
    public final long ts; // epoch seconds
    public final String source; // "report" | "console"
    public final String from;
    public final String text;
    public final String target;

    public Alert(String aid, long ts, String source, String from, String text, String target) {
        this.aid = aid;
        this.ts = ts;
        this.source = source;
        this.from = from;
        this.text = text;
        this.target = target == null ? "" : target;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("aid", aid);
        o.addProperty("ts", ts);
        o.addProperty("source", source);
        o.addProperty("from", from);
        o.addProperty("text", text);
        o.addProperty("target", target);
        return o;
    }
}
