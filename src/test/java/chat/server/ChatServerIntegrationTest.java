package chat.server;

import chat.protocol.Message;
import chat.protocol.MessageType;
import chat.transfer.Hashing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServerIntegrationTest {
    private ChatServer server;

    @BeforeEach
    void startServer() {
        server = new ChatServer("127.0.0.1", 0);
        server.startAsync();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(3));
        while (server.port() == 0 && Instant.now().isBefore(deadline)) Thread.onSpinWait();
        assertTrue(server.port() > 0, "servidor deve escolher uma porta livre");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void acceptsValidLoginAndRejectsDuplicateIgnoringCase() throws Exception {
        try (RawTestClient first = new RawTestClient(server.port(), "Isaac");
             RawTestClient duplicate = new RawTestClient(server.port(), "isaac")) {
            assertEquals(MessageType.LOGIN_OK, first.readUntil(MessageType.LOGIN_OK).type);
            Message error = duplicate.readUntil(MessageType.ERROR);
            assertEquals("USERNAME_IN_USE", error.code);
        }
    }

    @Test
    void broadcastsAndReturnsSortedUserList() throws Exception {
        try (RawTestClient alice = logged("alice"); RawTestClient bruno = logged("bruno")) {
            Message broadcast = Message.of(MessageType.BROADCAST);
            broadcast.content = "Olá, turma";
            alice.send(broadcast);
            assertEquals("Olá, turma", alice.readUntil(MessageType.CHAT_MESSAGE).content);
            Message delivered = bruno.readUntil(MessageType.CHAT_MESSAGE);
            assertEquals("alice", delivered.from);
            assertEquals("BROADCAST", delivered.scope);

            bruno.send(Message.of(MessageType.LIST_USERS));
            assertEquals(List.of("alice", "bruno"), bruno.readUntil(MessageType.USER_LIST).users);
        }
    }

    @Test
    void sendsPrivateOnlyToParticipantsAndReportsMissingRecipient() throws Exception {
        try (RawTestClient alice = logged("alice"); RawTestClient bruno = logged("bruno")) {
            Message privateMessage = Message.of(MessageType.PRIVATE_MESSAGE);
            privateMessage.to = "BRUNO";
            privateMessage.content = "segredo";
            alice.send(privateMessage);
            assertEquals("segredo", alice.readUntil(MessageType.CHAT_MESSAGE).content);
            assertEquals("PRIVATE", bruno.readUntil(MessageType.CHAT_MESSAGE).scope);

            privateMessage.to = "nobody";
            alice.send(privateMessage);
            assertEquals("USER_NOT_FOUND", alice.readUntil(MessageType.ERROR).code);
        }
    }

    @Test
    void handlesNormalAndUnexpectedExitWithoutGhostUsers() throws Exception {
        try (RawTestClient observer = logged("observer")) {
            RawTestClient normal = logged("normal");
            normal.quit();
            assertEquals("normal", observer.readUntil(MessageType.USER_LEFT).username);
            normal.close();

            RawTestClient abrupt = logged("abrupt");
            observer.readUntil(MessageType.USER_JOINED);
            abrupt.closeAbruptly();
            assertEquals("abrupt", observer.readUntil(MessageType.USER_LEFT).username);
            observer.send(Message.of(MessageType.LIST_USERS));
            assertEquals(List.of("observer"), observer.readUntil(MessageType.USER_LIST).users);
        }
    }

    @Test
    void acceptsThreeConcurrentClientsAndKeepsWorkingAfterFailure() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<RawTestClient>> tasks = List.of(
                    () -> logged("user_a"), () -> logged("user_b"), () -> logged("user_c"));
            List<Future<RawTestClient>> futures = pool.invokeAll(tasks);
            try (RawTestClient a = futures.get(0).get(); RawTestClient b = futures.get(1).get(); RawTestClient c = futures.get(2).get()) {
                b.closeAbruptly();
                a.readUntil(MessageType.USER_LEFT);
                Message broadcast = Message.of(MessageType.BROADCAST);
                broadcast.content = "servidor continua ativo";
                c.send(broadcast);
                assertEquals("servidor continua ativo", a.readUntil(MessageType.CHAT_MESSAGE).content);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void relaysValidatedFileChunksAndReceipt() throws Exception {
        byte[] bytes = "arquivo de integração".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = Files.createTempFile("chat-test", ".txt");
        Files.write(source, bytes);
        try (RawTestClient alice = logged("alice"); RawTestClient bruno = logged("bruno")) {
            String id = UUID.randomUUID().toString();
            Message start = Message.of(MessageType.FILE_START);
            start.transferId = id;
            start.to = "bruno";
            start.fileName = "teste.txt";
            start.fileSize = (long) bytes.length;
            start.totalChunks = 1;
            alice.send(start);
            assertEquals("alice", bruno.readUntil(MessageType.FILE_START).from);

            Message chunk = Message.of(MessageType.FILE_CHUNK);
            chunk.transferId = id;
            chunk.chunkIndex = 0;
            chunk.data = Base64.getEncoder().encodeToString(bytes);
            alice.send(chunk);
            assertEquals(chunk.data, bruno.readUntil(MessageType.FILE_CHUNK).data);

            Message end = Message.of(MessageType.FILE_END);
            end.transferId = id;
            end.sha256 = Hashing.sha256(source);
            alice.send(end);
            assertEquals(end.sha256, bruno.readUntil(MessageType.FILE_END).sha256);

            Message receipt = Message.of(MessageType.FILE_RECEIVED);
            receipt.transferId = id;
            receipt.status = "COMPLETED";
            bruno.send(receipt);
            assertEquals("COMPLETED", alice.readUntil(MessageType.FILE_RECEIVED).status);
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void fileFailureDoesNotDropChatConnection() throws Exception {
        try (RawTestClient alice = logged("alice"); RawTestClient bruno = logged("bruno")) {
            String id = UUID.randomUUID().toString();
            Message start = Message.of(MessageType.FILE_START);
            start.transferId = id;
            start.to = "bruno";
            start.fileName = "falha.txt";
            start.fileSize = 1L;
            start.totalChunks = 1;
            alice.send(start);
            bruno.readUntil(MessageType.FILE_START);

            Message invalidChunk = Message.of(MessageType.FILE_CHUNK);
            invalidChunk.transferId = id;
            invalidChunk.chunkIndex = 1;
            invalidChunk.data = Base64.getEncoder().encodeToString(new byte[]{1});
            alice.send(invalidChunk);
            assertEquals("FILE_TRANSFER_FAILED", alice.readUntil(MessageType.ERROR).code);

            Message chat = Message.of(MessageType.BROADCAST);
            chat.content = "chat ainda funciona";
            alice.send(chat);
            assertEquals("chat ainda funciona", bruno.readUntil(MessageType.CHAT_MESSAGE).content);
        }
    }

    private RawTestClient logged(String username) throws Exception {
        RawTestClient client = new RawTestClient(server.port(), username);
        assertEquals(MessageType.LOGIN_OK, client.readUntil(MessageType.LOGIN_OK).type);
        return client;
    }
}
