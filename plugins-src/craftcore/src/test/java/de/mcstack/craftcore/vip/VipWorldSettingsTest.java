package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** The six rules: contract defaults, partial updates, strict browser input, tolerant sync. */
class VipWorldSettingsTest {
    @Test void defaultsMatchTheContract() {
        assertEquals("survival", VipWorldSettings.DEFAULTS.gamemode());
        assertEquals("normal", VipWorldSettings.DEFAULTS.difficulty());
        assertTrue(VipWorldSettings.DEFAULTS.pvp());
        assertFalse(VipWorldSettings.DEFAULTS.keepInventory());
        assertTrue(VipWorldSettings.DEFAULTS.mobGriefing());
        assertTrue(VipWorldSettings.DEFAULTS.daylightCycle());
    }

    @Test void mergeKeepsUntouchedKeys() throws Exception {
        JsonObject partial = new JsonObject();
        partial.addProperty("keepInventory", true);
        partial.addProperty("difficulty", "hard");
        VipWorldSettings out = VipWorldSettings.DEFAULTS.merge(partial);
        assertTrue(out.keepInventory());
        assertEquals("hard", out.difficulty());
        assertEquals("survival", out.gamemode(), "unberührte Regel bleibt");
        assertTrue(out.pvp());
    }

    @Test void unknownKeyAndBadValueAreErrorsForBrowserInput() {
        JsonObject unknown = new JsonObject();
        unknown.addProperty("weather", "rain");
        assertThrows(VipWorldSettings.InvalidSettingException.class,
            () -> VipWorldSettings.DEFAULTS.merge(unknown));

        JsonObject wrongType = new JsonObject();
        wrongType.addProperty("pvp", "yes");
        assertThrows(VipWorldSettings.InvalidSettingException.class,
            () -> VipWorldSettings.DEFAULTS.merge(wrongType));

        JsonObject badDifficulty = new JsonObject();
        badDifficulty.addProperty("difficulty", "insane");
        assertThrows(VipWorldSettings.InvalidSettingException.class,
            () -> VipWorldSettings.DEFAULTS.merge(badDifficulty));
    }

    @Test void syncDropsUnknownKeysButKeepsTheWorld() {
        JsonObject raw = new JsonObject();
        raw.addProperty("pvp", false);
        raw.addProperty("weather", "rain");
        VipWorldSettings out = VipWorldSettings.tolerant(raw);
        assertFalse(out.pvp());
        assertEquals("survival", out.gamemode());
    }

    @Test void jsonRoundTrip() {
        VipWorldSettings settings = new VipWorldSettings("creative", "easy", false, true, false, false);
        assertEquals(settings, VipWorldSettings.fromJson(settings.toJson()));
        assertEquals(6, settings.toJson().size());
    }
}
