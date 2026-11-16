package app.chatcraft.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VerifyChannelListenerTest {

    @Test
    public void channelIsLegacyBelow113() {
        assertEquals(VerifyChannelListener.LEGACY_CHANNEL, VerifyChannelListener.channelNameFor("1.8.8-R0.1-SNAPSHOT"));
        assertEquals(VerifyChannelListener.LEGACY_CHANNEL, VerifyChannelListener.channelNameFor("1.8-R0.1-SNAPSHOT"));
        assertEquals(VerifyChannelListener.LEGACY_CHANNEL, VerifyChannelListener.channelNameFor("1.12.2-R0.1-SNAPSHOT"));
    }

    @Test
    public void channelIsNamespacedFrom113Up() {
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor("1.13-R0.1-SNAPSHOT"));
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor("1.13.2-R0.1-SNAPSHOT"));
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor("1.20.6-R0.1-SNAPSHOT"));
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor("1.21.11-R0.1-SNAPSHOT"));
    }

    @Test
    public void unparseableVersionFallsBackToModern() {
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor("garbage"));
        assertEquals(VerifyChannelListener.MODERN_CHANNEL, VerifyChannelListener.channelNameFor(null));
    }
}
