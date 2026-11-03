package de.mcstack.craftcore.vip;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The VIP slot servers run CraftCore as {@code craft_vip}. Every statement the in-world menu
 * really issues must be covered by a grant, and the {@code role} column must accept the
 * {@code trusted} value the menu writes.
 *
 * <p>Reads the migration scripts of the repository, so a change of the SQL without the
 * matching grant fails here instead of at runtime on a VIP slot.</p>
 */
class VipSqlMigrationTest {
    /** In-world code path: VipGui/VipWorldMenu issue these VipStore statements. */
    private static final List<String> IN_WORLD_STATEMENTS = List.of(
        VipStore.FIND_WORLD, VipStore.SET_STATUS, VipStore.SET_STARTED, VipStore.RENAME_WORLD,
        VipStore.SET_STOPPED, VipStore.STOP_RUNS, VipStore.STOP_IDLE_RUNS, VipStore.INSERT_RUN,
        VipStore.LIST_MEMBERS, VipStore.COUNT_MEMBERS, VipStore.INSERT_MEMBER, VipStore.DELETE_MEMBER,
        VipStore.SET_MEMBER_ROLE, VipStore.LIST_ADDONS, VipStore.DELETE_ADDONS, VipStore.INSERT_ADDON,
        VipStore.FIND_PLAYER, VipStore.FIND_BY_OWNER_NAME, VipStore.FIND_OWNERS_BY_NAME,
        "SELECT owner, EXISTS(SELECT 1 FROM vip_world_members m WHERE m.world_id=w.id AND m.player=?) FROM vip_worlds w WHERE w.id=? AND w.status<>'deleted'");

    private static String script(String name) throws Exception {
        Path path = Path.of("scripts", name);
        if (!Files.exists(path)) path = Path.of("../..", "scripts", name);
        assertTrue(Files.exists(path), "Migration fehlt: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    @Test void roleEnumAcceptsTrustedInEveryScript() throws Exception {
        String base = script("vip-worlds.sql");
        String rework = script("vip-worlds-rework.sql");
        assertTrue(roleEnum(base).contains("trusted"), "vip-worlds.sql legt role ohne 'trusted' an");
        assertTrue(roleEnum(rework).contains("trusted"), "vip-worlds-rework.sql hebt das ENUM nicht auf 'trusted' an");
        assertTrue(rework.toUpperCase(Locale.ROOT).contains("ALTER TABLE"),
            "Die Migration braucht ein idempotentes ALTER TABLE ... MODIFY role");
        assertTrue(rework.toUpperCase(Locale.ROOT).matches("(?s).*ALTER TABLE [\\w.]*VIP_WORLD_MEMBERS\\s+MODIFY ROLE ENUM\\([^)]*'TRUSTED'[^)]*\\).*"),
            "ALTER TABLE ... MODIFY role ENUM(... 'trusted' ...) fehlt");
        assertEquals(Set.of("member", "builder", "trusted"), roleEnum(rework),
            "Das ENUM in der Migration muss exakt die Rollen des In-World-Codes erlauben");
    }

    @Test void noFlushPrivilegesInMigration() throws Exception {
        assertFalse(script("vip-worlds-rework.sql").toUpperCase(Locale.ROOT).contains("FLUSH PRIVILEGES"));
    }

    @Test void craftVipCanRunEveryInWorldStatement() throws Exception {
        Privileges grants = Privileges.parse(script("vip-db-user.sql"), script("vip-worlds-rework.sql"));
        List<String> missing = new ArrayList<>();
        for (String sql : IN_WORLD_STATEMENTS) require(grants, sql, missing);
        assertTrue(missing.isEmpty(), "craft_vip fehlen Rechte fuer: " + missing);
    }

    @Test void grantParserUnderstandsColumnGrants() throws Exception {
        Privileges grants = Privileges.parse(script("vip-worlds-rework.sql"));
        assertTrue(grants.hasAny("vip_worlds", "UPDATE"));
        assertTrue(grants.hasColumn("vip_worlds", "status", "UPDATE"));
        assertTrue(grants.hasColumn("vip_worlds", "slot", "UPDATE"));
        assertTrue(grants.hasColumn("vip_world_members", "role", "UPDATE"));
        assertFalse(grants.hasColumn("vip_world_members", "player", "UPDATE"));
        assertFalse(grants.has("vip_worlds", "DELETE"), "Der VIP-Server darf Welten nicht loeschen");
        assertFalse(grants.has("vip_worlds", "INSERT"), "Welten werden nur in der Lobby erstellt");
    }

    private static Set<String> roleEnum(String sql) {
        Matcher matcher = Pattern.compile("role\\s+ENUM\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!matcher.find()) return Set.of();
        Set<String> values = new HashSet<>();
        for (String value : matcher.group(1).split(",")) {
            String clean = value.trim().replace("'", "").toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) values.add(clean);
        }
        return values;
    }

    // ---------------------------------------------------------------- coverage

    private static void require(Privileges grants, String sql, List<String> missing) {
        String text = sql.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        if (text.startsWith("SELECT")) {
            for (String table : selectTables(text)) if (!grants.has(table, "SELECT")) missing.add("SELECT " + table);
            return;
        }
        if (text.startsWith("UPDATE")) {
            String table = between(text, "UPDATE ", " SET").trim().toLowerCase(Locale.ROOT);
            Set<String> columns = columns(text.substring(text.indexOf(" SET ") + 5).split(" WHERE ")[0]);
            if (!grants.has(table, "UPDATE")) for (String column : columns) {
                if (!grants.hasColumn(table, column, "UPDATE")) missing.add("UPDATE " + table + "." + column);
            }
            return;
        }
        if (text.startsWith("INSERT INTO")) {
            String table = between(text, "INSERT INTO ", " (").trim().toLowerCase(Locale.ROOT);
            Set<String> columns = columns(between(text, "(", ")"));
            boolean tableGrant = grants.has(table, "INSERT");
            if (!tableGrant) for (String column : columns) {
                if (!grants.hasColumn(table, column, "INSERT")) missing.add("INSERT " + table + "." + column);
            }
            return;
        }
        if (text.startsWith("DELETE FROM")) {
            String table = between(text, "DELETE FROM ", " WHERE").trim().toLowerCase(Locale.ROOT);
            if (!grants.has(table, "DELETE")) missing.add("DELETE " + table);
            return;
        }
        throw new AssertionError("Unbekanntes Statement: " + sql);
    }

    private static String between(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = end.isEmpty() ? text.length() : text.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, "Statement nicht lesbar: " + text);
        return text.substring(from + start.length(), to);
    }

