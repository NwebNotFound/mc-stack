package de.mcstack.craftcore.vip;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

/** Values from {@code config/vipworlds.yml} that the game side needs. */
public final class VipConfig {
    public record Values(int vipPlayers, int mvpPlayers, int adminPlayers, int maxPlayers, int adminSlots,
                         int addonMax, int startTimeoutSeconds, int pollSeconds, int maxErrors,
                         boolean trustedMayStart) {
        public int players(String rank) {
            int limit = VipLimits.playerLimit(rank, vipPlayers, mvpPlayers, adminPlayers);
            return limit <= 0 ? 0 : Math.min(limit, maxPlayers);
        }
    }

    public interface Source {
        int getInt(String path, int fallback);
        boolean getBoolean(String path, boolean fallback);
    }

    public static final Values DEFAULTS = new Values(
        VipLimits.DEFAULT_VIP_PLAYERS, VipLimits.DEFAULT_MVP_PLAYERS, VipLimits.DEFAULT_ADMIN_PLAYERS,
        VipLimits.MAX_PLAYERS, VipLimits.DEFAULT_ADMIN_SLOTS, VipAddons.MAX, 180, 2, 5, true);

    private VipConfig() {}

    public static Values read(Source source) {
        int max = Math.max(1, Math.min(VipLimits.MAX_PLAYERS, source.getInt("players.max", VipLimits.MAX_PLAYERS)));
        int vip = Math.min(max, VipLimits.clampPlayers(source.getInt("players.vip", DEFAULTS.vipPlayers())));
        int mvp = Math.min(max, VipLimits.clampPlayers(source.getInt("players.mvp", DEFAULTS.mvpPlayers())));
        int admin = Math.min(max, VipLimits.clampPlayers(source.getInt("players.admin", DEFAULTS.adminPlayers())));
        return new Values(
            vip, mvp, admin, max,
            Math.max(0, Math.min(VipLimits.MAX_SLOTS, source.getInt("admin-slots", DEFAULTS.adminSlots()))),
            Math.max(1, Math.min(VipAddons.MAX, source.getInt("addons.max", DEFAULTS.addonMax()))),
            Math.max(30, source.getInt("start-timeout-seconds", DEFAULTS.startTimeoutSeconds())),
            Math.max(1, source.getInt("start-poll-seconds", DEFAULTS.pollSeconds())),
            Math.max(1, source.getInt("start-max-errors", DEFAULTS.maxErrors())),
            source.getBoolean("trusted-may-start", DEFAULTS.trustedMayStart()));
    }

    /** Reads the file the VIP servers mount; a missing file keeps the defaults. */
    public static Values load() {
        File file = new File(System.getenv().getOrDefault("VIP_CONFIG", "config/vipworlds.yml"));
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (Exception ignored) { return DEFAULTS; }
        YamlConfiguration source = yaml;
        return read(new Source() {
            public int getInt(String path, int fallback) { return source.getInt(path, fallback); }
            public boolean getBoolean(String path, boolean fallback) { return source.getBoolean(path, fallback); }
        });
    }

    public static Source from(YamlConfiguration yaml) {
        return new Source() {
            public int getInt(String path, int fallback) { return yaml.getInt(path, fallback); }
            public boolean getBoolean(String path, boolean fallback) { return yaml.getBoolean(path, fallback); }
        };
    }
}
