package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class ChallengePeriodsTest {
 @Test void mondayReset(){ZoneId z=ZoneId.of("Europe/Berlin");LocalDate before=ChallengePeriods.day(Instant.parse("2026-10-26T03:59:59Z"),z,5),after=ChallengePeriods.day(Instant.parse("2026-10-26T04:00:00Z"),z,5);assertEquals("D:2026-10-25",ChallengePeriods.period(before,false));assertEquals("W:2026-10-19",ChallengePeriods.period(before,true));assertEquals("D:2026-10-26",ChallengePeriods.period(after,false));assertEquals("W:2026-10-26",ChallengePeriods.period(after,true));}
 @Test void daylightSaving(){ZoneId z=ZoneId.of("Europe/Berlin");assertEquals(LocalDate.parse("2026-03-28"),ChallengePeriods.day(Instant.parse("2026-03-29T02:59:59Z"),z,5));assertEquals(LocalDate.parse("2026-03-29"),ChallengePeriods.day(Instant.parse("2026-03-29T03:00:00Z"),z,5));}
}
