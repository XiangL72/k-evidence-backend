package com.kreview.kreview.document;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class Utf8TxtDecoder implements TextDocumentDecoder {
  public static final int MAX_BYTES = 1_048_576;
  public static final int MAX_UTF16 = 100_000;
  public static final int MAX_LINES = 5_000;

  public DecodedDocument decode(String filename, byte[] bytes) {
    if (filename == null || filename.isBlank() || filename.length() > 255
        || filename.codePoints().anyMatch(Character::isISOControl)) {
      throw new DocumentInputException(400, "INVALID_FILENAME", "文件名须为 1–255 个字符，且不含控制字符");
    }
    if (!filename.toLowerCase(Locale.ROOT).endsWith(".txt")) {
      throw new DocumentInputException(415, "UNSUPPORTED_FORMAT", "仅支持 UTF-8 TXT 文件");
    }
    if (bytes.length > MAX_BYTES) {
      throw new DocumentInputException(413, "FILE_TOO_LARGE", "每份文件最多 1 MiB");
    }
    String text;
    try {
      text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException ex) {
      throw new DocumentInputException(400, "INVALID_UTF8", "文件不是有效的 UTF-8 TXT");
    }
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    if (text.indexOf('\0') >= 0) throw new DocumentInputException(400, "NUL_NOT_ALLOWED", "文本不能包含 NUL 字符");
    if (text.length() > MAX_UTF16) throw new DocumentInputException(413, "TEXT_TOO_LARGE", "每份文本最多 100,000 个 UTF-16 单元");
    int lines = !text.isEmpty() && !text.endsWith("\n") ? 1 : 0;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
    if (lines > MAX_LINES) throw new DocumentInputException(413, "TOO_MANY_LINES", "每份文本最多 5,000 行");
    return new DecodedDocument(filename, bytes, text, sha256(bytes), sha256(text.getBytes(StandardCharsets.UTF_8)), lines);
  }

  public static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
  }
}
