package de.mcstack.parkour;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HoloAdminTest {
 ParkourBridge plugin; HoloAdmin admin; Player player; World world; Chunk chunk; TextDisplay display; YamlConfiguration config; Command command; UUID uuid;
 MockedStatic<Bukkit> bukkit;
 @BeforeEach void setup() {
  bukkit=mockStatic(Bukkit.class); plugin=mock(ParkourBridge.class); player=mock(Player.class); world=mock(World.class); chunk=mock(Chunk.class); display=mock(TextDisplay.class); command=mock(Command.class); config=new YamlConfiguration(); uuid=UUID.randomUUID();
  Location loc=new Location(world,1,2,3); when(world.getName()).thenReturn("world"); when(world.getChunkAt(any(Location.class))).thenReturn(chunk); when(player.getLocation()).thenReturn(loc); when(player.getEyeLocation()).thenReturn(loc); when(player.getWorld()).thenReturn(world); when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.hasPermission("craftcore.holo.admin")).thenReturn(true); when(plugin.getConfig()).thenReturn(config); when(plugin.saveConfigChecked()).thenReturn(true); config.set("boards.test.location",loc); config.set("boards.test.entity",uuid.toString()); bukkit.when(()->Bukkit.getEntity(uuid)).thenReturn(display); bukkit.when(Bukkit::getWorlds).thenReturn(List.of(world)); when(world.getLoadedChunks()).thenReturn(new Chunk[]{chunk}); when(chunk.getEntities()).thenReturn(new Entity[0]); admin=new HoloAdmin(plugin);
 }
 @AfterEach void close() { bukkit.close(); }
 @Test void listAndCompletionRespectPermission() {
  admin.onCommand(player,command,"cc",new String[]{"holo","list"}); verify(player).sendMessage(contains(HoloAdmin.id("boards.test"))); assertEquals(List.of(HoloAdmin.id("boards.test")),admin.onTabComplete(player,command,"cc",new String[]{"holo","remove","pb-"}));
  when(player.hasPermission(anyString())).thenReturn(false); assertTrue(admin.onTabComplete(player,command,"cc",new String[]{"holo","move",""}).isEmpty());
 }
 String requestRemoval() throws Exception {
  admin.onCommand(player,command,"cc",new String[]{"holo","remove",HoloAdmin.id("boards.test")}); ArgumentCaptor<Component> c=ArgumentCaptor.forClass(Component.class); verify(player).sendMessage(c.capture()); assertNotNull(c.getValue().clickEvent()); var field=HoloAdmin.class.getDeclaredField("pending"); field.setAccessible(true); Object pending=((Map<?,?>)field.get(admin)).values().iterator().next(); var token=pending.getClass().getDeclaredMethod("token"); token.setAccessible(true); return (String)token.invoke(pending);
 }
 @Test void removeRequiresConfirmationAndPersistsBeforeEntityRemoval() throws Exception {
  String token=requestRemoval(); verify(display,never()).remove(); admin.onCommand(player,command,"cc",new String[]{"holo","confirm",token}); assertNull(config.get("boards.test")); InOrder order=inOrder(plugin,display); order.verify(plugin).saveConfigChecked(); order.verify(display).remove();
 }
 @Test void changedBoardRejectsConfirmation() throws Exception {
  String token=requestRemoval(); config.set("boards.test.entity",UUID.randomUUID().toString()); admin.onCommand(player,command,"cc",new String[]{"holo","confirm",token}); verify(plugin,never()).saveConfigChecked(); verify(display,never()).remove();
 }
 @Test void failedSaveRetainsBoardAndEntity() throws Exception {
  String token=requestRemoval(); when(plugin.saveConfigChecked()).thenReturn(false); bukkit.when(()->Bukkit.getWorld(anyString())).thenReturn(world); admin.onCommand(player,command,"cc",new String[]{"holo","confirm",token}); assertNotNull(config.get("boards.test")); verify(display,never()).remove();
 }
 @Test void cleanupOnlyRemovesTaggedUnreferencedDisplaysAndArmorStands() {
  TextDisplay orphan=mock(TextDisplay.class), kept=mock(TextDisplay.class), foreign=mock(TextDisplay.class); ArmorStand stand=mock(ArmorStand.class);
  when(orphan.getScoreboardTags()).thenReturn(Set.of("ParkourBridge")); when(orphan.getUniqueId()).thenReturn(UUID.randomUUID()); when(stand.getScoreboardTags()).thenReturn(Set.of("ParkourBridge")); when(stand.getUniqueId()).thenReturn(UUID.randomUUID()); when(kept.getScoreboardTags()).thenReturn(Set.of("ParkourBridge")); when(kept.getUniqueId()).thenReturn(uuid); when(foreign.getScoreboardTags()).thenReturn(Set.of()); when(chunk.getEntities()).thenReturn(new Entity[]{orphan,stand,kept,foreign});
  doCallRealMethod().when(plugin).cleanOrphans(chunk); doCallRealMethod().when(plugin).referencedDisplays(); plugin.cleanOrphans(chunk); verify(orphan).remove(); verify(stand).remove(); verify(kept,never()).remove(); verify(foreign,never()).remove();
 }
 @Test void moveTeleportsAndUpdatesLocation() {
  Location dest=new Location(world,5,6,7); when(player.getLocation()).thenReturn(dest); when(display.teleport(dest)).thenReturn(true); admin.onCommand(player,command,"cc",new String[]{"holo","move",HoloAdmin.id("boards.test")}); verify(display).teleport(dest); assertEquals(dest,config.getLocation("boards.test.location")); verify(plugin).saveConfig();
 }
}
