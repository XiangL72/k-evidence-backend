package com.kreview.kreview.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.exception.GlobalExceptionHandler;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ComparisonControllerTests {

  private final ObjectMapper mapper = new ObjectMapper();
  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
      new ComparisonController(mapper), new HealthController(), new CapabilitiesController())
      .setControllerAdvice(new GlobalExceptionHandler()).build();

  @Test
  void insertionReturnsExplicitSourceRangesAndOriginalNewText() throws Exception {
    compare("a\r\nb\n", "a\nnew\r\nb\n")
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"INSERT",
              "oldRange":{"startLine":1,"endLineExclusive":1},
              "newRange":{"startLine":1,"endLineExclusive":2},
              "oldText":"","newText":"new\\r\\n"
            }]}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void deletionReturnsExplicitSourceRangesAndOriginalOldText() throws Exception {
    compare("a\nold\r\nb\n", "a\r\nb\n")
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"DELETE",
              "oldRange":{"startLine":1,"endLineExclusive":2},
              "newRange":{"startLine":1,"endLineExclusive":1},
              "oldText":"old\\r\\n","newText":""
            }]}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void replacementReturnsExplicitUnicodeTextAndEndExclusiveRanges() throws Exception {
    compare("标题\r\n付款期限30天。😀\r\n尾", "标题\n付款期限60天。😀\n尾")
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"REPLACE",
              "oldRange":{"startLine":1,"endLineExclusive":2},
              "newRange":{"startLine":1,"endLineExclusive":2},
              "oldText":"付款期限30天。😀\\r\\n","newText":"付款期限60天。😀\\n"
            }]}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void identicalAndCrLfOnlyInputsReturnAnEmptyChangeList() throws Exception {
    compare("same\r\n尾", "same\n尾").andExpect(status().isOk())
        .andExpect(content().json("{\"mode\":\"STRICT\",\"changes\":[]}", JsonCompareMode.STRICT));
    compare("", "").andExpect(status().isOk())
        .andExpect(content().json("{\"mode\":\"STRICT\",\"changes\":[]}", JsonCompareMode.STRICT));
  }

  @Test
  void oldPreviewRejectsOverBudgetWorkWithFixed422Shape() throws Exception {
    compare("甲\n".repeat(1001), "乙\n".repeat(1001))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().json("{\"error\":\"Line diff exceeds work budget\"}", JsonCompareMode.STRICT));
    compare("甲\n".repeat(1001), "甲\n".repeat(1001))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"mode\":\"STRICT\",\"changes\":[]}", JsonCompareMode.STRICT));
  }

  @Test
  void emptyOldSideAndWhitespaceNewSideAreAWholeTextInsertion() throws Exception {
    compare("", " \t\r\n")
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"INSERT",
              "oldRange":{"startLine":0,"endLineExclusive":0},
              "newRange":{"startLine":0,"endLineExclusive":1},
              "oldText":"","newText":" \\t\\r\\n"
            }]}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void emptyNewSideIsAWholeTextDeletion() throws Exception {
    compare("old", "")
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"DELETE",
              "oldRange":{"startLine":0,"endLineExclusive":1},
              "newRange":{"startLine":0,"endLineExclusive":0},
              "oldText":"old","newText":""
            }]}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void terminalNewlineChangeIsSerializedAsAReplacement() throws Exception {
    compare("a", "a\n")
        .andExpect(status().isOk())
        .andExpect(content().json("""
            {"mode":"STRICT","changes":[{
              "changeId":"chg-0001","type":"REPLACE",
              "oldRange":{"startLine":0,"endLineExclusive":1},
              "newRange":{"startLine":0,"endLineExclusive":1},
              "oldText":"a","newText":"a\\n"
            }]}
            """, JsonCompareMode.STRICT));
  }

  @ParameterizedTest
  @MethodSource("invalidTextFields")
  void missingNullAndNonStringFieldsReturnSafeFieldErrors(String json, String field) throws Exception {
    expectBadRequest(json, field + " is required and must be a string");
  }

  static Stream<Arguments> invalidTextFields() {
    var cases = Stream.<Arguments>builder();
    cases.add(Arguments.of("{\"newText\":\"private-document\"}", "oldText"));
    cases.add(Arguments.of("{\"oldText\":\"private-document\"}", "newText"));
    for (String invalid : new String[]{"null", "123", "true", "[]", "{\"private-document\":1}"}) {
      cases.add(Arguments.of("{\"oldText\":" + invalid + ",\"newText\":\"private-document\"}", "oldText"));
      cases.add(Arguments.of("{\"oldText\":\"private-document\",\"newText\":" + invalid + "}", "newText"));
    }
    return cases.build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "[]", "123", "true", "\"private-document\""})
  void rootMustBeAnObject(String json) throws Exception {
    expectBadRequest(json, "Request body must be a JSON object");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "", "   ", "{\"oldText\":\"private-document\",",
      "{\"oldText\":\"private-document\",\"newText\":\"\"} {}",
      "{\"oldText\":\"private-document\",\"newText\":\"\"} junk",
      "{\"oldText\":\"private-document\",\"oldText\":\"\",\"newText\":\"\"}"
  })
  void malformedMissingAndAmbiguousJsonReturnOnlyTheErrorType(String json) throws Exception {
    expectBadRequest(json, "Invalid JSON request body");
  }

  @ParameterizedTest
  @ValueSource(strings = {"private-document", "options"})
  void unknownFieldsAreRejectedWithoutEchoingTheirNames(String unknown) throws Exception {
    String request = mapper.writeValueAsString(Map.of(
        "oldText", "private-document", "newText", "", unknown, "private-document"));
    expectBadRequest(request, "Unknown fields: only oldText and newText are allowed");
  }

  @ParameterizedTest
  @MethodSource("overLimitInputs")
  void overLimitTextReturnsAFieldLimitErrorWithoutItsContent(String before, String after, String error)
      throws Exception {
    expectBadRequest(mapper.writeValueAsString(Map.of("oldText", before, "newText", after)), error);
  }

  static Stream<Arguments> overLimitInputs() {
    String longText = "private-document" + "x".repeat(100_001);
    String manyLines = "private-document\n" + "x\n".repeat(5_000);
    return Stream.of(
        Arguments.of(longText, "", "oldText exceeds UTF-16 character limit: 100000"),
        Arguments.of("", longText, "newText exceeds UTF-16 character limit: 100000"),
        Arguments.of(manyLines, "", "oldText exceeds line limit: 5000"),
        Arguments.of("", manyLines, "newText exceeds line limit: 5000"));
  }

  @ParameterizedTest
  @MethodSource("boundaryInputs")
  void exactlyAtLimitsIsAcceptedWithTheCompleteOriginalText(String text) throws Exception {
    compare("", text).andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("STRICT"))
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].type").value("INSERT"))
        .andExpect(jsonPath("$.changes[0].newText").value(text));
    compare(text, "").andExpect(status().isOk())
        .andExpect(jsonPath("$.changes.length()").value(1))
        .andExpect(jsonPath("$.changes[0].type").value("DELETE"))
        .andExpect(jsonPath("$.changes[0].oldText").value(text));
  }

  static Stream<String> boundaryInputs() {
    return Stream.of("x".repeat(100_000), "😀".repeat(50_000), "x\n".repeat(5_000));
  }

  @Test
  void strictReaderAndErrorHandlingLeaveOtherInterfacesAndSharedMapperUnchanged() throws Exception {
    expectBadRequest("{\"oldText\":\"private-document\",\"newText\":\"\"} {}", "Invalid JSON request body");
    assertFalse(mapper.isEnabled(DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    assertFalse(mapper.isEnabled(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature()));
    assertTrue(mapper.readTree("{\"n\":1,\"n\":2}").get("n").asInt() == 2);
    mvc.perform(get("/api/health")).andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    mvc.perform(get("/api/capabilities")).andExpect(status().isOk())
        .andExpect(content().json("""
            {"aiAvailable":false,"supportedInputTypes":["TEXT","TXT"],"documentSetPersistence":true,"analysisPersistence":true,
             "limits":{"maxUtf16UnitsPerText":100000,"maxLinesPerText":5000,
             "filesPerSet":2,"maxBytesPerFile":1048576,"maxRequestBytes":3145728,"maxMetadataBytes":16384}}
            """, JsonCompareMode.STRICT));
  }

  private ResultActions compare(String before, String after) throws Exception {
    return send(mapper.writeValueAsString(Map.of("oldText", before, "newText", after)));
  }

  private ResultActions send(String json) throws Exception {
    return mvc.perform(post("/api/comparisons/preview")
        .contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private void expectBadRequest(String json, String message) throws Exception {
    var result = send(json).andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json(mapper.writeValueAsString(Map.of("error", message)), JsonCompareMode.STRICT))
        .andReturn();
    assertFalse(result.getResponse().getContentAsString().contains("private-document"));
  }
}
