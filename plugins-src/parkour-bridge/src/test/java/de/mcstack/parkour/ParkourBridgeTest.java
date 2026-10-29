package de.mcstack.parkour;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ParkourBridgeTest {
 @Test void formatsTimes() { assertEquals("0:00.001",TimeFormat.format(1)); assertEquals("1:01.234",TimeFormat.format(61234)); assertEquals("60:00.000",TimeFormat.format(3600000)); }
}
