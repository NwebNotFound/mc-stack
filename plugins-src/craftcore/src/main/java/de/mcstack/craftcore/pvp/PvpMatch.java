package de.mcstack.craftcore.pvp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public final class PvpMatch {
    public static final long ROUND_LIMIT_MS = 300_000;

    private final String matchId;
    private final long startedAt;
    private final UUID a;
    private final UUID b;
    private final List<String> kits;
    private final List<RoundRecord> rounds = new ArrayList<>();
    private long roundStartedAt;
    private UUID disconnectWinner;

    public PvpMatch(String matchId, UUID a, UUID b, String kitA, String kitB,
                    Random random, long startedAtMs) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.startedAt = startedAtMs;
        this.a = Objects.requireNonNull(a, "a");
        this.b = Objects.requireNonNull(b, "b");
        if (a.equals(b)) throw new IllegalArgumentException("Players must be different");
        this.kits = PvpLogic.kits(kitA, kitB, random);
        this.roundStartedAt = startedAtMs;
    }

    public record RoundRecord(int roundNo, String kit, UUID winner, long durationMs) {}

    public String matchId() { return matchId; }
    public long startedAt() { return startedAt; }
    public int roundNo() { return Math.min(rounds.size() + 1, 3); }
    public String currentKit() { return kits.get(roundNo() - 1); }
    public String kitRound1() { return kits.get(0); }
    public long roundStartedAt() { return roundStartedAt; }
    public void startRound(long nowMs) { roundStartedAt = nowMs; }
    public List<RoundRecord> rounds() { return List.copyOf(rounds); }

    public RoundRecord death(UUID dead, long nowMs) {
        if (finished()) return null;
        return record(other(dead), nowMs);
    }

    public RoundRecord disconnect(UUID who, long nowMs) {
        if (finished()) return null;
        UUID winner = other(who);
        RoundRecord round = record(winner, nowMs);
        disconnectWinner = winner;
        return round;
    }

    public RoundRecord tick(long nowMs) {
        if (finished() || nowMs - roundStartedAt < ROUND_LIMIT_MS) return null;
        return record(null, nowMs);
    }

    public boolean finished() {
        if (disconnectWinner != null) return true;
        List<PvpLogic.Result> results = rounds.stream().map(round ->
            round.winner() == null ? PvpLogic.Result.DRAW :
                round.winner().equals(a) ? PvpLogic.Result.A : PvpLogic.Result.B).toList();
        return PvpLogic.result(results) != PvpLogic.Result.PENDING;
    }

    public UUID matchWinner() {
        if (!finished()) return null;
        if (disconnectWinner != null) return disconnectWinner;
        long winsA = rounds.stream().filter(round -> a.equals(round.winner())).count();
        long winsB = rounds.stream().filter(round -> b.equals(round.winner())).count();
        return winsA == winsB ? null : winsA > winsB ? a : b;
    }

    public String result() { return matchWinner() == null ? "draw" : "win"; }

    public UUID matchLoser() {
        UUID winner = matchWinner();
        return winner == null ? null : winner.equals(a) ? b : a;
    }

    private UUID other(UUID player) {
        if (a.equals(player)) return b;
        if (b.equals(player)) return a;
        throw new IllegalArgumentException("Unknown player");
    }

    private RoundRecord record(UUID winner, long nowMs) {
        RoundRecord round = new RoundRecord(roundNo(), currentKit(), winner, nowMs - roundStartedAt);
        rounds.add(round);
        return round;
    }
}
