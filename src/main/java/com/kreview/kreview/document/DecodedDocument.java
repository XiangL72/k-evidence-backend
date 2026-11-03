package com.kreview.kreview.document;

/** Preserve byte identity separately from the BOM-stripped text identity. */
public record DecodedDocument(String filename, byte[] rawBytes, String text,
    String rawSha256, String textSha256, int lineCount) {
  public DecodedDocument { rawBytes = rawBytes.clone(); }
  @Override public byte[] rawBytes() { return rawBytes.clone(); }
}
