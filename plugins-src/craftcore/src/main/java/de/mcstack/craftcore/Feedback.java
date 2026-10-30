package de.mcstack.craftcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;

/** Chat link only. No database, menu, or extra text. */
final class Feedback {
  static final String URL = "https://nwebnotfound.dev/feedback";
  static final String LABEL = "Feedback & Bugs: hier klicken";
  private Feedback() {}
  static Component line() { return Component.text(LABEL).clickEvent(ClickEvent.openUrl(URL)); }
}
