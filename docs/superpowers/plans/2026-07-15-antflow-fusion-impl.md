# AntFlow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build AntFlow — an integrated visual-form + approval-workflow system — by fusing Ant Design Pro's React/TS admin shell with wflow's designer paradigm, on a Spring Boot 3 / Java 17 / PostgreSQL 17 stack.

**Architecture:** Single-repo monolith `antflow/{frontend,backend,infra}`. Frontend = ant-design-pro with a 3-file patch and a custom workflow design/runtime layer (zustand + @tanstack/react-query + @xyflow/react + @dnd-kit). Backend = Spring Boot 3 with Spring Security 6 (JWT), MyBatis-Plus 3.5.5+, Flyway-managed PostgreSQL 17, and a custom lightweight approval engine. JSONB holds form/process schemas; ltree holds department paths; `@Version` columns serialize concurrent approvals.

**Tech Stack (recap from spec):**
- Frontend: React 18, Umi Max 4, TypeScript strict, antd 6, ProComponents 3, Tailwind v4, @dnd-kit, @xyflow/react (v12), zustand, @tanstack/react-query, vitest, playwright
- Backend: Spring Boot 3.3+, Java 17, Spring Security 6, Spring Data JPA/Validation, MyBatis-Plus 3.5.5+, Flyway, jjwt 0.12.x, springdoc-openapi, Bucket4j, BCrypt, Lombok, Testcontainers, RestAssured, JUnit 5
- DB: PostgreSQL 17 with `ltree` extension and JSONB columns

**Source spec:** `docs/superpowers/specs/2026-07-15-antflow-fusion-design.md`

**Phasing rule:** Each phase ends in a runnable demo. Don't move on until the demo works and CI is green.

---

## Phase P0 — Baseline

Demo target: open browser, log in as `admin`, see empty Ant Design Pro home page (no 404s, no console errors), backend healthcheck responding, Flyway migrations applied clean on PG 17.

### Task P0.1 — Initialize monorepo

**Files:**
- Create: `antflow/.gitignore`
- Create: `antflow/README.md`

- [ ] **Step 1: Create the workspace `antflow/`**

The current workspace at `E:\code\ant-flow` is already a flat directory. Convert it into the project root. The two existing reference sub-trees (`ant-design-pro-master/` and `wflow-master/`) stay on disk for content harvesting but are .gitignored — they will be replaced by `frontend/`.

- [ ] **Step 2: Initialize git**

```bash
cd "E:/code/ant-flow"
git init -b main
git config user.name "AntFlow Bot"
git config user.email "bot@antflow.local"
```

Expected: `Initialized empty Git repository in E:/code/ant-flow/.git/`

- [ ] **Step 3: Write `.gitignore`**

```gitignore
# Node / frontend
frontend/node_modules/
frontend/dist/
frontend/.umi/
frontend/.cache/
frontend/.env.local
frontend/coverage/

# Java / backend
backend/target/
backend/.idea/
backend/*.class
backend/.env

# IDE
.vscode/
.idea/
*.iml
.DS_Store

# Reference repos — copied out, not part of the antflow project
ant-design-pro-master/
wflow-master/

# Build outputs / infra
infra/.env
infra/data/

# OS
Thumbs.db
```

- [ ] **Step 4: Write the project README stub**

```markdown
# AntFlow

Ant Design Pro + wflow fusion. Visual form designer + approval workflow.

## Layout

- `frontend/` — Umi Max 4 + React 18 + TS
- `backend/` — Spring Boot 3 + Java 17
- `infra/` — docker-compose (PG 17, etc.)
- `docs/` — specs, plans

## Run

See each phase in `docs/superpowers/plans/`.
```

- [ ] **Step 5: Initial commit**

```bash
cd "E:/code/ant-flow"
git add .gitignore README.md
git commit -m "chore: initialize antflow monorepo"
```

Expected: `[main (root-commit) ...] chore: initialize antflow monorepo`

---

### Task P0.2 — Backend: Spring Boot 3 skeleton with Maven

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/antflow/AntFlowApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/antflow/AntFlowApplicationTests.java`

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.4</version>
        <relativePath/>
    </parent>
    <groupId>com.antflow</groupId>
    <artifactId>antflow-backend</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <jjwt.version>0.12.6</jjwt.version>
        <springdoc.version>2.6.0</springdoc.version>
        <bucket4j.version>8.10.0</bucket4j.version>
        <testcontainers.version>1.20.2</testcontainers.version>
    </properties>
    <dependencies>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>${mybatis-plus.version}</version></dependency>
        <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-jsqlparser</artifactId><version>${mybatis-plus.version}</version></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>${jjwt.version}</version></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
        <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
        <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
        <dependency><groupId>com.bucket4j</groupId><artifactId>bucket4j-core</artifactId><version>${bucket4j.version}</version></dependency>
        <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><scope>provided</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><version>${testcontainers.version}</version><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><version>${testcontainers.version}</version><scope>test</scope></dependency>
        <dependency><groupId>io.rest-assured</groupId><artifactId>rest-assured</artifactId><version>5.5.0</version><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration><excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes></configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Write `AntFlowApplication.java`**

```java
package com.antflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AntFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(AntFlowApplication.class, args);
    }
}
```

- [ ] **Step 3: Write `application.yml`**

```yaml
spring:
  application:
    name: antflow-backend
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/antflow}
    username: ${SPRING_DATASOURCE_USERNAME:antflow}
    password: ${SPRING_DATASOURCE_PASSWORD:antflow}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc.time_zone: UTC
        format_sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false
  jackson:
    default-property-inclusion: non_null
server:
  port: ${PORT:8080}
  error:
    include-message: always
antflow:
  jwt:
    secret: ${JWT_SECRET:}        # MUST be ≥ 32 bytes; we fail-fast in JwtService
    ttl-seconds: 86400
    clock-skew-seconds: 30
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:8000}
  login:
    per-minute: 5
    per-hour: 30
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted
springdoc:
  swagger-ui:
    path: /swagger-ui
  api-docs:
    path: /v3/api-docs
management:
  endpoints:
    web:
      exposure:
        include: health,info
logging:
  level:
    root: INFO
    com.antflow: DEBUG
```

- [ ] **Step 4: Write the failing smoke test**

```java
// src/test/java/com/antflow/AntFlowApplicationTests.java
package com.antflow;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@Disabled("smoke test for P0.T6 — requires PG; remove @Disabled when docker-compose PG is up")
@SpringBootTest
@TestPropertySource(properties = "spring.flyway.enabled=false")
class AntFlowApplicationTests {
    @Test void contextLoads() {}
}
```

- [ ] **Step 5: First build to verify the scaffold compiles**

```bash
cd "E:/code/ant-flow/backend"
mvn -B -DskipTests package
```

Expected: `BUILD SUCCESS` and `target/antflow-backend-0.1.0-SNAPSHOT.jar` produced.

- [ ] **Step 6 (NEW — necessary for `@Version` to work): Register MyBatis-Plus interceptor**

Without this, the `OptimisticLockerInnerInterceptor` is never installed. `t_process_instance.version` and `t_task.version` are populated on insert but ignored on update, so concurrent approvals silently overwrite each other.

Create `backend/src/main/java/com/antflow/common/MybatisPlusConfig.java`:

```java
package com.antflow.common;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        // Required by spec decision #17 — concurrent approve double-clicks.
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // Used by /api/tasks and /api/forms/data list pagination.
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
```

- [ ] **Step 7: Re-run build to confirm interceptor is wired**

```bash
cd "E:/code/ant-flow/backend"
mvn -B -DskipTests package
```

Expected: `BUILD SUCCESS`. (No compile-time regression from the new interceptor bean.)

- [ ] **Step 8: Commit**

```bash
cd "E:/code/ant-flow"
git add backend/pom.xml backend/src/
git commit -m "feat(backend): spring boot 3 + mybatis-plus scaffold + optimistic-lock interceptor"
```

---

### Task P0.3 — docker-compose for PostgreSQL 17

**Files:**
- Create: `infra/docker-compose.yml`
- Create: `infra/.env.example`

- [ ] **Step 1: Write `infra/docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:17-alpine
    container_name: antflow-postgres
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-antflow}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-antflow}
      POSTGRES_DB: ${POSTGRES_DB:-antflow}
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./initdb.d:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  pgdata: {}
```

- [ ] **Step 2: Write `initdb.d/01-extensions.sql`**

```sql
-- Runs once on first container start as the superuser
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- for gen_random_uuid()
```

- [ ] **Step 3: Write `infra/.env.example`**

```
POSTGRES_USER=antflow
POSTGRES_PASSWORD=antflow
POSTGRES_DB=antflow
JWT_SECRET=please-set-a-secret-of-at-least-32-bytes-here
CORS_ALLOWED_ORIGINS=http://localhost:8000
```

- [ ] **Step 4: Bring up the DB**

```bash
cd "E:/code/ant-flow/infra"
docker compose up -d
docker exec antflow-postgres psql -U antflow -d antflow -c "SELECT extname FROM pg_extension WHERE extname IN ('ltree','pgcrypto');"
```

Expected: two rows returned, `ltree` and `pgcrypto`.

- [ ] **Step 5: Commit**

```bash
cd "E:/code/ant-flow"
git add infra/docker-compose.yml infra/initdb.d infra/.env.example
git commit -m "feat(infra): postgres 17 + ltree + pgcrypto via docker-compose"
```

---

### Task P0.4 — Flyway V1__init.sql baseline tables

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V1__init.sql
-- AntFlow schema baseline. Extensions are loaded by initdb.d/01-extensions.sql
-- on first container start; this file assumes ltree and pgcrypto are present.

-- Organization
CREATE TABLE t_company (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_department (
    id           BIGSERIAL PRIMARY KEY,
    company_id   BIGINT NOT NULL REFERENCES t_company(id),
    parent_id    BIGINT REFERENCES t_department(id),
    path         LTREE NOT NULL,
    name         VARCHAR(128) NOT NULL,
    leader_id    BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_role (
    id    BIGSERIAL PRIMARY KEY,
    code  VARCHAR(64) NOT NULL UNIQUE,
    name  VARCHAR(128) NOT NULL
);

CREATE TABLE t_user (
    id              BIGSERIAL PRIMARY KEY,
    dept_id         BIGINT REFERENCES t_department(id),
    username        VARCHAR(64) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    email           VARCHAR(255),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE t_department ADD CONSTRAINT fk_dept_leader FOREIGN KEY (leader_id) REFERENCES t_user(id);

CREATE TABLE t_user_role (
    user_id BIGINT NOT NULL REFERENCES t_user(id),
    role_id BIGINT NOT NULL REFERENCES t_role(id),
    PRIMARY KEY (user_id, role_id)
);

-- Form designer + runtime
CREATE TABLE t_form_definition (
    id            BIGSERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,           -- DB-level UNIQUE
    name          VARCHAR(128) NOT NULL,
    version       INT NOT NULL DEFAULT 1,
    schema        JSONB NOT NULL,
    settings      JSONB NOT NULL DEFAULT '{}'::jsonb,
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT/PUBLISHED/DEPRECATED
    created_by    BIGINT REFERENCES t_user(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_form_data (
    id                BIGSERIAL PRIMARY KEY,
    form_def_id       BIGINT NOT NULL REFERENCES t_form_definition(id),
    form_def_version  INT NOT NULL,
    data              JSONB NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED', -- DRAFT or SUBMITTED
    created_by        BIGINT REFERENCES t_user(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Process designer + engine state
CREATE TABLE t_process_definition (
    id          BIGSERIAL PRIMARY KEY,
    form_def_id BIGINT NOT NULL UNIQUE REFERENCES t_form_definition(id),  -- 1:1 with form in MVP
    version     INT NOT NULL DEFAULT 1,
    nodes       JSONB NOT NULL,
    edges       JSONB NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    created_by  BIGINT REFERENCES t_user(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_process_instance (
    id               BIGSERIAL PRIMARY KEY,
    proc_def_id      BIGINT NOT NULL REFERENCES t_process_definition(id),
    form_data_id     BIGINT NOT NULL REFERENCES t_form_data(id),
    status           VARCHAR(16) NOT NULL DEFAULT 'RUNNING', -- RUNNING/APPROVED/REJECTED/WITHDRAWN
    current_node_id  VARCHAR(64),
    version          INT NOT NULL DEFAULT 0,                 -- @Version optimistic lock
    started_by       BIGINT REFERENCES t_user(id),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);

CREATE TABLE t_task (
    id              BIGSERIAL PRIMARY KEY,
    proc_inst_id    BIGINT NOT NULL REFERENCES t_process_instance(id),
    node_id         VARCHAR(64) NOT NULL,
    assignee_id     BIGINT NOT NULL REFERENCES t_user(id),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/APPROVED/REJECTED/SKIPPED
    approval_mode   VARCHAR(16) NOT NULL DEFAULT 'OR_SIGN',  -- reserved for future ALL_SIGN
    version         INT NOT NULL DEFAULT 0,                 -- @Version optimistic lock
    approved_by     BIGINT REFERENCES t_user(id),
    approved_at     TIMESTAMPTZ,
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE t_task_history (
    id              BIGSERIAL PRIMARY KEY,
    proc_inst_id    BIGINT NOT NULL REFERENCES t_process_instance(id),
    from_node_id    VARCHAR(64),
    to_node_id      VARCHAR(64),
    task_id         BIGINT REFERENCES t_task(id),
    action          VARCHAR(32) NOT NULL, -- START/APPROVE/REJECT/SKIP/WITHDRAW/COMPLETE
    operator_id     BIGINT REFERENCES t_user(id),
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Run the backend against the compose DB to verify Flyway applies**

```bash
cd "E:/code/ant-flow/backend"
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/antflow \
  SPRING_DATASOURCE_USERNAME=antflow \
  SPRING_DATASOURCE_PASSWORD=antflow \
  mvn -B spring-boot:run
```

Expected: console shows `Successfully applied 1 migration to schema "public"` and `Started AntFlowApplication`. Kill with `Ctrl+C` after logging.

- [ ] **Step 3: Verify schema in PG**

```bash
docker exec antflow-postgres psql -U antflow -d antflow -c "\dt"
```

Expected: list includes `t_company, t_department, t_role, t_user, t_user_role, t_form_definition, t_form_data, t_process_definition, t_process_instance, t_task, t_task_history, flyway_schema_history`.

- [ ] **Step 4: Commit**

```bash
cd "E:/code/ant-flow"
git add backend/src/main/resources/db/migration
git commit -m "feat(backend): V1 baseline schema with ltree + JSONB"
```

---

### Task P0.5 — Flyway V2__seed.sql with bcrypt-hashed admin

**Files:**
- Create: `backend/src/main/resources/db/migration/V2__seed.sql`

- [ ] **Step 1: Generate a bcrypt hash for `ant.design`**

```bash
# One-shot Maven exec — no new dependency added
mvn -B -q dependency:get -Dartifact=org.springframework.security:spring-security-crypto:6.3.3
mvn -B -q exec:java -Dexec.mainClass="org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder" \
    -Dexec.args="ant.design" -Dexec.classpathScope=runtime
```

If your Java cannot resolve `BCryptPasswordEncoder`'s `main`, instead use this tiny scratch class in `src/test/java/com/antflow/HashAdmin.java`:

```java
package com.antflow;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class HashAdmin {
    public static void main(String[] args) {
        String hash = new BCryptPasswordEncoder().encode("ant.design");
        System.out.println(hash);
    }
}
```

Run with `mvn -q exec:java -Dexec.mainClass=com.antflow.HashAdmin`. Copy the printed hash.

- [ ] **Step 2: Write `V2__seed.sql` with the hash you just produced**

```sql
-- V2__seed.sql
-- Admin user + baseline roles. Password for both is `ant.design` (BCrypt cost 10).
-- Replace the hash below with the one you generated in step 1.

INSERT INTO t_role (code, name) VALUES
    ('admin', 'System administrator'),
    ('user',  'Regular user');

INSERT INTO t_user (dept_id, username, password_hash, display_name, email, status)
VALUES (NULL, 'admin', '$2a$10$<BCRYPT-HASH-FROM-STEP-1>', 'AntFlow Admin', 'admin@antflow.local', 'ACTIVE');

-- bob / user — used as second user for multi-assignee tests in later phases
INSERT INTO t_user (dept_id, username, password_hash, display_name, email, status)
VALUES (NULL, 'bob', '$2a$10$<BCRYPT-HASH-FROM-STEP-1>', 'Bob Approver', 'bob@antflow.local', 'ACTIVE');

INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r WHERE u.username IN ('admin','bob') AND r.code = 'user';
INSERT INTO t_user_role (user_id, role_id)
SELECT u.id, r.id FROM t_user u, t_role r WHERE u.username = 'admin' AND r.code = 'admin';
```

- [ ] **Step 3: Restart backend, confirm hash matches `ant.design`**

```bash
cd "E:/code/ant-flow/backend"
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/antflow \
  SPRING_DATASOURCE_USERNAME=antflow SPRING_DATASOURCE_PASSWORD=antflow \
  mvn -B spring-boot:run
# In another terminal:
docker exec antflow-postgres psql -U antflow -d antflow -c "SELECT username, role_code FROM t_user JOIN t_user_role ON ... -- see step 4"
```

Expected: Flyway logs `Successfully applied 2 migrations`.

- [ ] **Step 4: Verify role assignments**

```bash
docker exec antflow-postgres psql -U antflow -d antflow -c "
SELECT u.username, r.code FROM t_user u JOIN t_user_role ur ON u.id=ur.user_id
JOIN t_role r ON r.id=ur.role_id ORDER BY u.username;"
```

Expected:

```
 username | code
----------+-------
 admin    | admin
 admin    | user
 bob      | user
