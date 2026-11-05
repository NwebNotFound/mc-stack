package de.mcstack.craftcore.vip;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Clickable VIP menus. The lobby shows the hub with every owned world and the free slots;
 * a VIP world shows the manage screen of the world it belongs to. Every click runs one
 * operation, so no menu ever builds a command string.
 */
public final class VipGui implements Listener {
    public interface Connector {
        void connect(Player player, String slot);
        void lobby(Player player);
    }

    private enum Screen { HUB, WORLD, CREATE, MEMBERS, ADDONS }

    private record Button(String label, Material material, List<String> lore, String action, String arg) {}

    private record Loaded(List<Button> buttons, String error) {}

    private static final class Holder implements InventoryHolder {
        private final Screen screen;
        private final String worldId;
        private final List<Button> buttons;
        private Inventory inventory;
        Holder(Screen screen, String worldId, List<Button> buttons) {
            this.screen = screen; this.worldId = worldId; this.buttons = buttons;
        }
        public Inventory getInventory() { return inventory; }
    }

    private static final long INPUT_MILLIS = 60_000L;

    private final JavaPlugin plugin;
    private final VipStore store;
    private final VipHostClient host = new VipHostClient();
    private final VipStartCoordinator coordinator;
    private final VipStartBars bars;
    private final VipConfig.Values config;
    private final Connector connector;
    private final boolean inWorld;
    private final String worldId;
    private final Map<UUID, Consumer<String>> inputs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inputExpiry = new ConcurrentHashMap<>();
    private final VipQueueState queue = new VipQueueState();
    private final Map<UUID, String> waiting = queue.waiting;
    /** Players that already left this server, so a connect retry does not bounce them again. */
    private final Set<UUID> transferred = ConcurrentHashMap.newKeySet();
    /** Bumped when the player chooses another server; a pending wait or auto-connect of an older generation is dropped. */
    private final Map<UUID, Integer> generation = queue.generation;
    private final Map<UUID, String> sentSlot = queue.sentSlot;
    private final Map<UUID, Long> sentAt = queue.sentAt;

