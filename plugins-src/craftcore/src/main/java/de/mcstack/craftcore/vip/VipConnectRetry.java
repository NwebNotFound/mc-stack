package de.mcstack.craftcore.vip;

/**
 * A slot that the host reports as ready may still be missing in Velocity for a moment.
 * The lobby therefore sends {@code Connect} up to {@link #ATTEMPTS} times, two seconds apart,
 * and only while the player is still online on this server.
 */
public final class VipConnectRetry {
    public static final int ATTEMPTS = 3;
    public static final long DELAY_MILLIS = 2_000L;

    private VipConnectRetry() {}

    /** {@code attempt} is 0-based; a player who already switched servers gets no second Connect. */
    public static boolean maySend(int attempt, boolean online, boolean stillOnServer) {
        return attempt >= 0 && attempt < ATTEMPTS && online && stillOnServer;
    }

    public static long delayTicks(int attempt) {
        return Math.max(0, attempt) * DELAY_MILLIS / 50L;
    }
}
