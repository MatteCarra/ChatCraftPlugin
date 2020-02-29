package mattecarra.chatcraft.plugin;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class Conf {

    private Map<String, DisallowedFeature> modsDisallowed =  new HashMap<String, DisallowedFeature>();


    public Map<String, DisallowedFeature> getDisallowedFeatures() {
        return this.modsDisallowed;
    }

    private class DisallowedFeature {

        private boolean disabled;
        private JsonObject extra_data;
    }
}
