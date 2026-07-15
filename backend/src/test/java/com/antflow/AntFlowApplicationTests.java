package com.antflow;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@Disabled("smoke test for P0 — requires PG; remove @Disabled when docker-compose PG is up")
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class AntFlowApplicationTests {
    @Test void contextLoads() {}
}
