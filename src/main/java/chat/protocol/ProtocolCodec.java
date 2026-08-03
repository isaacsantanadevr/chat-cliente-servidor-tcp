package chat.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ProtocolCodec {
    public static final int VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public String encode(Message message) throws JsonProcessingException {
        return mapper.writeValueAsString(message);
    }

    public Message decode(String json) throws JsonProcessingException {
        JsonNode tree = mapper.readTree(json);
        if (tree == null || !tree.isObject() || !tree.has("version")) {
            throw new IllegalArgumentException("Campo version é obrigatório");
        }
        Message message = mapper.treeToValue(tree, Message.class);
        if (message.version != VERSION) {
            throw new IllegalArgumentException("Versão de protocolo não suportada: " + message.version);
        }
        if (message.type == null) {
            throw new IllegalArgumentException("Campo type é obrigatório");
        }
        return message;
    }
}
