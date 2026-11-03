package com.kreview.kreview.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8TxtDecoderTests {
  final Utf8TxtDecoder decoder = new Utf8TxtDecoder();
  @Test void preservesBomBytesCrLfEmojiAndSpacesButRemovesOnlyFirstBomFromText() {
    String raw = "\uFEFF\uFEFF  合同😀\r\n尾款  \r\n";
    byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
    var result = decoder.decode("采购.TXT", bytes);
    assertEquals(raw.substring(1), result.text());
    assertArrayEquals(bytes, result.rawBytes());
    assertNotEquals(result.rawSha256(), result.textSha256());
    assertEquals(2, result.lineCount());
    byte[] copy = result.rawBytes(); copy[0] = 0;
    assertArrayEquals(bytes, result.rawBytes());
    bytes[0] = 0;
    assertNotEquals(0, result.rawBytes()[0]);
  }
  @Test void acceptsEmptyAndHtmlAsLiteralText() {
    assertEquals(0, decoder.decode("empty.txt", new byte[0]).lineCount());
    assertEquals("<script>alert(1)</script>", decoder.decode("html.txt", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)).text());
  }
  @Test void rejectsMalformedUtf8IncludingEncodedSurrogate() {
    for (byte[] bytes : new byte[][]{{(byte)0xc3, 0x28}, {(byte)0xed,(byte)0xa0,(byte)0x80}, {(byte)0xff,(byte)0xfe,0,0}}) {
      assertEquals("INVALID_UTF8", assertThrows(DocumentInputException.class, () -> decoder.decode("a.txt", bytes)).code());
    }
  }
  @Test void rejectsNul() {
    assertEquals("NUL_NOT_ALLOWED", assertThrows(DocumentInputException.class, () -> decoder.decode("a.txt", new byte[]{65,0})).code());
  }
  @ParameterizedTest @ValueSource(strings={"a.pdf", "a.docx", "a.txt.exe", "合同"})
  void rejectsUnsupportedExtensions(String name) {
    assertEquals(415, assertThrows(DocumentInputException.class, () -> decoder.decode(name, new byte[0])).status());
  }
  @Test void enforcesFilenameAndInputLimitsAtTheBoundary() {
    assertEquals("INVALID_FILENAME", assertThrows(DocumentInputException.class, () -> decoder.decode("a".repeat(252)+".txt", new byte[0])).code());
    assertThrows(DocumentInputException.class, () -> decoder.decode("a\nb.txt", new byte[0]));
    assertEquals("FILE_TOO_LARGE", assertThrows(DocumentInputException.class, () -> decoder.decode("a.txt", new byte[1_048_577])).code());
    assertEquals(100_000, decoder.decode("a.txt", "中".repeat(100_000).getBytes(StandardCharsets.UTF_8)).text().length());
    assertEquals("TEXT_TOO_LARGE", assertThrows(DocumentInputException.class, () -> decoder.decode("a.txt", "a".repeat(100_001).getBytes(StandardCharsets.UTF_8))).code());
    assertEquals(5000, decoder.decode("a.txt", "\r\n".repeat(5000).getBytes(StandardCharsets.UTF_8)).lineCount());
    assertEquals("TOO_MANY_LINES", assertThrows(DocumentInputException.class, () -> decoder.decode("a.txt", "\n".repeat(5001).getBytes(StandardCharsets.UTF_8))).code());
  }
}
