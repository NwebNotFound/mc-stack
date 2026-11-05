package de.mcstack.craftcore.vip;

/** Pure parts of "another server chosen while queued for a VIP world" (bug 4). */
public final class VipSwitch {
    /** Window in which Velocity may still be processing our VIP connect. */
    public static final long IN_FLIGHT_MS = 10_000;

    private VipSwitch() {}

    public static String leftQueueText(String server) {
        return "VIP-Warteschlange verlassen: du wechselst zu " + (server == null ? "einem anderen Server" : server) + ".";
    }

    public static boolean recent(Long sentAt, long now) {
        return sentAt != null && now - sentAt >= 0 && now - sentAt < IN_FLIGHT_MS;
    }
}