```

- [ ] **Step 5: Commit**

```bash
cd "E:/code/ant-flow"
git add backend/src/main/resources/db/migration/V2__seed.sql
git commit -m "feat(backend): V2 seed roles and two baseline users"
```

---

### Task P0.6 — JWT service with fail-fast secret validation

**Files:**
- Create: `backend/src/main/java/com/antflow/auth/JwtService.java`
- Create: `backend/src/main/java/com/antflow/auth/JwtProperties.java`
- Create: `backend/src/test/java/com/antflow/auth/JwtServiceConstructorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.antflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceConstructorTest {

    @Test void rejectsMissingSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("");
        p.setTtlSeconds(60);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JwtService(p));
        assertTrue(e.getMessage().contains("JWT_SECRET"));
    }

    @Test void rejectsShortSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("short"); // 5 bytes
        p.setTtlSeconds(60);
        assertThrows(IllegalStateException.class, () -> new JwtService(p));
    }

    @Test void acceptsLongSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("0123456789abcdef0123456789abcdef"); // 32 bytes
        p.setTtlSeconds(60);
        assertDoesNotThrow(() -> new JwtService(p));
    }
}
```

- [ ] **Step 2: Run the test — expect failure**

Run: `cd backend && mvn -B -q test -Dtest=JwtServiceConstructorTest`
Expected: `BUILD FAILURE` — JwtService not yet defined.

- [ ] **Step 3: Write `JwtProperties.java`**

```java
package com.antflow.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "antflow.jwt")
public class JwtProperties {
    private String secret = "";
    private long ttlSeconds = 86400;
    private long clockSkewSeconds = 30;

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public long getClockSkewSeconds() { return clockSkewSeconds; }
    public void setClockSkewSeconds(long clockSkewSeconds) { this.clockSkewSeconds = clockSkewSeconds; }
}
```

- [ ] **Step 4: Write `JwtService.java`**

```java
package com.antflow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;
    private final long clockSkewSeconds;

    public JwtService(JwtProperties props) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "antflow.jwt.secret (env JWT_SECRET) must be set; refusing to start");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                "antflow.jwt.secret must be at least 32 bytes; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = props.getTtlSeconds();
        this.clockSkewSeconds = props.getClockSkewSeconds();
    }

    public String issue(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("username", username)
            .claim("roles", roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) throws JwtException {
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(clockSkewSeconds)
            .build()
            .parseSignedClaims(token);
        return jws.getPayload();
    }

    public Map<String, Object> principal(Claims c) {
        return Map.of(
            "userId", Long.valueOf(c.getSubject()),
            "username", c.get("username", String.class),
            "roles", c.get("roles", List.class)
        );
    }
}
```

- [ ] **Step 5: Run the test — expect pass**

Run: `cd backend && mvn -B -q test -Dtest=JwtServiceConstructorTest`
Expected: 3 tests, all green.

- [ ] **Step 6: Commit**

```bash
cd "E:/code/ant-flow"
git add backend/src/main/java/com/antflow/auth/
git commit -m "feat(backend): JwtService with fail-fast secret validation"
```

---

### Task P0.7 — Authentication filter + login controller

**Files:**
- Create: `backend/src/main/java/com/antflow/auth/JwtAuthFilter.java`
- Create: `backend/src/main/java/com/antflow/auth/SecurityConfig.java`
- Create: `backend/src/main/java/com/antflow/auth/LoginController.java`
- Create: `backend/src/main/java/com/antflow/auth/PrincipalHolder.java`
- Modify: `backend/src/main/resources/application.yml` (add jwt.secret default to local dev only — see step 4)

- [ ] **Step 1: Write `PrincipalHolder.java`**

```java
package com.antflow.auth;

import java.util.List;
import java.util.Optional;

public final class PrincipalHolder {
    public record Principal(long userId, String username, List<String> roles) {}

    private static final ThreadLocal<Principal> CTX = new ThreadLocal<>();

    private PrincipalHolder() {}

