package com.kreview.kreview.document;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A disposable database only. Never reads application/developer database credentials. */
@Testcontainers
public abstract class PostgresTestSupport {
  @Container
  protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine")
      .withDatabaseName("kcomparison_test").withUsername("comparison_test").withPassword("disposable_test_only");
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry props) {
    props.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    props.add("spring.datasource.username", POSTGRES::getUsername);
    props.add("spring.datasource.password", POSTGRES::getPassword);
    props.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
  }
}
