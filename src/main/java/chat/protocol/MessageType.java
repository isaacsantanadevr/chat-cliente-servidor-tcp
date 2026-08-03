package chat.protocol;

public enum MessageType {
    LOGIN,
    LOGIN_OK,
    BROADCAST,
    PRIVATE_MESSAGE,
    CHAT_MESSAGE,
    LIST_USERS,
    USER_LIST,
    USER_JOINED,
    USER_LEFT,
    FILE_START,
    FILE_CHUNK,
    FILE_END,
    FILE_RECEIVED,
    FILE_PROGRESS,
    QUIT,
    BYE,
    ERROR
}
