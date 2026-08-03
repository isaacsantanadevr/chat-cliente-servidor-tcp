package chat.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Message {
    public int version = 1;
    public MessageType type;
    public String username;
    public String content;
    public String from;
    public String to;
    public String scope;
    public String timestamp;
    public List<String> users;
    public String transferId;
    public String fileName;
    public Long fileSize;
    public Integer totalChunks;
    public Integer chunkIndex;
    public String data;
    public String sha256;
    public String code;
    public String error;
    public Integer progress;
    public String direction;
    public String status;
    public String path;

    public Message() {
    }

    public Message(MessageType type) {
        this.type = type;
    }

    public static Message of(MessageType type) {
        return new Message(type);
    }

    public static Message error(String code, String detail) {
        Message message = of(MessageType.ERROR);
        message.code = code;
        message.error = detail;
        return message;
    }
}
