package chat;

import chat.server.ChatServer;
import chat.ui.ChatApplication;

import java.io.IOException;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !"server".equalsIgnoreCase(args[0])) {
            ChatApplication.main(args);
            return;
        }
        String host = option(args, "--host", "0.0.0.0");
        int port = Integer.parseInt(option(args, "--port", "5000"));
        ChatServer server = new ChatServer(host, port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "chat-server-shutdown"));
        server.start();
    }

    private static String option(String[] args, String name, String fallback) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return args[index + 1];
            }
        }
        return fallback;
    }
}
