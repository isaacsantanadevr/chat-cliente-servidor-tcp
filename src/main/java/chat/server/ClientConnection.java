package chat.server;

import chat.protocol.Message;
import chat.protocol.ProtocolCodec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

final class ClientConnection {
    private final Socket socket;
    private final BufferedWriter writer;
    private final ProtocolCodec codec;
    private final Object writeLock = new Object();
    private volatile String username;

    ClientConnection(Socket socket, BufferedWriter writer, ProtocolCodec codec) {
        this.socket = socket;
        this.writer = writer;
        this.codec = codec;
    }

    String username() {
        return username;
    }

    void username(String username) {
        this.username = username;
    }

    void send(Message message) throws IOException {
        String json = codec.encode(message);
        synchronized (writeLock) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    void close() throws IOException {
        socket.close();
    }
}