    public VipGui(JavaPlugin plugin, VipStore.ConnectionSource source, VipStartCoordinator coordinator,
                  VipStartBars bars, VipConfig.Values config, Connector connector,
                  boolean inWorld, String worldId) {
        this.plugin = plugin;
        this.store = new VipStore(source);
        this.coordinator = coordinator;
        this.bars = bars;
        this.config = config;
        this.connector = connector;
        this.inWorld = inWorld;
        this.worldId = worldId;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, bars::tick, 20L, 20L);
        de.mcstack.craftcore.ProxyChannel.onSwitch(this::beforeSwitch);
    }

    /**
     * Another server was chosen (portal, menu, NPC, /lobby) while a VIP start is queued: leave the queue with a chat
     * line and drop the pending auto-connects, so they cannot pull the player away or block the switch.
     */
    boolean beforeSwitch(Player player, String server) {
        UUID id = player.getUniqueId();
        VipQueueState.Left left = queue.beforeSwitch(id, server, System.currentTimeMillis());
        if (left == null) return false;
        if (left.world() != null) { coordinator.leave(left.world(), id); bars.clear(id); }
        message(player, left.message());
        return left.recentConnect();
    }

    // ---------------------------------------------------------------- screens

    public void openHub(Player player) {
        if (inWorld) { openWorld(player, worldId); return; }
        UUID id = player.getUniqueId();
        load(() -> {
            List<VipStore.World> worlds = store.list(id);
            int slots = slotsFor(player);
            List<Button> buttons = new ArrayList<>();
            for (VipMenuModel.Slot slot : VipMenuModel.slots(Math.min(slots, 45), Math.min(worlds.size(), 45))) {
                switch (slot.kind()) {
                    case OCCUPIED -> {
                        VipStore.World world = worlds.get(slot.worldIndex());
                        buttons.add(new Button(world.name(), Material.GRASS_BLOCK,
                            List.of("Status: " + statusText(world.status()),
                                "Slot: " + (world.slot() == null ? "-" : world.slot()),
                                "Typ: " + world.type(), "Klick: verwalten"),
                            "world", world.id()));
                    }
                    case FREE -> buttons.add(new Button("Freier Slot", Material.LIME_DYE,
                        List.of("Klick: neue Welt erstellen"), "create", ""));
                    case LOCKED -> buttons.add(new Button("Gesperrter Slot", Material.BARRIER,
                        List.of(slot.reason()), "none", ""));
                }
            }
            // Worlds the player was invited to: open read-only (Beitreten), at most one row.
            for (VipStore.World shared : store.listShared(id).stream().limit(8).toList()) {
                buttons.add(new Button("Eingeladen: " + shared.name(), Material.OAK_DOOR,
                    List.of("Status: " + statusText(shared.status()), "Klick: ansehen und beitreten"), "world", shared.id()));
            }
            buttons.add(new Button("Hilfe", Material.BOOK, List.of(
                "VIP: " + config.vipPlayers() + " Spieler, MVP: " + config.mvpPlayers() + " Spieler",
                "Maximal " + VipLimits.MAX_PLAYERS + " Spieler pro Welt",
                "/vw invite <name> | /vw kick <name>",
                "Addons: maximal " + config.addonMax() + " pro Welt"), "none", ""));
            return new Loaded(buttons, null);
        }, loaded -> open(player, Screen.HUB, null, loaded.buttons()), error -> failed(player, "VIP-Menue", error));
    }

    public void openWorld(Player player, String id) {
        load(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) return new Loaded(null, "Diese VIP-Welt wurde nicht gefunden.");
            if (!mayManage(player, entry)) {
                List<Button> readOnly = List.of(
                    new Button(entry.name(), Material.GRASS_BLOCK, List.of(
                        "Status: " + statusText(entry.status()),
                        "Slot: " + (entry.slot() == null ? "-" : entry.slot()),
                        "Nur Besitzer und Admins verwalten diese Welt"), "none", ""),
                    new Button("Beitreten", Material.ENDER_PEARL, List.of("Verbinde mit der laufenden Welt"), "join", id),
                    new Button("Zurueck", Material.ARROW, List.of(), inWorld ? "none" : "hub", ""));
                return new Loaded(readOnly, null);
            }
            List<String> addons = store.addons(id);
            List<Button> buttons = List.of(
                new Button("Starten", Material.LIME_WOOL, List.of("Startet die Welt und verbindet dich automatisch"), "start", id),
                new Button("Stoppen", Material.RED_WOOL, List.of("Stoppt die Welt"), "stop", id),
                new Button("Beitreten", Material.ENDER_PEARL, List.of("Verbinde mit der laufenden Welt"), "join", id),
                new Button("Mitglieder", Material.PLAYER_HEAD, List.of("Einladen, kicken, Vertrauen setzen"), "members", id),
                new Button("Addons", Material.BREWING_STAND, List.of(
                    addons.isEmpty() ? "Keine Addons aktiv" : "Aktiv: " + String.join(", ", addons),
                    "Pro Welt waehlbar, maximal " + config.addonMax()), "addons", id),
                new Button("Umbenennen", Material.NAME_TAG, List.of("Neuen Namen im Chat eingeben"), "rename", id),
                new Button("Loeschen", Material.LAVA_BUCKET, List.of("Nur gestoppte Welten", "Zur Bestaetigung den Weltnamen eintippen"), "delete", id),
                new Button("Zurueck", Material.ARROW, List.of("Zurueck zum Menue"), "hub", ""));
            return new Loaded(buttons, null);
        }, loaded -> {
            if (loaded.error() != null) { message(player, loaded.error()); return; }
            open(player, Screen.WORLD, id, loaded.buttons());
        }, error -> failed(player, "VIP-Welt", error));
    }

    public void openCreate(Player player) {
        List<Button> buttons = List.of(
            new Button("Normale Welt", Material.GRASS_BLOCK, List.of("Berge, Hoehlen, Erze"), "new", "normal"),
            new Button("Flache Welt", Material.SAND, List.of("Ebene Flaeche zum Bauen"), "new", "flat"),
            new Button("Leere Welt", Material.GLASS, List.of("Nur Leere, kein Gelaende"), "new", "void"),
            new Button("Zurueck", Material.ARROW, List.of(), "hub", ""));
        open(player, Screen.CREATE, null, buttons);
    }

    public void openMembers(Player player, String id) {
        load(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) return new Loaded(null, "Diese VIP-Welt wurde nicht gefunden.");
            if (!mayManage(player, entry)) return new Loaded(null, "Du darfst diese Welt nicht verwalten.");
            List<Button> buttons = new ArrayList<>();
            buttons.add(new Button("Spieler einladen", Material.WRITABLE_BOOK,
                List.of("Namen im Chat eingeben", "Maximal " + VipMemberRules.MAX_MEMBERS + " Mitglieder"), "invite", id));
            for (VipStore.Member member : store.members(id)) {
                String name = member.name() == null ? member.player().toString() : member.name();
                buttons.add(new Button(name, Material.PLAYER_HEAD, List.of(
                    "trusted".equals(member.role()) ? "Rolle: vertraut" : "Rolle: Mitglied",
                    "Linksklick: kicken", "Rechtsklick: Vertrauen umschalten"),
                    "member", id + ":" + member.player() + ":" + member.role()));
            }
            buttons.add(new Button("Zurueck", Material.ARROW, List.of(), "world", id));
            return new Loaded(buttons, null);
        }, loaded -> {
            if (loaded.error() != null) { message(player, loaded.error()); return; }
            open(player, Screen.MEMBERS, id, loaded.buttons());
        }, error -> failed(player, "Mitglieder", error));
    }

    public void openAddons(Player player, String id) {
        load(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) return new Loaded(null, "Diese VIP-Welt wurde nicht gefunden.");
            if (!mayManage(player, entry)) return new Loaded(null, "Du darfst diese Welt nicht verwalten.");
            List<String> selected = store.addons(id);
            List<Button> buttons = new ArrayList<>();
            for (VipAddons.Addon addon : VipAddons.CATALOG) {
                boolean on = selected.contains(addon.id());
                buttons.add(new Button((on ? "[AN] " : "[AUS] ") + addon.name(), material(addon.icon()),
                    List.of(addon.description(), "Maximal " + config.addonMax() + " Addons",
                        on ? "Klick: deaktivieren" : "Klick: aktivieren"),
                    "addon", id + ":" + addon.id()));
            }
            buttons.add(new Button("Zurueck", Material.ARROW, List.of(), "world", id));
            return new Loaded(buttons, null);
        }, loaded -> {
            if (loaded.error() != null) { message(player, loaded.error()); return; }
            open(player, Screen.ADDONS, id, loaded.buttons());
        }, error -> failed(player, "Addons", error));
    }

    // ------------------------------------------------------------- operations

    public void startWorld(Player player, String id) {
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            if (!mayStart(player, entry)) { message(player, "Du darfst diese Welt nicht starten."); return; }
            VipStartDecision.Decision d = VipStartDecision.decide(entry.status(), entry.slot(), entry.id(), host.slots());
            switch (d.step()) {
                case HOST_DOWN -> message(player, "VIP-Host nicht erreichbar, bitte spaeter erneut versuchen.");
                case CONNECT -> {
                    if (d.staleRow()) store.status(id, "running", d.slot());
                    message(player, "Die Welt laeuft schon, du wirst verbunden ...");
                    retryConnect(player, d.slot(), generation.getOrDefault(player.getUniqueId(), 0));
                }
                case WAIT -> {
                    if (d.staleRow()) store.status(id, "starting", d.slot());
                    waitFor(player, entry);
                }
                case START -> {
                    if (d.staleRow()) store.idleStopped(id);
                    if (!store.status(id, "starting", null)) { message(player, "Diese VIP-Welt wurde gerade geloescht."); return; }
                    waitFor(player, entry);
                }
            }
        }, error -> failed(player, "VIP-Start", error));
    }

    public void joinWorld(Player player, String id) {
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            if (!mayJoin(player, entry)) { message(player, "Du hast keinen Zugang zu dieser VIP-Welt."); return; }
            VipReconcile.Action decision = VipReconcile.decide(entry.status(), entry.slot(), entry.id(), host.slots());
            switch (decision.kind()) {
                case RUNNING -> {
                    if (!"running".equals(entry.status()) || !decision.slot().equals(entry.slot())) store.status(id, "running", decision.slot());
                    retryConnect(player, decision.slot(), generation.getOrDefault(player.getUniqueId(), 0));
                }
                case STARTING -> {
                    if (!decision.slot().equals(entry.slot())) store.status(id, "starting", decision.slot());
                    waitFor(player, entry);
                }
                case STOPPED -> { store.idleStopped(id); message(player, "Starte die Welt zuerst."); }
                case HOST_UNAVAILABLE -> message(player, "VIP-Host nicht erreichbar, bitte spaeter erneut versuchen.");
                case UNCHANGED -> message(player, "Starte die Welt zuerst.");
            }
        }, error -> failed(player, "VIP-Beitritt", error));
    }

    /** Soft delete (status 'deleted'); only the exact typed name confirms it. World files are left to the host. */
    public void deleteWorld(Player player, String id, String typed) {
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            if (!mayManage(player, entry)) { message(player, "Du darfst diese Welt nicht verwalten."); return; }
            switch (VipRules.deleteCheck(entry.name(), typed, entry.status())) {
                case NAME_MISMATCH -> message(player, "Name stimmt nicht, die Welt wurde nicht geloescht.");
                case RUNNING -> message(player, "Stoppe die Welt zuerst, dann kannst du sie loeschen.");
                case OK -> {
                    if (!store.markDeleted(id)) { message(player, "Loeschen fehlgeschlagen, die Welt laeuft wieder oder wurde schon geloescht."); return; }
                    message(player, "VIP-Welt \"" + entry.name() + "\" wurde geloescht.");
                    Bukkit.getScheduler().runTask(plugin, () -> openHub(player));
                }
            }
        }, error -> failed(player, "Loeschen", error));
    }

    public void stopWorld(Player player, String id) {
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            if (!mayManage(player, entry)) { message(player, "Du darfst diese Welt nicht verwalten."); return; }
            if (!VipRules.canStop(entry.status(), entry.slot())) { message(player, "Die VIP-Welt laeuft nicht."); return; }
            if (host.stop(entry.slot()).outcome() != VipHostClient.Outcome.STOPPED) {
                message(player, "VIP-Welt konnte nicht gestoppt werden."); return;
            }
            store.stopped(id);
            message(player, "VIP-Welt gestoppt.");
        }, error -> failed(player, "VIP-Stopp", error));
    }

    public void createWorld(Player player, String name, String type) {
        String clean = VipMemberRules.cleanName(name);
        if (!VipNames.validate(clean)) {
            message(player, "Der Name muss 3 bis 24 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ und - enthalten.");
            return;
        }
        String kind = type == null ? "normal" : type.toLowerCase(Locale.ROOT);
        if (!List.of("normal", "flat", "void").contains(kind)) { message(player, "Welttyp: normal, flat oder void."); return; }
        async(() -> {
            int slots = slotsFor(player);
            if (!VipRules.canCreate(store.count(player.getUniqueId()), slots)) {
                message(player, slots == 0 ? "Du hast keinen VIP-Rang." : "Du hast keinen freien VIP-Weltplatz.");
                return;
            }
            store.create(player.getUniqueId(), clean, kind);
            message(player, "VIP-Welt erstellt.");
            openHub(player);
        }, error -> {
            if (error instanceof SQLException sql && VipStore.duplicate(sql)) message(player, "Du hast bereits eine Welt mit diesem Namen.");
            else failed(player, "VIP-Welt erstellen", error);
        });
    }

    public void renameWorld(Player player, String id, String name) {
        String clean = VipMemberRules.cleanName(name);
        if (!VipNames.validate(clean)) {
            message(player, "Der Name muss 3 bis 24 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ und - enthalten.");
            return;
        }
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null || !mayManage(player, entry)) { message(player, "Du darfst diese Welt nicht verwalten."); return; }
            store.rename(entry.owner(), id, clean);
            message(player, "VIP-Welt umbenannt.");
            openWorld(player, id);
        }, error -> failed(player, "VIP-Welt umbenennen", error));
    }

    public void invite(Player player, String id, String name) {
        String clean = VipMemberRules.cleanName(name);
        if (!VipNames.validate(clean)) { message(player, "Ungueltiger Spielername."); return; }
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            UUID target = store.player(clean).orElse(null);
            if (target == null) { message(player, "Spieler nicht gefunden."); return; }
            if (target.equals(entry.owner())) { message(player, "Der Besitzer ist bereits Mitglied dieser Welt."); return; }
            if (target.equals(player.getUniqueId())) { message(player, "Du kannst dich nicht selbst einladen."); return; }
            List<VipStore.Member> members = store.members(id);
            boolean already = members.stream().anyMatch(m -> m.player().equals(target));
            VipMemberRules.Verdict verdict = VipMemberRules.invite(
                entry.owner().equals(player.getUniqueId()), isAdmin(player), already, members.size());
            if (!verdict.allowed()) { message(player, verdictText(verdict.reason())); return; }
            store.addMember(id, target, "member");
            message(player, clean + " wurde eingeladen.");
            openMembers(player, id);
        }, error -> failed(player, "Einladen", error));
    }

    public void kick(Player player, String id, String name) {
        String clean = VipMemberRules.cleanName(name);
        if (clean.isEmpty()) { message(player, "Ungueltiger Spielername."); return; }
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null) { message(player, "Diese VIP-Welt wurde nicht gefunden."); return; }
            UUID target = store.player(clean).orElse(null);
            if (target == null) { message(player, "Spieler nicht gefunden."); return; }
            kickTarget(player, entry, target, clean);
        }, error -> failed(player, "Kicken", error));
    }

    /** Removes the member off the server thread; the display name is never a UUID. */
    private void kickTarget(Player player, VipStore.Entry entry, UUID target, String name) {
        VipMemberRules.Verdict verdict = VipMemberRules.kick(
            entry.owner().equals(player.getUniqueId()), isAdmin(player),
            entry.owner().equals(target), target.equals(player.getUniqueId()));
        if (!verdict.allowed()) { message(player, verdictText(verdict.reason())); return; }
        String display = name == null || name.isBlank() ? target.toString() : name;
        load(() -> store.removeMember(entry.id(), target), removed -> {
            if (!removed) { message(player, "Spieler ist kein Mitglied."); return; }
            message(player, display + " wurde entfernt.");
            Player online = Bukkit.getPlayer(target);
            if (online != null && inWorld && worldId != null && worldId.equals(entry.id())) lobby(online);
            openMembers(player, entry.id());
        }, error -> failed(player, "Kicken", error));
    }

    private void toggleAddon(Player player, String id, String addon) {
        async(() -> {
            VipStore.Entry entry = store.findById(id).orElse(null);
            if (entry == null || !mayManage(player, entry)) { message(player, "Du darfst diese Welt nicht verwalten."); return; }
            List<String> current = VipAddons.sanitize(store.addons(id));
            boolean adding = !current.contains(addon);
            if (adding && current.size() >= config.addonMax()) {
                message(player, "Maximal " + config.addonMax() + " Addons pro Welt."); return;
            }
            List<String> next = VipAddons.toggle(current, addon);
            store.setAddons(id, next);
            message(player, "Addons gespeichert: " + (next.isEmpty() ? "keine" : String.join(", ", next)));
            openAddons(player, id);
        }, error -> failed(player, "Addons", error));
    }

    private void waitFor(Player player, VipStore.Entry entry) {
        UUID id = player.getUniqueId();
        int gen = generation.getOrDefault(id, 0);
        waiting.put(id, entry.id());
        bars.wait(player, entry.id());
        message(player, "Deine Welt startet ... du wirst automatisch verbunden.");
        CompletableFuture<VipStartCoordinator.Outcome> future = coordinator.join(entry.id(), id);
        future.whenComplete((outcome, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (generation.getOrDefault(id, 0) != gen) return; // the player chose another server meanwhile
            waiting.remove(id);
            bars.clear(id);
            if (outcome == null || !outcome.ok()) {
                // Der Coordinator setzt den Status genau einmal auf stopped und stoppt einen
                // bereits gestarteten Slot; hier bleibt nur die Meldung an den Wartenden.
                String reason = outcome == null ? VipStartCoordinator.START_FAILED : outcome.reason();
                if (!VipStartCoordinator.CANCELLED.equals(reason)) message(player, VipStartProgress.reasonText(reason));
                return;
            }
            message(player, VipStartProgress.ready(outcome.slot()).message());
            retryConnect(player, outcome.slot(), gen);
        }));
    }

    /**
     * Connects only once the slot answers a status ping (bounded), then checks that the player really left; one retry,
     * then a clear chat line instead of Velocity's raw error.
     */
    private void retryConnect(Player player, String slot, int gen) {
        UUID id = player.getUniqueId();
        sentSlot.put(id, slot);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            de.mcstack.craftcore.pvp.PvpPing ping = new de.mcstack.craftcore.pvp.PvpPing(slot, 25565);
            boolean up = VipReachable.waitFor(ping::ping, VipReachable.PING_ATTEMPTS, VipReachable.PING_DELAY_MS, Thread::sleep);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!stillHere(player, id, gen)) return;
                if (!up) { sentSlot.remove(id); message(player, VipReachable.NOT_REACHABLE); return; }
                attemptConnect(player, slot, gen, 1);
            });
        });
    }

    private void attemptConnect(Player player, String slot, int gen, int attempt) {
        UUID id = player.getUniqueId();
        sentAt.put(id, System.currentTimeMillis());
        connector.connect(player, slot);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!stillHere(player, id, gen)) return;
            message(player, VipReachable.afterStillHere(attempt));
            if (attempt < VipReachable.CONNECT_ATTEMPTS) attemptConnect(player, slot, gen, attempt + 1);
            else sentSlot.remove(id);
        }, VipReachable.CONNECT_CHECK_TICKS);
    }

    private boolean stillHere(Player player, UUID id, int gen) {
        return player.isOnline() && !transferred.contains(id) && generation.getOrDefault(id, 0) == gen;
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= holder.buttons.size() || !(event.getWhoClicked() instanceof Player player)) return;
        Button button = holder.buttons.get(slot);
        player.closeInventory();
        handle(player, button, event.isRightClick());
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private void handle(Player player, Button button, boolean rightClick) {
        switch (button.action()) {
            case "hub" -> openHub(player);
            case "world" -> openWorld(player, button.arg());
            case "create" -> openCreate(player);
            case "new" -> ask(player, "Name der neuen Welt", name -> createWorld(player, name, button.arg()));
            case "start" -> startWorld(player, button.arg());
            case "stop" -> stopWorld(player, button.arg());
            case "join" -> joinWorld(player, button.arg());
            case "members" -> openMembers(player, button.arg());
            case "addons" -> openAddons(player, button.arg());
            case "invite" -> ask(player, "Spielername zum Einladen", name -> invite(player, button.arg(), name));
            case "rename" -> ask(player, "Neuer Weltname", name -> renameWorld(player, button.arg(), name));
            case "delete" -> ask(player, "Zum Loeschen den Weltnamen genau eintippen", typed -> deleteWorld(player, button.arg(), typed));
            case "addon" -> {
                String[] parts = button.arg().split(":", 2);
                if (parts.length == 2) toggleAddon(player, parts[0], parts[1]);
            }
            case "member" -> memberClick(player, button, rightClick);
            default -> { }
        }
    }

    private record MemberState(VipStore.Entry entry, String role) {}

    /** Left click kicks, right click toggles "trusted". The role always comes from the database. */
    private void memberClick(Player player, Button button, boolean rightClick) {
        String[] parts = button.arg().split(":", 3);
        if (parts.length < 3) return;
        UUID target;
        try { target = UUID.fromString(parts[1]); } catch (IllegalArgumentException invalid) { return; }
        String name = button.label();
        load(() -> {
            VipStore.Entry entry = store.findById(parts[0]).orElse(null);
            if (entry == null) return null;
            String role = store.members(parts[0]).stream()
                .filter(m -> m.player().equals(target)).map(VipStore.Member::role).findFirst().orElse(null);
            return role == null ? null : new MemberState(entry, role);
        }, state -> {
            if (state == null) { message(player, "Dieses Mitglied gibt es nicht mehr."); openMembers(player, parts[0]); return; }
            if (rightClick) {
                VipMemberRules.Verdict verdict = VipMemberRules.mayManage(
                    state.entry().owner().equals(player.getUniqueId()), isAdmin(player));
                if (!verdict.allowed()) { message(player, verdictText(verdict.reason())); return; }
                String next = "trusted".equals(state.role()) ? "member" : "trusted";
                async(() -> store.setMemberRole(parts[0], target, next),
                    () -> openMembers(player, parts[0]), error -> failed(player, "Mitglieder", error));
            } else {
                kickTarget(player, state.entry(), target, name);
            }
        }, error -> failed(player, "Mitglieder", error));
    }

    // ------------------------------------------------------------ chat input

    private void ask(Player player, String prompt, Consumer<String> handler) {
        inputs.put(player.getUniqueId(), handler);
        inputExpiry.put(player.getUniqueId(), System.currentTimeMillis() + INPUT_MILLIS);
        message(player, prompt + ": schreibe den Wert in den Chat (Abbrechen: /vw menu).");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void chat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Consumer<String> handler = inputs.remove(id);
        Long expiry = inputExpiry.remove(id);
        if (handler == null) return;
        if (expiry != null && System.currentTimeMillis() > expiry) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> handler.accept(text));
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        inputs.remove(id);
        inputExpiry.remove(id);
        transferred.add(id);
        String world = waiting.remove(id);
        if (world != null) coordinator.leave(world, id);
        bars.clear(id);
        sentSlot.remove(id);
        sentAt.remove(id);
    }

    @EventHandler
    public void join(PlayerJoinEvent event) {
        transferred.remove(event.getPlayer().getUniqueId());
    }

    // --------------------------------------------------------------- helpers

    public boolean mayManage(Player player, VipStore.Entry entry) {
        return entry.owner().equals(player.getUniqueId()) || isAdmin(player);
    }

    public boolean mayStart(Player player, VipStore.Entry entry) {
        if (mayManage(player, entry)) return true;
        try {
            boolean trusted = store.members(entry.id()).stream()
                .anyMatch(m -> m.player().equals(player.getUniqueId()) && "trusted".equals(m.role()));
            return VipMemberRules.mayStart(false, false, trusted, config.trustedMayStart()).allowed();
        } catch (SQLException e) { return false; }
    }

    public boolean mayJoin(Player player, VipStore.Entry entry) {
        if (mayManage(player, entry)) return true;
        try { return store.members(entry.id()).stream().anyMatch(m -> m.player().equals(player.getUniqueId())); }
        catch (SQLException e) { return false; }
    }

    /** Player cap of a world. Loads the owner's LuckPerms user when the owner is offline. */
    public int playerLimit(VipStore.Entry entry) {
        int limit = config.players(VipPerks.rankLoaded(entry.owner()));
        return limit > 0 ? limit : config.vipPlayers();
    }

    private int slotsFor(Player player) {
        return VipPerks.slots(player.getUniqueId(), isAdmin(player), config.adminSlots());
    }

    public static boolean isAdmin(Player player) {
        return player.hasPermission("group.admin") || player.hasPermission("craftcore.vip.admin");
    }

    static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case "running" -> "laeuft";
            case "starting" -> "startet";
            case "stopping" -> "stoppt";
            case "error" -> "Fehler";
            default -> "gestoppt";
        };
    }

    static String verdictText(String reason) {
        return switch (reason == null ? "" : reason) {
            case "not_owner" -> "Nur der Besitzer oder ein Admin darf das.";
            case "already_member" -> "Der Spieler ist bereits eingeladen.";
            case "member_limit" -> "Die Mitgliederliste ist voll.";
            case "is_owner" -> "Der Besitzer kann nicht entfernt werden.";
            case "not_trusted" -> "Nur vertraute Mitglieder duerfen die Welt starten.";
            default -> "Aktion nicht erlaubt.";
        };
    }

    private static Material material(String icon) {
        Material material = Material.matchMaterial(icon == null ? "" : icon);
        return material == null ? Material.PAPER : material;
    }

    private void open(Player player, Screen screen, String id, List<Button> buttons) {
        int size = Math.max(9, Math.min(54, ((buttons.size() + 8) / 9) * 9));
        Holder holder = new Holder(screen, id, buttons);
        Inventory inventory = Bukkit.createInventory(holder, size,
            Component.text("VIP - " + screen.name().toLowerCase(Locale.ROOT), NamedTextColor.DARK_PURPLE));
        holder.inventory = inventory;
        for (int i = 0; i < buttons.size() && i < size; i++) {
            Button button = buttons.get(i);
            ItemStack item = new ItemStack(button.material());
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(button.label(), NamedTextColor.AQUA));
            meta.lore(button.lore().stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
            item.setItemMeta(meta);
            inventory.setItem(i, item);
        }
        player.openInventory(inventory);
    }

    private interface Work { void run() throws Exception; }

    private interface Value<T> { T run() throws Exception; }

    private void async(Work work, Consumer<Exception> failed) {
        async(work, () -> { }, failed);
    }

    private void async(Work work, Runnable done, Consumer<Exception> failed) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Exception error = null;
            try { work.run(); } catch (Exception e) { error = e; }
            Exception failure = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure != null) { failed.accept(failure); return; }
                done.run();
            });
        });
    }

    private <T> void load(Value<T> work, Consumer<T> done, Consumer<Exception> failed) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            T value = null;
            Exception error = null;
            try { value = work.run(); } catch (Exception e) { error = e; }
            T result = value;
            Exception failure = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failure != null) { failed.accept(failure); return; }
                done.accept(result);
            });
        });
    }

    private void failed(Player player, String what, Exception error) {
        plugin.getLogger().warning(what + ": " + error.getClass().getSimpleName());
        message(player, what + " fehlgeschlagen. Bitte spaeter erneut versuchen.");
    }

    private void message(Player player, String text) {
        Component line = Component.text("[VIP] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.WHITE));
        if (Bukkit.isPrimaryThread()) player.sendMessage(line);
        else Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(line));
    }

    private void connect(Player player, String slot) {
        if (Bukkit.isPrimaryThread()) connector.connect(player, slot);
        else Bukkit.getScheduler().runTask(plugin, () -> connector.connect(player, slot));
    }

    private void lobby(Player player) {
        if (Bukkit.isPrimaryThread()) connector.lobby(player);
        else Bukkit.getScheduler().runTask(plugin, () -> connector.lobby(player));
    }
}
