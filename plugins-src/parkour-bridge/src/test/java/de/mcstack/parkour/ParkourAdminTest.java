package de.mcstack.parkour;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParkourAdminTest {
 private final List<CourseArgs.Course> courses=List.of(
  new CourseArgs.Course(4,"Cave Rush","cave_rush","Cave Rush"),
  new CourseArgs.Course(5,"House Climb","house_climb","House"),
  new CourseArgs.Course(6,"New Parkour 01.11.26 16:27:08","new_parkour_01.11.26_16:27:08","Sky Run"));

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
 @Test void resolvesCourseByIdSlugNameAndDisplay() {
  assertEquals("House Climb",ParkourAdmin.match("5",courses).pkName());
  assertEquals("House Climb",ParkourAdmin.match("HOUSE climb",courses).pkName());
  assertEquals("House Climb",ParkourAdmin.match("house_climb",courses).pkName());
  assertEquals("New Parkour 01.11.26 16:27:08",ParkourAdmin.match("sky_run",courses).pkName());
  assertEquals("Cave Rush",ParkourAdmin.match("\"Cave Rush\"",courses).pkName());
  assertNull(ParkourAdmin.match("missing",courses));
  assertNull(ParkourAdmin.match(" ",courses));
 }
 @Test void splitsPlayerAfterResolvedCourse() {
  String[] withPlayer={"reset","Cave","Rush","Steve","confirm"};
  var target=ParkourAdmin.select(withPlayer,withPlayer.length-1,true,courses);
  assertEquals("Cave Rush",target.course().pkName());
  assertEquals(4,target.course().id());
  assertEquals("Steve",target.player());
  String[] whole={"reset","house_climb","confirm"};
  var all=ParkourAdmin.select(whole,whole.length-1,true,courses);
  assertEquals("House Climb",all.course().pkName());
  assertNull(all.player());
  String[] listed={"times","sky","run"};
  var times=ParkourAdmin.select(listed,listed.length,false,courses);
  assertEquals("New Parkour 01.11.26 16:27:08",times.course().pkName());
  assertNull(times.player());
  assertNull(ParkourAdmin.select(new String[]{"reset","missing","Steve"},3,true,courses).course());
 }
 @Test void completesSlugsPlayersAndConfirm() {
  var players=Map.of("house_climb",List.of("Steve","Connor"),"cave_rush",List.of("Steve"));
  assertEquals(List.of("house_climb"),ParkourAdmin.complete(new String[]{"times","ho"},courses,players));
  assertEquals(List.of("house_climb"),ParkourAdmin.complete(new String[]{"setboard","Ho"},courses,players));
  assertEquals(List.of("confirm"),ParkourAdmin.complete(new String[]{"resetall","con"},courses,players));
  assertEquals(List.of("confirm","Connor"),ParkourAdmin.complete(new String[]{"reset","house_climb","con"},courses,players));
  assertEquals(List.of("Steve"),ParkourAdmin.complete(new String[]{"removetime","5","St"},courses,players));
  assertEquals(List.of("confirm"),ParkourAdmin.complete(new String[]{"reset","Cave","Rush","Steve","c"},courses,players));
  assertEquals(List.of(),ParkourAdmin.complete(new String[]{"times","house_climb","Steve"},courses,players));
  assertEquals(List.of(),ParkourAdmin.complete(new String[]{"resetall","nope"},courses,players));
 }
}
