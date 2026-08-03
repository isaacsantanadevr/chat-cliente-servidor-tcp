package chat.transfer;

import chat.protocol.Message;
import chat.protocol.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileTransferTest {
    @TempDir
    Path temp;

    @Test
    void calculatesChunksAndRebuildsFileWithValidHash() throws Exception {
        byte[] content = new byte[FileRules.RAW_CHUNK_SIZE * 2 + 37];
        for (int index = 0; index < content.length; index++) content[index] = (byte) (index % 251);
        String id = UUID.randomUUID().toString();
        Message start = start(id, "dados.bin", content.length);

        Path source = temp.resolve("source.bin");
        Files.write(source, content);
        try (IncomingFileManager manager = new IncomingFileManager(temp.resolve("downloads"), FileRules.DEFAULT_MAX_SIZE)) {
            manager.begin(start);
            for (int index = 0; index < start.totalChunks; index++) {
                int from = index * FileRules.RAW_CHUNK_SIZE;
                int to = Math.min(from + FileRules.RAW_CHUNK_SIZE, content.length);
                Message chunk = Message.of(MessageType.FILE_CHUNK);
                chunk.transferId = id;
                chunk.chunkIndex = index;
                chunk.data = Base64.getEncoder().encodeToString(Arrays.copyOfRange(content, from, to));
                manager.acceptChunk(chunk);
            }
            Message end = Message.of(MessageType.FILE_END);
            end.transferId = id;
            end.sha256 = Hashing.sha256(source);
            Path rebuilt = manager.finish(end);
            assertArrayEquals(content, Files.readAllBytes(rebuilt));
        }
        assertEquals(3, FileRules.totalChunks(content.length));
    }

    @Test
    void rejectsWrongOrderAndDeletesPartialFile() throws Exception {
        String id = UUID.randomUUID().toString();
        Path downloads = temp.resolve("downloads");
        try (IncomingFileManager manager = new IncomingFileManager(downloads, FileRules.DEFAULT_MAX_SIZE)) {
            manager.begin(start(id, "texto.txt", 3));
            Message chunk = Message.of(MessageType.FILE_CHUNK);
            chunk.transferId = id;
            chunk.chunkIndex = 1;
            chunk.data = Base64.getEncoder().encodeToString("abc".getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> manager.acceptChunk(chunk));
        }
        assertFalse(Files.exists(downloads.resolve(id + ".part")));
    }

    @Test
    void rejectsBadHashOversizeAndUnsafeName() throws Exception {
        String id = UUID.randomUUID().toString();
        try (IncomingFileManager manager = new IncomingFileManager(temp, 2)) {
            assertThrows(IllegalArgumentException.class, () -> manager.begin(start(id, "large.bin", 3)));
        }
        assertThrows(IllegalArgumentException.class, () -> FileRules.safeFileName("../senha.txt"));
        assertThrows(IllegalArgumentException.class, () -> FileRules.safeFileName("..\\senha.txt"));

        try (IncomingFileManager manager = new IncomingFileManager(temp.resolve("hash"), 10)) {
            Message start = start(id, "small.bin", 3);
            manager.begin(start);
            Message chunk = Message.of(MessageType.FILE_CHUNK);
            chunk.transferId = id;
            chunk.chunkIndex = 0;
            chunk.data = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
            manager.acceptChunk(chunk);
            Message end = Message.of(MessageType.FILE_END);
            end.transferId = id;
            end.sha256 = "00".repeat(32);
            assertThrows(IllegalArgumentException.class, () -> manager.finish(end));
        }
    }

    private Message start(String id, String name, long size) {
        Message start = Message.of(MessageType.FILE_START);
        start.transferId = id;
        start.fileName = name;
        start.fileSize = size;
        start.totalChunks = FileRules.totalChunks(size);
        return start;
    }
}
