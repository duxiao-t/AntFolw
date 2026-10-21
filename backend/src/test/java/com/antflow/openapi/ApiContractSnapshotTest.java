package com.antflow.openapi;

import com.antflow.attachment.FileStorage;
import com.antflow.attachment.StoredObject;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 真实后端 OpenAPI 契约的**快照门禁**：boot 一次上下文，取 {@code /v3/api-docs}，归一化后
 * 与仓库里提交的 {@code frontend/config/antflow-api.json} **逐字比对**。
 *
 * <p>为什么是这里：契约的消费方（桌面 codegen）在前端，但契约的**真源**在后端代码。
 * 没有这道门禁，提交的 spec 会跟代码悄悄漂——`frontend/config/oneapi.json` 就是躺了两年的反例。
 *
 * <p>归一化的目的是让门禁抓**契约变化**，而不是抖动（详见 {@link #normalized}）。数组顺序
 * （`parameters`/`required`/`tags`）**故意不动**：实测两个独立进程的导出逐字相同，所以不需要处理；
 * 哪天它真的漂了，再单独处理并在这里写清原因。
 *
 * <p>更新方式：{@code mvn -B test -Dtest=ApiContractSnapshotTest -Dantflow.openapi.write=true}
 * （默认**不写工作区**）。
 */
@SpringBootTest(properties = {
    "antflow.audit.archive-cron=-",
    "antflow.automation.poll-interval-ms=3600000",
    "antflow.automation.recovery-interval-ms=3600000",
    "antflow.outbox.poll-interval-ms=3600000",
    "antflow.wecom.schedule-poll-interval-ms=3600000",
    "antflow.mobile.files.processing-reap-interval-ms=3600000",
    // 上下文只要能把 bean 装起来；附件走内存实现，**不需要 MinIO**。
    "antflow.mobile.files.storage=test",
    "antflow.jwt.secret=test-secret-0123456789-test-secret-0123456789",
    "antflow.auth.bootstrap-admin-password=test-bootstrap-password",
    "antflow.auth.bootstrap-bob-password=test-bootstrap-password"
})
@Import(ApiContractSnapshotTest.InMemoryFileStorageConfig.class)
@AutoConfigureMockMvc
class ApiContractSnapshotTest {

    /** 系统属性名要跟测试方法名一起出现在报错信息里，故意写全。 */
    private static final String REWRITE_HINT =
        "契约变了：跑 `mvn -B test -Dtest=ApiContractSnapshotTest -Dantflow.openapi.write=true` "
            + "重写 frontend/config/antflow-api.json，review diff 后再提交";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String EXTERNAL_POSTGRES_URL = System.getenv("ANTFLOW_TEST_POSTGRES_URL");

    /**
     * 与集成用例各起一个容器（**不共享**）：本用例只读 {@code /v3/api-docs}、不碰业务表，
     * 所以共库/独立库都不影响结论；独立容器换来的是"不与别人的残留互相干扰"。
     */
    static final PostgreSQLContainer<?> POSTGRES =
        externalPostgres() ? null : new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("antflow")
            .withUsername("antflow")
            .withPassword("antflow");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        if (externalPostgres()) {
            registry.add("spring.datasource.url", () -> jdbcUrl(EXTERNAL_POSTGRES_URL));
            registry.add("spring.datasource.username",
                () -> env("ANTFLOW_TEST_POSTGRES_USERNAME", "antflow"));
            registry.add("spring.datasource.password",
                () -> env("ANTFLOW_TEST_POSTGRES_PASSWORD", "antflow"));
            return;
        }
        POSTGRES.start();
        registry.add("spring.datasource.url", () -> jdbcUrl(POSTGRES.getJdbcUrl()));
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static boolean externalPostgres() {
        return EXTERNAL_POSTGRES_URL != null && !EXTERNAL_POSTGRES_URL.isBlank();
    }

    private static String jdbcUrl(String url) {
        return url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryFileStorageConfig {
        /** 自带一份等价实现，而不是去引用另一个测试类的内部类（那会把两个类的生命周期绑在一起）。 */
        @Bean
        FileStorage fileStorage() {
            return new FileStorage() {
                private final Map<String, byte[]> files =
                    new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public StoredObject put(String storageKey, InputStream content, long size,
                                        String contentType) {
                    try {
                        byte[] bytes = content.readAllBytes();
                        files.put(storageKey, bytes);
                        return new StoredObject(storageKey, bytes.length);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }

                @Override
                public Resource get(String storageKey) {
                    return new ByteArrayResource(files.getOrDefault(storageKey, new byte[0]));
                }

                @Override
                public boolean exists(String storageKey) {
                    return files.containsKey(storageKey);
                }

                @Override
                public void delete(String storageKey) {
                    files.remove(storageKey);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    /**
     * 用真实的路由表当"端点全集"，比手写清单可靠——新增 controller 时不需要改测试。
     *
     * <p>必须指名 {@code requestMappingHandlerMapping}：actuator 也注册了一个
     * {@code controllerEndpointHandlerMapping}，按类型注入会撞 NoUniqueBeanDefinitionException。
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMappings;

    @Test
    void matchesTheCommittedContract() throws Exception {
        String actual = normalized(exportedDocument());
        Path target = contractPath();

        if (Boolean.getBoolean("antflow.openapi.write")) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, actual, StandardCharsets.UTF_8);
            System.out.println("[openapi] 已写入 " + target + "（" + pathCount(actual) + " 条 /api/ 路径）");
        } else {
            assertThat(Files.exists(target))
                .withFailMessage("提交的契约文件不存在：%s。%s", target, REWRITE_HINT)
                .isTrue();
            assertThat(actual)
                .withFailMessage("%s（提交 %d 条 /api/ 路径，当前 %d 条）",
                    REWRITE_HINT, pathCount(Files.readString(target, StandardCharsets.UTF_8)),
                    pathCount(actual))
                .isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
        }

        assertKeySurface(JSON.readTree(actual));
    }

    /**
     * 端点覆盖率下限（C6 后半）：**路由表里的每个 /api 端点**都必须出现在提交的契约里。
     *
     * <p>为什么需要：{@link #matchesTheCommittedContract()} 只保证"实时导出的文档 == 提交的文件"，
     * 如果某个 controller 被 {@code @Hidden}、被 springdoc 的 paths 过滤器排除、或干脆没被扫到，
     * 两条文档会一起"少一条"——两边相等，门禁却是绿的，前端 codegen 也拿不到这个端点。
     *
     * <p>口径：路径按原文比对（springdoc 的 `{id}` 与 Spring 的 `{id}` 写法一致），
     * 方法按 {@code RequestMethod} 比对；无方法条件的映射（任意方法）只要求路径存在。
     * 本仓库没有任何 {@code @Hidden}，所以下限是**全覆盖**；将来真要隐藏端点，
     * 在这里加显式白名单并写明原因，别把断言放宽成"比例够了就行"。
     */
    @Test
    void everyApiMappingAppearsInTheCommittedContract() throws Exception {
        JsonNode contract = JSON.readTree(Files.readString(contractPath(), StandardCharsets.UTF_8));
        JsonNode paths = contract.path("paths");
        Set<String> missing = new TreeSet<>();
        int endpoints = 0;
        for (Map.Entry<org.springframework.web.servlet.mvc.method.RequestMappingInfo,
                org.springframework.web.method.HandlerMethod> entry
                : handlerMappings.getHandlerMethods().entrySet()) {
            var info = entry.getKey();
            var methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) continue;
                endpoints++;
                JsonNode path = paths.path(pattern);
                if (path.isMissingNode()) {
                    missing.add(pattern + "（整条路径缺失）← " + entry.getValue().getBeanType().getSimpleName());
                    continue;
                }
                for (var method : methods) {
                    if (!path.has(method.name().toLowerCase())) {
                        missing.add(pattern + " " + method + " ← " + entry.getValue().getBeanType().getSimpleName());
                    }
                }
            }
        }
        assertThat(missing)
            .withFailMessage("以下端点在后端存在、但提交的契约里没有：\n%s\n%s",
                String.join("\n", missing), REWRITE_HINT)
            .isEmpty();
        // 防"路由表读空了导致上面必然通过"：/api 端点数必须达到下限量级。
        assertThat(endpoints).withFailMessage("路由表里只解析出 %d 个 /api 端点，明显不对", endpoints)
            .isGreaterThan(150);
    }

    /** 取文档走**跟线上同一条路**（连带 springdoc 的 writer 设置一起测到）；`/v3/api-docs` 是 permitAll。 */
    private JsonNode exportedDocument() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 归一化：只做**实测证明必需**的几样（结论见下，别凭直觉加东西）。
     *
     * <ol>
     *   <li>{@code servers}：springdoc 原样吐的是
     *       {@code [{"url":"http://localhost","description":"Generated server url"}]}——**从请求推导**的。
     *       这里走 MockMvc，两次独立进程实测相同，但换一种取文档的方式（RANDOM_PORT / TestRestTemplate）
     *       就会变成 {@code http://localhost:<随机端口>}。钉成空数组 = "同源"，正好也是前端要的
     *       （它用的是 {@code /api} 前缀）。**字段留着**（不整段删掉），将来后端真改了 server 配置，
     *       仍然看得见——只是那一次要顺手改这里的约定值。</li>
     *   <li>对象键**递归排序**：默认没有 {@code writer-with-order-by-keys}，键序来自反射与注册顺序。</li>
     *   <li>缩进固定两个空格、换行固定 {@code \n}：{@code DefaultIndenter} 默认跟
     *       {@code System.lineSeparator()} 走，Windows 上会写出 {@code \r\n}，
     *       跟 CI（Linux）就不一致了。</li>
     * </ol>
     *
     * <p>**数组故意不处理**（{@code parameters}/{@code required}/{@code tags}）：实测两个独立 JVM 的导出
     * 逐字相同（md5 一致，含 163 条 {@code /api/} 路径），没有跨进程抖动。哪天它真的漂了，
     * 先确认"顺序无业务含义"再在这里单独归一化，并把原因写进这段注释。
     */
    private static String normalized(JsonNode document) throws IOException {
        ((ObjectNode) document).set("servers", JSON.createArrayNode());
        Object generic = JSON.convertValue(document, Object.class);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return JSON.writer(printer).writeValueAsString(sortedKeys(generic)) + "\n";
    }

    private static Object sortedKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), sortedKeys(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> sorted = new ArrayList<>(list.size());
            for (Object item : list) {
                sorted.add(sortedKeys(item));
            }
            return sorted;
        }
        return value;
    }

    private static Path contractPath() {
        Path resolved = Paths.get(System.getProperty("user.dir"))
            .resolve("../frontend/config/antflow-api.json").normalize();
        assertThat(resolved.getParent())
            .withFailMessage("找不到 frontend/config（由 %s 推出 %s）：测试的工作目录变了？",
                System.getProperty("user.dir"), resolved)
            .isDirectory();
        return resolved;
    }

    private static long pathCount(String contract) throws IOException {
        long count = 0;
        for (java.util.Iterator<String> names = JSON.readTree(contract).path("paths").fieldNames();
                names.hasNext(); ) {
            if (names.next().startsWith("/api/")) {
                count++;
            }
        }
        return count;
    }

    /**
     * 整份比对之外的正向断言：门禁要能说出**缺了什么**，而且这些锚点不受"接口合法增删"影响
     * （路径总数只记录、不做验收条件）。
     */
    private static void assertKeySurface(JsonNode document) {
        JsonNode paths = document.path("paths");
        assertThat(paths.path("/api/forms/definitions").has("get")).as("表单定义列表").isTrue();
        assertThat(paths.path("/api/forms/definitions").has("post")).as("表单定义保存").isTrue();
        assertThat(paths.path("/api/forms/definitions/{id}").has("get")).as("表单定义详情").isTrue();
        assertThat(paths.has("/api/processes/definitions")).as("流程定义列表").isTrue();
        assertThat(paths.path("/api/instances").has("get")).as("实例列表").isTrue();
        assertThat(paths.path("/api/instances/start").has("post")).as("P4a 发起入口").isTrue();
        assertThat(paths.path("/api/mobile/drafts").has("post")).as("P4a 草稿创建").isTrue();
        assertThat(paths.path("/api/mobile/instances").has("post")).as("P4a 移动发起").isTrue();

        JsonNode schemas = document.path("components").path("schemas");
        JsonNode formDefinition = schemas.path("FormDefinition").path("properties");
        assertThat(formDefinition.has("schema")).as("FormDefinition.schema").isTrue();
        assertThat(formDefinition.has("settings")).as("FormDefinition.settings").isTrue();
        assertThat(formDefinition.has("status")).as("FormDefinition.status").isTrue();
        assertThat(formDefinition.has("version")).as("FormDefinition.version").isTrue();
        // 三个角色必须都在契约里：列表回 Page<Summary>、详情回 FormDefinition、保存收 SaveBody。
        // 列表那个 payload 里的 schema/settings 是**没有**的（列表端点不返回它们）——页面不能假设有。
        assertThat(schemas.path("PageSummary").path("properties").has("records"))
            .as("列表载荷 Page<Summary>").isTrue();
        assertThat(schemas.path("Summary").path("properties").has("version"))
            .as("Summary.version").isTrue();
        assertThat(schemas.path("Summary").path("properties").has("schema"))
            .as("Summary 不该带 schema（列表端点不返回）").isFalse();
        assertThat(schemas.path("SaveBody").path("properties").has("schema"))
            .as("保存体 SaveBody.schema").isTrue();

        // P4a：新增的两列必须出现在对应 DTO 上，否则前端拿不到归属字段。
        assertThat(schemas.path("FormData").path("properties").has("companyId"))
            .as("FormData.companyId").isTrue();
        assertThat(schemas.path("FormData").path("properties").has("companyInferred"))
            .as("FormData.companyInferred").isTrue();
        assertThat(schemas.path("ProcessInstance").path("properties").has("companyId"))
            .as("ProcessInstance.companyId").isTrue();
    }
}
