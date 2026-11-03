package com.kreview.kreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class FoundationConfigurationTests {

  @Test
  void usesDedicatedDatabaseWithFlywayAndDefaultsAiOff() throws Exception {
    Properties config = new Properties();
    try (var input = getClass().getResourceAsStream("/application.properties")) {
      config.load(input);
    }
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    assertEquals("jdbc:postgresql://127.0.0.1:5432/kcomparison",
        environment.resolveRequiredPlaceholders(config.getProperty("spring.datasource.url")));
    assertEquals("validate", config.getProperty("spring.jpa.hibernate.ddl-auto"));
    assertEquals("false", environment.resolveRequiredPlaceholders(config.getProperty("comparison.ai.enabled")));
    environment.getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of("AI_ENABLED", "true")));
    assertEquals("true", environment.resolveRequiredPlaceholders(config.getProperty("comparison.ai.enabled")));
    assertEquals("true", config.getProperty("spring.flyway.clean-disabled"));
    assertEquals("false", config.getProperty("spring.flyway.baseline-on-migrate"));
    assertEquals("OFF", config.getProperty("logging.level.org.hibernate.orm.jdbc.error"));
    assertFalse(config.containsKey("gemini.api.key"));
  }
}