    private static Set<String> columns(String text) {
        Set<String> columns = new HashSet<>();
        Matcher matcher = Pattern.compile("([A-Z_][A-Z0-9_]*)\\s*(?:=|,)").matcher(text);
        while (matcher.find()) columns.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return columns;
    }

    private static Set<String> selectTables(String text) {
        Set<String> tables = new HashSet<>();
        Matcher matcher = Pattern.compile("\\b(?:FROM|JOIN)\\s+([A-Z_][A-Z0-9_]*)").matcher(text);
        while (matcher.find()) tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        return tables;
    }

    /** Privileges granted to 'craft_vip', table wide or per column. */
    static final class Privileges {
        private final Map<String, Set<String>> tables = new HashMap<>();
        private final Map<String, Map<String, Set<String>>> columns = new LinkedHashMap<>();

        static Privileges parse(String... scripts) {
            Privileges result = new Privileges();
            for (String script : scripts) result.read(script);
            return result;
        }

        private void read(String script) {
            String withoutComments = script.replaceAll("(?m)--.*$", "");
            for (String statement : withoutComments.split(";")) {
                Matcher grant = Pattern.compile("(?is)^\\s*GRANT\\s+(.+?)\\s+ON\\s+[`\\w]*\\.?([`\\w]+)\\s+TO\\s+'craft_vip'@'%'")
                    .matcher(statement.trim());
                if (!grant.find()) continue;
                String table = grant.group(2).replace("`", "").toLowerCase(Locale.ROOT);
                String privileges = grant.group(1);
                Matcher item = Pattern.compile("(?i)([A-Z]+)(?:\\s*\\(([^)]*)\\))?").matcher(privileges);
                while (item.find()) {
                    String kind = item.group(1).toUpperCase(Locale.ROOT);
                    String columnList = item.group(2);
                    if (columnList == null || columnList.isBlank()) {
                        tables.computeIfAbsent(table, key -> new HashSet<>()).add(kind);
                    } else {
                        for (String column : columnList.split(",")) {
                            String name = column.trim().replace("`", "").toLowerCase(Locale.ROOT);
                            if (name.isEmpty()) continue;
                            columns.computeIfAbsent(table, key -> new LinkedHashMap<>())
                                .computeIfAbsent(name, key -> new HashSet<>()).add(kind);
                        }
                    }
                }
            }
        }

        boolean has(String table, String privilege) {
            return tables.getOrDefault(table, Set.of()).contains(privilege);
        }

        /** Table wide or on at least one column. */
        boolean hasAny(String table, String privilege) {
            if (has(table, privilege)) return true;
            return columns.getOrDefault(table, Map.of()).values().stream().anyMatch(privs -> privs.contains(privilege));
        }

        boolean hasColumn(String table, String column, String privilege) {
            return columns.getOrDefault(table, Map.of()).getOrDefault(column, Set.of()).contains(privilege);
        }
    }
}
