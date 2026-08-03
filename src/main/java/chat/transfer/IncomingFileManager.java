package chat.transfer;

import chat.protocol.Message;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class IncomingFileManager implements AutoCloseable {
    private final Path downloadDirectory;
    private final long maxFileSize;
    private final Map<String, IncomingTransfer> transfers = new ConcurrentHashMap<>();

    public IncomingFileManager(Path downloadDirectory, long maxFileSize) {
        this.downloadDirectory = downloadDirectory.toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
    }

    public void begin(Message message) throws IOException {
        String name = FileRules.safeFileName(message.fileName);
        long size = message.fileSize == null ? -1 : message.fileSize;
        FileRules.validateSize(size, maxFileSize);
        int chunks = message.totalChunks == null ? -1 : message.totalChunks;
        if (chunks != FileRules.totalChunks(size)) {
            throw new IllegalArgumentException("Metadados de arquivo inconsistentes");
        }
        Files.createDirectories(downloadDirectory);
        Path finalPath = availablePath(name);
        Path partPath = downloadDirectory.resolve(message.transferId + ".part").normalize();
        if (!partPath.getParent().equals(downloadDirectory)) {
            throw new IllegalArgumentException("Caminho temporário inválido");
        }
        IncomingTransfer transfer = new IncomingTransfer(message.transferId, name, size, chunks,
                partPath, finalPath, Files.newOutputStream(partPath));
        IncomingTransfer old = transfers.putIfAbsent(message.transferId, transfer);
        if (old != null) {
            transfer.abort();
            throw new IllegalArgumentException("Transferência duplicada");
        }
    }

    public int acceptChunk(Message message) throws IOException {
        IncomingTransfer transfer = required(message.transferId);
        if (message.chunkIndex == null || message.chunkIndex != transfer.nextChunk) {
            abort(message.transferId);
            throw new IllegalArgumentException("Bloco recebido fora de ordem");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(message.data == null ? "" : message.data);
        } catch (IllegalArgumentException error) {
            abort(message.transferId);
            throw new IllegalArgumentException("Bloco Base64 inválido", error);
        }
        if (raw.length > FileRules.RAW_CHUNK_SIZE || transfer.receivedBytes + raw.length > transfer.fileSize) {
            abort(message.transferId);
            throw new IllegalArgumentException("Bloco excede o tamanho declarado");
        }
        transfer.output.write(raw);
        transfer.digest.update(raw);
        transfer.receivedBytes += raw.length;
        transfer.nextChunk++;
        return transfer.totalChunks == 0 ? 100 : (int) ((100L * transfer.nextChunk) / transfer.totalChunks);
    }

    public Path finish(Message message) throws IOException {
        IncomingTransfer transfer = required(message.transferId);
        transfers.remove(message.transferId, transfer);
        transfer.output.close();
        String actualHash = HexFormat.of().formatHex(transfer.digest.digest());
        if (transfer.receivedBytes != transfer.fileSize || transfer.nextChunk != transfer.totalChunks
                || message.sha256 == null || !actualHash.equalsIgnoreCase(message.sha256)) {
            Files.deleteIfExists(transfer.partPath);
            throw new IllegalArgumentException("Arquivo recebido falhou na validação de tamanho ou SHA-256");
        }
        try {
            return Files.move(transfer.partPath, transfer.finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnavailable) {
            return Files.move(transfer.partPath, transfer.finalPath);
        }
    }

    public void abort(String transferId) {
        IncomingTransfer transfer = transfers.remove(transferId);
        if (transfer != null) {
            transfer.abort();
        }
    }

    private IncomingTransfer required(String transferId) {
        IncomingTransfer transfer = transfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Transferência recebida não foi iniciada");
        }
        return transfer;
    }

    private Path availablePath(String fileName) {
        Path candidate = downloadDirectory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int suffix = 1; ; suffix++) {
            candidate = downloadDirectory.resolve(stem + " (" + suffix + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public void close() {
        for (String id : transfers.keySet()) {
            abort(id);
        }
    }

    private static final class IncomingTransfer {
        private final String id;
        private final String fileName;
        private final long fileSize;
        private final int totalChunks;
        private final Path partPath;
        private final Path finalPath;
        private final OutputStream output;
        private final MessageDigest digest = Hashing.sha256Digest();
        private int nextChunk;
        private long receivedBytes;

        private IncomingTransfer(String id, String fileName, long fileSize, int totalChunks,
                                 Path partPath, Path finalPath, OutputStream output) {
            this.id = id;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.totalChunks = totalChunks;
            this.partPath = partPath;
            this.finalPath = finalPath;
            this.output = output;
        }

        private void abort() {
            try {
                output.close();
            } catch (IOException closeError) {
                System.err.println("Falha ao fechar arquivo parcial " + fileName + ": " + closeError.getMessage());
            }
            try {
                Files.deleteIfExists(partPath);
            } catch (IOException deleteError) {
                System.err.println("Falha ao excluir arquivo parcial " + id + ": " + deleteError.getMessage());
            }
        }
    }
}
