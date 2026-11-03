package com.kreview.kreview.document;

/** Fixed, user-safe messages only; never retain document contents in an exception. */
public class DocumentInputException extends RuntimeException {
  private final int status;
  private final String code;
  public DocumentInputException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }
  public int status() { return status; }
  public String code() { return code; }
}
