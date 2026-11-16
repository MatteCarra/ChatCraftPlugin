package app.chatcraft.plugin;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AddressesTest {

    @Test
    public void normalisesCaseAndOneTrailingDot() {
        assertEquals("play.example.com:25565", Addresses.key("Play.Example.com.", 25565));
        assertEquals("play.example.com:25565", Addresses.key("play.example.com", 25565));
    }

    @Test
    public void keepsExplicitPort() {
        assertEquals("a.b:1234", Addresses.key("a.b", 1234));
    }

    @Test(expected = IllegalArgumentException.class)
    public void blankHostThrows() {
        Addresses.key("   ", 25565);
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyHostThrows() {
        Addresses.key("", 25565);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullHostThrows() {
        Addresses.key(null, 25565);
    }

    @Test(expected = IllegalArgumentException.class)
    public void portZeroThrows() {
        Addresses.key("a.b", 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void portTooLargeThrows() {
        Addresses.key("a.b", 65536);
    }

    @Test
    public void parseDefaultsToPort25565() {
        Addresses.Address a = Addresses.parse("Play.Example.com.");
        assertEquals("play.example.com", a.host);
        assertEquals(25565, a.port);
        assertEquals("play.example.com:25565", Addresses.key("Play.Example.com."));
    }

    @Test
    public void parseExplicitPort() {
        Addresses.Address a = Addresses.parse("a.b:1234");
        assertEquals("a.b", a.host);
        assertEquals(1234, a.port);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseBlankThrows() {
        Addresses.parse("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseBadPortThrows() {
        Addresses.parse("a.b:notaport");
    }
}
