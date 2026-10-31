package de.mcstack.craftcore;

final class WithdrawConfirm {
  private boolean armed;
  private long since;
  boolean armed(){return armed;}
  boolean reset(){boolean was=armed;armed=false;return was;}
  boolean click(long now){
    if(armed && now-since>=0 && now-since<=10_000_000_000L){armed=false;return true;}
    armed=true;since=now;return false;
  }
}
