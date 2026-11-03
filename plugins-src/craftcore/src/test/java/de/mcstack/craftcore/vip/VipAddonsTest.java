package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class VipAddonsTest {
    @Test void catalogHasFiveRules() {
        assertEquals(5, VipAddons.CATALOG.size());
        assertEquals(3, VipAddons.MAX);
        for (VipAddons.Addon addon : VipAddons.CATALOG) {
            assertTrue(VipAddons.valid(addon.id()));
            assertFalse(addon.name().isBlank());
            assertFalse(addon.icon().isBlank());
        }
        assertFalse(VipAddons.valid("nope"));
        assertFalse(VipAddons.valid(null));
    }

    @Test void sanitizeDropsUnknownDuplicatesAndCapsAtThree() {
        assertEquals(List.of("keep-inventory", "peaceful"), VipAddons.sanitize(List.of("peaceful", "nope", "keep-inventory", "peaceful")));
        assertEquals(List.of("keep-inventory", "peaceful", "creative"),
            VipAddons.sanitize(List.of("pvp", "creative", "peaceful", "keep-inventory")));
        assertTrue(VipAddons.sanitize(null).isEmpty());
    }

    @Test void toggleAddsRemovesAndRespectsTheCap() {
        List<String> selected = List.of();
        selected = VipAddons.toggle(selected, "keep-inventory");
        assertEquals(List.of("keep-inventory"), selected);
        selected = VipAddons.toggle(selected, "pvp");
        assertEquals(List.of("keep-inventory", "pvp"), selected);
        selected = VipAddons.toggle(selected, "keep-inventory");
        assertEquals(List.of("pvp"), selected);
        List<String> full = List.of("keep-inventory", "peaceful", "creative");
        assertEquals(full, VipAddons.toggle(full, "pvp"), "a fourth addon is refused");
        assertFalse(VipAddons.canAdd(full, "pvp"));
        assertTrue(VipAddons.canAdd(List.of("pvp"), "peaceful"));
        assertFalse(VipAddons.canAdd(List.of("pvp"), "pvp"));
    }

    @Test void selectedAndFind() {
        assertTrue(VipAddons.selected(List.of("pvp"), "pvp"));
        assertFalse(VipAddons.selected(List.of(), "pvp"));
        assertEquals("day-lock", VipAddons.find("day-lock").id());
        assertNull(VipAddons.find("nope"));
    }
}
