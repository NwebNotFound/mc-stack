package de.mcstack.craftcore.vip;

/** Boss bar and chat text for one world start. Pure, so the wording and the bar are tested. */
public final class VipStartProgress {
    public enum Phase { STARTING, READY, FAILED }
    public record Update(Phase phase, float fraction, String title, String message, String slot) {}

    private VipStartProgress() {}

    public static Update starting(String slot, long elapsedMillis, long timeoutMillis) {
        float fraction = timeoutMillis <= 0 ? 0.05f
            : Math.min(0.95f, Math.max(0.05f, (float) elapsedMillis / (float) timeoutMillis));
        int percent = Math.round(fraction * 100f);
        String where = slot == null || slot.isBlank() ? "" : " (" + slot + ")";
        return new Update(Phase.STARTING, fraction, "VIP-Welt startet ... " + percent + "%",
            "Deine Welt startet" + where + " ...", slot);
    }

    public static Update ready(String slot) {
        return new Update(Phase.READY, 1f, "VIP-Welt bereit", "Verbinde mit " + slot + " ...", slot);
    }

    public static Update failed(String reason) {
        return new Update(Phase.FAILED, 0f, "VIP-Welt Start fehlgeschlagen", reasonText(reason), null);
    }

    public static String reasonText(String reason) {
        return switch (reason == null ? "" : reason) {
            case VipStartCoordinator.TIMEOUT -> "Der Start dauert zu lange. Bitte versuche es erneut.";
            case VipStartCoordinator.NO_FREE_SLOT -> "Alle VIP-Slots sind belegt. Bitte spaeter erneut versuchen.";
            case VipStartCoordinator.RAM_BUDGET -> "Gerade ist kein RAM frei. Bitte spaeter erneut versuchen.";
            case VipStartCoordinator.WORLD_NOT_FOUND -> "Der Weltordner fehlt auf dem Host.";
            case VipStartCoordinator.HOST_UNAVAILABLE -> "Der VIP-Host antwortet nicht.";
            case VipStartCoordinator.CANCELLED -> "Start abgebrochen.";
            default -> "Die VIP-Welt konnte nicht gestartet werden.";
        };
    }
}
