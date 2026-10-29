package de.mcstack.craftcore;

final class TradeState {
  boolean first, second;
  int ticks = -1;
  void changed() { first=false; second=false; ticks=-1; }
  void confirm(boolean a) { if(a) first=true; else second=true; if(first && second && ticks<0) ticks=60; }
  boolean tick() { return ticks>=0 && --ticks==0; }
}
