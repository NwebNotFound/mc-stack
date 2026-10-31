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
}
