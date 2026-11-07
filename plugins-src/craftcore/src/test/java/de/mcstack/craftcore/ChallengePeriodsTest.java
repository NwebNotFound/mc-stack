package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.time.*;
import org.bukkit.Material;
import static org.junit.jupiter.api.Assertions.*;
class ChallengePeriodsTest {
 @Test void mondayReset(){ZoneId z=ZoneId.of("Europe/Berlin");LocalDate before=ChallengePeriods.day(Instant.parse("2026-10-26T03:59:59Z"),z,5),after=ChallengePeriods.day(Instant.parse("2026-10-26T04:00:00Z"),z,5);assertEquals("D:2026-10-25",ChallengePeriods.period(before,false));assertEquals("W:2026-10-19",ChallengePeriods.period(before,true));assertEquals("D:2026-10-26",ChallengePeriods.period(after,false));assertEquals("W:2026-10-26",ChallengePeriods.period(after,true));}
 @Test void daylightSaving(){ZoneId z=ZoneId.of("Europe/Berlin");assertEquals(LocalDate.parse("2026-03-28"),ChallengePeriods.day(Instant.parse("2026-03-29T02:59:59Z"),z,5));assertEquals(LocalDate.parse("2026-03-29"),ChallengePeriods.day(Instant.parse("2026-03-29T03:00:00Z"),z,5));}
 @Test void silkTouchOreDoesNotCountAsMined(){assertTrue(Challenges.minedIron(Material.IRON_ORE,false));assertTrue(Challenges.minedIron(Material.DEEPSLATE_IRON_ORE,false));assertFalse(Challenges.minedIron(Material.IRON_ORE,true),"place and break with Silk Touch is farmable (#158)");assertFalse(Challenges.minedIron(Material.STONE,false));}
}