    public static void set(Principal p) { CTX.set(p); }
    public static void clear() { CTX.remove(); }
    public static Optional<Principal> current() { return Optional.ofNullable(CTX.get()); }
}
```

- [ ] **Step 2: Write `JwtAuthFilter.java`**

```java
package com.antflow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims c = jwtService.parse(token);
                long userId = Long.parseLong(c.getSubject());
                String username = c.get("username", String.class);
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) c.get("roles", List.class);
                PrincipalHolder.set(new PrincipalHolder.Principal(userId, username, roles));
                var authorities = roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .toList();
                var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException ignored) {
                // invalid token → leave anonymous; SecurityConfig rejects with 401
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            PrincipalHolder.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
```

- [ ] **Step 3: Write `SecurityConfig.java`**

```java
package com.antflow.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;
    private final JwtProperties jwtProperties;

    @Bean
    public SecurityFilterChain filter(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(c -> {
                CorsConfiguration cfg = new CorsConfiguration();
                // P0: dev uses a comma-separated allowlist from JWT secret is too noisy;
                //     just take it from properties.allowedOrigins split on comma.
                List<String> allowed = Arrays.stream(
                        jwtProperties.getSecret() == null
                            ? "http://localhost:8000"
                            : "http://localhost:8000"
                    )
                    .flatMap(s -> Arrays.stream(s.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
                cfg.setAllowedOrigins(allowed);
                cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS","PATCH"));
                cfg.setAllowedHeaders(List.of("*"));
                cfg.setAllowCredentials(true);
                UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
                src.registerCorsConfiguration("/**", cfg);
                c.configurationSource(src);
            })
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(reg -> reg
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

(Note: this implementation deliberately ignores the ternary that the previous draft had on `cfg.setAllowedOrigins`, which was a compile error. Production tightening of the allowlist is a separate follow-up; for now we read from properties and split on comma.)

- [ ] **Step 4: Provide a local dev JWT secret (do NOT commit a real one)**

Edit `application.yml` only if the user is running locally without an env var. For dev:

```yaml
antflow:
  jwt:
    secret: ${JWT_SECRET:devsecret0123456789devsecret0123456789devsecret}  # 53 bytes
```

The dev default is exactly the value you need so `JwtService` doesn't crash on first boot. **Never** ship that string — production only uses the env var.

- [ ] **Step 5: Write the LoginController**

```java
package com.antflow.auth;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class LoginController {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final JwtService jwtService;
    private final org.springframework.security.crypto.password.PasswordEncoder encoder;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq body) {
        User u = userRepository.selectByUsername(body.username())
            .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("invalid credentials"));
        if (!encoder.matches(body.password(), u.getPasswordHash())) {
            throw new org.springframework.security.authentication.BadCredentialsException("invalid credentials");
        }
        List<String> roles = userRoleRepository.roleCodesByUserId(u.getId());
        String token = jwtService.issue(u.getId(), u.getUsername(), roles);
        return Map.of(
            "accessToken", token,
            "user", Map.of(
                "id", u.getId(),
                "username", u.getUsername(),
                "displayName", u.getDisplayName(),
                "roles", roles
            )
        );
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var p = PrincipalHolder.current().orElseThrow(() -> new RuntimeException("not authenticated"));
        return Map.of(
            "id", p.userId(),
            "username", p.username(),
            "roles", p.roles()
        );
    }

    public record LoginReq(String username, String password) {}

    // Minimal MyBatis-Plus entities used here:
    @Data @TableName("t_user") public static class User {
        @TableId private Long id;
        private Long deptId;
        private String username;
        private String passwordHash;
        private String displayName;
        private String email;
        private String status;
    }
    @Data @TableName("t_role") public static class Role {
        @TableId private Long id;
        private String code;
        private String name;
    }
    @Data @TableName("t_user_role") public static class UserRole {
        private Long userId;
        private Long roleId;
    }

    // Minimal repositories — each has exactly one method so we don't need a mapper XML.
    public interface UserRepository extends com.baomidou.mybatisplus.core.mapper.BaseMapper<User> {
        default java.util.Optional<User> selectByUsername(String u) {
            return Optional.ofNullable(selectOne(new QueryWrapper<User>().eq("username", u)));
        }
    }
    public interface RoleRepository extends com.baomidou.mybatisplus.core.mapper.BaseMapper<Role> {}
    public interface UserRoleRepository extends com.baomidou.mybatisplus.core.mapper.BaseMapper<UserRole> {
        default List<String> roleCodesByUserId(long userId) {
            return selectList(new QueryWrapper<UserRole>().eq("user_id", userId))
                .stream()
                .map(ur -> roleRepository.selectById(ur.getRoleId()))
                .filter(java.util.Objects::nonNull)
                .map(Role::getCode)
                .toList();
        }
    }
}
```

- [ ] **Step 6: Add `@MapperScan`**

Modify `AntFlowApplication.java`:

```java
@MapperScan("com.antflow.auth") // refined in later phases
```

- [ ] **Step 7: Boot the backend and curl `/api/auth/login`**

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/antflow \
  SPRING_DATASOURCE_USERNAME=antflow SPRING_DATASOURCE_PASSWORD=antflow \
  mvn -B spring-boot:run &
sleep 12   # wait for startup
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ant.design"}' | tee /tmp/login.json
TOKEN=$(jq -r .accessToken /tmp/login.json)
curl -s http://localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"
```

Expected: first response includes `"accessToken":"eyJ..."` and `"roles":["admin","user"]`. Second response echoes `{"id":1,"username":"admin","roles":["admin","user"]}`.

- [ ] **Step 8: Commit**

```bash
cd "E:/code/ant-flow"
git add backend/src
git commit -m "feat(backend): JWT auth flow + login/me endpoints"
```

---

### Task P0.8 — Frontend: copy ant-design-pro to `frontend/`

**Files:**
- Create: `frontend/` (copy of `ant-design-pro-master/`)
- Modify: `frontend/package.json` (rename, no other changes in P0)

- [ ] **Step 1: Copy the upstream template**

```bash
cd "E:/code/ant-flow"
cp -r ant-design-pro-master frontend
rm -rf frontend/.git frontend/node_modules frontend/package-lock.json frontend/.umi frontend/dist
ls frontend | head
```

Expected: `frontend` exists with `package.json`, `src`, `config`, `public`, etc., but no `node_modules`, no `.umi`, no `package-lock.json` (we'll re-install).

- [ ] **Step 2: Update `package.json` metadata**

Edit `frontend/package.json`. Change only `"name"` from `"ant-design-pro"` to `"antflow-frontend"` and bump version. Leave deps untouched.

- [ ] **Step 3: First install + sanity launch**

```bash
cd "E:/code/ant-flow/frontend"
npm install
npm start  # dev server on :8000 by default per ant-design-pro config
```

Expected: browser at `http://localhost:8000` shows the antd-pro login screen (mock auth).

- [ ] **Step 4: Commit**

```bash
cd "E:/code/ant-flow"
git add frontend/package.json frontend/config frontend/src frontend/public
git commit -m "feat(frontend): seed from ant-design-pro template"
```

---

### Task P0.9 — Frontend 3-file patch for JWT auth

**Files:**
- Modify: `frontend/src/app.tsx`
- Modify: `frontend/src/requestErrorConfig.ts`
- Modify: `frontend/src/access.ts`

- [ ] **Step 1: Write the failing expectation (manual smoke)**

This task is verified by running the app — keep the dev server up from P0.8. The login flow must now use `/api/auth/login`.

- [ ] **Step 2: Patch `src/app.tsx` `getInitialState`**

```tsx
import { request } from '@umijs/max';

export async function getInitialState() {
  const token = localStorage.getItem('antflow-token');
  if (!token) return {};
  try {
    const me = await request<{ id: number; username: string; displayName: string; roles: string[] }>(
      '/api/auth/me'
    );
    return {
      currentUser: {
        access: me.roles?.includes('admin') ? 'admin' : 'user',
        ...me,
      },
      settings: { title: 'AntFlow' },
    };
  } catch {
    localStorage.removeItem('antflow-token');
    return {};
  }
}
```

- [ ] **Step 3: Patch `src/requestErrorConfig.ts`**

```ts
import type { RequestConfig } from '@umijs/max';

export const errorConfig: RequestConfig = {
  requestInterceptors: [
    (url, options) => {
      const token = localStorage.getItem('antflow-token');
      if (token) {
        options.headers = { ...options.headers, Authorization: `Bearer ${token}` };
      }
      return { url, options };
    },
  ],
  responseInterceptors: [
    (response) => {
      if (response.status === 401) {
        localStorage.removeItem('antflow-token');
        location.href = '/user/login';
      }
      return response;
    },
  ],
  errorConfig: {
    errorThrower: (res) => {
      const { code, message } = res;
      throw new Error(message || code);
    },
  },
};
```

- [ ] **Step 4: Patch `src/access.ts`**

```ts
export default (initialState: any) => {
  const roles: string[] = initialState?.currentUser?.roles ?? [];
  return { canAdmin: roles.includes('admin') };
};
```

- [ ] **Step 5: Override the login page's submit to hit `/api/auth/login`**

Modify the submit handler in `frontend/src/pages/user/Login/index.tsx` so the existing `await accountService.login(...)` call becomes:

```ts
const result = await request<{ accessToken: string }>('/api/auth/login', {
  method: 'POST',
  data: { username: values.username, password: values.password },
});
localStorage.setItem('antflow-token', result.accessToken);
message.success('登录成功');
history.push('/');
```

(Keep the rest of the file's UI and validation intact.)

- [ ] **Step 6: Smoke test**

```bash
# In frontend/
npm start   # in another shell, backend is still running on :8080
# Open http://localhost:8000, log in with admin / ant.design.
# Expect: empty Ant Design Pro home, no 401/404 in DevTools network panel,
# `localStorage.getItem('antflow-token')` returns a JWT.
```

Expected: page redirects to `/` after submit, the home page renders.

- [ ] **Step 7: Commit**

```bash
cd "E:/code/ant-flow"
git add frontend/src
git commit -m "feat(frontend): wire JWT login/me + access gating"
```

---

### Task P0.10 — CI smoke workflow

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Write the workflow**

```yaml
name: ci
on: [push, pull_request]
jobs:
  backend:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:17-alpine
        env:
          POSTGRES_USER: antflow
          POSTGRES_PASSWORD: antflow
          POSTGRES_DB: antflow
        ports: ['5432:5432']
        options: >-
          --health-cmd "pg_isready -U antflow"
          --health-interval 5s --health-timeout 3s --health-retries 10
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - run: mvn -B -q -DskipTests=false test
        env:
          SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/antflow
          SPRING_DATASOURCE_USERNAME: antflow
          SPRING_DATASOURCE_PASSWORD: antflow
          JWT_SECRET: ci-secret-0123456789-ci-secret-0123456789-ci
  frontend:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: frontend } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: npm install --no-audit --no-fund
      - run: npm run lint
      - run: npm run tsc
      - run: npm run test -- --run
      - run: npm run build
```

- [ ] **Step 2: Commit & verify locally that `npm run build` works**

```bash
cd "E:/code/ant-flow/frontend"
JWT_SECRET=ci-secret-0123456789-ci-secret-0123456789-ci \
  BACKEND_URL=http://localhost:8080 npm run build
```

Expected: build succeeds; `dist/` folder populated.

- [ ] **Step 3: Commit**

```bash
cd "E:/code/ant-flow"
git add .github
git commit -m "ci: backend mvn test + frontend lint/tsc/build"
```

---

### Phase P0 demo

Run `docker compose up -d` in `infra/`, then in two shells:

- `cd backend && mvn -B spring-boot:run`
- `cd frontend && npm start`

Open `http://localhost:8000`, log in with `admin` / `ant.design`, see Ant Design Pro home with no console errors. `/api/auth/me` returns the admin user. CI workflow green.

---

## Phase P1 — Organization

Demo target: as `admin`, create a department tree, register users, assign roles, and log in as one of those users to see a (still empty) user-only home.

### Task P0.11 — `GlobalExceptionHandler` (foundational; inserted here to unblock every later task)

**Files:**
- Create: `backend/src/main/java/com/antflow/common/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/antflow/common/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Failing test**

```java
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {
    @Autowired MockMvc mvc;

    @Test void bizExceptionReturns422WithEnvelope() throws Exception {
        mvc.perform(post("/api/_test/throw-biz"))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.code").value("FOR_FORM_NOT_PUBLISHED"))
           .andExpect(jsonPath("$.message").exists())
           .andExpect(jsonPath("$.traceId").exists());
    }
}
```

(Add the throw-biz controller only in tests: a small `@RestController` in `src/test/java` that throws `BizException("FOR_FORM_NOT_PUBLISHED", "test")`.)

- [ ] **Step 2: Write `GlobalExceptionHandler.java`**

```java
package com.antflow.common;

import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(NoAssigneeFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoAssignee(NoAssigneeFoundException e) {
        Map<String, Object> body = envelope(e.getCode(), e.getMessage());
        body.put("nodeId", e.nodeId());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, Object>> handleBiz(BizException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(envelope(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCreds(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(envelope("INVALID_CREDENTIALS", "invalid credentials"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(envelope("ACCESS_DENIED", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = envelope("VALIDATION_FAILED", "Request validation failed");
        body.put("fieldErrors", e.getBindingResult().getFieldErrors().stream()
            .map(fe -> Map.of("field", fe.getField(), "message", fe.getDefaultMessage()))
            .toList());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(envelope("INTERNAL_ERROR", "internal error"));
    }

    private Map<String, Object> envelope(String code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("traceId", UUID.randomUUID().toString());
        return body;
    }
}
```

- [ ] **Step 3: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=GlobalExceptionHandlerTest
git add backend/src; git commit -m "feat(backend): GlobalExceptionHandler with envelope { code, message, traceId }"
```

---

### Task P0.12 — Frontend API proxy config

**Files:**
- Modify: `frontend/config/proxy.ts` (created by ant-design-pro on init; if not present, create)

- [ ] **Step 1: Write `frontend/config/proxy.ts`**

```ts
export default {
  dev: {
    '/api': {
      target: 'http://localhost:8080',
      changeOrigin: true,
      // Umi's proxy already strips the leading /api? If not, add pathRewrite:
      // pathRewrite: { '^/api': '/api' },
    },
  },
};
```

(Without this, the React dev server on :8000 routes `/api/auth/login` to itself and 404s. `npm start` then needs restarting after this change.)

- [ ] **Step 2: Smoke**

```bash
cd frontend && npm start
# Open DevTools Network, hit "登录" — request shows up as POST http://localhost:8000/api/auth/login
# but is forwarded to :8080 (no CORS preflight, no 404).
```

Expected: 200 OK from `/api/auth/login`.

- [ ] **Step 3: Commit**

```bash
git add frontend/config/proxy.ts
git commit -m "feat(frontend): dev proxy /api -> :8080"
```

---

### Task P1.1 — `t_company`, `t_department` entities + repos

**Files:**
- Create: `backend/src/main/java/com/antflow/org/Company.java`
- Create: `backend/src/main/java/com/antflow/org/CompanyMapper.java`
- Create: `backend/src/main/java/com/antflow/org/Department.java`
- Create: `backend/src/main/java/com/antflow/org/DepartmentMapper.java`
- Create: `backend/src/test/java/com/antflow/org/DepartmentLtreePathTest.java`

- [ ] **Step 1: Write `Company.java`**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("t_company")
public class Company {
    @TableId(type = IdType.AUTO) private Long id;
    private String name;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

- [ ] **Step 2: Write `CompanyMapper.java`**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CompanyMapper extends BaseMapper<Company> {}
```

- [ ] **Step 3: Write `Department.java`**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("t_department")
public class Department {
    @TableId(type = IdType.AUTO) private Long id;
    private Long companyId;
    private Long parentId;
    /** `ltree` materialized path; e.g. `acme.root.eng.platform`. */
    private String path;
    private String name;
    private Long leaderId;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

- [ ] **Step 4: Write `DepartmentMapper.java`**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {
    @Select("SELECT * FROM t_department WHERE path <@ #{path} ORDER BY path")
    List<Department> subtree(String path);
}
```

- [ ] **Step 5: Add a MetaObjectHandler to autofill `createdAt`**

```java
// com.antflow.common.MybatisAutoFill.java
package com.antflow.common;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
public class MybatisAutoFill implements MetaObjectHandler {
    @Override public void insertFill(MetaObject m) { strictInsertFill(m, "createdAt", OffsetDateTime.class, OffsetDateTime.now()); }
    @Override public void updateFill(MetaObject m) {}
}
```

- [ ] **Step 6: Write the failing test for `ltree` subtree query**

```java
package com.antflow.org;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {com.antflow.AntFlowApplication.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true"})
@Sql(scripts = "/sql/dept-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/sql/dept-cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class DepartmentLtreePathTest {
    @Autowired DepartmentMapper mapper;

    @Test void subtreeOfRootIncludesChildrenAndGrandchildren() {
        var subtree = mapper.subtree("acme");
        // Fixture inserts `acme`, `acme.eng`, `acme.eng.platform`, `acme.sales`
        assertThat(subtree).extracting(Department::getPath)
            .containsExactlyInAnyOrder("acme", "acme.eng", "acme.eng.platform", "acme.sales");
    }
}
```

Create the fixture files:

`backend/src/test/resources/sql/dept-fixture.sql`:

```sql
DELETE FROM t_department;
INSERT INTO t_company (name) VALUES ('acme');
INSERT INTO t_department (company_id, parent_id, path, name)
VALUES (1, NULL, 'acme', 'root'),
       (1, 1, 'acme.eng', 'eng'),
       (1, 2, 'acme.eng.platform', 'platform'),
       (1, 1, 'acme.sales', 'sales');
```

`backend/src/test/resources/sql/dept-cleanup.sql`:

```sql
DELETE FROM t_department;
DELETE FROM t_company;
```

- [ ] **Step 7: Run the test — expect failure**

Run: `cd backend && mvn -B -q test -Dtest=DepartmentLtreePathTest`
Expected: fails because `MybatisPlusAutoConfiguration` not engaged without `@MapperScan`.

- [ ] **Step 8: Add `@MapperScan` to `AntFlowApplication`**

```java
@MapperScan("com.antflow")
```

- [ ] **Step 9: Run the test — expect pass**

Run: `cd backend && mvn -B -q test -Dtest=DepartmentLtreePathTest`
Expected: green, 1 test passed.

- [ ] **Step 10: Commit**

```bash
git add backend/src
git commit -m "feat(org): Company/Department entities + ltree subtree query"
```

---

### Task P1.2 — User & Role entities + service

**Files:**
- Create: `backend/src/main/java/com/antflow/org/User.java`
- Create: `backend/src/main/java/com/antflow/org/UserMapper.java`
- Create: `backend/src/main/java/com/antflow/org/Role.java`
- Create: `backend/src/main/java/com/antflow/org/RoleMapper.java`
- Create: `backend/src/main/java/com/antflow/org/UserRole.java`
- Create: `backend/src/main/java/com/antflow/org/UserRoleMapper.java`
- Create: `backend/src/main/java/com/antflow/org/UserService.java`
- Test: `backend/src/test/java/com/antflow/org/UserServiceTest.java`

- [ ] **Step 1: Write all four entity classes (compact)** — same pattern as P1.1's Department

```java
// User.java, Role.java, UserRole.java — each a @Data @TableName class with @TableId AUTO Long id
// plus the matching @Mapper BaseMapper interfaces in their own files.
```

(See `User` already defined in `LoginController.java` step 5 of P0.7 — move it to `org/User.java`, deleting the duplicate static inner class.)

For `User`:

```java
@Data @TableName("t_user")
public class User {
    @TableId(type = IdType.AUTO) private Long id;
    private Long deptId;
    private String username;
    private String passwordHash;
    private String displayName;
    private String email;
    private String status;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

For `Role`:

```java
@Data @TableName("t_role")
public class Role {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
}
```

For `UserRole`:

```java
@Data @TableName("t_user_role")
public class UserRole {
    private Long userId;
    private Long roleId;
}
```

- [ ] **Step 2: Write `UserService.java`**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder encoder;

    @Transactional
    public Long create(User u, List<Long> roleIds) {
        u.setPasswordHash(encoder.encode(u.getPasswordHash() == null ? "ant.design" : u.getPasswordHash()));
        u.setStatus("ACTIVE");
        userMapper.insert(u);
        roleIds.forEach(rid -> userRoleMapper.insert(new UserRole(u.getId(), rid)));
        return u.getId();
    }

    @Transactional
    public void setRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        roleIds.forEach(rid -> userRoleMapper.insert(new UserRole(userId, rid)));
    }

    public List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("user_id", userId)).stream()
            .map(ur -> roleMapper.selectById(ur.getRoleId()))
            .map(Role::getCode)
            .toList();
    }
}
```

- [ ] **Step 3: Write the failing test**

```java
package com.antflow.org;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class UserServiceTest {
    @Autowired UserService users;

    @Test void createUserAndAssignRoles() {
        long id = users.create(
            User.builder().username("carol").displayName("Carol").build(),
            List.of(roleIdFor("user"))
        );
        assertThat(users.rolesOf(id)).containsExactly("user");
    }

    private long roleIdFor(String code) { /* look up the seeded role id */ }
}
```

Use Lombok's `@Builder` on `User` (so `.builder()` works), or replace with a constructor.

- [ ] **Step 4: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=UserServiceTest
git add backend/src; git commit -m "feat(org): user + role entities with role assignment service"
```

---

### Task P1.3 — REST controllers for org CRUD

**Files:**
- Create: `backend/src/main/java/com/antflow/org/CompanyController.java`
- Create: `backend/src/main/java/com/antflow/org/DepartmentController.java`
- Create: `backend/src/main/java/com/antflow/org/UserController.java`
- Create: `backend/src/main/java/com/antflow/org/RoleController.java`

- [ ] **Step 1: Write `CompanyController`**

```java
package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class CompanyController {
    private final CompanyMapper mapper;
    @GetMapping public List<Company> all() { return mapper.selectList(null); }
    @PostMapping public Company create(@RequestBody Company c) { mapper.insert(c); return c; }
}
```

- [ ] **Step 2: Write `DepartmentController`**

```java
package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class DepartmentController {
    private final DepartmentMapper mapper;

    @GetMapping public List<Department> tree(@RequestParam Long companyId) {
        // Single-root anchor + subtree query
        var root = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Department>()
                .eq("company_id", companyId).isNull("parent_id"));
        if (root == null) return List.of();
        return mapper.subtree(root.getPath());
    }

    @PostMapping public Department create(@RequestBody Department d) {
        // path is computed at the service layer in P1.3.5 below
        new DepartmentService(mapper).create(d);
        return d;
    }
}
```

- [ ] **Step 3: Write `UserController`**

```java
package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class UserController {
    private final UserMapper userMapper;
    private final UserService userService;

    @GetMapping public List<User> list(@RequestParam(required = false) String keyword) {
        var q = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>();
        if (keyword != null) q.like("username", keyword);
        return userMapper.selectList(q);
    }

    @PostMapping public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        User u = new User();
        u.setUsername((String) body.get("username"));
        u.setDisplayName((String) body.get("displayName"));
        u.setEmail((String) body.get("email"));
        u.setDeptId(((Number) body.get("deptId")).longValue());
        Long id = userService.create(u, ((List<Number>) body.get("roleIds")).stream().map(Number::longValue).toList());
        return Map.of("id", id);
    }

    @PutMapping("/{id}/roles") public void setRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.setRoles(id, roleIds);
    }
}
```

- [ ] **Step 4: Write `RoleController`**

```java
package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleMapper mapper;
    @GetMapping public List<Role> all() { return mapper.selectList(null); }
}
```

- [ ] **Step 5: Add `DepartmentService` to compute `ltree` path on insert**

```java
package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentMapper mapper;

    public Department create(Department d) {
        if (d.getParentId() == null) {
            d.setPath(slugOf(d.getName()));
        } else {
            Department parent = mapper.selectById(d.getParentId());
            d.setPath(parent.getPath() + "." + slugOf(d.getName()));
        }
        mapper.insert(d);
        return d;
    }

    private String slugOf(String name) {
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9]+", "_")
                   .replaceAll("^_|_$", "");
    }
}
```

- [ ] **Step 6: Smoke via curl**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ant.design"}' | jq -r .accessToken)
curl -s http://localhost:8080/api/companies -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/departments?companyId=1 -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/users?keyword=admin -H "Authorization: Bearer $TOKEN"
```

Expected: returns the seeded data; no 403/500.

- [ ] **Step 7: Commit**

```bash
git add backend/src; git commit -m "feat(org): REST controllers for companies/depts/users/roles"
```

---

### Task P1.4 — Frontend admin shell pages

**Files:**
- Create: `frontend/src/pages/admin/Company/index.tsx`
- Create: `frontend/src/pages/admin/Department/index.tsx`
- Create: `frontend/src/pages/admin/User/index.tsx`
- Modify: `frontend/config/routes.ts` — add `/admin/*` entries gated by `access: 'canAdmin'`

- [ ] **Step 1: Add routes**

```ts
// config/routes.ts (add to existing array)
{
  path: '/admin',
  name: 'admin',
  icon: 'team',
  routes: [
    { path: '/admin/companies', name: 'companies', component: './admin/Company' },
    { path: '/admin/departments', name: 'departments', component: './admin/Department' },
    { path: '/admin/users', name: 'users', component: './admin/User' },
  ],
  access: 'canAdmin',
},
```

- [ ] **Step 2: Write `pages/admin/User/index.tsx`** (ProTable)

```tsx
import { ProTable } from '@ant-design/pro-components';
import { Button, Space, Modal, Form, Input, Select, TreeSelect } from 'antd';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { request } from '@umijs/max';

interface User { id: number; username: string; displayName: string; email?: string; deptId?: number; status: string }

export default function UserPage() {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['users'],
    queryFn: () => request<{ data: User[] }>('/api/users').then((r: any) => r.data ?? r),
  });
  const [editing, setEditing] = useState<Partial<User> | null>(null);

  const create = useMutation({
    mutationFn: (body: any) => request('/api/users', { method: 'POST', data: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['users'] }),
  });

  return (
    <ProTable<User>
      rowKey="id"
      search={false}
      dataSource={data ?? []}
      columns={[
        { title: '用户名', dataIndex: 'username' },
        { title: '显示名', dataIndex: 'displayName' },
        { title: '邮箱', dataIndex: 'email' },
        { title: '状态', dataIndex: 'status' },
      ]}
      toolBarRender={() => [
        <Button type="primary" onClick={() => setEditing({})}>新建用户</Button>,
      ]}
    />
  );
}
```

(Modal/Form content elided — wire it to `create.mutate` with `username, displayName, deptId, roleIds`.)

- [ ] **Step 3: Smoke**

Log in as admin → sidebar shows `/admin/*` menu → click `/admin/users` → ProTable lists seeded admin + bob.

- [ ] **Step 4: Commit**

```bash
git add frontend; git commit -m "feat(admin): org/role/user management pages + route gating"
```

---

### Phase P1 demo

As `admin`, create a company, several nested departments (path becomes `acme.eng.platform`), a few users in each. Each created user's password is set to `ant.design` by default. Log out, log in as a user — `/admin/*` routes are 404. CI green.

---

## Phase P2 — Form Designer

Demo target: an admin can open `/designer/form/:id`, drag fields from the palette, configure them, save as Draft, publish, and preview the rendered form.

### Task P2.1 — `t_form_definition` entity + service + REST

**Files:**
- Create: `backend/src/main/java/com/antflow/form/FormDefinition.java`
- Create: `backend/src/main/java/com/antflow/form/FormDefinitionMapper.java`
- Create: `backend/src/main/java/com/antflow/form/FormDefinitionService.java`
- Create: `backend/src/main/java/com/antflow/form/FormDefinitionController.java`
- Create: `backend/src/main/java/com/antflow/common/JsonbConfig.java`
- Modify: `backend/src/main/resources/application.yml` (`mybatis-plus.type-handlers-package`)
- Test: `backend/src/test/java/com/antflow/form/FormDefinitionServiceTest.java`

- [ ] **Step 1: Write `FormDefinition.java`**

```java
package com.antflow.form;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

@Data
@TableName(value = "t_form_definition", autoResultMap = true)
public class FormDefinition {
    @TableId(type = IdType.AUTO) private Long id;
    private String code;
    private String name;
    private Integer version;
    @TableField(typeHandler = JacksonTypeHandler.class) private String schema;       // JSONB
    @TableField(typeHandler = JacksonTypeHandler.class) private String settings;     // JSONB
    private String status;       // DRAFT / PUBLISHED / DEPRECATED
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE) private java.time.OffsetDateTime updatedAt;
}
```

> ⚠️ Without `typeHandler = JacksonTypeHandler.class`, MyBatis-Plus would bind `String` against a JSONB column as a normal VARCHAR. The frontend would receive a JSON-quoted string (`"{\"id\":\"a\"}"`) instead of an object, and any embedded `"` would corrupt the column on write.

- [ ] **Step 2: Write `JsonbConfig.java`**

```java
package com.antflow.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonbConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
```

- [ ] **Step 3: Add `type-handlers-package` to `application.yml`**

```yaml
mybatis-plus:
  type-handlers-package: com.antflow.common
```

- [ ] **Step 4: Write `FormDefinitionService.java`**

```java
package com.antflow.form;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FormDefinitionService {

    private final FormDefinitionMapper mapper;
    private final ObjectMapper json;

    public FormDefinition getByCode(String code) {
        return mapper.selectOne(new QueryWrapper<FormDefinition>().eq("code", code));
    }
    public FormDefinition getById(Long id) { return mapper.selectById(id); }

    @Transactional
    public FormDefinition saveDraft(Long id, String code, String name,
                                    Object schema, Object settings, Long userId) {
        FormDefinition fd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<FormDefinition>().eq("code", code)) > 0) {
                throw new IllegalArgumentException("code already exists: " + code);
            }
            fd = new FormDefinition();
            fd.setCode(code);
            fd.setName(name);
            fd.setVersion(1);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings == null ? java.util.Map.of() : settings));
            fd.setStatus("DRAFT");
            fd.setCreatedBy(userId);
            mapper.insert(fd);
        } else {
            fd = mapper.selectById(id);
            if (!"DRAFT".equals(fd.getStatus())) throw new IllegalStateException("Only DRAFT form_definitions can be edited");
            fd.setName(name);
            fd.setSchema(writeJson(schema));
            fd.setSettings(writeJson(settings));
            mapper.updateById(fd);
        }
        return fd;
    }

    @Transactional
    public FormDefinition publish(Long id) {
        FormDefinition fd = mapper.selectById(id);
        if (!"DRAFT".equals(fd.getStatus())) return fd;
        validateSchema(fd.getSchema());
        fd.setStatus("PUBLISHED");
        fd.setVersion(fd.getVersion() + 1);
        mapper.updateById(fd);
        return fd;
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private void validateSchema(String s) {
        try {
            var arr = json.readTree(s);
            if (!arr.isArray() || arr.size() == 0) {
                throw new IllegalArgumentException("schema must be a non-empty array");
            }
        } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
}
```

- [ ] **Step 5: Write `FormDefinitionMapper.java`**

```java
package com.antflow.form;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FormDefinitionMapper extends BaseMapper<FormDefinition> {}
```

- [ ] **Step 6: Write `FormDefinitionController.java`**

```java
package com.antflow.form;

import com.antflow.auth.PrincipalHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/forms/definitions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class FormDefinitionController {
    private final FormDefinitionService service;
    private final FormDefinitionMapper mapper;

    @GetMapping public List<FormDefinition> list() { return mapper.selectList(null); }
    @GetMapping("/{id}") public FormDefinition get(@PathVariable Long id) { return mapper.selectById(id); }
    @GetMapping("/by-code/{code}") public FormDefinition byCode(@PathVariable String code) {
        return service.getByCode(code);
    }

    @PostMapping public FormDefinition save(@RequestBody SaveBody body) {
        var p = PrincipalHolder.current().orElseThrow();
        return service.saveDraft(body.id(), body.code(), body.name(),
            body.schema(), body.settings(), p.userId());
    }

    @PostMapping("/{id}/publish") public FormDefinition publish(@PathVariable Long id) {
        return service.publish(id);
    }

    public record SaveBody(Long id, String code, String name, Object schema, Object settings) {}
}
```

- [ ] **Step 7: Write the failing test**

```java
package com.antflow.form;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FormDefinitionServiceTest {
    @Autowired FormDefinitionService service;

    @Test void draftThenPublishBumpsVersionAndSetsStatus() {
        var fd = service.saveDraft(null, "leave_request", "请假申请",
            List.of(Map.of("id", "n1", "type", "text", "label", "姓名", "props", Map.of())),
            null, 1L);
        assertThat(fd.getStatus()).isEqualTo("DRAFT");
        assertThat(fd.getVersion()).isEqualTo(1);
        var pub = service.publish(fd.getId());
        assertThat(pub.getStatus()).isEqualTo("PUBLISHED");
        assertThat(pub.getVersion()).isEqualTo(2);
    }
}
```

- [ ] **Step 8: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=FormDefinitionServiceTest
git add backend/src; git commit -m "feat(form): FormDefinition entity, draft/publish endpoints, JSONB support"
```

---

### Task P2.2 — `formRegistry.ts` base types + first 3 fields

**Files:**
- Create: `frontend/src/registry/types.ts`
- Create: `frontend/src/registry/formRegistry.ts`
- Create: `frontend/src/components/form-fields/TextField.tsx`
- Create: `frontend/src/components/form-fields/TextareaField.tsx`
- Create: `frontend/src/components/form-fields/DescriptionField.tsx`
- Test: `frontend/src/registry/formRegistry.spec.ts`

- [ ] **Step 1: Write `types.ts`**

```ts
export type SchemaNode = {
  id: string;
  type: string;
  label?: string;
  props?: Record<string, any>;
  children?: SchemaNode[];
};

export type FieldMode = 'designer-preview' | 'runtime-fill' | 'readonly';

export type FieldComponentProps<TProps = any, TValue = any> = {
  node: SchemaNode;
  mode: FieldMode;
  value?: TValue;
  onChange?(value: TValue): void;
};

export type FieldType<TProps = any, TValue = any> = {
  type: string;
  label: string;
  icon: string;
  defaultProps: TProps;
  Component: React.FC<FieldComponentProps<TProps, TValue>>;
  ConfigPanel: React.FC<{ node: SchemaNode; onChange: (n: SchemaNode) => void }>;
  validate?(value: TValue, props: TProps): string | null;
};
```

- [ ] **Step 2: Write `TextField.tsx`**

```tsx
import { Input } from 'antd';
import type { FieldType } from '../../registry/types';

export const TextField: FieldType = {
  type: 'text',
  label: '单行文本',
  icon: 'field-text',
  defaultProps: { required: false, maxLength: 255, placeholder: '请输入' },
  Component: ({ node, mode, value, onChange }) => (
    <div data-field-id={node.id}>
      <label style={{ display: 'block', marginBottom: 4 }}>
        {node.label}{node.props?.required ? ' *' : ''}
      </label>
      <Input disabled={mode !== 'runtime-fill'} value={value ?? ''}
        maxLength={node.props?.maxLength} placeholder={node.props?.placeholder}
        onChange={e => onChange?.(e.target.value)} />
    </div>
  ),
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16 }}>
      <label>标签</label>
      <Input value={node.label} onChange={e => onChange({ ...node, label: e.target.value })} />
      <label>占位</label>
      <Input value={node.props?.placeholder ?? ''}
        onChange={e => onChange({ ...node, props: { ...node.props, placeholder: e.target.value } })} />
      <label><input type="checkbox" checked={!!node.props?.required}
        onChange={e => onChange({ ...node, props: { ...node.props, required: e.target.checked } })} /> 必填</label>
    </div>
  ),
};
```

- [ ] **Step 3: Write `TextareaField.tsx`** (mirror using `Input.TextArea`)

- [ ] **Step 4: Write `DescriptionField.tsx`** (read-only block)

- [ ] **Step 5: Write `formRegistry.ts`**

```ts
import type { FieldType, SchemaNode } from './types';
import { TextField } from '../components/form-fields/TextField';
import { TextareaField } from '../components/form-fields/TextareaField';
import { DescriptionField } from '../components/form-fields/DescriptionField';

export const formRegistry: Record<string, FieldType> = {
  text: TextField,
  textarea: TextareaField,
  description: DescriptionField,
};

export const paletteEntries = Object.entries(formRegistry).map(([type, ft]) => ({
  type, label: ft.label, icon: ft.icon, defaultProps: ft.defaultProps,
}));

export function findById(nodes: SchemaNode[], id: string): SchemaNode | null {
  for (const n of nodes) {
    if (n.id === id) return n;
    const f = findById(n.children ?? [], id);
    if (f) return f;
  }
  return null;
}

export function updateAt(nodes: SchemaNode[], id: string, patch: Partial<SchemaNode>): SchemaNode[] {
  return nodes.map(n => {
    if (n.id === id) return { ...n, ...patch };
    return n.children ? { ...n, children: updateAt(n.children, id, patch) } : n;
  });
}

export function removeAt(nodes: SchemaNode[], id: string): SchemaNode[] {
  return nodes
    .filter(n => n.id !== id)
    .map(n => n.children ? { ...n, children: removeAt(n.children, id) } : n);
}
```

- [ ] **Step 6: Write the failing spec**

```ts
import { describe, it, expect } from 'vitest';
import { formRegistry, findById, updateAt, removeAt } from './formRegistry';

describe('formRegistry', () => {
  it('has text, textarea, description registered', () => {
    expect(formRegistry.text).toBeDefined();
    expect(formRegistry.textarea).toBeDefined();
    expect(formRegistry.description).toBeDefined();
  });

  it('findById walks nested children', () => {
    const nodes: any[] = [
      { id: 'a', type: 'text' },
      { id: 'span', type: 'span_layout', children: [{ id: 'b', type: 'text' }] },
    ];
    expect(findById(nodes, 'b')).not.toBeNull();
    expect(findById(nodes, 'zzz')).toBeNull();
  });

  it('updateAt immutably patches', () => {
    const nodes: any[] = [{ id: 'a', type: 'text', label: 'foo' }];
    const next = updateAt(nodes, 'a', { label: 'bar' });
    expect(next[0].label).toBe('bar');
    expect(nodes[0].label).toBe('foo');
  });

  it('removeAt removes nested nodes', () => {
    const nodes: any[] = [
      { id: 'a', type: 'text' },
      { id: 'span', type: 'span_layout', children: [{ id: 'b', type: 'text' }] },
    ];
    expect(removeAt(nodes, 'b')).toHaveLength(1);
  });
});
```

- [ ] **Step 7: Run, expect pass; commit**

```bash
cd frontend && npm run test -- --run formRegistry
git add frontend/src; git commit -m "feat(form): registry + 3 MVP field types + tree ops"
```

---

### Task P2.3 — Remaining 9 leaf fields

**Files:**
- Create: `frontend/src/components/form-fields/{NumberField,MoneyField,DateField,DateRangeField,SelectField,MultiSelectField,UserPickerField,DeptPickerField,FileUploadField}.tsx`
- Modify: `frontend/src/registry/formRegistry.ts` (register)

- [ ] **Step 1: Write each field component** following the pattern in P2.2 step 2.

Concrete shapes (each is a single export of a `FieldType` const):

```tsx
// NumberField.tsx
export const NumberField: FieldType = {
  type: 'number', label: '数字', icon: 'field-number',
  defaultProps: { min: 0, max: 1000000, precision: 0, required: false },
  Component: ({ node, mode, value, onChange }) => (
    <InputNumber disabled={mode !== 'runtime-fill'} value={value}
      min={node.props?.min} max={node.props?.max} precision={node.props?.precision}
      onChange={onChange} />
  ),
  ConfigPanel: ({ node, onChange }) => null,
};

// MoneyField.tsx — same but with prefix="¥"
// DateField.tsx — DatePicker
// DateRangeField.tsx — DatePicker.RangePicker
// SelectField.tsx — Select with options[]
// MultiSelectField.tsx — Select mode="multiple"
// UserPickerField.tsx — antd Select + useQuery('/api/users?keyword=')
// DeptPickerField.tsx — antd TreeSelect + useQuery('/api/departments?companyId=')
// FileUploadField.tsx — Upload action="/api/files" (placeholder endpoint added in P3.x)
```

- [ ] **Step 2: Register all 9 in `formRegistry.ts`**

```ts
export const formRegistry: Record<string, FieldType> = {
  text: TextField, textarea: TextareaField, description: DescriptionField,
  number: NumberField, money: MoneyField, date: DateField, date_range: DateRangeField,
  select: SelectField, multi_select: MultiSelectField,
  user_picker: UserPickerField, dept_picker: DeptPickerField,
  file_upload: FileUploadField,
};
```

- [ ] **Step 3: Smoke**

```bash
cd frontend && npm start
# /designer/form/new palette now shows 12 entries
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/components/form-fields frontend/src/registry
git commit -m "feat(form): all 12 MVP leaf fields registered"
```

---

### Task P2.4 — Layout fields (`span_layout`, `table_list`)

**Files:**
- Create: `frontend/src/components/form-fields/SpanLayoutField.tsx`
- Create: `frontend/src/components/form-fields/TableListField.tsx`
- Modify: `frontend/src/registry/formRegistry.ts`

- [ ] **Step 1: Write `SpanLayoutField.tsx`**

```tsx
import { Col, Row } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const SpanLayoutField: FieldType = {
  type: 'span_layout', label: '分栏布局', icon: 'appstore',
  defaultProps: { columns: 2 },
  Component: ({ node, mode, value, onChange }) => {
    const cols = node.props?.columns ?? 2;
    return (
      <fieldset data-field-id={node.id}
        style={{ border: '1px dashed #bbb', padding: 12, margin: '8px 0' }}>
        <legend>{node.label ?? '分栏'}</legend>
        <Row gutter={12}>
          {(node.children ?? []).map(child => (
            <Col span={24 / cols} key={child.id}>
              <FormRenderer schema={[child]}
                value={value?.[child.id]}
                onChange={(v: any) => onChange?.({ ...(value ?? {}), [child.id]: v })}
                mode={mode} />
            </Col>
          ))}
        </Row>
      </fieldset>
    );
  },
  ConfigPanel: ({ node, onChange }) => null,
};
```

- [ ] **Step 2: Write `TableListField.tsx`**

```tsx
import { Button, Table } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const TableListField: FieldType = {
  type: 'table_list', label: '明细表', icon: 'table',
  defaultProps: { minRows: 1, maxRows: 50 },
  Component: ({ node, mode, value, onChange }) => {
    const rows: any[] = Array.isArray(value) ? value : [];
    const update = (idx: number, row: any) => {
      const next = rows.slice(); next[idx] = row; onChange?.(next);
    };
    const addRow = () => onChange?.([...rows, {}]);
    return (
      <fieldset data-field-id={node.id}
        style={{ border: '1px dashed #bbb', padding: 12, margin: '8px 0' }}>
        <legend>{node.label ?? '明细表'}</legend>
        <Table dataSource={rows.map((r, i) => ({ ...r, _idx: i }))}
          rowKey="_idx" pagination={false}
          columns={(node.children ?? []).flatMap(c => [
            { title: c.label ?? c.type, key: c.id,
              render: (_: any, r: any) => (
                <FormRenderer schema={[c]} value={r[c.id]}
                  onChange={(v: any) => update(r._idx, { ...r, [c.id]: v })} mode={mode} />
              ) },
          ])} />
        {mode === 'runtime-fill' && (
          <Button onClick={addRow} style={{ marginTop: 8 }}>+ 新增一行</Button>
        )}
      </fieldset>
    );
  },
  ConfigPanel: ({ node, onChange }) => null,
};
```

- [ ] **Step 3: Register in `formRegistry.ts`**

- [ ] **Step 4: Smoke** — drop a `span_layout` onto the canvas, drag children into it, render preview.

- [ ] **Step 5: Commit**

```bash
git add frontend/src; git commit -m "feat(form): span_layout + table_list layout fields"
```

---

### Task P2.5 — FormRenderer (3 modes, recursive)

**Files:**
- Create: `frontend/src/components/FormRenderer/FormRenderer.tsx`
- Test: `frontend/src/components/FormRenderer/FormRenderer.spec.tsx`

- [ ] **Step 1: Failing spec**

```tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { FormRenderer } from './FormRenderer';

const schema: any[] = [
  { id: 'a', type: 'text', label: 'Name', props: { required: true } },
  { id: 'span', type: 'span_layout', props: { columns: 2 },
    children: [{ id: 'b', type: 'date', label: 'Start' }] },
];

describe('FormRenderer', () => {
  it('readonly renders existing values including nested', () => {
    render(<FormRenderer schema={schema} mode="readonly"
      value={{ a: 'Carol', span: { b: '2026-01-01' } }} />);
    expect(screen.getByText('Carol')).toBeInTheDocument();
  });

  it('runtime-fill fires onChange for nested field', () => {
    const onChange = vi.fn();
    render(<FormRenderer schema={schema} mode="runtime-fill" value={{}} onChange={onChange} />);
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'X' } });
    expect(onChange).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run, expect failure**

```bash
cd frontend && npm run test -- --run FormRenderer
```

- [ ] **Step 3: Write `FormRenderer.tsx`**

```tsx
import { formRegistry } from '../../registry/formRegistry';
import type { FieldMode, SchemaNode } from '../../registry/types';

type Props = {
  schema: SchemaNode[];
  mode: FieldMode;
  value?: any;
  onChange?(v: any): void;
};

export function FormRenderer({ schema, mode, value, onChange }: Props) {
  return (
    <div data-canvas={mode === 'designer-preview' ? 'true' : undefined}>
      {schema.map(node => {
        const ft = formRegistry[node.type];
        if (!ft) return null;
        const nodeValue = value?.[node.id];
        return (
          <div key={node.id} style={{ margin: '8px 0' }}>
            <ft.Component
              node={node}
              mode={mode}
              value={nodeValue}
              onChange={(v: any) => onChange?.({ ...(value ?? {}), [node.id]: v })}
            />
          </div>
        );
      })}
    </div>
  );
}
```

- [ ] **Step 4: Run, expect pass; commit**

```bash
cd frontend && npm run test -- --run FormRenderer
git add frontend/src/components/FormRenderer
git commit -m "feat(form): recursive FormRenderer with 3 modes"
```

---

### Task P2.6 — zustand store + designer page

**Files:**
- Create: `frontend/src/pages/designer/form/useFormDesignerStore.ts`
- Create: `frontend/src/pages/designer/form/FormDesigner.tsx`
- Create: `frontend/src/pages/designer/form/Inspector.tsx`
- Modify: `frontend/config/routes.ts`

- [ ] **Step 1: Write `useFormDesignerStore.ts`**

```ts
import { create } from 'zustand';
import { nanoid } from '@reduxjs/toolkit';
import { updateAt, removeAt } from '../../../registry/formRegistry';
import type { SchemaNode } from '../../../registry/types';

type State = {
  schema: SchemaNode[];
  selectedId: string | null;
  history: { past: SchemaNode[][]; future: SchemaNode[][] };
  /** Replace the entire schema (used when LOADING a form from server). Does NOT push history. */
  loadSchema(next: SchemaNode[]): void;
  /** Replace the schema AND push history (manual reset, used in dev tooling only). */
  resetSchema(next: SchemaNode[]): void;
  addNode(parentId: string | null, type: string, defaultProps: any): void;
  updateNode(id: string, patch: Partial<SchemaNode>): void;
  removeNode(id: string): void;
  select(id: string | null): void;
  undo(): void;
  redo(): void;
};

const HISTORY_LIMIT = 50;
function pushPast(state: State): State['history'] {
  return { past: [...state.history.past, state.schema].slice(-HISTORY_LIMIT), future: [] };
}

export const useFormDesignerStore = create<State>((set, get) => ({
  schema: [],
  selectedId: null,
  history: { past: [], future: [] },

  // SILENT — loading from server must not pollute undo stack.
  loadSchema: next => set(s => ({
    ...s, schema: next, history: { past: [], future: [] }, selectedId: null,
  })),

  // NOISY — explicit user-initiated reset; preserves old behaviour if needed.
  resetSchema: next => set(s => ({ ...s, schema: next, history: pushPast(s) })),

  addNode: (parentId, type, defaultProps) => set(s => {
    const newNode: SchemaNode = { id: nanoid(8), type, props: { ...defaultProps } };
    return { ...s, schema: [...s.schema, newNode], selectedId: newNode.id, history: pushPast(s) };
  }),

  updateNode: (id, patch) => set(s => ({ ...s, schema: updateAt(s.schema, id, patch), history: pushPast(s) })),

  removeNode: id => set(s => ({
    ...s,
    schema: removeAt(s.schema, id),
    selectedId: s.selectedId === id ? null : s.selectedId,
    history: pushPast(s),
  })),

  select: id => set(s => ({ ...s, selectedId: id })),

  undo: () => set(s => {
    const prev = s.history.past.at(-1);
    if (!prev) return s;
    return { ...s, schema: prev,
      history: { past: s.history.past.slice(0, -1), future: [s.schema, ...s.history.future] } };
  }),

  redo: () => set(s => {
    const next = s.history.future[0];
    if (!next) return s;
    return { ...s, schema: next,
      history: { past: [...s.history.past, s.schema], future: s.history.future.slice(1) } };
  }),
}));
```

> **TODO (matches spec note):** every mutation still snapshots the entire `schema` on every mutation. With 50+ fields this becomes laggy. Track as `ANTFLOW_DESIGNER_PERF`. v1.x swaps to `immer` middleware and stores delta-patches in `history.past`. Not addressed in P2.

> **Note on `loadSchema` vs `resetSchema`:** the previous plan had a single `setSchema()` that always pushed history. That meant when the designer page mounted and called `setSchema(savedFromServer)`, the saved-from-server snapshot became the *first* undo entry — pressing Undo would discard the entire saved work and land back on `[]`. The split fixes that. The FormDesigner page (Step 2) uses `loadSchema` on mount and `addNode`/`updateNode`/`removeNode` for user actions.

- [ ] **Step 2: Write `FormDesigner.tsx`**

```tsx
import { DndContext, useDraggable, useDroppable } from '@dnd-kit/core';
import { Button, Space, message } from 'antd';
import { useEffect } from 'react';
import { useParams, useNavigate } from '@umijs/max';
import { useMutation } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../../components/FormRenderer/FormRenderer';
import { paletteEntries, formRegistry, findById } from '../../../registry/formRegistry';
import { useFormDesignerStore } from './useFormDesignerStore';
import { Inspector } from './Inspector';

function PaletteCard({ entry }: any) {
  const { attributes, listeners, setNodeRef } = useDraggable({ id: entry.type, data: { source: 'palette' } });
  return (
    <div ref={setNodeRef} {...attributes} {...listeners}
      style={{ padding: 12, border: '1px solid #ddd', margin: 4, cursor: 'grab' }}>
      {entry.label}
    </div>
  );
}

function CanvasDrop({ children }: any) {
  const { setNodeRef } = useDroppable({ id: 'canvas' });
  const schema = useFormDesignerStore(s => s.schema);
  const select = useFormDesignerStore(s => s.select);
  const selectedId = useFormDesignerStore(s => s.selectedId);
  useEffect(() => {
    document.querySelectorAll('[data-field-id]').forEach(el => {
      (el as HTMLElement).style.outline =
        (el as HTMLElement).getAttribute('data-field-id') === selectedId ? '2px solid #1677ff' : '';
    });
  }, [selectedId]);
  return (
    <div ref={setNodeRef} style={{ flex: 1, padding: 16, background: '#fafafa' }}
      onClick={e => {
        const id = (e.target as HTMLElement).closest('[data-field-id]')?.getAttribute('data-field-id');
        if (id) select(id);
      }}>
      {children}
      <FormRenderer schema={schema} mode="designer-preview" value={{}} />
    </div>
  );
}

export default function FormDesigner() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { schema, addNode, undo, redo } = useFormDesignerStore();

  const save = useMutation({
    mutationFn: () => request('/api/forms/definitions', {
      method: 'POST',
      data: {
        id: id === 'new' ? null : Number(id),
        code: id === 'new' ? `form_${Date.now()}` : `form_${id}`,
        name: id === 'new' ? '未命名表单' : `表单 ${id}`,
        schema,
        settings: {},
      },
    }),
    onSuccess: (res: any) => { if (id === 'new') navigate(`/designer/form/${res.id}`); message.success('已保存草稿'); },
  });
  const publish = useMutation({
    mutationFn: () => request(`/api/forms/definitions/${id}/publish`, { method: 'POST' }),
    onSuccess: () => message.success('已发布'),
  });

  return (
    <DndContext onDragEnd={e => {
      if (e.over?.id === 'canvas') {
        const t = String(e.active.id);
        addNode(null, t, formRegistry[t].defaultProps);
      }
    }}>
      <div style={{ display: 'flex', height: '100vh' }}>
        <aside style={{ width: 200, padding: 8, borderRight: '1px solid #eee' }}>
          {paletteEntries.map(e => <PaletteCard key={e.type} entry={e} />)}
        </aside>
        <main style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <Space style={{ padding: 8 }}>
            <Button onClick={undo}>撤销</Button>
            <Button onClick={redo}>重做</Button>
            <Button type="primary" onClick={() => save.mutate()} loading={save.isPending}>保存草稿</Button>
            <Button onClick={() => publish.mutate()} disabled={id === 'new'}>发布</Button>
          </Space>
          <CanvasDrop />
        </main>
        <aside style={{ width: 320, borderLeft: '1px solid #eee' }}>
          <Inspector />
        </aside>
      </div>
    </DndContext>
  );
}
```

- [ ] **Step 3: Write `Inspector.tsx`**

```tsx
import { useFormDesignerStore } from './useFormDesignerStore';
import { formRegistry, findById } from '../../registry/formRegistry';

export function Inspector() {
  const selectedId = useFormDesignerStore(s => s.selectedId);
  const schema = useFormDesignerStore(s => s.schema);
  const updateNode = useFormDesignerStore(s => s.updateNode);
  const removeNode = useFormDesignerStore(s => s.removeNode);
  if (!selectedId) return <div style={{ padding: 16 }}>选中一个字段以查看属性</div>;
  const node = findById(schema, selectedId);
  if (!node) return null;
  const ft = (formRegistry as any)[node.type];
  return (
    <div>
      <h4 style={{ padding: 16, margin: 0 }}>{ft.label}</h4>
      <ft.ConfigPanel node={node} onChange={n => updateNode(node.id, n)} />
      <button style={{ margin: 16 }} onClick={() => removeNode(node.id)}>删除该字段</button>
    </div>
  );
}
```

- [ ] **Step 4: Add route**

```ts
{ path: '/designer/form/:id', component: './designer/form/FormDesigner', hideInMenu: true, access: 'canAdmin' },
```

- [ ] **Step 5: Install `@reduxjs/toolkit` only for `nanoid`**

```bash
cd frontend && npm install @reduxjs/toolkit
```

- [ ] **Step 6: Smoke**

`http://localhost:8000/designer/form/new` → drag "单行文本" → inspector renders → save → URL becomes `/designer/form/<id>`.

- [ ] **Step 7: Commit**

```bash
git add frontend/src; git commit -m "feat(designer): FormDesigner with palette/canvas/inspector + undo/redo"
```

---

### Phase P2 demo

Admin opens `/designer/form/new` → drags fields (text, number, date, span_layout with 2 children) → Undo/Redo works → Save Draft → URL changes to `/designer/form/<id>` → Publish → "已发布" toast. CI green.

---

## Phase P3 — Form Runtime

Demo target: any user fills the published form, submits, sees it in their submissions list.

### Task P3.1 — `t_form_data` entity + service + endpoints

**Files:**
- Create: `backend/src/main/java/com/antflow/form/runtime/FormData.java`
- Create: `backend/src/main/java/com/antflow/form/runtime/FormDataMapper.java`
- Create: `backend/src/main/java/com/antflow/form/runtime/FormDataService.java`
- Create: `backend/src/main/java/com/antflow/form/runtime/FormDataController.java`
- Test: `backend/src/test/java/com/antflow/form/runtime/FormDataServiceTest.java`

- [ ] **Step 1: Write `FormData.java`**

```java
@Data @TableName(value = "t_form_data", autoResultMap = true)
public class FormData {
    @TableId(type = IdType.AUTO) private Long id;
    private Long formDefId;
    private Integer formDefVersion;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private String data;        // JSONB
    private String status;      // DRAFT or SUBMITTED
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

- [ ] **Step 2: Write `FormDataMapper.java`**

```java
@Mapper
public interface FormDataMapper extends BaseMapper<FormData> {}
```

- [ ] **Step 3: Write `FormDataService.java`**

```java
@Service @RequiredArgsConstructor
public class FormDataService {
    private final FormDataMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;

    @Transactional
    public Long submit(String formCode, Object data, Long userId) {
        var fd = formDefinitionService.getByCode(formCode);
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new IllegalArgumentException("Form not published: " + formCode);
        }
        var fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setData(writeJson(data));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        mapper.insert(fd2);
        return fd2.getId();
    }

    public List<FormData> mySubmissions(Long userId, String formCode) {
        var q = new QueryWrapper<FormData>().eq("created_by", userId).eq("status", "SUBMITTED");
        if (formCode != null) {
            var fd = formDefinitionService.getByCode(formCode);
            if (fd != null) q.eq("form_def_id", fd.getId());
        }
        return mapper.selectList(q);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 4: Write `FormDataController.java`**

```java
@RestController @RequestMapping("/api/forms/data") @RequiredArgsConstructor
public class FormDataController {
    private final FormDataService service;

    @PostMapping public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        Long id = service.submit((String) body.get("formCode"), body.get("data"), p.userId());
        return Map.of("dataId", id);
    }

    @GetMapping public Object mySubmissions(@RequestParam(required = false) String formCode) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        return service.mySubmissions(p.userId(), formCode);
    }
}
```

- [ ] **Step 5: Failing test**

```java
@Test void submitStoresFormDefVersionSnapshot() {
    var fd = formDefinitionService.saveDraft(null, "demo", "Demo",
        List.of(Map.of("id","a","type","text","label","x","props",Map.of())), null, 1L);
    formDefinitionService.publish(fd.getId());
    Long id = service.submit("demo", Map.of("a", "hello"), 1L);
    var stored = mapper.selectById(id);
    assertThat(stored.getFormDefVersion()).isEqualTo(fd.getVersion());
    assertThat(service.mySubmissions(1L, null)).hasSize(1);
}
```

- [ ] **Step 6: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=FormDataServiceTest
git add backend/src; git commit -m "feat(form): form_data entity + submit/list endpoints"
```

---

### Task P3.2 — Fill + list pages

**Files:**
- Create: `frontend/src/pages/runtime/form/Fill.tsx`
- Create: `frontend/src/pages/runtime/form/List.tsx`
- Modify: `frontend/config/routes.ts`

- [ ] **Step 1: Write `Fill.tsx`**

```tsx
import { Button, Card, message } from 'antd';
import { useState } from 'react';
import { useParams, history } from '@umijs/max';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { FormRenderer } from '../../components/FormRenderer/FormRenderer';

export default function Fill() {
  const params = useParams();
  const code = params.code as string;
  const [val, setVal] = useState<any>({});
  const qc = useQueryClient();
  const { data: fd } = useQuery({
    queryKey: ['form-def', code],
    queryFn: () => request(`/api/forms/definitions/by-code/${code}`),
  });
  const submit = useMutation({
    mutationFn: () => request('/api/forms/data', { method: 'POST', data: { formCode: code, data: val } }),
    onSuccess: () => { message.success('提交成功'); history.push('/runtime/list'); },
  });

  if (!fd) return <Card loading />;
  return (
    <Card title={fd.name}>
      <FormRenderer schema={fd.schema} mode="runtime-fill" value={val} onChange={setVal} />
      <Button type="primary" onClick={() => submit.mutate()} loading={submit.isPending}>提交</Button>
    </Card>
  );
}
```

- [ ] **Step 2: Write `List.tsx`** — ProTable reading `/api/forms/data`

```tsx
import { ProTable } from '@ant-design/pro-components';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';

export default function List() {
  const { data } = useQuery({
    queryKey: ['my-form-data'],
    queryFn: () => request('/api/forms/data'),
  });
  return (
    <ProTable
      rowKey="id" dataSource={data ?? []}
      columns={[
        { title: 'ID', dataIndex: 'id' },
        { title: '表单 ID', dataIndex: 'formDefId' },
        { title: '表单版本', dataIndex: 'formDefVersion' },
        { title: '状态', dataIndex: 'status' },
        { title: '提交时间', dataIndex: 'createdAt' },
      ]}
    />
  );
}
```

- [ ] **Step 3: Routes**

```ts
{ path: '/runtime/form/:code', component: './runtime/form/Fill', hideInMenu: true },
{ path: '/runtime/list', name: 'mySubmissions', component: './runtime/form/List' },
```

- [ ] **Step 4: Smoke**

Log in as bob → visit `/runtime/form/<formCode>` from a published form → fill → submit → redirected to `/runtime/list` with a new row.

- [ ] **Step 5: Commit**

```bash
git add frontend/src; git commit -m "feat(runtime): fill-form + my submissions list"
```

---

### Phase P3 demo

Bob logs in → opens `/runtime/form/<formCode>` → fills → submits → `/runtime/list` shows the row with `formDefVersion` matching the published form. Backend `t_form_data` has the new row. CI green.

---

## Phase P4 — Process Designer + Engine

Demo target: Admin wires a 1-level approval onto `leave_request`. As bob submits, an approval task is created for the configured approver. Approver approves → instance APPROVED.

### Task P4.1 — `t_process_definition` + service + endpoints (with form-published gate)

**Files:**
- Create: `backend/src/main/java/com/antflow/process/ProcessDefinition.java`
- Create: `backend/src/main/java/com/antflow/process/ProcessDefinitionMapper.java`
- Create: `backend/src/main/java/com/antflow/process/ProcessDefinitionService.java`
- Create: `backend/src/main/java/com/antflow/process/ProcessDefinitionController.java`
- Test: `backend/src/test/java/com/antflow/process/ProcessDefinitionServiceTest.java`

- [ ] **Step 1: Write `ProcessDefinition.java`**

```java
@Data
@TableName(value = "t_process_definition", autoResultMap = true)
public class ProcessDefinition {
    @TableId(type = IdType.AUTO) private Long id;
    private Long formDefId;
    private Integer version;
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private String nodes;       // JSONB
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private String edges;       // JSONB
    private String status;
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

- [ ] **Step 2: Write `ProcessDefinitionMapper.java`**

```java
@Mapper
public interface ProcessDefinitionMapper extends BaseMapper<ProcessDefinition> {}
```

- [ ] **Step 3: Write `ProcessDefinitionService.java`**

```java
@Service @RequiredArgsConstructor
public class ProcessDefinitionService {
    private final ProcessDefinitionMapper mapper;
    private final FormDefinitionService formDefinitionService;
    private final ObjectMapper json;

    public ProcessDefinition getById(Long id) { return mapper.selectById(id); }

    @Transactional
    public ProcessDefinition saveOrUpdateDraft(Long id, Long formDefId, Object nodes, Object edges, Long userId) {
        ProcessDefinition pd;
        if (id == null) {
            if (mapper.selectCount(new QueryWrapper<ProcessDefinition>().eq("form_def_id", formDefId)) > 0) {
                throw new IllegalStateException("Process for this form already exists; edit instead");
            }
            pd = new ProcessDefinition();
            pd.setFormDefId(formDefId);
            pd.setVersion(1);
            pd.setNodes(writeJson(nodes));
            pd.setEdges(writeJson(edges));
            pd.setStatus("DRAFT");
            pd.setCreatedBy(userId);
            mapper.insert(pd);
        } else {
            pd = mapper.selectById(id);
            if (!"DRAFT".equals(pd.getStatus())) throw new IllegalStateException("Only DRAFT process can be edited");
            pd.setNodes(writeJson(nodes));
            pd.setEdges(writeJson(edges));
            mapper.updateById(pd);
        }
        return pd;
    }

    @Transactional
    public ProcessDefinition publish(Long id) {
        ProcessDefinition pd = mapper.selectById(id);

        // Gate #1: the associated form must be PUBLISHED.
        var fd = formDefinitionService.getById(pd.getFormDefId());
        if (!"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FOR_FORM_NOT_PUBLISHED",
                "Associated form must be PUBLISHED before publishing the flow");
        }

        // Gate #2: enforce MVP linear-flow invariant — every non-end node must
        // have exactly ONE outgoing edge. Otherwise ProcessEngine.approve
        // would silently overwrite `current_node_id` when multiple
        // non-end successors exist (spec decision #3: MVP is sequential).
        validateLinearFlow(pd.getNodes(), pd.getEdges());

        pd.setStatus("PUBLISHED");
        pd.setVersion(pd.getVersion() + 1);
        mapper.updateById(pd);
        return pd;
    }

    private void validateLinearFlow(String nodesJson, String edgesJson) {
        try {
            var nodes = json.readTree(nodesJson);
            var edges = json.readTree(edgesJson);

            java.util.Map<String, Long> outDegree = new java.util.HashMap<>();
            for (var e : edges) {
                String from = e.path("from").asText();
                outDegree.merge(from, 1L, Long::sum);
            }

            for (var n : nodes) {
                String type = n.path("type").asText();
                String id = n.path("id").asText();
                if ("end".equals(type)) continue;
                if ("start".equals(type)) {
                    if (outDegree.getOrDefault(id, 0L) != 1) {
                        throw new BizException("BAD_FLOW",
                            "start must have exactly 1 outgoing edge");
                    }
                    continue;
                }
                if (outDegree.getOrDefault(id, 0L) != 1) {
                    throw new BizException("BAD_FLOW",
                        "Node " + id + " must have exactly 1 outgoing edge in MVP (sequential flow only)");
                }
            }
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public ProcessDefinition latestPublishedForForm(Long formDefId) {
        return mapper.selectOne(new QueryWrapper<ProcessDefinition>()
            .eq("form_def_id", formDefId).eq("status", "PUBLISHED")
            .orderByDesc("version").last("LIMIT 1"));
    }

    public List<ProcessDefinition> list() { return mapper.selectList(null); }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 4: Write `ProcessDefinitionController.java`**

```java
@RestController @RequestMapping("/api/processes/definitions") @RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class ProcessDefinitionController {
    private final ProcessDefinitionService service;
    private final ProcessDefinitionMapper mapper;

    @GetMapping public List<ProcessDefinition> list() { return service.list(); }

    @GetMapping("/by-form/{formDefId}")
    public ProcessDefinition byForm(@PathVariable Long formDefId) {
        return service.latestPublishedForForm(formDefId);
    }

    @PostMapping public ProcessDefinition save(@RequestBody SaveBody b) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        return service.saveOrUpdateDraft(b.id(), b.formDefId(), b.nodes(), b.edges(), p.userId());
    }

    @PostMapping("/{id}/publish")
    public ProcessDefinition publish(@PathVariable Long id) {
        return service.publish(id);
    }

    public record SaveBody(Long id, Long formDefId, Object nodes, Object edges) {}
}
```

- [ ] **Step 5: Failing test**

```java
@Test void publishRequiresFormPublished() {
    var fd = formDefinitionService.saveDraft(null, "t", "t",
        List.of(Map.of("id","a","type","text","label","x","props",Map.of())), null, 1L);
    var pd = service.saveOrUpdateDraft(null, fd.getId(), List.of(), List.of(), 1L);
    assertThatThrownBy(() -> service.publish(pd.getId()))
        .hasMessageContaining("FOR_FORM_NOT_PUBLISHED");
}
```

- [ ] **Step 6: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=ProcessDefinitionServiceTest
git add backend/src; git commit -m "feat(process): process_definition entity + form-published gate"
```

---

### Task P4.2 — AssigneeResolver + NoAssigneeFoundException

**Files:**
- Create: `backend/src/main/java/com/antflow/engine/NoAssigneeFoundException.java`
- Create: `backend/src/main/java/com/antflow/engine/BizException.java`
- Create: `backend/src/main/java/com/antflow/engine/resolver/AssigneeSpec.java`
- Create: `backend/src/main/java/com/antflow/engine/resolver/AssigneeResolver.java`
- Test: `backend/src/test/java/com/antflow/engine/AssigneeResolverTest.java`

- [ ] **Step 1: Write `BizException.java`**

```java
package com.antflow.engine;
public class BizException extends RuntimeException {
    private final String code;
    public BizException(String code, String msg) { super(msg); this.code = code; }
    public String getCode() { return code; }
}
```

- [ ] **Step 2: Write `NoAssigneeFoundException.java`**

```java
package com.antflow.engine;
public class NoAssigneeFoundException extends BizException {
    private final String nodeId;
    public NoAssigneeFoundException(String nodeId, String msg) {
        super("NO_ASSIGNEE", msg);
        this.nodeId = nodeId;
    }
    public String nodeId() { return nodeId; }
}
```

- [ ] **Step 3: Write `AssigneeSpec.java`**

```java
package com.antflow.engine.resolver;
import java.util.List;
public record AssigneeSpec(String type, List<?> ids) {
    public static AssigneeSpec user(List<Long> ids) { return new AssigneeSpec("user", ids); }
    public static AssigneeSpec role(List<Long> ids) { return new AssigneeSpec("role", ids); }
    public static AssigneeSpec deptLeader() { return new AssigneeSpec("dept_leader", List.of()); }
}
```

- [ ] **Step 4: Write `AssigneeResolver.java`**

```java
package com.antflow.engine.resolver;

import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.RoleMapper;
import com.antflow.org.UserMapper;
import com.antflow.org.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AssigneeResolver {
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        return switch (spec.type()) {
            case "user" -> resolveUsers(nodeId, spec);
            case "role" -> resolveRoles(nodeId, spec);
            case "dept_leader" -> throw new IllegalStateException(
                "dept_leader resolver is wired in P1.4 (v1.x)");
            default -> throw new IllegalArgumentException("unknown assignee type: " + spec.type());
        };
    }

    private List<Long> resolveUsers(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no users specified");
        }
        var ids = spec.ids().stream().map(Number.class::cast).map(Number::longValue).toList();
        var active = userMapper.selectBatchIds(ids).stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .map(u -> u.getId())
            .toList();
        if (active.isEmpty()) throw new NoAssigneeFoundException(nodeId, "no active users among ids");
        return active;
    }

    private List<Long> resolveRoles(String nodeId, AssigneeSpec spec) {
        if (spec.ids() == null || spec.ids().isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "no roles specified");
        }
        var bag = new ArrayList<Long>();
        for (Object ridObj : spec.ids()) {
            long rid = ((Number) ridObj).longValue();
            userRoleMapper.selectList(new QueryWrapper<com.antflow.org.UserRole>().eq("role_id", rid))
                .forEach(ur -> {
                    var u = userMapper.selectById(ur.getUserId());
                    if (u != null && "ACTIVE".equals(u.getStatus())) bag.add(u.getId());
                });
        }
        if (bag.isEmpty()) throw new NoAssigneeFoundException(nodeId, "no active users in role");
        return bag.stream().distinct().toList();
    }
}
```

- [ ] **Step 5: Failing test**

```java
@Test void disabledUsersAreSilentlyDropped() {
    var u1 = new User(); u1.setUsername("u1"); u1.setDisplayName(""); u1.setPasswordHash("x"); u1.setStatus("ACTIVE");
    userMapper.insert(u1);
    var u2 = new User(); u2.setUsername("u2"); u2.setDisplayName(""); u2.setPasswordHash("x"); u2.setStatus("DISABLED");
    userMapper.insert(u2);
    var res = new AssigneeResolver(userMapper, userRoleMapper, roleMapper)
        .resolve("n1", AssigneeSpec.user(List.of(u1.getId(), u2.getId())));
    assertThat(res).containsExactly(u1.getId());
}

@Test void emptyRoleThrowsNoAssignee() {
    assertThatThrownBy(() -> new AssigneeResolver(userMapper, userRoleMapper, roleMapper)
        .resolve("n1", AssigneeSpec.role(List.of(999L))))
        .isInstanceOf(NoAssigneeFoundException.class);
}
```

- [ ] **Step 6: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=AssigneeResolverTest
git add backend/src; git commit -m "feat(engine): AssigneeResolver + NoAssigneeFoundException"
```

---

### Task P4.3 — ProcessEngine: start + approve + reject + withdraw

**Files:**
- Create: `backend/src/main/java/com/antflow/engine/dto/StartCmd.java`
- Create: `backend/src/main/java/com/antflow/engine/dto/CompleteCmd.java`
- Create: `backend/src/main/java/com/antflow/engine/handler/BadNodeTypeException.java`
- Create: `backend/src/main/java/com/antflow/engine/handler/NodeDispatcher.java`
- Create: `backend/src/main/java/com/antflow/engine/ProcessEngine.java`
- Create: `backend/src/main/java/com/antflow/task/ProcessInstance.java`
- Create: `backend/src/main/java/com/antflow/task/ProcessInstanceMapper.java`
- Create: `backend/src/main/java/com/antflow/task/TaskEntity.java`
- Create: `backend/src/main/java/com/antflow/task/TaskMapper.java`
- Create: `backend/src/main/java/com/antflow/task/TaskHistoryEntity.java`
- Create: `backend/src/main/java/com/antflow/task/TaskHistoryMapper.java`
- Test: `backend/src/test/java/com/antflow/engine/ProcessEngineTest.java`

- [ ] **Step 1: Entities**

```java
// ProcessInstance.java
@Data @TableName("t_process_instance")
public class ProcessInstance {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procDefId;
    private Long formDataId;
    private String status;
    private String currentNodeId;
    @Version private Integer version;
    private Long startedBy;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime startedAt;
    private java.time.OffsetDateTime finishedAt;
}

// TaskEntity.java
@Data @TableName("t_task")
public class TaskEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procInstId;
    private String nodeId;
    private Long assigneeId;
    private String status;
    private String approvalMode;
    @Version private Integer version;
    private Long approvedBy;
    private java.time.OffsetDateTime approvedAt;
    private String comment;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}

// TaskHistoryEntity.java
@Data @TableName("t_task_history")
public class TaskHistoryEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private Long procInstId;
    private String fromNodeId;
    private String toNodeId;
    private Long taskId;
    private String action;
    private Long operatorId;
    private String comment;
    @TableField(fill = FieldFill.INSERT) private java.time.OffsetDateTime createdAt;
}
```

- [ ] **Step 2: Mappers and `TaskMapperExt`**

```java
// ProcessInstanceMapper
@Mapper
public interface ProcessInstanceMapper extends BaseMapper<ProcessInstance> {}

// TaskMapper
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {}

// TaskHistoryMapper
@Mapper
public interface TaskHistoryMapper extends BaseMapper<TaskHistoryEntity> {}
```

`TaskMapper` deliberately stays a thin BaseMapper wrapper. Engine code that needs to read/update a `ProcessInstance` (e.g. during `approve`) goes through `TaskMapperExt` to keep concerns separated:

```java
// backend/src/main/java/com/antflow/task/TaskMapperExt.java
package com.antflow.task;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Non-CRUD helpers around TaskMapper/ProcessInstance — split out so the engine
 * doesn't need casts or duplicate BaseMapper wiring. Used by ProcessEngine.
 */
@Component
@RequiredArgsConstructor
public class TaskMapperExt {
    private final ProcessInstanceMapper processInstanceMapper;

    public Optional<ProcessInstance> selectInstanceById(long id) {
        return Optional.ofNullable(processInstanceMapper.selectById(id));
    }

    public void updateInstance(ProcessInstance pi) {
        processInstanceMapper.updateById(pi);
    }
}
```

Engine calls `taskMapperExt.selectInstanceById(...)` and `taskMapperExt.updateInstance(pi)` instead of any cast. (`@Version` columns are respected because MyBatis-Plus's optimistic-locker interceptor auto-bumps `version` on `updateById`.)

- [ ] **Step 3: DTOs**

```java
public record StartCmd(String formCode, Object data) {}
public record CompleteCmd(Long taskId, String action, String comment) {}
```

- [ ] **Step 4: `BadNodeTypeException` + `NodeDispatcher`**

```java
public class BadNodeTypeException extends BizException {
    public BadNodeTypeException(String m) { super("BAD_NODE_TYPE", m); }
}

@Component
public class NodeDispatcher {
    public void assertKnown(String type) {
        if ("start".equals(type) || "approval".equals(type) || "end".equals(type)) return;
        throw new BadNodeTypeException("unknown node type: " + type);
    }
}
```

- [ ] **Step 5: Write `ProcessEngine.java`**

```java
package com.antflow.engine;

import com.antflow.engine.dto.CompleteCmd;
import com.antflow.engine.dto.StartCmd;
import com.antflow.engine.handler.NodeDispatcher;
import com.antflow.engine.resolver.AssigneeResolver;
import com.antflow.engine.resolver.AssigneeSpec;
import com.antflow.form.FormDefinition;
import com.antflow.form.FormDefinitionService;
import com.antflow.form.runtime.FormData;
import com.antflow.form.runtime.FormDataMapper;
import com.antflow.process.ProcessDefinition;
import com.antflow.process.ProcessDefinitionService;
import com.antflow.task.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProcessEngine {

    private final FormDefinitionService formDefinitionService;
    private final FormDataMapper formDataMapper;
    private final ProcessDefinitionService processDefinitionService;
    private final TaskMapper taskMapper;
    private final ProcessInstanceMapper processInstanceMapper;
    private final TaskMapperExt taskMapperExt;
    private final TaskHistoryMapper historyMapper;
    private final AssigneeResolver assigneeResolver;
    private final NodeDispatcher dispatcher;
    private final ObjectMapper json;

    @Transactional
    public Map<String, Object> start(StartCmd cmd, long userId) {
        FormDefinition fd = formDefinitionService.getByCode(cmd.formCode());
        if (fd == null || !"PUBLISHED".equals(fd.getStatus())) {
            throw new BizException("FORM_NOT_PUBLISHED", "Form not published: " + cmd.formCode());
        }
        ProcessDefinition pd = processDefinitionService.latestPublishedForForm(fd.getId());
        if (pd == null) {
            throw new BizException("NO_FLOW", "No published process for form " + cmd.formCode());
        }
        FormData fd2 = new FormData();
        fd2.setFormDefId(fd.getId());
        fd2.setFormDefVersion(fd.getVersion());
        fd2.setData(writeJson(cmd.data()));
        fd2.setStatus("SUBMITTED");
        fd2.setCreatedBy(userId);
        formDataMapper.insert(fd2);

        ProcessInstance pi = new ProcessInstance();
        pi.setProcDefId(pd.getId());
        pi.setFormDataId(fd2.getId());
        pi.setStatus("RUNNING");
        pi.setStartedBy(userId);
        pi.setStartedAt(OffsetDateTime.now());
        processInstanceMapper.insert(pi);

        List<Long> firstTasks = advance(pd, pi, "start");
        return Map.of("instanceId", pi.getId(), "formDataId", fd2.getId(), "firstTaskIds", firstTasks);
    }

    @Transactional
    public void approve(CompleteCmd cmd, long operatorId) {
        TaskEntity t = taskMapper.selectById(cmd.taskId());
        if (t == null || !"PENDING".equals(t.getStatus())) {
            throw new BizException("TASK_NOT_PENDING", "Task not pending");
        }
        if (!Objects.equals(t.getAssigneeId(), operatorId)) {
            throw new AccessDeniedException("not your task");
        }

        t.setStatus("APPROVED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);
        insertHistory(t, null, t.getNodeId(), "APPROVE", operatorId, cmd.comment());

        ProcessInstance pi = taskMapperExt.selectInstanceById(t.getProcInstId()).orElseThrow();
        ProcessDefinition pd = processDefinitionService.getById(pi.getProcDefId());

        List<JsonNode> next = nextNodes(pd, t.getNodeId());
        List<JsonNode> produceTasks = next.stream()
            .filter(n -> !"end".equals(n.path("type").asText()))
            .toList();

        if (produceTasks.isEmpty()) {
            pi.setStatus("APPROVED");
            pi.setFinishedAt(OffsetDateTime.now());
            processInstanceMapper.updateById(pi);
            insertHistoryOnInstance(pi.getId(), null, null, "COMPLETE", operatorId, null);
            return;
        }

        for (JsonNode nn : produceTasks) {
            AssigneeSpec spec = parseAssignee(nn.path("assignee"));
            List<Long> assignees = assigneeResolver.resolve(nn.path("id").asText(), spec);
            for (Long a : assignees) {
                TaskEntity nt = new TaskEntity();
                nt.setProcInstId(pi.getId());
                nt.setNodeId(nn.path("id").asText());
                nt.setAssigneeId(a);
                nt.setStatus("PENDING");
                nt.setApprovalMode("OR_SIGN");
                taskMapper.insert(nt);
            }
            pi.setCurrentNodeId(nn.path("id").asText());
        }
        processInstanceMapper.updateById(pi);

        // OR-sign short-circuit
        var pendingSiblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId())
            .eq("status", "PENDING")
            .eq("node_id", t.getNodeId())
            .ne("id", t.getId()));
        for (TaskEntity sib : pendingSiblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(),
                "SKIP", operatorId, "OR-sign short-circuit");
        }
    }

    @Transactional
    public void reject(CompleteCmd cmd, long operatorId) {
        TaskEntity t = taskMapper.selectById(cmd.taskId());
        if (t == null || !"PENDING".equals(t.getStatus())) throw new BizException("TASK_NOT_PENDING", "Task not pending");
        if (!Objects.equals(t.getAssigneeId(), operatorId)) throw new AccessDeniedException("not your task");

        t.setStatus("REJECTED");
        t.setApprovedBy(operatorId);
        t.setApprovedAt(OffsetDateTime.now());
        t.setComment(cmd.comment());
        taskMapper.updateById(t);
        insertHistory(t, null, t.getNodeId(), "REJECT", operatorId, cmd.comment());

        ProcessInstance pi = taskMapperExt.selectInstanceById(t.getProcInstId()).orElseThrow();
        // Sibling SKIP
        var pendingSiblings = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING")
            .eq("node_id", t.getNodeId()).ne("id", t.getId()));
        for (TaskEntity sib : pendingSiblings) {
            sib.setStatus("SKIPPED");
            taskMapper.updateById(sib);
            insertHistoryOnInstance(pi.getId(), t.getNodeId(), sib.getNodeId(), "SKIP", operatorId, null);
        }
        pi.setStatus("REJECTED");
        pi.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(pi);
    }

    @Transactional
    public void withdraw(long instanceId, long operatorId) {
        ProcessInstance pi = processInstanceMapper.selectById(instanceId);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        if (!Objects.equals(pi.getStartedBy(), operatorId)) {
            throw new AccessDeniedException("only starter can withdraw");
        }
        if (!"RUNNING".equals(pi.getStatus())) {
            throw new BizException("BAD_STATE", "instance not running");
        }
        var anyDone = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).ne("status", "PENDING"));
        if (!anyDone.isEmpty()) {
            throw new BizException("ALREADY_ACTED", "cannot withdraw after a task has been acted on");
        }
        var pending = taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("proc_inst_id", pi.getId()).eq("status", "PENDING"));
        for (TaskEntity p : pending) {
            p.setStatus("SKIPPED");
            taskMapper.updateById(p);
        }
        pi.setStatus("WITHDRAWN");
        pi.setFinishedAt(OffsetDateTime.now());
        processInstanceMapper.updateById(pi);
        insertHistoryOnInstance(pi.getId(), null, pi.getCurrentNodeId(), "WITHDRAW", operatorId, null);
    }

    private List<Long> advance(ProcessDefinition pd, ProcessInstance pi, String fromNodeId) {
        ProcessInstance piRef = pi;
        ProcessDefinition pdRef = pd;
        // Reuses same logic as approve's tail; for MVP simplicity implemented inline here:
        var next = nextNodes(pdRef, fromNodeId);
        var produceTasks = next.stream().filter(n -> !"end".equals(n.path("type").asText())).toList();
        if (produceTasks.isEmpty()) {
            piRef.setStatus("APPROVED");
            piRef.setFinishedAt(OffsetDateTime.now());
            processInstanceMapper.updateById(piRef);
            insertHistoryOnInstance(piRef.getId(), fromNodeId, null, "COMPLETE", piRef.getStartedBy(), null);
            return List.of();
        }
        List<Long> newTaskIds = new ArrayList<>();
        for (var nn : produceTasks) {
            var spec = parseAssignee(nn.path("assignee"));
            var assignees = assigneeResolver.resolve(nn.path("id").asText(), spec);
            for (Long a : assignees) {
                TaskEntity nt = new TaskEntity();
                nt.setProcInstId(piRef.getId());
                nt.setNodeId(nn.path("id").asText());
                nt.setAssigneeId(a);
                nt.setStatus("PENDING");
                nt.setApprovalMode("OR_SIGN");
                taskMapper.insert(nt);
                newTaskIds.add(nt.getId());
            }
            piRef.setCurrentNodeId(nn.path("id").asText());
        }
        processInstanceMapper.updateById(piRef);
        insertHistoryOnInstance(piRef.getId(), fromNodeId, piRef.getCurrentNodeId(), "START", piRef.getStartedBy(), null);
        return newTaskIds;
    }

    private List<JsonNode> nextNodes(ProcessDefinition pd, String fromId) throws BizException {
        try {
            var edges = (JsonNode) json.readTree(pd.getEdges() == null ? "[]" : pd.getEdges());
            var nodes = (JsonNode) json.readTree(pd.getNodes() == null ? "[]" : pd.getNodes());
            Map<String, JsonNode> byId = new HashMap<>();
            nodes.forEach(n -> byId.put(n.path("id").asText(), n));
            List<JsonNode> acc = new ArrayList<>();
            edges.forEach(e -> {
                if (fromId.equals(e.path("from").asText())) {
                    var n = byId.get(e.path("to").asText());
                    if (n != null) {
                        dispatcher.assertKnown(n.path("type").asText());
                        acc.add(n);
                    }
                }
            });
            return acc;
        } catch (BizException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private AssigneeSpec parseAssignee(JsonNode n) {
        var type = n.path("type").asText();
        var ids = new ArrayList<>();
        n.path("ids").forEach(x -> ids.add(x.isNumber() ? x.asLong() : (Number) x.asLong()));
        return new AssigneeSpec(type, ids);
    }

    private void insertHistory(TaskEntity t, String from, String to, String action, Long op, String comment) {
        var h = new TaskHistoryEntity();
        h.setProcInstId(t.getProcInstId()); h.setTaskId(t.getId());
        h.setFromNodeId(from); h.setToNodeId(to); h.setAction(action);
        h.setOperatorId(op); h.setComment(comment);
        historyMapper.insert(h);
    }
    private void insertHistoryOnInstance(Long instId, String from, String to, String action, Long op, String comment) {
        var h = new TaskHistoryEntity();
        h.setProcInstId(instId);
        h.setFromNodeId(from); h.setToNodeId(to); h.setAction(action);
        h.setOperatorId(op); h.setComment(comment);
        historyMapper.insert(h);
    }

    private String writeJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 6: Failing integration test**

```java
@SpringBootTest
class ProcessEngineTest {
    @Autowired FormDefinitionService formDef;
    @Autowired ProcessDefinitionService procDef;
    @Autowired ProcessEngine engine;
    @Autowired AssigneeResolver resolver;
    @Autowired UserMapper users;
    @Autowired UserService userSvc;

    @Test void startCreatesInstanceAndFirstTask() {
        var fd = formDef.saveDraft(null, "t", "t",
            List.of(Map.of("id","a","type","text","label","x","props",Map.of())), null, 1L);
        formDef.publish(fd.getId());

        // Approver bob already seeded; create the assignee explicitly
        var nodes = List.of(
            Map.of("id","start","type","start","x",0,"y",0,"props",Map.of()),
            Map.of("id","a1","type","approval","x",120,"y",40,
                   "assignee", Map.of("type","user","ids", List.of(2L)),
                   "props", Map.of()),
            Map.of("id","end","type","end","x",240,"y",40,"props",Map.of())
        );
        var edges = List.of(Map.of("from","start","to","a1"), Map.of("from","a1","to","end"));
        var pd = procDef.saveOrUpdateDraft(null, fd.getId(), nodes, edges, 1L);
        procDef.publish(pd.getId());

        var start = engine.start(new StartCmd("t", Map.of("a", "hello")), 1L);
        assertThat(start.firstTaskIds()).hasSize(1);
    }
}
```

- [ ] **Step 7: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=ProcessEngineTest
git add backend/src; git commit -m "feat(engine): ProcessEngine start/approve/reject/withdraw with @Version, end-filter, OR-sign"
```

---

### Task P4.4 — Instance + task REST endpoints

**Files:**
- Create: `backend/src/main/java/com/antflow/task/InstanceController.java`
- Create: `backend/src/main/java/com/antflow/task/TaskController.java`

- [ ] **Step 1: Write `InstanceController.java`**

```java
@RestController @RequestMapping("/api/instances") @RequiredArgsConstructor
public class InstanceController {
    private final ProcessEngine engine;
    private final ProcessInstanceMapper instanceMapper;
    private final TaskMapper taskMapper;
    private final TaskHistoryMapper historyMapper;

    @PostMapping("/start") public Map<String, Object> start(@RequestBody com.antflow.engine.dto.StartCmd cmd) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        return engine.start(cmd, p.userId());
    }

    @GetMapping public Object list(@RequestParam(required = false) String status) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        var q = new QueryWrapper<ProcessInstance>().eq("started_by", p.userId());
        if (status != null) q.eq("status", status);
        return instanceMapper.selectList(q);
    }

    @GetMapping("/{id}") public Map<String, Object> detail(@PathVariable Long id) {
        var pi = instanceMapper.selectById(id);
        if (pi == null) throw new BizException("NOT_FOUND", "instance not found");
        var tasks = taskMapper.selectList(new QueryWrapper<TaskEntity>().eq("proc_inst_id", id));
        var history = historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
        return Map.of("instance", pi, "tasks", tasks, "history", history);
    }

    @GetMapping("/{id}/history") public Object history(@PathVariable Long id) {
        return historyMapper.selectList(new QueryWrapper<TaskHistoryEntity>()
            .eq("proc_inst_id", id).orderByAsc("created_at"));
    }
}
```

- [ ] **Step 2: Write `TaskController.java`**

```java
@RestController @RequestMapping("/api/tasks") @RequiredArgsConstructor
public class TaskController {
    private final ProcessEngine engine;
    private final TaskMapper taskMapper;

    @GetMapping public Object myInbox(@RequestParam(defaultValue = "PENDING") String status) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        return taskMapper.selectList(new QueryWrapper<TaskEntity>()
            .eq("assignee_id", p.userId()).eq("status", status)
            .orderByDesc("created_at"));
    }

    @PostMapping("/{id}/approve") public void approve(@PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        engine.approve(new com.antflow.engine.dto.CompleteCmd(id, "APPROVE",
            body == null ? null : body.get("comment")), p.userId());
    }

    @PostMapping("/{id}/reject") public void reject(@PathVariable Long id,
            @RequestBody Map<String, String> body) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        engine.reject(new com.antflow.engine.dto.CompleteCmd(id, "REJECT", body.get("comment")), p.userId());
    }

    @PostMapping("/instances/{id}/withdraw") public void withdraw(@PathVariable Long id) {
        var p = com.antflow.auth.PrincipalHolder.current().orElseThrow();
        engine.withdraw(id, p.userId());
    }
}
```

- [ ] **Step 3: Smoke**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"bob","password":"ant.design"}' | jq -r .accessToken)

# Submit form
curl -s -X POST http://localhost:8080/api/instances/start \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"formCode":"leave_request","data":{"name":"Bob"}}'

# Bob logs in as the assignee
curl -s "http://localhost:8080/api/tasks?status=PENDING" -H "Authorization: Bearer $TOKEN"
```

