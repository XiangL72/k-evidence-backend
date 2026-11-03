package com.kreview.kreview.controller;

import com.kreview.kreview.comparison.DiffEngine;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CapabilitiesController {

  @GetMapping("/api/capabilities")
  public Map<String, Object> capabilities() {
    // A configuration flag alone cannot make an unimplemented AI provider available.
    return Map.of(
        "aiAvailable", false,
        "supportedInputTypes", List.of("TEXT", "TXT"),
        "documentSetPersistence", true,
        "analysisPersistence", true,
        "limits", Map.of(
            "filesPerSet", 2,
            "maxBytesPerFile", 1048576,
            "maxRequestBytes", 3145728,
            "maxMetadataBytes", 16384,
            "maxUtf16UnitsPerText", DiffEngine.DEFAULT_MAX_CHARACTERS,
            "maxLinesPerText", DiffEngine.DEFAULT_MAX_LINES));
  }
}
