package com.kreview.kreview.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kreview.kreview.controller.ComparisonController;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Real Spring MVC mappings and CORS processing, without a database or listening socket. */
class WebConfigTests {

  private static final String LOCAL_FRONTEND = "http://127.0.0.1:5174";
  private static final String PREVIEW = "/api/comparisons/preview";
  private static final String INPUT = "{\"oldText\":\"30天\",\"newText\":\"60天\"}";

  @Test
  void defaultAllowsComparisonPostFromTheViteOrigin() throws Exception {
    try (var context = context(Map.of())) {
      mvc(context).perform(post(PREVIEW)
              .header(HttpHeaders.ORIGIN, LOCAL_FRONTEND)
              .contentType(MediaType.APPLICATION_JSON).content(INPUT))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL_FRONTEND))
          .andExpect(jsonPath("$.mode").value("STRICT"))
          .andExpect(jsonPath("$.changes[0].newText").value("60天"));
    }
  }

  @Test
  void defaultAllowsJsonPostPreflightFromTheViteOrigin() throws Exception {
    try (var context = context(Map.of())) {
      mvc(context).perform(options(PREVIEW)
              .header(HttpHeaders.ORIGIN, LOCAL_FRONTEND)
              .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
              .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, LOCAL_FRONTEND))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,DELETE"))
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "http://localhost:5173", "https://untrusted.example", "http://127.0.0.1:51740"
  })
  void rejectsOriginsOutsideTheExactAllowList(String origin) throws Exception {
    try (var context = context(Map.of())) {
      mvc(context).perform(post(PREVIEW).header(HttpHeaders.ORIGIN, origin)
              .contentType(MediaType.APPLICATION_JSON).content(INPUT))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
  }

  @Test
  void rejectsUntrustedPreflight() throws Exception {
    try (var context = context(Map.of())) {
      mvc(context).perform(options(PREVIEW)
              .header(HttpHeaders.ORIGIN, "https://untrusted.example")
              .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
          .andExpect(status().isForbidden())
          .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
  }

  @Test
  void explicitConfigurationReplacesTheDefaultAllowList() throws Exception {
    String override = "http://127.0.0.1:5175";
    try (var context = context(Map.of("CORS_ALLOWED_ORIGINS", override))) {
      var mvc = mvc(context);
      mvc.perform(post(PREVIEW).header(HttpHeaders.ORIGIN, override)
              .contentType(MediaType.APPLICATION_JSON).content(INPUT))
          .andExpect(status().isOk())
          .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, override));
      mvc.perform(post(PREVIEW).header(HttpHeaders.ORIGIN, LOCAL_FRONTEND)
              .contentType(MediaType.APPLICATION_JSON).content(INPUT))
          .andExpect(status().isForbidden());
    }
  }

  private AnnotationConfigWebApplicationContext context(Map<String, Object> properties) {
    var context = new AnnotationConfigWebApplicationContext();
    // Test the application default even when a developer exports a local override.
    var sources = context.getEnvironment().getPropertySources();
    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    sources.addFirst(new MapPropertySource("test", properties));
    context.setServletContext(new MockServletContext());
    context.register(MvcConfiguration.class);
    context.refresh();
    return context;
  }

  private MockMvc mvc(AnnotationConfigWebApplicationContext context) {
    return MockMvcBuilders.webAppContextSetup(context).build();
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({WebConfig.class, ComparisonController.class})
  static class MvcConfiguration {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }
}
