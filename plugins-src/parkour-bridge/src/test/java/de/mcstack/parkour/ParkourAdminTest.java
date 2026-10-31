package de.mcstack.parkour;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParkourAdminTest {
 @Test void parsesCoursesAndConfirmation() {
  String[] args={"reset","Cave","Rush","confirm"};
  assertTrue(ParkourAdmin.confirmed(args));
  assertEquals("Cave Rush",ParkourAdmin.course(args,1,args.length-1));
  assertEquals("Cave Rush",ParkourAdmin.course(new String[]{"times","\"Cave","Rush\""},1,3));
  assertFalse(ParkourAdmin.confirmed(new String[]{"reset","Cave","Rush"}));
 }
 @Test void variantsAndTime() {
  assertTrue(ParkourAdmin.variants("Cave Rush").containsAll(java.util.Set.of("cave rush","cave_rush")));
  assertEquals("1:23.456",ParkourAdmin.format(83456));
 }
}
