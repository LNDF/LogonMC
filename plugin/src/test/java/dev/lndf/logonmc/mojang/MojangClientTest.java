package dev.lndf.logonmc.mojang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class MojangClientTest {

    @Test
    void parsesProfile() throws Exception {
        MojangProfile profile = MojangClient.parse("{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}");
        assertEquals(UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), profile.id());
        assertEquals("Notch", profile.name());
    }

    @Test
    void rejectsUnexpectedBody() {
        assertThrows(ProfileLookup.LookupException.class, () -> MojangClient.parse("{\"error\":\"x\"}"));
        assertThrows(ProfileLookup.LookupException.class, () -> MojangClient.parse("not json"));
    }
}
