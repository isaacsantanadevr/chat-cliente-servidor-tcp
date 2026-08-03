package chat.client;

import chat.protocol.Message;
import chat.protocol.MessageType;
import chat.protocol.ProtocolCodec;
import chat.transfer.FileRules;
import chat.transfer.Hashing;
import chat.transfer.IncomingFileManager;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpChatClient implements AutoCloseable {
    private final ProtocolCodec codec = new ProtocolCodec();
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final Object writeLock = new Object();
    private final IncomingFileManager incomingFiles;
    private final long maxFileSize;
    private final AtomicBoolean connected = new AtomicBoolean();
    private volatile Socket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private volatile ChatEventListener listener;
    private volatile String username;

    public TcpChatClient(Path downloadDirectory) {
        this(downloadDirectory, FileRules.DEFAULT_MAX_SIZE);
    }

    public TcpChatClient(Path downloadDirectory, long maxFileSize) {
        this.incomingFiles = new IncomingFileManager(downloadDirectory, maxFileSize);
        this.maxFileSize = maxFileSize;
    }

    public Message connect(String host, int port, String username, ChatEventListener listener) throws IOException {
        if (!connected.compareAndSet(false, true)) {
            throw new IllegalStateException("Cliente já está conectado");
        }
        this.listener = listener;
        this.username = username;
        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, port), 5000);
            newSocket.setTcpNoDelay(true);
            socket = newSocket;
            reader = new BufferedReader(new InputStreamReader(newSocket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));

            Message login = Message.of(MessageType.LOGIN);
            login.username = username;
            send(login);
            String responseLine = reader.readLine();
            if (responseLine == null) {
                throw new IOException("Servidor encerrou a conexão durante o login");
            }
            Message response = codec.decode(responseLine);
            if (response.type != MessageType.LOGIN_OK) {
                connected.set(false);
                closeSocket();
                return response;
            }
            listener.onEvent(response);
            ioExecutor.submit(this::readLoop);
            return response;
        } catch (IOException | RuntimeException error) {
            connected.set(false);
            closeSocket();
            throw error;
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String username() {
        return username;
    }

    public void sendBroadcast(String content) throws IOException {
        Message message = Message.of(MessageType.BROADCAST);
        message.content = content;
        send(message);
    }

    public void sendPrivateMessage(String recipient, String content) throws IOException {
        Message message = Message.of(MessageType.PRIVATE_MESSAGE);
        message.to = recipient;
        message.content = content;
        send(message);
    }

    public void requestUsers() throws IOException {
        send(Message.of(MessageType.LIST_USERS));
    }

    public void sendFile(Path path, String recipient) {
        ioExecutor.submit(() -> {
            try {
                transferFile(path, recipient);
            } catch (IOException | RuntimeException error) {
                emit(Message.error("FILE_SEND_FAILED", error.getMessage()));
            }
        });
    }

    private void transferFile(Path path, String recipient) throws IOException {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("Selecione um destinatário para o arquivo");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Selecione um arquivo válido");
        }
        long size = Files.size(path);
        FileRules.validateSize(size, maxFileSize);
        String fileName = FileRules.safeFileName(path.getFileName().toString());
        int totalChunks = FileRules.totalChunks(size);
        String transferId = UUID.randomUUID().toString();

        Message start = Message.of(MessageType.FILE_START);
        start.transferId = transferId;
        start.to = recipient;
        start.fileName = fileName;
        start.fileSize = size;
        start.totalChunks = totalChunks;
        send(start);
        emitProgress(start, 0, "SENDING", "OUTGOING");

        MessageDigest digest = Hashing.sha256Digest();
        byte[] buffer = new byte[FileRules.RAW_CHUNK_SIZE];
        int chunkIndex = 0;
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                byte[] exact = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                Message chunk = Message.of(MessageType.FILE_CHUNK);
                chunk.transferId = transferId;
                chunk.chunkIndex = chunkIndex++;
                chunk.data = Base64.getEncoder().encodeToString(exact);
                send(chunk);
                emitProgress(start, (int) ((100L * chunkIndex) / totalChunks), "SENDING", "OUTGOING");
            }
        }
        Message end = Message.of(MessageType.FILE_END);
        end.transferId = transferId;
        end.sha256 = HexFormat.of().formatHex(digest.digest());
        send(end);
    }

    public void disconnect() {
        if (!connected.get()) {
            return;
        }
        try {
            send(Message.of(MessageType.QUIT));
        } catch (IOException error) {
            finishDisconnect(error.getMessage());
        }
    }

    private void send(Message message) throws IOException {
        BufferedWriter current = writer;
        if (!connected.get() || current == null) {
            throw new IOException("Cliente não está conectado");
        }
        String json = codec.encode(message);
        synchronized (writeLock) {
            current.write(json);
            current.newLine();
            current.flush();
        }
    }

    private void readLoop() {
        String reason = "Conexão encerrada pelo servidor";
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                Message message;
                try {
                    message = codec.decode(line);
                } catch (IllegalArgumentException error) {
                    emit(Message.error("INVALID_SERVER_MESSAGE", error.getMessage()));
                    continue;
                }
                if (message.type == MessageType.BYE) {
                    reason = "Desconectado";
                    break;
                }
                handleIncoming(message);
            }
        } catch (IOException error) {
            reason = "Conexão perdida: " + error.getMessage();
        } finally {
            finishDisconnect(reason);
        }
    }

    private void handleIncoming(Message message) {
        try {
            switch (message.type) {
                case FILE_START -> {
                    incomingFiles.begin(message);
                    message.direction = "INCOMING";
                    message.status = "RECEIVING";
                    emit(message);
                }
                case FILE_CHUNK -> {
                    int progress = incomingFiles.acceptChunk(message);
                    Message update = Message.of(MessageType.FILE_PROGRESS);
                    update.transferId = message.transferId;
                    update.progress = progress;
                    update.direction = "INCOMING";
                    update.status = "RECEIVING";
                    emit(update);
                }
                case FILE_END -> {
                    Path completed = incomingFiles.finish(message);
                    Message received = Message.of(MessageType.FILE_RECEIVED);
                    received.transferId = message.transferId;
                    received.fileName = completed.getFileName().toString();
                    received.path = completed.toString();
                    received.progress = 100;
                    received.direction = "INCOMING";
                    received.status = "COMPLETED";
                    emit(received);
                    Message confirmation = Message.of(MessageType.FILE_RECEIVED);
                    confirmation.transferId = message.transferId;
                    confirmation.status = "COMPLETED";
                    send(confirmation);
                }
                case ERROR -> {
                    if (message.transferId != null) {
                        incomingFiles.abort(message.transferId);
                    }
                    emit(message);
                }
                default -> emit(message);
            }
        } catch (IOException | RuntimeException error) {
            incomingFiles.abort(message.transferId);
            Message failure = Message.error("FILE_RECEIVE_FAILED", error.getMessage());
            failure.transferId = message.transferId;
            emit(failure);
        }
    }

    private void emitProgress(Message metadata, int progress, String status, String direction) {
        Message event = Message.of(MessageType.FILE_PROGRESS);
        event.transferId = metadata.transferId;
        event.fileName = metadata.fileName;
        event.fileSize = metadata.fileSize;
        event.to = metadata.to;
        event.progress = progress;
        event.status = status;
        event.direction = direction;
        emit(event);
    }

    private void emit(Message message) {
        ChatEventListener current = listener;
        if (current != null) {
            current.onEvent(message);
        }
    }

    private void finishDisconnect(String reason) {
        if (!connected.getAndSet(false)) {
            return;
        }
        incomingFiles.close();
        closeSocket();
        ChatEventListener current = listener;
        if (current != null) {
            current.onDisconnected(reason);
        }
    }

    private void closeSocket() {
        Socket current = socket;
        if (current != null) {
            try {
                current.close();
            } catch (IOException error) {
                System.err.println("Falha ao fechar socket do cliente: " + error.getMessage());
            }
        }
    }

    @Override
    public void close() {
        disconnect();
        finishDisconnect("Cliente encerrado");
        ioExecutor.shutdownNow();
    }
}
