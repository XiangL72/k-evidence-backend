package com.kreview.kreview.document;

/** Format-specific boundary; additional formats require separate implementations. */
public interface TextDocumentDecoder {
  DecodedDocument decode(String filename, byte[] bytes);
}
