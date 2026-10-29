package de.mcstack.parkour;
import java.util.Locale;
final class TimeFormat { static String format(long ms) { return String.format(Locale.ROOT,"%d:%02d.%03d",ms/60000,(ms/1000)%60,ms%1000); } }