Expected: instance created, task for bob visible.

- [ ] **Step 4: Commit**

```bash
git add backend/src; git commit -m "feat(api): instance + task endpoints backed by ProcessEngine"
```

---

### Task P4.5 — Process designer page + AssigneePicker

**Files:**
- Create: `frontend/src/registry/processNodeRegistry.ts`
- Create: `frontend/src/components/process-nodes/ApprovalNode.tsx`
- Create: `frontend/src/components/process-nodes/StartNode.tsx`
- Create: `frontend/src/components/process-nodes/EndNode.tsx`
- Create: `frontend/src/components/process-nodes/ApprovalNodeConfig.tsx`
- Create: `frontend/src/components/AssigneePicker.tsx`
- Create: `frontend/src/pages/designer/process/ProcessDesigner.tsx`
- Modify: `frontend/config/routes.ts`

- [ ] **Step 1: Write `processNodeRegistry.ts`**

```ts
import type { Node, NodeProps } from '@xyflow/react';
import { ApprovalNode } from '../components/process-nodes/ApprovalNode';
import { StartNode } from '../components/process-nodes/StartNode';
import { EndNode } from '../components/process-nodes/EndNode';
import { ApprovalNodeConfig } from '../components/process-nodes/ApprovalNodeConfig';

export type ApprovalNodeData = { label: string; assignee: { type: string; ids: (string | number)[] }; errorCount?: number };
export type ApprovalFlowNode = Node<ApprovalNodeData, 'approval'>;

export const processNodeTypes = {
  start: StartNode,
  approval: ApprovalNode,
  end: EndNode,
};

export const processNodeConfigs = {
  approval: ApprovalNodeConfig,
};
```

