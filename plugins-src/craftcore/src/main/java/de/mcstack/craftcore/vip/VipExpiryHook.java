package de.mcstack.craftcore.vip;

import java.util.UUID;

// TODO Frage 11 (Rangablauf) offen
@FunctionalInterface
public interface VipExpiryHook {
    VipExpiryHook NO_OP = id -> {};
    void onRankLost(UUID id);
}
