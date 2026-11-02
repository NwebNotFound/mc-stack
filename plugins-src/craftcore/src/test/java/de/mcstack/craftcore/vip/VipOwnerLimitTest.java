package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The player cap of a world follows the <em>owner's</em> rank, even when the owner is offline
 * and LuckPerms has not cached the user.
 */
class VipOwnerLimitTest {
    @Test void offlineMvpOwnerKeepsTheLimitOfTen() {
        String rank = VipPerks.resolveGroups(() -> null, () -> List.of("default", "mvp"));
        assertEquals("mvp", rank, "die Gruppen muessen nachgeladen werden, wenn der Owner offline ist");
        assertEquals(10, VipLimits.playerLimit(rank, 5, 10, 10));
    }

    @Test void cachedRankWinsWithoutLoading() {
        String rank = VipPerks.resolveGroups(() -> List.of("vip"), () -> { throw new IllegalStateException("nicht laden"); });
        assertEquals("vip", rank);
        assertEquals(5, VipLimits.playerLimit(rank, 5, 10, 10));
    }

    @Test void adminBeatsMvpAndVip() {
        assertEquals("admin", VipPerks.resolveGroups(() -> null, () -> List.of("vip", "mvp", "admin")));
    }

    @Test void unknownOwnerFallsBackToTheVipLimit() {
        String rank = VipPerks.resolveGroups(() -> null, () -> List.of("default"));
        assertEquals("", rank);
        assertEquals(0, VipLimits.playerLimit(rank, 5, 10, 10));
    }

    @Test void failingLoaderNeverOverstatesTheLimit() {
        String rank = VipPerks.resolveGroups(() -> null, () -> { throw new RuntimeException("LuckPerms weg"); });
        assertEquals("", rank);
        assertEquals(0, VipLimits.playerLimit(rank, 5, 10, 10));
    }
}
