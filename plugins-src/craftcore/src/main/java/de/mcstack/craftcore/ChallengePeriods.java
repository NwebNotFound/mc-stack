package de.mcstack.craftcore;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
final class ChallengePeriods {
  static LocalDate day(Instant now,ZoneId zone,int hour){ZonedDateTime t=now.atZone(zone); LocalDate d=t.toLocalDate();return t.toLocalTime().isBefore(LocalTime.of(hour,0))?d.minusDays(1):d;}
  static String period(LocalDate day,boolean weekly){return (weekly?"W:":"D:")+(weekly?day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)):day);}
}
