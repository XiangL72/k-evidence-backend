package com.kreview.kreview.controller;

import com.kreview.kreview.document.DocumentInputException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DocumentSetsController.class, AnalysesController.class})
public class DocumentApiErrors {
  public record ErrorBody(String code, String message, String requestId) {}
  public static ResponseEntity<ErrorBody> response(int status, String code, String message) {
    return ResponseEntity.status(status).body(new ErrorBody(code, message, UUID.randomUUID().toString()));
  }
  @ExceptionHandler(DocumentInputException.class)
  ResponseEntity<ErrorBody> input(DocumentInputException ex) { return response(ex.status(), ex.code(), ex.getMessage()); }
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorBody> tooLarge(Exception ex) { return response(413, "UPLOAD_TOO_LARGE", "每份文件最多 1 MiB，请求总量最多 3 MiB"); }
  @ExceptionHandler({HttpMediaTypeNotSupportedException.class, InvalidMediaTypeException.class})
  ResponseEntity<ErrorBody> type(Exception ex) { return response(415, "UNSUPPORTED_CONTENT_TYPE", "请使用规定的 multipart / JSON 格式"); }
  @ExceptionHandler({MethodArgumentTypeMismatchException.class, MultipartException.class, jakarta.servlet.ServletException.class})
  ResponseEntity<ErrorBody> malformed(Exception ex) { return response(400, "INVALID_REQUEST", "请求格式无效，请检查文件、关系和分页参数"); }
  @ExceptionHandler(Exception.class)
  ResponseEntity<ErrorBody> failure(Exception ex) { return response(500, "INTERNAL_ERROR", "保存或读取失败，请刷新文件组列表确认后再重试"); }
}
