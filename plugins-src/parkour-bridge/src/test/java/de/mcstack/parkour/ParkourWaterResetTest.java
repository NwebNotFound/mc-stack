package de.mcstack.parkour;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wasser-Reset in {@link ParkourBridge#move}: das Hineinlaufen in eine Stroemung ist eben, der
 * Y-Wert aendert sich dabei nicht. Die Wasserpruefung muss deshalb vor dem Y-Early-Return laufen
 * und ohne Server auskommen.
 */
class ParkourWaterResetTest {
    private final ParkourBridge bridge = Mockito.mock(ParkourBridge.class, Mockito.CALLS_REAL_METHODS);

    private boolean triggered(String mode, Material type, BlockData data, boolean touching, double y) {
        FileConfiguration config = mock(FileConfiguration.class);
        when(config.getString("water-reset")).thenReturn(mode);
        Mockito.doReturn(config).when(bridge).getConfig();
        Block feet = mock(Block.class);
        when(feet.getType()).thenReturn(type);
        when(feet.getY()).thenReturn((int) Math.floor(y));
        when(feet.getBlockData()).thenReturn(data);
        Location to = mock(Location.class);
        when(to.getBlock()).thenReturn(feet);
        when(to.getY()).thenReturn(y);
        Player player = mock(Player.class);
        when(player.isInWater()).thenReturn(touching);
        return bridge.waterTriggered(player, to);
    }

    @Test
    void levelWalkIntoFlowingWaterResets() {
        Levelled water = mock(Levelled.class);
        when(water.getLevel()).thenReturn(8);
        assertTrue(triggered("feet", Material.WATER, water, false, 64.0),
            "ebenes Hineinlaufen in eine Stroemung muss zuruecksetzen");
    }

    @Test
    void shallowWaterSurfaceStillResetsOnlyBelowSurface() {
        Levelled water = mock(Levelled.class);
        when(water.getLevel()).thenReturn(0);
        assertTrue(triggered("feet", Material.WATER, water, false, 64.0));
        assertFalse(triggered("feet", Material.WATER, water, false, 64.95),
            "ueber der Oberflaeche kein Reset");
    }

    @Test
    void waterloggedFeetBlockResetsInFeetMode() {
        Waterlogged stairs = mock(Waterlogged.class);
        when(stairs.isWaterlogged()).thenReturn(true);
        assertTrue(triggered("feet", Material.OAK_STAIRS, stairs, false, 64.0));
    }

    @Test
    void dryGroundDoesNotReset() {
        assertFalse(triggered("feet", Material.STONE, mock(BlockData.class), false, 64.0));
    }

    @Test
    void offModeAndTouchModeWithoutContactDoNotReset() {
        Levelled water = mock(Levelled.class);
        when(water.getLevel()).thenReturn(8);
        assertFalse(triggered("off", Material.WATER, water, true, 64.0));
        assertFalse(triggered("touch", Material.STONE, mock(BlockData.class), false, 64.0));
        assertTrue(triggered("touch", Material.STONE, mock(BlockData.class), true, 64.0));
    }

    @Test
    void touchModeSkipsBlockDataWhenNothingIsWet() {
        assertFalse(triggered("touch", Material.STONE, null, false, 64.0),
            "ohne Wasser und ohne Kontakt wird kein Blockdatum gelesen");
    }
}
