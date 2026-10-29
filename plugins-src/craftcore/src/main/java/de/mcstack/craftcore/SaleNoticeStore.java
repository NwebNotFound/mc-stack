package de.mcstack.craftcore;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;

final class SaleNoticeStore {
  record Summary(String item, long amount, long cents) {}
  static void record(Connection c,String id,String seller,String item,int amount,long cp,long time) throws SQLException {
    try(PreparedStatement q=c.prepareStatement("INSERT INTO sale_notices(id,seller,item,amount,cp,sold_at,reported) VALUES(?,?,?,?,?,?,FALSE)")){q.setString(1,id);q.setString(2,seller);q.setString(3,item);q.setInt(4,amount);q.setLong(5,cp);q.setLong(6,time);q.executeUpdate();}
  }
  static void deliver(Connection c,String seller,Consumer<Summary> send) throws SQLException {
    Map<String,long[]> totals=new LinkedHashMap<>(); List<String> ids=new ArrayList<>();
    try(PreparedStatement q=c.prepareStatement("SELECT id,item,amount,cp FROM sale_notices WHERE seller=? AND reported=FALSE ORDER BY sold_at,id FOR UPDATE")){q.setString(1,seller);try(ResultSet r=q.executeQuery()){while(r.next()){ids.add(r.getString(1));long[] sum=totals.computeIfAbsent(r.getString(2),k->new long[2]);sum[0]=Math.addExact(sum[0],r.getLong(3));sum[1]=Math.addExact(sum[1],r.getLong(4));}}}
    for(var e:totals.entrySet())send.accept(new Summary(e.getKey(),e.getValue()[0],e.getValue()[1]));
    try(PreparedStatement q=c.prepareStatement("UPDATE sale_notices SET reported=TRUE WHERE id=? AND reported=FALSE")){for(String id:ids){q.setString(1,id);q.executeUpdate();}}
  }
}
