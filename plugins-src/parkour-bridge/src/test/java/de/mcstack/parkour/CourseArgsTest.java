package de.mcstack.parkour;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CourseArgsTest {
 private final Map<String,String> names=Map.of("cave_rush","Cave Rush", "cave","Cave");
 @Test void resolvesNamesSlugsAndQuotes() {
  assertEquals("cave_rush",CourseArgs.resolve(List.of("Cave","Rush"),names));
  assertEquals("cave_rush",CourseArgs.resolve(List.of("\"Cave","Rush\""),names));
  assertEquals("cave_rush",CourseArgs.resolve(List.of("CAVE_RUSH"),names));
  assertEquals("cave_rush",CourseArgs.resolve(List.of("cAvE","rUsH"),names));
  assertNull(CourseArgs.resolve(List.of("unknown"),names));
 }
 @Test void renameUsesLongestPrefix() {
  assertEquals(new CourseArgs.Match("cave_rush",2),CourseArgs.longestPrefix(List.of("Cave","Rush","New","Name"),names));
  assertEquals(new CourseArgs.Match("cave_rush",2),CourseArgs.longestPrefix(List.of("\"Cave","Rush\"","New"),names));
 }
 @Test void completesSecondWord() {
  assertEquals(List.of("Rush"),CourseArgs.complete(List.of("cave","r"),names));
 }
 private final List<CourseArgs.Course> courses=List.of(
  new CourseArgs.Course(4,"Cave Rush","cave_rush","Cave Rush"),
  new CourseArgs.Course(5,"House Climb","house_climb","house_climb"),
  new CourseArgs.Course(6,"New Parkour 01.11.26 16:27:08","new_parkour_01.11.26_16:27:08","Sky Run"));
 @Test void resolvesCourseByIdNameSlugAndDisplay() {
  assertEquals("House Climb",CourseArgs.resolveCourse("house_climb",courses));
  assertEquals("House Climb",CourseArgs.resolveCourse("HOUSE  climb",courses));
  assertEquals("House Climb",CourseArgs.resolveCourse("5",courses));
  assertEquals("New Parkour 01.11.26 16:27:08",CourseArgs.resolveCourse("sky_run",courses));
  assertEquals("Cave Rush",CourseArgs.resolveCourse("\"Cave Rush\"",courses));
  assertNull(CourseArgs.resolveCourse("2",courses));
  assertNull(CourseArgs.resolveCourse(" ",courses));
 }
 @Test void completesCourseSlugs() {
  assertEquals(List.of("house_climb"),CourseArgs.completeCourses("Ho",courses));
  assertEquals(3,CourseArgs.completeCourses("",courses).size());
 }
}
