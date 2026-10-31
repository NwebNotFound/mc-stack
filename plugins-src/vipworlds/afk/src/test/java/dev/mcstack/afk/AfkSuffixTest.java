package dev.mcstack.afk;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AfkSuffixTest {
  @Test void preservesCurrentNameAndOnlyRemovesSuffix() {
    Component name=Component.text("[VIP] ",NamedTextColor.GOLD).append(Component.text("Player",NamedTextColor.BLUE));
    Component marked=AfkPlugin.withAfkSuffix(name,true);
    assertEquals(marked,AfkPlugin.withAfkSuffix(marked,true));
    assertEquals(name,AfkPlugin.withAfkSuffix(marked,false));
    assertEquals(name,AfkPlugin.withAfkSuffix(name,false));
  }
}
