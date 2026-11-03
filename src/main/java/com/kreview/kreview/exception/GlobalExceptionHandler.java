package com.kreview.kreview.exception;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> handleValidationError(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(fieldError -> fieldError.getDefaultMessage())
        .collect(Collectors.joining("; "));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
  }

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<Map<String, String>> handleInvalidRequest(InvalidRequestException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<?> handleMediaType(org.springframework.web.HttpMediaTypeNotSupportedException ex,
      jakarta.servlet.http.HttpServletRequest request) throws org.springframework.web.HttpMediaTypeNotSupportedException {
    if (request.getRequestURI().startsWith("/api/document-sets")) {
      return com.kreview.kreview.controller.DocumentApiErrors.response(415, "UNSUPPORTED_CONTENT_TYPE", "请使用 multipart/form-data 上传两份文件");
    }
    throw ex;
  }

  @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
  public ResponseEntity<?> handleMultipart(org.springframework.web.multipart.MultipartException ex,
      jakarta.servlet.http.HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/document-sets")) {
      return com.kreview.kreview.controller.DocumentApiErrors.response(400, "INVALID_REQUEST", "multipart 请求格式无效");
    }
    throw ex;
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<?> handleMaxUploadSizeExceeded(
      MaxUploadSizeExceededException ex, jakarta.servlet.http.HttpServletRequest request) {
    if (request.getRequestURI().startsWith("/api/document-sets")) {
      return com.kreview.kreview.controller.DocumentApiErrors.response(413, "UPLOAD_TOO_LARGE", "每份文件最多 1 MiB，请求总量最多 3 MiB");
    }
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(Map.of("error", "Uploaded file exceeds the maximum allowed size"));
  }
}
