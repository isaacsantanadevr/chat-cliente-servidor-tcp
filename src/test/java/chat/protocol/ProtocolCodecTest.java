package chat.protocol;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolCodecTest {
    private final ProtocolCodec codec = new ProtocolCodec();

    @Test
    void serializesAndReadsNewlineFramedMessages() throws Exception {
        Message first = Message.of(MessageType.BROADCAST);
        first.content = "Olá";
        Message second = Message.of(MessageType.LIST_USERS);
        String wire = codec.encode(first) + "\n" + codec.encode(second) + "\n";

        try (BufferedReader reader = new BufferedReader(new StringReader(wire))) {
            assertEquals("Olá", codec.decode(reader.readLine()).content);
            assertEquals(MessageType.LIST_USERS, codec.decode(reader.readLine()).type);
        }
    }

    @Test
    void rejectsUnsupportedVersionAndMissingType() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"version\":2,\"type\":\"LOGIN\"}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"version\":1}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"LOGIN\"}"));
    }
}
