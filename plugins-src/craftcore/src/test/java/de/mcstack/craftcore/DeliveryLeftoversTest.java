package de.mcstack.craftcore;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryLeftoversTest {
  @Test void sumsLeftoversAndFormatsMessage() {
    assertEquals(0, DeliveryLeftovers.sum(List.of()));
    assertEquals(1, DeliveryLeftovers.sum(List.of(1)));
    int amount = DeliveryLeftovers.sum(List.of(64, 12, 3));
    assertEquals(79, amount);
    assertEquals("79 Items bleiben im Abholfach.", DeliveryLeftovers.message(amount));
  }
}
