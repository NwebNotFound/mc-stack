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
        assertFalse(VipStarRules.sellable(true), "Bank, Auktionshaus, Shop und Handel duerfen den Stern nicht ankaufen");
        assertTrue(VipStarRules.sellable(false));
    }

    @Test void refusalShowsTheMarketWordingForTheStarOnly() {
        assertEquals("Nicht handelbar", VipStarRules.refusal(true),
            "der Stern wird mit der Markt-Meldung abgelehnt");
        assertNull(VipStarRules.refusal(false), "normale Items werden nicht abgelehnt");
    }

    @Test void refusalIsConsistentWithSellable() {
        for (boolean tagged : new boolean[] {true, false}) {
            assertEquals(VipStarRules.sellable(tagged) ? null : "Nicht handelbar",
                VipStarRules.refusal(tagged),
                "Ablehnung und sellable() duerfen sich nie widersprechen (tagged=" + tagged + ")");
        }
    }

    @Test void joinRemovesLeftoverMenuStars() {
        assertTrue(VipStarRules.stripOnJoin(true), "alte Stern-Items werden bei Join, Respawn und Weltwechsel entfernt");
        assertFalse(VipStarRules.stripOnJoin(false), "normale Nethersterne bleiben im Inventar");
        assertEquals("vip_menu_star", VipStarRules.KEY, "der Key darf sich nicht aendern, sonst ueberleben alte Sterne");
    }

    @Test void stripSlotsCoversEveryInventoryArea() {
        boolean[] tagged = new boolean[41];
        tagged[8] = true;   // Hotbar-Slot, in dem frueher der Stern lag
        tagged[39] = true;  // Ruestung
        tagged[40] = true;  // Offhand
        assertEquals(java.util.List.of(8, 39, 40), VipStarRules.stripSlots(tagged));
        assertEquals(java.util.List.of(), VipStarRules.stripSlots(new boolean[41]), "ohne Stern wird nichts angefasst");
        assertEquals(java.util.List.of(), VipStarRules.stripSlots(new boolean[0]));
    }
}
