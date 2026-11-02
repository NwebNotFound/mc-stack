package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

/** Every addon has its own verifiable effect, and removing creative or peaceful gives the state back. */
class VipAddonRulesTest {
    @Test void creativeAddonGrantsCreativeOnceAndLeavesGamemodeAlone() {
        assertEquals(GameMode.CREATIVE, VipAddonRules.grant(true, false, GameMode.SURVIVAL));
        assertNull(VipAddonRules.grant(true, true, GameMode.SURVIVAL),
            "nach dem Aktivieren muss /gamemode wirken, nicht zurueckgesetzt werden");
        assertNull(VipAddonRules.grant(true, true, GameMode.ADVENTURE));
        assertNull(VipAddonRules.grant(true, false, GameMode.SPECTATOR), "Spectator bleibt unangetastet");
        assertNull(VipAddonRules.grant(false, false, GameMode.SURVIVAL), "ohne Addon wird nichts gesetzt");
    }

    @Test void removingCreativeGivesTheModeBack() {
        assertEquals(GameMode.SURVIVAL, VipAddonRules.revoke(true, GameMode.CREATIVE, GameMode.SURVIVAL));
        assertEquals(GameMode.ADVENTURE, VipAddonRules.revoke(true, GameMode.CREATIVE, GameMode.ADVENTURE));
        assertEquals(GameMode.SURVIVAL, VipAddonRules.revoke(true, GameMode.CREATIVE, null));
        assertNull(VipAddonRules.revoke(true, GameMode.SURVIVAL, GameMode.SURVIVAL), "kein fremdes Kreativ zuruecksetzen");
        assertNull(VipAddonRules.revoke(false, GameMode.CREATIVE, GameMode.SURVIVAL));
    }

    @Test void keepInventoryAddonOnlyTouchesKeepInventory() {
        VipAddonRules.Effects none = VipAddonRules.world(List.of());
        VipAddonRules.Effects one = VipAddonRules.world(List.of("keep-inventory"));
        assertFalse(none.keepInventory());
        assertTrue(one.keepInventory());
        assertEquals(none.daylightCycle(), one.daylightCycle());
        assertEquals(none.mobSpawning(), one.mobSpawning());
        assertEquals(none.pvp(), one.pvp());
        assertFalse(one.pinNoon());
    }

    @Test void peacefulAddonStopsMobSpawningAndTakesTheDifficulty() {
        VipAddonRules.Effects one = VipAddonRules.world(List.of("peaceful"));
        assertFalse(one.mobSpawning());
        assertEquals(Difficulty.PEACEFUL, VipAddonRules.difficulty(true, Difficulty.HARD, Difficulty.NORMAL));
    }

    @Test void dayLockAddonStopsTheDaylightCycleAndPinsNoon() {
        VipAddonRules.Effects one = VipAddonRules.world(List.of("day-lock"));
        assertFalse(one.daylightCycle());
        assertTrue(one.pinNoon());
    }

    @Test void pvpAddonEnablesPlayerDamage() {
        assertFalse(VipAddonRules.world(List.of()).pvp());
        assertTrue(VipAddonRules.world(List.of("pvp")).pvp());
    }

    @Test void peacefulOwnsTheDifficultyAndGivesItBack() {
        assertEquals(Difficulty.PEACEFUL, VipAddonRules.difficulty(true, Difficulty.HARD, Difficulty.NORMAL));
        assertEquals(Difficulty.HARD, VipAddonRules.difficulty(false, Difficulty.HARD, Difficulty.PEACEFUL),
            "die vorher eingestellte Schwierigkeit kommt zurueck statt immer NORMAL");
        assertEquals(Difficulty.NORMAL, VipAddonRules.difficulty(false, null, Difficulty.PEACEFUL));
        assertEquals(Difficulty.HARD, VipAddonRules.difficulty(false, null, Difficulty.HARD),
            "ohne Addon wird die Schwierigkeit nicht ueberschrieben");
    }

    @Test void everyCatalogAddonHasAVerifiableEffect() {
        VipAddonRules.Effects none = VipAddonRules.world(List.of());
        for (VipAddons.Addon addon : VipAddons.CATALOG) {
            boolean changesWorld = !VipAddonRules.world(List.of(addon.id())).equals(none);
            boolean changesMode = addon.id().equals("creative")
                && VipAddonRules.grant(true, false, GameMode.SURVIVAL) != null;
            boolean changesDifficulty = addon.id().equals("peaceful");
            assertTrue(changesWorld || changesMode || changesDifficulty, "Addon ohne Wirkung: " + addon.id());
        }
    }
}
