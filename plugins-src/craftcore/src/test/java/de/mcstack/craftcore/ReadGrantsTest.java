package de.mcstack.craftcore;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadGrantsTest {
  @Test void detectsGrantOptionCaseInsensitively() {
    assertTrue(ReadGrants.hasGrantOption(List.of("GRANT SELECT ON *.* TO 'user'@'%'", "GRANT ALL ON *.* TO 'user'@'%' WITH GRANT OPTION")));
    assertTrue(ReadGrants.hasGrantOption(List.of("grant all on *.* to 'user'@'%' with grant option")));
    assertFalse(ReadGrants.hasGrantOption(List.of("GRANT SELECT ON *.* TO 'user'@'%'")));
    assertFalse(ReadGrants.hasGrantOption(List.of()));
  }
}
