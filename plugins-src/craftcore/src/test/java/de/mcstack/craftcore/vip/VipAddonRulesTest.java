package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

/** Removing the creative or peaceful addon must give the previous state back. */
class VipAddonRulesTest {
    @Test void creativeAddonTurnsCreativeOn() {
        assertEquals(GameMode.CREATIVE, VipAddonRules.mode(true, false, GameMode.SURVIVAL));
        assertNull(VipAddonRules.mode(true, true, GameMode.CREATIVE), "schon kreativ: nichts tun");
    }

    @Test void removingCreativeReturnsSurvival() {
        assertEquals(GameMode.SURVIVAL, VipAddonRules.mode(false, true, GameMode.CREATIVE),
            "ohne das Addon darf niemand im Kreativmodus bleiben");
        assertNull(VipAddonRules.mode(false, true, GameMode.SURVIVAL));
        assertNull(VipAddonRules.mode(false, false, GameMode.CREATIVE), "fremdes Kreativ bleibt unangetastet");
    }

    @Test void peacefulOwnsTheDifficultyAndGivesItBack() {
        assertEquals(Difficulty.PEACEFUL, VipAddonRules.difficulty(true, Difficulty.HARD, Difficulty.NORMAL));
        assertEquals(Difficulty.HARD, VipAddonRules.difficulty(false, Difficulty.HARD, Difficulty.PEACEFUL),
            "die vorher eingestellte Schwierigkeit kommt zurueck statt immer NORMAL");
        assertEquals(Difficulty.NORMAL, VipAddonRules.difficulty(false, null, Difficulty.PEACEFUL));
        assertEquals(Difficulty.HARD, VipAddonRules.difficulty(false, null, Difficulty.HARD),
            "ohne Addon wird die Schwierigkeit nicht ueberschrieben");
    }
}
