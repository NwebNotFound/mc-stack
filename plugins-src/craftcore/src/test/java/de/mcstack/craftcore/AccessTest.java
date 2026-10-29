package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AccessTest {
 @Test void ownerAndCurrentTeamCanAccess() {
  assertTrue(Access.allowed("owner","owner",null,null));
  assertTrue(Access.allowed("member","owner","12","12"));
  assertFalse(Access.allowed("stranger","owner","12","13"));
  assertFalse(Access.allowed("stranger","owner",null,null));
  assertFalse(Access.allowed("former","owner","12",null));
 }
}