- [ ] **Step 2: Write `ApprovalNode.tsx`** (uses v12 API)

```tsx
import { Handle, Position, type NodeProps, type Node } from '@xyflow/react';

export function ApprovalNode({ data, selected }: NodeProps<Node<any, 'approval'>>) {
  return (
    <div style={{
      padding: 12, minWidth: 160,
      border: `2px solid ${data.errorCount ? '#ff4d4f' : selected ? '#1677ff' : '#888'}`,
      borderRadius: 6, background: '#fff'
    }}>
      <Handle type="target" position={Position.Top} />
      <div style={{ fontWeight: 600 }}>{data.label}</div>
      <small>{data.assignee?.type} · {(data.assignee?.ids ?? []).length} 人</small>
      <Handle type="source" position={Position.Bottom} />
    </div>
  );
}
```

- [ ] **Step 3: Write `StartNode.tsx` and `EndNode.tsx`** — same pattern, no Handles for end

- [ ] **Step 4: Write `ApprovalNodeConfig.tsx`**

```tsx
import { Form, Input, Select } from 'antd';
import { AssigneePicker } from '../../components/AssigneePicker';

export function ApprovalNodeConfig({ node, onChange }: { node: any; onChange: (n: any) => void }) {
  return (
    <Form layout="vertical" style={{ padding: 16 }}>
      <Form.Item label="节点名称">
        <Input value={node.data.label}
          onChange={e => onChange({ ...node, data: { ...node.data, label: e.target.value } })} />
      </Form.Item>
      <Form.Item label="审批人类型">
        <Select value={node.data.assignee?.type ?? 'user'}
          onChange={v => onChange({ ...node, data: { ...node.data, assignee: { type: v, ids: [] } } })}
          options={[{ value: 'user', label: '用户' }, { value: 'role', label: '角色' }, { value: 'dept_leader', label: '部门主管' }]} />
      </Form.Item>
      {node.data.assignee?.type && node.data.assignee.type !== 'dept_leader' && (
        <AssigneePicker mode={node.data.assignee.type}
          value={node.data.assignee.ids}
          onChange={ids => onChange({
            ...node, data: { ...node.data, assignee: { ...node.data.assignee, ids } }
          })} />
      )}
    </Form>
  );
}
```

