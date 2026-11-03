package de.mcstack.craftcore.vip;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Allowlist of per-world addons. Selection is stored in {@code vip_world_addons}. */
public final class VipAddons {
    public static final int MAX = 3;

    public record Addon(String id, String name, String description, String icon, String rule) {}

    public static final List<Addon> CATALOG = List.of(
        new Addon("keep-inventory", "Inventar behalten", "Items bleiben nach dem Tod.", "CHEST", "keepInventory"),
        new Addon("peaceful", "Friedlich", "Keine Monster, Schwierigkeit friedlich.", "SUNFLOWER", "difficulty"),
        new Addon("creative", "Kreativmodus", "Bauen mit Fliegen.", "GRASS_BLOCK", "gamemode"),
        new Addon("day-lock", "Immer Tag", "Die Tageszeit bleibt stehen.", "CLOCK", "daylight"),
        new Addon("pvp", "PvP", "Spieler koennen sich verletzen.", "IRON_SWORD", "pvp")
    );

    private VipAddons() {}

    public static boolean valid(String id) {
        return id != null && CATALOG.stream().anyMatch(a -> a.id().equals(id));
    }

    public static Addon find(String id) {
        return CATALOG.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    /** Drops unknown ids and duplicates, keeps catalog order, caps at {@link #MAX}. */
    public static List<String> sanitize(Collection<String> ids) {
        if (ids == null) return List.of();
        Set<String> wanted = new LinkedHashSet<>(ids);
        List<String> result = new ArrayList<>();
        for (Addon addon : CATALOG) {
            if (wanted.contains(addon.id()) && result.size() < MAX) result.add(addon.id());
        }
        return List.copyOf(result);
    }

    public static boolean canAdd(Collection<String> current, String id) {
        return valid(id) && current != null && !current.contains(id) && current.size() < MAX;
    }

    /** Adds a valid addon when there is room, otherwise removes it. */
    public static List<String> toggle(Collection<String> current, String id) {
        List<String> base = sanitize(current);
        if (!valid(id)) return base;
        if (base.contains(id)) {
            List<String> next = new ArrayList<>(base);
            next.remove(id);
            return List.copyOf(next);
        }
        if (base.size() >= MAX) return base;
        List<String> next = new ArrayList<>(base);
        next.add(id);
        return sanitize(next);
    }

    public static boolean selected(Collection<String> current, String id) {
        return current != null && current.contains(id);
    }
}
