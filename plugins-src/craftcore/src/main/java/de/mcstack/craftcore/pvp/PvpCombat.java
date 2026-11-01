package de.mcstack.craftcore.pvp;

/** Knockback and reel that vanilla skips when a zero-damage hit is cancelled. */
public final class PvpCombat {
    private PvpCombat() {}
    public static boolean keepsKnockback(boolean inActiveRound, String projectile) {
        return inActiveRound && isUtilityProjectile(projectile);
    }
    public static boolean isUtilityProjectile(String projectile) {
        return "SNOWBALL".equals(projectile) || "EGG".equals(projectile) || "FISHING_BOBBER".equals(projectile);
    }
    /** Vanilla thrown-projectile knockback: half the horizontal speed, then push away from the source. */
    public static double[] snowballKnockback(double vx, double vy, double vz, double sourceX, double sourceZ, double victimX, double victimZ, boolean onGround) {
        double dx=sourceX-victimX, dz=sourceZ-victimZ, len=Math.sqrt(dx*dx+dz*dz);
        if (len<1e-8) return new double[] {vx, vy, vz};
        double strength=0.4;
        return new double[] {vx/2 - dx/len*strength, onGround ? Math.min(0.4, vy/2+strength) : vy, vz/2 - dz/len*strength};
    }
    /** Pull the hooked player toward the fisher, matching the fishing hook's 0.1 scale. */
    public static double[] reelPull(double vx, double vy, double vz, double victimX, double victimY, double victimZ, double fisherX, double fisherY, double fisherZ) {
        double dx=fisherX-victimX, dy=fisherY-victimY, dz=fisherZ-victimZ;
        return new double[] {vx+dx*0.1, vy+dy*0.1, vz+dz*0.1};
    }
}