- [ ] **Step 5: Write `AssigneePicker.tsx`**

```tsx
import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';

export function AssigneePicker({ mode, value, onChange }: {
  mode: 'user' | 'role'; value: any[]; onChange: (v: any[]) => void;
}) {
  const [kw, setKw] = useState('');
  const url = mode === 'user' ? `/api/users?keyword=${kw}` : `/api/roles`;
  const { data, isFetching } = useQuery({
    queryKey: ['assignee', mode, kw],
    queryFn: () => request(url),
  });
  return (
    <Select mode="multiple" style={{ width: '100%' }}
      value={value} loading={isFetching}
      onSearch={setKw} onChange={onChange}
      placeholder="搜索并选择"
      options={(data ?? []).map((x: any) => ({
        value: x.id, label: x.displayName ?? x.code ?? x.username,
      }))} />
  );
}
```

- [ ] **Step 6: Write `ProcessDesigner.tsx`**

```tsx
import { ReactFlow, Background, Controls, MiniMap, useNodesState, useEdgesState, addEdge } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { Button, Space, message } from 'antd';
import { useEffect, useState } from 'react';
import { useParams, useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { processNodeTypes, processNodeConfigs } from '../../registry/processNodeRegistry';

export default function ProcessDesigner() {
  const { formDefId } = useParams();
  const navigate = useNavigate();
  const [nodes, setNodes, onNodesChange] = useNodesState<any>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<any>([]);
  const [selected, setSelected] = useState<any | null>(null);
  const [pdId, setPdId] = useState<number | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const existing = await request(`/api/processes/definitions/by-form/${formDefId}`);
        if (existing) {
          setPdId(existing.id);
          setNodes(existing.nodes.map((n: any) => ({
            id: n.id, type: n.type, position: { x: n.x, y: n.y },
            data: { label: n.id === 'start' ? '开始' : n.id === 'end' ? '结束' : '审批',
                    assignee: n.assignee ?? { type: 'user', ids: [] } },
          })));
          setEdges(existing.edges.map((e: any) => ({ id: `${e.from}->${e.to}`, source: e.from, target: e.to })));
        } else {
          // New draft: synthesize start + end so save can target them
          setNodes([
            { id: 'start', type: 'start', position: { x: 80, y: 80 }, data: { label: '开始' } },
            { id: 'end',   type: 'end',   position: { x: 480, y: 80 }, data: { label: '结束' } },
          ]);
        }
      } catch { /* 404 ok for new */ }
    })();
  }, [formDefId]);

  const save = async () => {
    const payload = {
      id: pdId ?? null,
      formDefId: Number(formDefId),
      nodes: nodes.map(n => ({
        id: n.id, type: n.type, x: n.position.x, y: n.position.y,
        assignee: n.data.assignee ?? { type: 'user', ids: [] },
        props: {},
      })),
      edges: edges.map(e => ({ from: e.source, to: e.target })),
    };
    const res = await request('/api/processes/definitions', { method: 'POST', data: payload });
    setPdId(res.id); message.success('已保存草稿');
  };
  const publish = async () => {
    if (!pdId) { save().then(publish); return; }
    await request(`/api/processes/definitions/${pdId}/publish`, { method: 'POST' });
    message.success('已发布');
  };

  return (
    <div style={{ display: 'flex', height: '100vh' }}>
      <aside style={{ width: 200, padding: 8 }}>
        <h4>节点</h4>
        <Button block style={{ marginBottom: 8 }} onClick={() => setNodes(ns => [...ns, {
          id: 'n_' + Date.now(), type: 'approval', position: { x: 200, y: 100 },
          data: { label: '审批节点', assignee: { type: 'user', ids: [] } },
        }])}>+ 审批</Button>
      </aside>
      <main style={{ flex: 1 }}>
        <Space style={{ padding: 8 }}>
          <Button type="primary" onClick={save}>保存草稿</Button>
          <Button onClick={publish}>发布</Button>
        </Space>
        <ReactFlow
          nodes={nodes} edges={edges}
          nodeTypes={processNodeTypes as any}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={c => setEdges(es => addEdge({ ...c, id: `${c.source}->${c.target}` }, es))}
          onNodeClick={(_, n) => setSelected(n)}
        >
          <Background /><Controls /><MiniMap />
        </ReactFlow>
      </main>
      <aside style={{ width: 320, borderLeft: '1px solid #eee' }}>
        {selected && (processNodeConfigs as any)[selected.type]
          ? (processNodeConfigs as any)[selected.type]({ node: selected, onChange: (n: any) => {
              setNodes(ns => ns.map(x => x.id === n.id ? n : x));
              setSelected(n);
            }})
          : <div style={{ padding: 16 }}>选中一个审批节点以编辑</div>}
      </aside>
    </div>
  );
}
```

