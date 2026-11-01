package de.mcstack.craftcore;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebsiteStatsTest {
  @Test void unknownIsNotExcludedButEntityCollisionsRemainProtected() {
    String ids=WebsiteStats.entityIdsSql(Stream.of("UNKNOWN","ZOMBIE","CREEPER"));
    assertEquals("'zombie','creeper'",ids);
    String sql=WebsiteStats.detailsSql(ids);
    assertTrue(sql.contains("NOT IN ('zombie','creeper')"));
    assertTrue(sql.contains("NOT EXISTS"));
    assertTrue(sql.contains("'blocks_mined'"));
    assertTrue(sql.contains("'kills_entity'"));
  }
}
