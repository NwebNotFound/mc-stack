package de.mcstack.craftcore.vip;

import java.util.function.BooleanSupplier;

/**
 * The host may report a slot "online" while the Paper server behind it does not accept players yet; a Connect then
 * shows Velocity's raw "Unable to connect you to vipworld-1" (Nils, 05.11.2026). The auto-connect therefore waits until
 * the slot answers a Minecraft status ping, bounded, and tries the connect at most twice.
 */
public final class VipReachable {
    public static final int PING_ATTEMPTS = 30;
    public static final long PING_DELAY_MS = 2_000;
    public static final int CONNECT_ATTEMPTS = 2;
    public static final long CONNECT_CHECK_TICKS = 100;
    public static final String NOT_REACHABLE = "Deine VIP-Welt antwortet noch nicht. Versuch es gleich ueber \"Beitreten\" nochmal.";
    public static final String RETRYING = "Verbindung zur VIP-Welt klappt noch nicht, neuer Versuch ...";

    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private VipReachable() {}

    /** Pings until the slot answers, at most {@code attempts} times. Off the server thread. */
    public static boolean waitFor(BooleanSupplier ping, int attempts, long delayMs, Sleeper sleeper) {
        for (int i = 1; i <= attempts; i++) {
            if (ping.getAsBoolean()) return true;
            if (i < attempts) {
                try { sleeper.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return false;
    }

    /** What to do when the player is still on this server {@link #CONNECT_CHECK_TICKS} after connect number {@code attempt}. */
    public static String afterStillHere(int attempt) {
        return attempt < CONNECT_ATTEMPTS ? RETRYING : NOT_REACHABLE;
    }
}
