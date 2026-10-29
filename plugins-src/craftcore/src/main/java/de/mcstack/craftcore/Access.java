package de.mcstack.craftcore;
final class Access {
  static boolean allowed(String player, String owner, String claimTeam, String playerTeam) {
    return player.equals(owner) || (claimTeam != null && claimTeam.equals(playerTeam));
  }
}
