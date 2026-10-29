package de.mcstack.craftcore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.BooleanSupplier;
final class BuyOrderTransaction {
 static boolean sell(Connection c,String action,String order,String seller,int amount,String item,BooleanSupplier remove)throws SQLException {
  if(!BuyOrderStore.sell(c,action,order,seller,amount,item,item))return false;
  if(!remove.getAsBoolean())throw new SQLException("Inventarentnahme fehlgeschlagen");
  return true;
 }
}
