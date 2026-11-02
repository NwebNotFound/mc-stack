package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

/** Every addon has its own verifiable effect, and removing creative or peaceful gives the state back. */
class VipAddonRulesTest {
    @Test void creativeAddonGrantsOnceAndKeepsGamemodeWorking() {
        VipAddonRules.Mode first = VipAddonRules.onJoin(true, false, GameMode.SURVIVAL, null);
        assertTrue(first.change());
        assertEquals(GameMode.CREATIVE, first.target());
        assertTrue(first.mark(), "die Vergabe muss am Spieler gespeichert werden");
        assertEquals(VipAddonRules.Mode.none(), VipAddonRules.onJoin(true, true, GameMode.SURVIVAL, GameMode.SURVIVAL),
            "nach dem Aktivieren muss /gamemode wirken, nicht zurueckgesetzt werden");
        assertEquals(VipAddonRules.Mode.none(), VipAddonRules.onJoin(true, false, GameMode.SPECTATOR, null),
            "Spectator bleibt unangetastet");
        assertEquals(VipAddonRules.Mode.none(), VipAddonRules.onJoin(false, false, GameMode.SURVIVAL, null),
            "ohne Addon und ohne Markierung passiert nichts");
    }

    @Test void removingCreativeGivesTheModeBackEvenAfterARestart() {
        VipAddonRules.Mode back = VipAddonRules.onJoin(false, true, GameMode.CREATIVE, GameMode.ADVENTURE);
        assertTrue(back.change(), "nach einem Neustart ist nur noch die Markierung da");
        assertEquals(GameMode.ADVENTURE, back.target());
        assertTrue(back.clear());
        assertEquals(GameMode.SURVIVAL, VipAddonRules.onJoin(false, true, GameMode.CREATIVE, null).target());
        VipAddonRules.Mode left = VipAddonRules.onJoin(false, true, GameMode.SURVIVAL, GameMode.SURVIVAL);
        assertFalse(left.change(), "wer selbst aus Kreativ raus ist, wird nicht angefasst");
        assertTrue(left.clear(), "nur die Markierung wird aufgeraeumt");
        assertEquals(VipAddonRules.Mode.none(), VipAddonRules.onJoin(false, false, GameMode.CREATIVE, null),
            "fremdes Kreativ bleibt unangetastet");
    }

    @Test void anAdminWhoWasCreativeBeforeTheAddonIsNotTracked() {
        VipAddonRules.Mode admin = VipAddonRules.onJoin(true, false, GameMode.CREATIVE, null);
        assertFalse(admin.change(), "vorhandenes Kreativ (Admin) bleibt unangetastet");
        assertFalse(admin.mark(), "ohne eigene Vergabe darf keine Markierung entstehen");
        assertFalse(VipAddonRules.onJoin(false, false, GameMode.CREATIVE, null).change(),
            "ein nicht markierter Admin verliert beim Abschalten nichts");
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
                && VipAddonRules.onJoin(true, false, GameMode.SURVIVAL, null).change();
            boolean changesDifficulty = addon.id().equals("peaceful");
            assertTrue(changesWorld || changesMode || changesDifficulty, "Addon ohne Wirkung: " + addon.id());
        }
    }
}
