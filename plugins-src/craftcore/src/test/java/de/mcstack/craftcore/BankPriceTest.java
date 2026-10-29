package de.mcstack.craftcore;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BankPriceTest {
 @Test void volumeRecoveryBoundsAndSpread() {
  double s=1,b=1; for(int i=0;i<10000;i++){s=BankPrice.moveSell(s);b=BankPrice.moveBuy(b);}
  assertEquals(.25,s,1e-9);assertEquals(2,b,1e-9);
  assertTrue(BankPrice.recover(s,3600000)>s);assertTrue(BankPrice.recover(b,3600000)<b);
  assertEquals(1,BankPrice.recover(s,1000000000),1e-9);
  for(long cents=0;cents<10000;cents++) assertTrue(BankPrice.minimumBuy(cents)>=Math.ceil(cents*1.10));
 }
}
