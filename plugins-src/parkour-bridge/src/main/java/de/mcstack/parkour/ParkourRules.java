package de.mcstack.parkour;

import java.util.Set;

/** Reine Regeln fuer Parkour-Ruecksetzungen (ohne Bukkit, testbar). */
final class ParkourRules {
 private ParkourRules() {}
 /** Vanilla: ab mehr als 3 Bloecken Fallhoehe gibt es Fallschaden. */
 static final double SAFE_FALL = 3.0;
 /** Bloecke, auf denen eine Landung keinen (vollen) Fallschaden verursacht. */
 static final Set<String> SOFT = Set.of("slime_block", "hay_block", "honey_block", "powder_snow", "water", "bubble_column", "cobweb", "scaffolding", "sweet_berry_bush");
 static boolean softLanding(String blockKey, boolean bed) { return bed || (blockKey != null && SOFT.contains(blockKey)); }
 /** Ruecksetzen, wenn die Landung echten Fallschaden verursachen wuerde. */
 static boolean fallResets(double drop, String blockKey, boolean bed) { return drop > SAFE_FALL && !softLanding(blockKey, bed); }
}