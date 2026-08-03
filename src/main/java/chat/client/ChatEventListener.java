package chat.client;

import chat.protocol.Message;

public interface ChatEventListener {
    void onEvent(Message message);

    void onDisconnected(String reason);
}
