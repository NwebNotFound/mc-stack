package de.mcstack.craftcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.Configuration;

/** Nur ein klickbarer Chat-Link. Keine DB, kein Menue, keine Permission. */
final class Feedback {
  static final String URL = "https://nwebnotfound.dev/feedback";
  static final String LABEL = "Feedback geben: hier klicken";
  private Feedback() {}
  static String url(Configuration cfg) {
    String u = cfg == null ? null : cfg.getString("feedback-url");
    return u == null || !(u.startsWith("https://") || u.startsWith("http://")) ? URL : u.trim();
  }
  static Component line() { return line(URL); }
  static Component line(String url) { return Component.text(LABEL).clickEvent(ClickEvent.openUrl(url)); }
}