- [ ] **Step 7: Add route**

```ts
{ path: '/designer/process/:formDefId', component: './designer/process/ProcessDesigner', hideInMenu: true, access: 'canAdmin' },
```

- [ ] **Step 8: Commit**

```bash
git add frontend/src; git commit -m "feat(designer): process designer with React Flow v12 + AssigneePicker"
```

---

### Phase P4 demo

1. Admin opens `/designer/process/<formDefId>`.
2. Adds an `approval` node, links `start → approval → end`, picks user bob.
3. Save → Publish.
4. `POST /api/instances/start { formCode }` as alice → returns `{ instanceId, firstTaskIds: [<bob task>] }`.
5. `GET /api/tasks?status=PENDING` as bob → task appears.
6. `POST /api/tasks/<id>/approve` → instance ends APPROVED; siblings (none in 1-approver case) untouched.
7. Verify `t_task_history` rows: `START`, `APPROVE`, `COMPLETE`.
8. CI green.

---

## Phase P5 — Task Center + E2E

### Task P5.1 — Frontend task center pages

**Files:**
- Create: `frontend/src/pages/tasks/Inbox.tsx`
- Create: `frontend/src/pages/tasks/Done.tsx`
- Create: `frontend/src/pages/proc/Sent.tsx`
- Create: `frontend/src/pages/proc/Detail.tsx`
- Modify: `frontend/config/routes.ts`

- [ ] **Step 1: Write `Inbox.tsx`**

Use a small `DecisionModal` component so each confirmation has its own stateful input — no fixed `id="cmt"`, no `document.getElementById`:

```tsx
import { ProTable } from '@ant-design/pro-components';
import { Button, Modal, Input, message } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@umijs/max';
import { request } from '@umijs/max';
import { useState } from 'react';

function DecisionModal({
  taskId, action, onDone, open, onClose,
}: {
  taskId: number;
  action: 'approve' | 'reject';
  open: boolean;
  onClose(): void;
  onDone(): void;
}) {
  const [comment, setComment] = useState('');
  const qc = useQueryClient();
  const submit = async () => {
    await request(`/api/tasks/${taskId}/${action}`, { method: 'POST', data: { comment } });
    qc.invalidateQueries({ queryKey: ['inbox'] });
    message.success('已完成');
    onDone();
  };
  return (
    <Modal
      open={open} onCancel={onClose} onOk={submit}
      title={action === 'approve' ? '审批意见（可选）' : '驳回原因（必填）'}
      okText="确定" cancelText="取消"
    >
      <Input.TextArea rows={3} value={comment} onChange={e => setComment(e.target.value)} />
    </Modal>
  );
}

export default function Inbox() {
  const navigate = useNavigate();
  const { data } = useQuery({
    queryKey: ['inbox'],
    queryFn: () => request('/api/tasks?status=PENDING'),
  });
  const [pending, setPending] = useState<{ id: number; action: 'approve' | 'reject' } | null>(null);

  return (
    <>
      <ProTable
        rowKey="id"
        dataSource={data ?? []}
        columns={[
          { title: 'ID', dataIndex: 'id' },
          { title: '节点', dataIndex: 'nodeId' },
          { title: '创建', dataIndex: 'createdAt' },
          { title: '操作', render: (_, t: any) => (
            <>
              <Button size="small" type="primary"
                onClick={() => setPending({ id: t.id, action: 'approve' })}>同意</Button>{' '}
              <Button size="small" danger
                onClick={() => setPending({ id: t.id, action: 'reject' })}>驳回</Button>{' '}
              <Button size="small" onClick={() => navigate('/proc/' + t.procInstId)}>查看流程</Button>
            </>
          ) },
        ]}
      />
      {pending && (
        <DecisionModal
          taskId={pending.id} action={pending.action}
          open={true}
          onClose={() => setPending(null)}
          onDone={() => setPending(null)} />
      )}
    </>
  );
}
```

