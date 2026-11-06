package de.mcstack.status;
import java.io.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SwitchRequestTest {
  @Test void backendWireFixture() throws Exception {
    var request=SwitchRequest.decode(HexFormat.of().parseHex("00000000000000070008737572766976616c"));
    assertEquals(7,request.revision()); assertEquals("survival",request.target());
  }
  @Test void rejectsInvalidMessages() {
    for(String hex : new String[]{"0000","00000000000000000008737572766976616c","00000000000000070008737572766976616c00"})
      assertThrows(IOException.class,()->SwitchRequest.decode(HexFormat.of().parseHex(hex)));
    assertThrows(IOException.class,()->SwitchRequest.decode(new byte[81]));
  }
}
