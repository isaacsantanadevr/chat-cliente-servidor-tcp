package chat.server;

import chat.protocol.Message;
import chat.protocol.MessageType;
import chat.protocol.ProtocolCodec;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class RawTestClient implements AutoCloseable {
    private final ProtocolCodec codec = new ProtocolCodec();
    private final Socket socket;
    private final BufferedReader reader;
    private final BufferedWriter writer;

    RawTestClient(int port, String username) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(2500);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Message login = Message.of(MessageType.LOGIN);
        login.username = username;
        send(login);
    }

    void send(Message message) throws IOException {
        writer.write(codec.encode(message));
        writer.newLine();
        writer.flush();
    }

    Message readUntil(MessageType type) throws IOException {
        for (int attempts = 0; attempts < 30; attempts++) {
            String line = reader.readLine();
            if (line == null) throw new IOException("Conexão encerrada antes de " + type);
            Message message = codec.decode(line);
            if (message.type == type) return message;
        }
        throw new IOException("Mensagem " + type + " não encontrada");
    }

    void quit() throws IOException {
        send(Message.of(MessageType.QUIT));
        readUntil(MessageType.BYE);
    }

    void closeAbruptly() throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
