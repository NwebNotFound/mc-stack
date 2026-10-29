package dev.mcstack.afk;

final class IdlePolicy {
    private IdlePolicy() { }
    static boolean shouldKick(long idle, long threshold) { return threshold > 0 && idle >= threshold; }
    static boolean shouldMark(long idle, long threshold) { return threshold > 0 && idle >= threshold; }
}
