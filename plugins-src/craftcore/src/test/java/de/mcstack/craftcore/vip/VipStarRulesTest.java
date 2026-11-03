package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Nether star of the VIP menu: never dropped, moved, cloned or sold. */
class VipStarRulesTest {
    @Test void taggedStarIsNeverDropped() {
        assertFalse(VipStarRules.mayDrop(true));
        assertTrue(VipStarRules.mayDrop(false), "normale Nethersterne bleiben unberuehrt");
    }

    @Test void taggedStarCannotBeMovedByClick() {
        assertTrue(VipStarRules.blocksClick(true, false, false), "Klick auf den Stern");
        assertTrue(VipStarRules.blocksClick(false, true, false), "Stern auf dem Cursor");
        assertTrue(VipStarRules.blocksClick(false, false, true), "Zahlentaste in die Hotbar");
        assertFalse(VipStarRules.blocksClick(false, false, false));
    }

    @Test void taggedStarCannotBeDragged() {
        assertTrue(VipStarRules.blocksDrag(true, false), "Stern auf dem Cursor");
        assertTrue(VipStarRules.blocksDrag(false, true), "Stern in den gezogenen Slots");
        assertFalse(VipStarRules.blocksDrag(false, false));
    }

    @Test void creativeCloneIsBlocked() {
        assertTrue(VipStarRules.blocksCreativeClone(true, true), "Mittelklick im Kreativmodus");
        assertFalse(VipStarRules.blocksCreativeClone(true, false));
        assertFalse(VipStarRules.blocksCreativeClone(false, true));
    }

    @Test void deathKeepsTheStar() {
        assertTrue(VipStarRules.survivesDeath(true), "der Stern bleibt im Inventar und gehoert nicht in die Drops");
        assertFalse(VipStarRules.survivesDeath(false));
    }

    @Test void offhandSwapAndFramesAreBlocked() {
        assertFalse(VipStarRules.maySwapHands(true, false));
        assertFalse(VipStarRules.maySwapHands(false, true));
        assertTrue(VipStarRules.maySwapHands(false, false));
        assertFalse(VipStarRules.mayTransfer(true), "Item-Frame, Trichter, Container");
        assertTrue(VipStarRules.mayTransfer(false));
    }

    @Test void marketSellAndBankRefuseTheStar() {
        assertFalse(VipStarRules.sellable(true), "Market, /sell und Bank duerfen den Stern nicht ankaufen");
        assertTrue(VipStarRules.sellable(false));
    }

    @Test void joinRemovesLeftoverMenuStars() {
        assertTrue(VipStarRules.stripOnJoin(true), "alte Stern-Items werden beim Join entfernt");
        assertFalse(VipStarRules.stripOnJoin(false), "normale Nethersterne bleiben im Inventar");
        assertEquals("vip_menu_star", VipStarRules.KEY);
    }
}
