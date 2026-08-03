package chat.transfer;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class FileRules {
    public static final long DEFAULT_MAX_SIZE = 10L * 1024 * 1024;
    public static final int RAW_CHUNK_SIZE = 24 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}._() -]{1,180}");

    private FileRules() {
    }

    public static String safeFileName(String supplied) {
        if (supplied == null || supplied.isBlank()) {
            throw new IllegalArgumentException("Nome de arquivo vazio");
        }
        String name = Path.of(supplied).getFileName().toString();
        if (!name.equals(supplied) || name.equals(".") || name.equals("..") || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Nome de arquivo inválido");
        }
        return name;
    }

    public static void validateSize(long size, long limit) {
        if (size < 0 || size > limit) {
            throw new IllegalArgumentException("Arquivo excede o limite de " + limit + " bytes");
        }
    }

    public static int totalChunks(long size) {
        return size == 0 ? 0 : Math.toIntExact((size + RAW_CHUNK_SIZE - 1) / RAW_CHUNK_SIZE);
    }
}
