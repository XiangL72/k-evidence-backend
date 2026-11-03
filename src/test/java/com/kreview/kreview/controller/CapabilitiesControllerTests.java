package com.kreview.kreview.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CapabilitiesControllerTests {

  private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
      new CapabilitiesController(), new HealthController()).build();

  @Test
  void reportsOnlyImplementedCapabilitiesWithoutSecrets() throws Exception {
    mvc.perform(get("/api/capabilities"))
        .andExpect(status().isOk())
        .andExpect(content().json(
            """
            {"aiAvailable":false,"supportedInputTypes":["TEXT","TXT"],"documentSetPersistence":true,"analysisPersistence":true,
             "limits":{"maxUtf16UnitsPerText":100000,"maxLinesPerText":5000,
             "filesPerSet":2,"maxBytesPerFile":1048576,"maxRequestBytes":3145728,"maxMetadataBytes":16384}}
            """, JsonCompareMode.STRICT));
  }

  @Test
  void preservesHealthEndpoint() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
  }
}