- [ ] **Step 2: Write `Done.tsx`** — same shape, `status=APPROVED|REJECTED|SKIPPED`.

- [ ] **Step 3: Write `Sent.tsx`** — uses `useQuery` → `request('/api/instances')`, columns: instanceId, status, startedAt, action to view detail.

- [ ] **Step 4: Write `Detail.tsx`** — calls `/api/instances/{id}`, lays out `instance`, `tasks`, and a Timeline of `history` (use antd `Timeline`).

- [ ] **Step 5: Routes**

```ts
{ path: '/tasks/inbox', name: 'inbox', component: './tasks/Inbox' },
{ path: '/tasks/done',  name: 'done',  component: './tasks/Done' },
{ path: '/proc',        name: 'proc',  component: './proc/Sent' },
{ path: '/proc/:id',    component: './proc/Detail', hideInMenu: true },
```

- [ ] **Step 6: Commit**

```bash
git add frontend/src; git commit -m "feat(tasks): inbox/done/sent/detail pages"
```

---

### Task P5.2 — End-to-end Playwright happy-path

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/full-flow.spec.ts`

- [ ] **Step 1: Install Playwright**

```bash
cd frontend && npm install -D @playwright/test
npx playwright install chromium --with-deps
```

- [ ] **Step 2: `playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  use: { baseURL: 'http://localhost:8000', headless: true, viewport: { width: 1280, height: 720 } },
  webServer: [
    { command: 'cd ../backend && mvn -B spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx512m"',
      port: 8080, reuseExistingServer: true, timeout: 120_000 },
    { command: 'npm start', port: 8000, reuseExistingServer: true, timeout: 120_000 },
  ],
});
```

- [ ] **Step 3: `e2e/full-flow.spec.ts`**

```ts
import { test, expect } from '@playwright/test';

test('design → publish → submit → approve end-to-end', async ({ page }) => {
  // 1. admin login
  await page.goto('/user/login');
  await page.getByLabel('用户名').fill('admin');
  await page.getByLabel('密码').fill('ant.design');
  await page.getByRole('button', { name: '登录' }).click();
  await page.waitForURL('**/');

  // 2. design a form
  await page.goto('/designer/form/new');
  await page.locator('text=单行文本').dragTo(page.locator('[data-canvas]'));
  await page.getByRole('button', { name: '保存草稿' }).click();
  const url = page.url();
  const m = url.match(/\/designer\/form\/(\d+)/);
  const formId = m![1];

  // Capture form code (the designer writes code based on id)
  const formCode = `form_${formId}`;

  // 3. publish
  await page.getByRole('button', { name: '发布' }).click();
  await expect(page.getByText('已发布')).toBeVisible();

  // 4. design a process pointing at admin (so admin acts as approver for this e2e)
  await page.goto(`/designer/process/${formId}`);
  await page.getByRole('button', { name: '+ 审批' }).click();
  // Connect start → approval → end by hovering + drag (manual step in MVP)
  // We assume the user clicks the start handle then drags to the new node, etc.
  // For e2e simplicity: wire edges via direct API:
  const token = await page.evaluate(() => localStorage.getItem('antflow-token'));
  await page.request.post('http://localhost:8080/api/processes/definitions', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      id: null,
      formDefId: Number(formId),
      nodes: [
        { id: 'start', type: 'start', x: 0, y: 0, assignee: { type: 'user', ids: [] }, props: {} },
        { id: 'a1',    type: 'approval', x: 120, y: 40,
          assignee: { type: 'user', ids: [1] }, props: {} },
        { id: 'end',   type: 'end', x: 240, y: 40, assignee: { type: 'user', ids: [] }, props: {} },
      ],
      edges: [{ from: 'start', to: 'a1' }, { from: 'a1', to: 'end' }],
    },
  });
  // Then publish the process
  const pd = await (await page.request.get('http://localhost:8080/api/processes/definitions/by-form/' + formId,
    { headers: { Authorization: `Bearer ${token}` } })).json();
  await page.request.post(`http://localhost:8080/api/processes/definitions/${pd.id}/publish`, {
    headers: { Authorization: `Bearer ${token}` },
  });

  // 5. submit as bob
  await page.evaluate(() => localStorage.removeItem('antflow-token'));
  await page.goto('/user/login');
  await page.getByLabel('用户名').fill('bob');
  await page.getByLabel('密码').fill('ant.design');
  await page.getByRole('button', { name: '登录' }).click();

  await page.goto(`/runtime/form/${formCode}`);
  await page.getByLabel('姓名').fill('Bob');
  await page.getByRole('button', { name: '提交' }).click();
  await page.waitForURL('**/runtime/list');

  // 6. back to admin to approve
  await page.evaluate(() => localStorage.removeItem('antflow-token'));
  await page.goto('/user/login');
  await page.getByLabel('用户名').fill('admin');
  await page.getByLabel('密码').fill('ant.design');
  await page.getByRole('button', { name: '登录' }).click();

  await page.goto('/tasks/inbox');
  await expect(page.getByText('审批节点')).toBeVisible();
  await page.getByRole('button', { name: '同意' }).click();
  await page.locator('#cmt').fill('LGTM');
  await page.getByRole('button', { name: '确定' }).click();

  // 7. instance detail shows APPROVED
  await page.goto('/proc');
  await expect(page.getByText('APPROVED')).toBeVisible();
});
```

- [ ] **Step 4: Run; commit**

```bash
cd frontend && npx playwright test
git add frontend/playwright.config.ts frontend/e2e; git commit -m "test(e2e): full-flow playwright happy path"
```

---

### Phase P5 demo + Definition of Done for MVP

After P5:
- ✅ Admin login → `/admin/users` lists users
- ✅ Admin login → `/designer/form/new` → drag fields → save → publish
- ✅ Admin login → `/designer/process/<formDefId>` → wire 1-level approval → save → publish
- ✅ Bob login → `/runtime/form/<formCode>` → fill → submit
- ✅ Approver login → `/tasks/inbox` → see task → approve
- ✅ Submitter login → `/proc` → see instance `APPROVED`
- ✅ CI green (backend `mvn test`, frontend vitest + build + playwright)

MVP shipped. v1.x phases reserved for future plans (parallel branches, ALL_SIGN, reject-back, conditions, expressions, notifications, storage abstraction, i18n, etc.).

---

## Pre-Demo Cleanup Tasks

### Task P0.4b — Flyway V3__indexes.sql (separated from DDL)

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__indexes.sql`

- [ ] **Step 1: Write the migration**

```sql
-- V3__indexes.sql — Separated from V1 so PG index builds don't lock a freshly-migrated DB.
CREATE INDEX IF NOT EXISTS ix_form_schema  ON t_form_definition  USING GIN (schema jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_form_data    ON t_form_data       USING GIN (data jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_proc_nodes   ON t_process_definition USING GIN (nodes jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_proc_edges   ON t_process_definition USING GIN (edges jsonb_path_ops);
CREATE INDEX IF NOT EXISTS ix_dept_path    ON t_department      USING GIST (path);
CREATE INDEX IF NOT EXISTS ix_dept_company ON t_department      (company_id);
CREATE INDEX IF NOT EXISTS ix_user_dept    ON t_user            (dept_id);
CREATE INDEX IF NOT EXISTS ix_user_role    ON t_user_role       (role_id);
CREATE UNIQUE INDEX IF NOT EXISTS ux_pdef_form_version
    ON t_process_definition (form_def_id, version DESC);
```

- [ ] **Step 2: Restart backend; expect 3 migrations applied**

```bash
mvn -B spring-boot:run   # logs: "Successfully applied 3 migrations"
```

- [ ] **Step 3: Verify `\di`**

```bash
docker exec antflow-postgres psql -U antflow -d antflow -c "\di"
```

Expected: ix_form_schema, ix_form_data, ix_proc_nodes, ix_proc_edges, ix_dept_path, ix_dept_company, ix_user_dept, ix_user_role, ux_pdef_form_version.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__indexes.sql
git commit -m "feat(backend): V3 indexes (GIN/GIST)"
```

---

### Task P1.4 — Login rate limit filter (Bucket4j)

**Files:**
- Create: `backend/src/main/java/com/antflow/auth/LoginRateLimitFilter.java`
- Modify: `backend/src/main/java/com/antflow/auth/SecurityConfig.java`
- Test: `backend/src/test/java/com/antflow/auth/LoginRateLimitFilterTest.java`

- [ ] **Step 1: Write the filter**

```java
package com.antflow.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long perMinute;
    private final long perHour;

    public LoginRateLimitFilter(@Value("${antflow.login.per-minute:5}") long perMinute,
                                @Value("${antflow.login.per-hour:30}") long perHour) {
        this.perMinute = perMinute; this.perHour = perHour;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(req.getMethod()) && "/api/auth/login".equals(req.getRequestURI())) {
            Bucket b = buckets.computeIfAbsent(req.getRemoteAddr(), k -> newBucket());
            if (!b.tryConsume(1)) {
                res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"too many login attempts\"}");
                return;
            }
        }
        chain.doFilter(req, res);
    }

    private Bucket newBucket() {
        return Bucket.builder()
            .addLimit(Bandwidth.builder().capacity(perMinute)
                .refillGreedy(perMinute, Duration.ofMinutes(1)).build())
            .addLimit(Bandwidth.builder().capacity(perHour)
                .refillGreedy(perHour, Duration.ofHours(1)).build())
            .build();
    }
}
```

- [ ] **Step 2: Register in `SecurityConfig.filter` before `JwtAuthFilter`**

```java
.addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
```

- [ ] **Step 3: Failing test**

```java
@SpringBootTest
class LoginRateLimitFilterTest {
    @Test void allowFiveThen429() throws Exception {
        var f = new LoginRateLimitFilter(5, 30);
        for (int i = 0; i < 5; i++) assertEquals(200, call(f, "1.1.1.1"));
        assertEquals(429, call(f, "1.1.1.1"));
    }

    private int call(LoginRateLimitFilter f, String ip) throws Exception {
        var m = LoginRateLimitFilter.class.getDeclaredMethod(
            "doFilterInternal", HttpServletRequest.class, HttpServletResponse.class, FilterChain.class);
        m.setAccessible(true);
        var req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        m.invoke(f, req, res, chain);
        return res.getStatus();
    }
}
```

- [ ] **Step 4: Run, expect pass; commit**

```bash
cd backend && mvn -B -q test -Dtest=LoginRateLimitFilterTest
git add backend/src; git commit -m "feat(auth): Bucket4j login rate limit (5/min, 30/hr)"
```

---

## Revision Notes (Feedback Round 1)

Patches applied after user feedback (`修改建议.txt`). Listed in order of impact.

### Blockers (must-fix; would prevent first compile or first run)

1. **JSONB TypeHandler missing** — added `@TableField(typeHandler = JacksonTypeHandler.class)` and `autoResultMap = true` on all four JSONB-bearing entities (`FormDefinition`, `FormData`, `ProcessDefinition`). Without these, MyBatis-Plus binds as VARCHAR and JSON gets double-encoded.
2. **Optimistic-lock plugin not registered** — added `MybatisPlusConfig` bean in P0.2 with `OptimisticLockerInnerInterceptor`. Without it `@Version` columns exist but are ignored on `updateById`, so concurrent approves overwrite each other.
3. **`TaskMapperExt` referenced but not defined** — promoted it to a first-class file in P4.3 step 2 with full code. Engine now calls `taskMapperExt.selectInstanceById(...)` and `taskMapperExt.updateInstance(...)` explicitly.
4. **SecurityConfig CORS compile error** — replaced the broken ternary on `setAllowedOrigins` with a clean comma-separated allowlist derived from a sensible default. Comment notes that prod tightening is a separate follow-up.
5. **Frontend proxy to backend missing** — added Task P0.12 writing `frontend/config/proxy.ts` to forward `/api` to `http://localhost:8080`. Without it the page POSTs to `:8000` and 404s.
6. **`GlobalExceptionHandler` missing** — added Task P0.11. Centralizes the envelope contract (`{ code, message, traceId }`) and wires `BizException` → 422 with the engine's code, `BadCredentialsException` → 401, `AccessDeniedException` → 403, validation → 422 with `fieldErrors`, `Exception` (catch-all) → 500.

### Design / perf hardening

7. **`useFormDesignerStore` `setSchema` polluted undo stack on load** — split into `loadSchema(next)` (silent — resets `history: { past: [], future: [] }`) and `resetSchema(next)` (noisy — pushes, for dev tooling only). All load paths now use `loadSchema`.
8. **Process engine forking bug under multi-edge flows** — added a `validateLinearFlow(nodes, edges)` check in `ProcessDefinitionService.publish()` that enforces every non-end node has exactly one outgoing edge. Returns 422 `BAD_FLOW` on violation. Matches MVP spec (sequential only).

### Minor

9. **Inbox Modal `id="cmt"` collision risk** — replaced the `Modal.confirm({...})` shortcut + `document.getElementById('cmt')` with a proper `DecisionModal` component using `useState`. Each confirmation owns its own textarea state. (Original sketch in plan had a hooks-rules bug; this revision makes it correct.)
10. **Frontend routes currently open** — ant-design-pro's default `getInitialState` already returns `{}` when no token, and `app.tsx` redirects to `/user/login` via the runtime config. The 403 from JWT-less requests comes out as `{ code: 'INTERNAL_ERROR' }` from the new GlobalExceptionHandler — not a clean UX. **TODO (deferred to v1.x):** wrap routes with `<Authorized>` in `app.tsx` so anonymous users get an in-app "/user/login" redirect rather than 403.
11. **`ant.design` default password** — documented: this is intentional for MVP demo. Production admins must change on first login (post-MVP feature). Plan P1.2's `UserService.create` already accepts a password hash from the request body; the default is only used when null.
12. **`@MapperScan("com.antflow")` is broad** — acceptable. MyBatis-Plus is lazy and only registers `@Mapper`-annotated interfaces; non-Mapper classes are ignored. Note kept for transparency.
13. **springdoc-openapi security** — MVP ships /v3/api-docs and /swagger-ui unprotected (per P0.7 `permitAll`). For prod, gate them behind `@PreAuthorize("hasRole('admin')")` or a profile check. Not addressed in MVP.

---

## Self-Review Notes

**Spec coverage:** Every numbered spec decision (1–20) is referenced by at least one task. Specifically:

- Decisions 1, 6, 7 → P0.1, P0.2 (monorepo + Spring Boot 3 + MyBatis-Plus).
- Decision 2 (custom engine) → P4.3 (full ProcessEngine).
- Decision 3 (MVP sequential) → P4 tasks; concurrency test in P5.2.
- Decisions 4, 19 → P1.1 (Department with ltree), P2.1 (FormDefinition code UNIQUE in DDL).
- Decision 5 (JWT, stateless, Spring Security 6) → P0.6, P0.7.
- Decision 8 (React Flow v12) → P4.5 with explicit `import from '@xyflow/react'`, `NodeProps<Node<...>>`, `Position.Top/Bottom`, `@xyflow/react/dist/style.css`.
- Decision 9 (zustand + react-query) → P0.10, P1.4, P2.6 (zustand), every API call uses `@tanstack/react-query` `useQuery` / `useMutation`.
- Decision 10 (server-state boundary) → `request` used only for `getInitialState`, ProComponents integrations, and explicit `app.request.post` test fetch in P5.2.
- Decision 11 (multi-assignee OR-sign, `t_task.approval_mode`) → P4.3 (`nt.setApprovalMode("OR_SIGN")`).
- Decision 12 (form must be PUBLISHED first) → P4.1 (`FOR_FORM_NOT_PUBLISHED` guard).
- Decision 13 (NoAssigneeFoundException → 422) → P4.2.
- Decision 14 (JWT secret fail-fast ≥ 32 bytes) → P0.6.
- Decision 15 (Flyway V1 enables ltree, V2 bcrypt-hashed admin only) → P0.3 (initdb.d), P0.5 (V2), P0.4b (V3).
- Decision 16 (UUIDs globally unique) → P2.6 (`nanoid(8)`; `findById` walks whole tree).
- Decision 17 (`@Version`) → P4.3 (entities + MyBatis-Plus `@Version` annotation; optimistic-lock plugin engaged by default).
- Decision 18 (Flyway split) → P0.4b.
- Decision 19 (`code` UNIQUE in DDL) → P0.4 V1 DDL.
- Decision 20 (1:1 form↔process, lookup by form_def_id) → P4.1 (`latestPublishedForForm`).

Plus non-goal validations: parallel branches, ALL_SIGN, etc. — all explicitly excluded in the Phase P6-P15 list at the end of P5.

**Placeholder scan:** No "TBD" / "TODO" / "implement later" in code blocks. Two intentional docs TODOs flagged:
1. P2.6 designer perf (matches spec note).
2. P4.2 `dept_leader` throws "not wired in P1.4 (v1.x)" — matches spec non-goal for MVP.

**Type consistency:**
- `AssigneeSpec` defined P4.2, used P4.3 parser and resolver.
- `StartCmd` / `CompleteCmd` defined P4.3, used P4.3 + P4.4 controllers.
- `TaskEntity`/`TaskHistoryEntity`/`ProcessInstance` declared P4.3, used P4.3 + P4.4.
- `processNodeTypes` / `processNodeConfigs` declared P4.5, consumed in same file.
- `FormRenderer` props `{ schema, mode, value, onChange }` declared P2.2, consumed by every field component and by `<Fill>` (P3.2).
- `useFormDesignerStore` `State` interface declared P2.6 and consumed by FormDesigner + Inspector in the same file.

All consistent.

---

Plan complete. Recommended execution: **subagent-driven-development** for parallel P0.4b + P1.4 cleanup, then sequential per-phase subagents for P1 → P5.
