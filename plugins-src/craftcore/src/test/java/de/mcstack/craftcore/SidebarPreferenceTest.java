package de.mcstack.craftcore;
import java.sql.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SidebarPreferenceTest {
 @Test void preferenceSurvivesConnectionsAndIsPerPlayer() throws Exception {
  String url="jdbc:h2:mem:sidebar;DB_CLOSE_DELAY=-1";
  try(Connection c=DriverManager.getConnection(url)){SidebarPreference.prepare(c);assertTrue(SidebarPreference.enabled(c,"one"));SidebarPreference.set(c,"one",false);}
  try(Connection c=DriverManager.getConnection(url)){assertFalse(SidebarPreference.enabled(c,"one"));assertTrue(SidebarPreference.enabled(c,"two"));SidebarPreference.set(c,"one",true);assertTrue(SidebarPreference.enabled(c,"one"));}
 }
}
