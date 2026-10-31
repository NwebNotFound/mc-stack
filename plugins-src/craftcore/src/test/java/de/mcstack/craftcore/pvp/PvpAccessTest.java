package de.mcstack.craftcore.pvp;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PvpAccessTest {
    @Test void eachPermissionAllowsBypass() {
        for (String permission : Set.of("craftcore.build.pvp", "group.builder", "group.admin")) {
            assertTrue(PvpAccess.mayBypass(permission::equals));
        }
    }

    @Test void otherPermissionsDoNotAllowBypass() {
        assertFalse(PvpAccess.mayBypass(Set.of("group.member")::contains));
    }
}
