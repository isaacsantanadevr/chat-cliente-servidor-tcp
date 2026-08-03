package chat.server;

import chat.protocol.Message;
import chat.protocol.MessageType;
import chat.protocol.ProtocolCodec;
import chat.transfer.FileRules;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class ChatServer implements AutoCloseable {
    private static final Pattern USERNAME = Pattern.compile("[\\p{L}\\p{N}_]{3,20}");
    private static final int SOCKET_TIMEOUT_MS = 0;

    private final String host;
    private final int requestedPort;
    private final long maxFileSize;
    private final ProtocolCodec codec = new ProtocolCodec();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();
    private final Map<String, FileRelay> transfers = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ServerSocket serverSocket;

    public ChatServer(String host, int port) {
        this(host, port, FileRules.DEFAULT_MAX_SIZE);
    }

    public ChatServer(String host, int port, long maxFileSize) {
        this.host = host;
        this.requestedPort = port;
        this.maxFileSize = maxFileSize;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Servidor já está em execução");
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host, requestedPort));
        serverSocket = socket;
        System.out.printf("Servidor ouvindo em %s:%d%n", host, socket.getLocalPort());
        try {
            while (running.get()) {
                Socket client = socket.accept();
                client.setSoTimeout(SOCKET_TIMEOUT_MS);
                client.setTcpNoDelay(true);
                executor.submit(() -> handleClient(client));
            }
        } catch (IOException error) {
            if (running.get()) {
                throw error;
            }
        } finally {
            running.set(false);
        }
    }

    public Thread startAsync() {
        Thread thread = new Thread(() -> {
            try {
                start();
            } catch (IOException error) {
                if (running.get()) {
                    throw new IllegalStateException("Falha no servidor", error);
                }
            }
        }, "chat-server-accept");
        thread.start();
        return thread;
    }

    public int port() {
        ServerSocket current = serverSocket;
        return current == null ? requestedPort : current.getLocalPort();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handleClient(Socket socket) {
        ClientConnection connection = null;
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            connection = new ClientConnection(socket, writer, codec);
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Message message = codec.decode(line);
                    if (connection.username() == null) {
                        handleLogin(connection, message);
                    } else if (!handleAuthenticated(connection, message)) {
                        break;
                    }
                } catch (JsonProcessingException error) {
                    safeSend(connection, Message.error("INVALID_MESSAGE", "JSON inválido"));
                } catch (IllegalArgumentException error) {
                    safeSend(connection, Message.error("INVALID_MESSAGE", error.getMessage()));
                } catch (RuntimeException error) {
                    safeSend(connection, Message.error("PROCESSING_ERROR", "Não foi possível processar a mensagem"));
                }
            }
        } catch (IOException error) {
            if (connection != null && connection.username() != null) {
                System.err.printf("Conexão perdida com %s: %s%n", connection.username(), error.getMessage());
            }
        } finally {
            disconnect(connection);
        }
    }

    private void handleLogin(ClientConnection connection, Message message) throws IOException {
        if (message.type != MessageType.LOGIN) {
            connection.send(Message.error("LOGIN_REQUIRED", "Faça login antes de usar o chat"));
            return;
        }
        String username = message.username == null ? "" : message.username.trim();
        if (!USERNAME.matcher(username).matches()) {
            connection.send(Message.error("INVALID_USERNAME", "Use de 3 a 20 letras, números ou underscore"));
            return;
        }
        String key = key(username);
        if (clients.putIfAbsent(key, connection) != null) {
            connection.send(Message.error("USERNAME_IN_USE", "Nome de usuário já está em uso"));
            return;
        }
        connection.username(username);
        Message ok = Message.of(MessageType.LOGIN_OK);
        ok.username = username;
        connection.send(ok);

        Message joined = Message.of(MessageType.USER_JOINED);
        joined.username = username;
        joined.timestamp = Instant.now().toString();
        broadcastExcept(joined, connection);
        broadcastUserList();
    }

    private boolean handleAuthenticated(ClientConnection connection, Message message) throws IOException {
        switch (message.type) {
            case BROADCAST -> broadcastChat(connection, message.content);
            case PRIVATE_MESSAGE -> privateChat(connection, message.to, message.content);
            case LIST_USERS -> connection.send(userList());
            case FILE_START -> startFile(connection, message);
            case FILE_CHUNK -> relayChunk(connection, message);
            case FILE_END -> finishFile(connection, message);
            case FILE_RECEIVED -> confirmFile(connection, message);
            case QUIT -> {
                connection.send(Message.of(MessageType.BYE));
                return false;
            }
            default -> connection.send(Message.error("UNEXPECTED_TYPE", "Tipo não aceito neste estado: " + message.type));
        }
        return true;
    }

    private void broadcastChat(ClientConnection sender, String content) {
        String text = requireContent(content);
        Message outgoing = chatMessage("BROADCAST", sender.username(), null, text);
        broadcast(outgoing);
    }

    private void privateChat(ClientConnection sender, String recipientName, String content) throws IOException {
        ClientConnection recipient = findClient(recipientName);
        if (recipient == null) {
            sender.send(Message.error("USER_NOT_FOUND", "Usuário não encontrado: " + recipientName));
            return;
        }
        Message outgoing = chatMessage("PRIVATE", sender.username(), recipient.username(), requireContent(content));
        sender.send(outgoing);
        if (recipient != sender) {
            safeSend(recipient, outgoing);
        }
    }

    private Message chatMessage(String scope, String from, String to, String content) {
        Message result = Message.of(MessageType.CHAT_MESSAGE);
        result.scope = scope;
        result.from = from;
        result.to = to;
        result.content = content;
        result.timestamp = Instant.now().toString();
        return result;
    }

    private String requireContent(String content) {
        String text = content == null ? "" : content.trim();
        if (text.isEmpty() || text.length() > 4000) {
            throw new IllegalArgumentException("A mensagem deve ter entre 1 e 4000 caracteres");
        }
        return text;
    }

    private void startFile(ClientConnection sender, Message message) throws IOException {
        ClientConnection recipient = findClient(message.to);
        if (recipient == null) {
            sender.send(Message.error("USER_NOT_FOUND", "Destinatário do arquivo não está conectado"));
            return;
        }
        String id = validateUuid(message.transferId);
        String fileName = FileRules.safeFileName(message.fileName);
        long size = message.fileSize == null ? -1 : message.fileSize;
        FileRules.validateSize(size, maxFileSize);
        int chunks = message.totalChunks == null ? -1 : message.totalChunks;
        if (chunks != FileRules.totalChunks(size)) {
            throw new IllegalArgumentException("Quantidade de blocos incompatível com o tamanho do arquivo");
        }
        FileRelay relay = new FileRelay(id, key(sender.username()), key(recipient.username()), fileName, size, chunks);
        if (transfers.putIfAbsent(id, relay) != null) {
            throw new IllegalArgumentException("transferId já está em uso");
        }
        Message outgoing = copyFileMetadata(message, MessageType.FILE_START);
        outgoing.from = sender.username();
        outgoing.to = recipient.username();
        if (!safeSend(recipient, outgoing)) {
            transfers.remove(id, relay);
            sender.send(Message.error("FILE_TRANSFER_FAILED", "Não foi possível iniciar a transferência no destinatário"));
        }
    }

    private void relayChunk(ClientConnection sender, Message message) throws IOException {
        FileRelay relay = ownedTransfer(sender, message.transferId);
        if (relay.waitingForReceipt || message.chunkIndex == null || message.chunkIndex != relay.nextChunk) {
            failTransfer(relay, sender, "Bloco fora de ordem");
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(message.data == null ? "" : message.data);
        } catch (IllegalArgumentException error) {
            failTransfer(relay, sender, "Bloco Base64 inválido");
            return;
        }
        if (raw.length > FileRules.RAW_CHUNK_SIZE || relay.receivedBytes + raw.length > relay.fileSize) {
            failTransfer(relay, sender, "Tamanho de bloco inválido");
            return;
        }
        relay.digest.update(raw);
        relay.receivedBytes += raw.length;
        relay.nextChunk++;
        ClientConnection recipient = clients.get(relay.recipientKey);
        if (recipient == null) {
            failTransfer(relay, sender, "Destinatário desconectou durante o envio");
            return;
        }
        Message outgoing = Message.of(MessageType.FILE_CHUNK);
        outgoing.transferId = relay.transferId;
        outgoing.chunkIndex = message.chunkIndex;
        outgoing.data = message.data;
        if (!safeSend(recipient, outgoing)) {
            failTransfer(relay, sender, "Destinatário desconectou durante o envio");
        }
    }

    private void finishFile(ClientConnection sender, Message message) throws IOException {
        FileRelay relay = ownedTransfer(sender, message.transferId);
        String actualHash = HexFormat.of().formatHex(relay.digest.digest());
        if (relay.nextChunk != relay.totalChunks || relay.receivedBytes != relay.fileSize
                || message.sha256 == null || !actualHash.equalsIgnoreCase(message.sha256)) {
            failTransfer(relay, sender, "Tamanho, quantidade de blocos ou SHA-256 inválido");
            return;
        }
        ClientConnection recipient = clients.get(relay.recipientKey);
        if (recipient == null) {
            failTransfer(relay, sender, "Destinatário desconectou antes da conclusão");
            return;
        }
        relay.waitingForReceipt = true;
        Message outgoing = Message.of(MessageType.FILE_END);
        outgoing.transferId = relay.transferId;
        outgoing.sha256 = actualHash;
        if (!safeSend(recipient, outgoing)) {
            failTransfer(relay, sender, "Destinatário desconectou antes da conclusão");
        }
    }

    private void confirmFile(ClientConnection recipient, Message message) throws IOException {
        FileRelay relay = transfers.get(message.transferId);
        if (relay == null || !relay.recipientKey.equals(key(recipient.username())) || !relay.waitingForReceipt) {
            throw new IllegalArgumentException("Confirmação de transferência desconhecida");
        }
        ClientConnection sender = clients.get(relay.ownerKey);
        if (sender != null) {
            Message confirmation = Message.of(MessageType.FILE_RECEIVED);
            confirmation.transferId = relay.transferId;
            confirmation.fileName = relay.fileName;
            confirmation.to = recipient.username();
            confirmation.status = message.status == null ? "COMPLETED" : message.status;
            safeSend(sender, confirmation);
        }
        transfers.remove(relay.transferId, relay);
    }

    private Message copyFileMetadata(Message source, MessageType type) {
        Message target = Message.of(type);
        target.transferId = source.transferId;
        target.fileName = source.fileName;
        target.fileSize = source.fileSize;
        target.totalChunks = source.totalChunks;
        return target;
    }

    private FileRelay ownedTransfer(ClientConnection sender, String transferId) {
        FileRelay relay = transfers.get(transferId);
        if (relay == null || !relay.ownerKey.equals(key(sender.username()))) {
            throw new IllegalArgumentException("Transferência desconhecida");
        }
        return relay;
    }

    private void failTransfer(FileRelay relay, ClientConnection sender, String detail) throws IOException {
        transfers.remove(relay.transferId, relay);
        sender.send(Message.error("FILE_TRANSFER_FAILED", detail));
        ClientConnection recipient = clients.get(relay.recipientKey);
        if (recipient != null) {
            Message error = Message.error("FILE_TRANSFER_FAILED", detail);
            error.transferId = relay.transferId;
            safeSend(recipient, error);
        }
    }

    private String validateUuid(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("transferId deve ser um UUID válido");
        }
    }

    private ClientConnection findClient(String username) {
        return username == null ? null : clients.get(key(username));
    }

    private String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private Message userList() {
        Message list = Message.of(MessageType.USER_LIST);
        list.users = clients.values().stream()
                .map(ClientConnection::username)
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();
        return list;
    }

    private void broadcastUserList() {
        broadcast(userList());
    }

    private void broadcast(Message message) {
        for (ClientConnection client : new ArrayList<>(clients.values())) {
            safeSend(client, message);
        }
    }

    private void broadcastExcept(Message message, ClientConnection excluded) {
        for (ClientConnection client : new ArrayList<>(clients.values())) {
            if (client != excluded) {
                safeSend(client, message);
            }
        }
    }

    private boolean safeSend(ClientConnection client, Message message) {
        try {
            client.send(message);
            return true;
        } catch (IOException error) {
            try {
                client.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            return false;
        }
    }

    private void disconnect(ClientConnection connection) {
        if (connection == null || connection.username() == null) {
            return;
        }
        String key = key(connection.username());
        if (!clients.remove(key, connection)) {
            return;
        }
        transfers.entrySet().removeIf(entry -> {
            FileRelay relay = entry.getValue();
            return relay.ownerKey.equals(key) || relay.recipientKey.equals(key);
        });
        Message left = Message.of(MessageType.USER_LEFT);
        left.username = connection.username();
        left.timestamp = Instant.now().toString();
        broadcast(left);
        broadcastUserList();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ServerSocket current = serverSocket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException error) {
                System.err.println("Falha ao fechar servidor: " + error.getMessage());
            }
        }
        for (ClientConnection client : clients.values()) {
            safeSend(client, Message.of(MessageType.BYE));
            try {
                client.close();
            } catch (IOException error) {
                System.err.println("Falha ao fechar cliente: " + error.getMessage());
            }
        }
        clients.clear();
        transfers.clear();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
