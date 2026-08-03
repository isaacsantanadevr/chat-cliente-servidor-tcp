package chat.server;

import chat.transfer.Hashing;

import java.security.MessageDigest;

final class FileRelay {
    final String transferId;
    final String ownerKey;
    final String recipientKey;
    final String fileName;
    final long fileSize;
    final int totalChunks;
    final MessageDigest digest = Hashing.sha256Digest();
    int nextChunk;
    long receivedBytes;
    boolean waitingForReceipt;

    FileRelay(String transferId, String ownerKey, String recipientKey,
              String fileName, long fileSize, int totalChunks) {
        this.transferId = transferId;
        this.ownerKey = ownerKey;
        this.recipientKey = recipientKey;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.totalChunks = totalChunks;
    }
}
