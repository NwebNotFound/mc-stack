package de.mcstack.craftcore;

import java.sql.*;
import java.util.UUID;

final class TeamInviteStore {
  static String apply(Connection c,String id,String op,String value) throws SQLException {
      String[] args={op,value}; String team=one(c,"SELECT team_id FROM members WHERE uuid=?",id);
      if(op.equals("invite")) {
        String role=one(c,"SELECT role FROM members WHERE uuid=?",id);
        if(team==null || "MEMBER".equals(role)) return "Nur Owner und Admins duerfen einladen.";
        String target=one(c,"SELECT uuid FROM online_players WHERE name=? AND seen_at >= UNIX_TIMESTAMP()-15",args[1]);
        if(target==null) return "Spieler ist nicht im Netzwerk online.";
        if(one(c,"SELECT team_id FROM members WHERE uuid=?",target)!=null) return "Spieler ist bereits in einem Team.";
        exec(c,"INSERT INTO team_invites(id,uuid,team_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=VALUES(id)",UUID.randomUUID().toString(),target,team);
        return "Einladung erstellt fuer "+args[1]+".";
      }
      String invitation=op.equals("join") ? one(c,"SELECT i.id FROM team_invites i JOIN teams t ON t.id=i.team_id WHERE i.uuid=? AND t.name=?",id,args[1]) : args[1];
      String invitedTeam=one(c,"SELECT team_id FROM team_invites WHERE id=? AND uuid=?",invitation,id);
      if(invitedTeam==null) return "Diese Einladung ist nicht mehr vorhanden.";
      if(op.equals("decline")){exec(c,"DELETE FROM team_invites WHERE id=? AND uuid=?",invitation,id);return "Einladung abgelehnt.";}
      if(team!=null) return "Du bist bereits in einem Team.";
      String name=one(c,"SELECT name FROM teams WHERE id=?",invitedTeam);
      exec(c,"INSERT INTO members(uuid,team_id,role) VALUES (?,?,?)",id,invitedTeam,"MEMBER");
      exec(c,"DELETE FROM team_invites WHERE uuid=?",id); return "Team beigetreten: "+name;
  }
  private static String one(Connection c,String sql,Object... args) throws SQLException { try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(ResultSet r=q.executeQuery()){return r.next()?r.getString(1):null;}} }
  private static void exec(Connection c,String sql,Object... args) throws SQLException { try(PreparedStatement q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();} }
}
