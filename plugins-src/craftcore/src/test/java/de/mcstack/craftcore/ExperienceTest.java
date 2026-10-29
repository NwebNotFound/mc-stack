package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExperienceTest {
 @Test void halvesLevelAndProgress() {
  assertEquals(0, Experience.half(0,0,7));
  assertEquals(176, Experience.half(16,0,37));
  assertEquals(197, Experience.half(17,0,42));
  assertEquals(814, Experience.half(32,0,121));
  assertEquals(179, Experience.half(16,0.2f,37));
 }
}

