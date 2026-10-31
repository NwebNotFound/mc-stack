package de.mcstack.craftcore.vip;

public final class VipNames {
    private VipNames() {}
    public static boolean validate(String name) { return name != null && name.matches("[A-Za-z0-9_-]{3,24}"); }
}
