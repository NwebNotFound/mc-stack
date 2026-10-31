package de.mcstack.craftcore.vip;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VipAccessRulesTest {
    @Test void allCombinationsFailClosedExceptBypass() {
        for (int bits=0; bits<32; bits++) {
            boolean owner=(bits&1)!=0, member=(bits&2)!=0, bypass=(bits&4)!=0, db=(bits&8)!=0, valid=(bits&16)!=0;
            assertEquals(bypass || (db && valid && (owner || member)),VipAccessRules.decide(owner,member,bypass,db,valid),"Kombination "+bits);
        }
    }
}
