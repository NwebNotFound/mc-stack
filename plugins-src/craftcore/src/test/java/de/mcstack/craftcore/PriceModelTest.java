package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PriceModelTest {
  @Test void spreadCapHandlesRoundingAndLargeValues() { assertEquals(0,PriceModel.cappedSell(1,2)); assertEquals(35,PriceModel.cappedSell(100,100)); assertEquals(5,PriceModel.cappedSell(5,100)); assertEquals(3228180212899171532L, PriceModel.cappedSell(Long.MAX_VALUE, Long.MAX_VALUE)); }
  @Test void pressureAndBounds() { assertEquals(9,PriceModel.move(10,1,false,5,12)); assertEquals(11,PriceModel.move(10,1,true,5,12)); assertEquals(5,PriceModel.move(5,2,false,5,12)); assertEquals(12,PriceModel.move(12,2,true,5,12)); }
  @Test void recoveryAndClockSkew() { assertEquals(10+(20-10)*Math.exp(-1),PriceModel.recover(20,10,5,30,1,3600000),0.00001); assertEquals(20,PriceModel.recover(20,10,5,30,1,-1)); }
  @org.junit.jupiter.api.Test void spreadIsPositiveEvenAtMinimumPrice() {
    org.junit.jupiter.api.Assertions.assertEquals(1,PriceModel.quote(.01,.05,false));
    org.junit.jupiter.api.Assertions.assertEquals(2,PriceModel.quote(.01,.05,true));
    org.junit.jupiter.api.Assertions.assertEquals(760,PriceModel.quote(8,.05,false));
    org.junit.jupiter.api.Assertions.assertEquals(840,PriceModel.quote(8,.05,true));
  }
  @Test void absoluteFloorSurvivesPriceAndDailyDiscount() { assertEquals(10000, PriceModel.withFloor((long)(PriceModel.factorQuote(10,.25)*.2),100)); assertEquals(20000,PriceModel.withFloor(20000,100)); }
  @Test void configuredFactorsAndDailyTiers(){assertEquals(250,PriceModel.factorQuote(10,.25));assertEquals(1500,PriceModel.factorQuote(10,1.5));assertEquals(1,PriceModel.diminishing(63,64,.5,256,.2));assertEquals(.5,PriceModel.diminishing(64,64,.5,256,.2));assertEquals(.2,PriceModel.diminishing(256,64,.5,256,.2));}
}
