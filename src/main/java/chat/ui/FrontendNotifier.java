package chat.ui;

import chat.protocol.Message;
import chat.protocol.MessageType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.web.WebEngine;

public final class FrontendNotifier {
    private final WebEngine webEngine;
    private final ObjectMapper mapper = new ObjectMapper();

    public FrontendNotifier(WebEngine webEngine) {
        this.webEngine = webEngine;
    }

    public void send(Message message) {
        final String json;
        try {
            json = mapper.writeValueAsString(message);
        } catch (JsonProcessingException error) {
            sendError("UI_SERIALIZATION_ERROR", error.getMessage());
            return;
        }
        runScript("window.receiveChatEvent && window.receiveChatEvent(" + json + ")");
    }

    public void sendError(String code, String detail) {
        Message error = Message.error(code, detail == null ? "Erro inesperado" : detail);
        try {
            String json = mapper.writeValueAsString(error);
            runScript("window.receiveChatEvent && window.receiveChatEvent(" + json + ")");
        } catch (JsonProcessingException serializationError) {
            System.err.println("Falha ao comunicar erro à interface: " + serializationError.getMessage());
        }
    }

    public void disconnected(String reason) {
        Message event = Message.of(MessageType.BYE);
        event.content = reason;
        send(event);
    }

    public void setDefaults(String host, int port, String username) {
        try {
            String json = mapper.writeValueAsString(new Defaults(host, port, username));
            runScript("window.setConnectionDefaults && window.setConnectionDefaults(" + json + ")");
        } catch (JsonProcessingException error) {
            sendError("UI_SERIALIZATION_ERROR", error.getMessage());
        }
    }

    private void runScript(String script) {
        Platform.runLater(() -> webEngine.executeScript(script));
    }

    private record Defaults(String host, int port, String username) {
    }
}
