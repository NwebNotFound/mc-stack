package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TradeStateTest {
 @Test void requiresBothAndWaitsSixtyTicks(){TradeState s=new TradeState();s.confirm(true);for(int i=0;i<80;i++)assertFalse(s.tick());s.confirm(false);for(int i=0;i<59;i++)assertFalse(s.tick());assertTrue(s.tick());}
 @Test void changeResetsConfirmationsAndCountdown(){TradeState s=new TradeState();s.confirm(true);s.confirm(false);for(int i=0;i<30;i++)assertFalse(s.tick());s.changed();assertFalse(s.first);assertFalse(s.second);assertEquals(-1,s.ticks);s.confirm(false);for(int i=0;i<80;i++)assertFalse(s.tick());s.confirm(true);for(int i=0;i<59;i++)assertFalse(s.tick());assertTrue(s.tick());}
 @Test void repeatedConfirmationDoesNotRestartCountdown(){TradeState s=new TradeState();s.confirm(true);s.confirm(false);for(int i=0;i<20;i++)s.tick();s.confirm(true);assertEquals(40,s.ticks);}
}
