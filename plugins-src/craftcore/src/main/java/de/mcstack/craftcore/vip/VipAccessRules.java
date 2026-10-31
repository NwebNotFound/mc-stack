package de.mcstack.craftcore.vip;

public final class VipAccessRules {
    private VipAccessRules() {}
    public static boolean decide(boolean isOwner, boolean isMember, boolean hasBypass, boolean dbOk, boolean worldIdValid) {
        return hasBypass || (dbOk && worldIdValid && (isOwner || isMember));
    }
}
