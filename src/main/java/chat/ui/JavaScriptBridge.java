package chat.ui;

import chat.client.ChatEventListener;
import chat.client.TcpChatClient;
import chat.protocol.Message;
import chat.protocol.MessageType;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class JavaScriptBridge implements AutoCloseable {
    private final FrontendNotifier notifier;
    private final Window owner;
    private final Path downloadDirectory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-ui-network");
        thread.setDaemon(true);
        return thread;
    });
    private volatile TcpChatClient client;

    public JavaScriptBridge(FrontendNotifier notifier, Window owner, Path downloadDirectory) {
        this.notifier = notifier;
        this.owner = owner;
        this.downloadDirectory = downloadDirectory.toAbsolutePath().normalize();
    }

    public void connect(String host, int port, String username) {
        worker.submit(() -> {
            if (client != null && client.isConnected()) {
                notifier.sendError("ALREADY_CONNECTED", "Já existe uma conexão ativa");
                return;
            }
            TcpChatClient candidate = new TcpChatClient(downloadDirectory);
            try {
                Message response = candidate.connect(host.trim(), port, username.trim(), new UiListener());
                if (response.type == MessageType.ERROR) {
                    notifier.send(response);
                    candidate.close();
                    return;
                }
                client = candidate;
                candidate.requestUsers();
            } catch (IOException | RuntimeException error) {
                candidate.close();
                notifier.sendError("CONNECTION_FAILED", readable(error));
            }
        });
    }

    public void disconnect() {
        TcpChatClient current = client;
        if (current != null) {
            current.disconnect();
        }
    }

    public void sendBroadcast(String content) {
        runNetwork(() -> requiredClient().sendBroadcast(content));
    }

    public void sendPrivateMessage(String recipient, String content) {
        runNetwork(() -> requiredClient().sendPrivateMessage(recipient, content));
    }

    public void chooseAndSendFile(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            notifier.sendError("RECIPIENT_REQUIRED", "Selecione um usuário antes de enviar um arquivo");
            return;
        }
        Platform.runLater(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Selecionar arquivo (máximo de 10 MB)");
            java.io.File selected = chooser.showOpenDialog(owner);
            if (selected != null) {
                try {
                    requiredClient().sendFile(selected.toPath(), recipient);
                } catch (IOException error) {
                    notifier.sendError("FILE_SEND_FAILED", error.getMessage());
                }
            }
        });
    }

    public void openFile(String suppliedPath) {
        try {
            Path path = Path.of(suppliedPath).toAbsolutePath().normalize();
            if (!path.startsWith(downloadDirectory)) {
                throw new IllegalArgumentException("O arquivo não pertence à pasta de downloads do chat");
            }
            ChatApplication.openDocument(path.toUri().toString());
        } catch (RuntimeException error) {
            notifier.sendError("OPEN_FILE_FAILED", readable(error));
        }
    }

    private TcpChatClient requiredClient() throws IOException {
        TcpChatClient current = client;
        if (current == null || !current.isConnected()) {
            throw new IOException("Cliente não está conectado");
        }
        return current;
    }

    private void runNetwork(NetworkAction action) {
        worker.submit(() -> {
            try {
                action.run();
            } catch (IOException | RuntimeException error) {
                notifier.sendError("SEND_FAILED", readable(error));
            }
        });
    }

    private String readable(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void close() {
        TcpChatClient current = client;
        if (current != null) {
            current.close();
        }
        worker.shutdownNow();
    }

    private final class UiListener implements ChatEventListener {
        @Override
        public void onEvent(Message message) {
            notifier.send(message);
        }

        @Override
        public void onDisconnected(String reason) {
            client = null;
            notifier.disconnected(reason);
        }
    }

    @FunctionalInterface
    private interface NetworkAction {
        void run() throws IOException;
    }
}
