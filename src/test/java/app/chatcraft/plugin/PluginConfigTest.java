package app.chatcraft.plugin;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PluginConfigTest {

    private static PluginConfig config(final Map<String, Object> values) {
        return new PluginConfig(new PluginConfig.ConfigSource() {
            @Override
            public Object get(String path) {
                return values.get(path);
            }
        }, Logger.getLogger("PluginConfigTest"));
    }

    private static Map<String, Object> map() {
        return new HashMap<String, Object>();
    }

    @Test
    public void defaultsWhenNothingConfigured() {
        PluginConfig c = config(map());
        assertTrue(c.isListing());
        assertTrue(c.getLanguages().isEmpty());
        assertNull(c.getAddress());
        assertTrue(c.getVoteLinks().isEmpty());
    }

    @Test
    public void listingFalseIsRespected() {
        Map<String, Object> m = map();
        m.put("listing", Boolean.FALSE);
        assertFalse(config(m).isListing());
    }

    @Test
    public void invalidListingFallsBackToDefaultTrue() {
        Map<String, Object> m = map();
        m.put("listing", "yes");
        assertTrue(config(m).isListing());
    }

    @Test
    public void languagesStoredAsLowercasePrimarySubtag() {
        Map<String, Object> m = map();
        m.put("languages", Arrays.asList("pt-BR", "zh-Hans"));
        assertEquals(Arrays.asList("pt", "zh"), config(m).getLanguages());
    }

    @Test
    public void duplicateLanguagesAfterNormalisationAreMerged() {
        Map<String, Object> m = map();
        m.put("languages", Arrays.asList("pt-BR", "PT"));
        assertEquals(Arrays.asList("pt"), config(m).getLanguages());
    }

    @Test
    public void invalidLanguagesAreDropped() {
        Map<String, Object> m = map();
        m.put("languages", Arrays.asList("en", "   ", "123", ""));
        assertEquals(Arrays.asList("en"), config(m).getLanguages());
    }

    @Test
    public void httpVoteLinkIsDropped() {
        Map<String, Object> m = map();
        m.put("vote-links", Arrays.asList("http://vote.example.com", "https://vote.example.com"));
        assertEquals(Arrays.asList("https://vote.example.com"), config(m).getVoteLinks());
    }

    @Test
    public void moreThanThreeVoteLinksAreTruncated() {
        Map<String, Object> m = map();
        m.put("vote-links", Arrays.asList(
                "https://a.example.com", "https://b.example.com",
                "https://c.example.com", "https://d.example.com"));
        assertEquals(Arrays.asList(
                "https://a.example.com", "https://b.example.com", "https://c.example.com"),
                config(m).getVoteLinks());
    }

    @Test
    public void nonListVoteLinksAreIgnored() {
        Map<String, Object> m = map();
        m.put("vote-links", "https://vote.example.com");
        assertTrue(config(m).getVoteLinks().isEmpty());
    }

    @Test
    public void addressIsParsedAndNormalised() {
        Map<String, Object> m = map();
        m.put("address", "Play.Example.com.:25566");
        Addresses.Address a = config(m).getAddress();
        assertEquals("play.example.com", a.host);
        assertEquals(25566, a.port);
    }

    @Test
    public void invalidAddressFallsBackToNull() {
        Map<String, Object> m = map();
        m.put("address", "a.b:notaport");
        assertNull(config(m).getAddress());
    }
}
