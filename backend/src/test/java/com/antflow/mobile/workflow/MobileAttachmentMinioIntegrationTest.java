package com.antflow.mobile.workflow;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "ANTFLOW_LIVE_MINIO_TESTS", matches = "true")
@SuppressWarnings({"unchecked", "rawtypes"})
class MobileAttachmentMinioIntegrationTest {
    private static final String MINIO_ACCESS_KEY = env("MINIO_ACCESS_KEY", "minioadmin");
    private static final String MINIO_SECRET_KEY = env("MINIO_SECRET_KEY", "minioadmin");
    private static final String MINIO_BUCKET = env("MINIO_BUCKET", "antflow-test-files");
    private static final String MINIO_ENDPOINT = env("MINIO_ENDPOINT", "http://localhost:9000");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MobileFileMapper fileMapper;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("antflow.mobile.files.storage", () -> "minio");
        registry.add("antflow.mobile.files.minio.endpoint", () -> MINIO_ENDPOINT);
        registry.add("antflow.mobile.files.minio.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("antflow.mobile.files.minio.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("antflow.mobile.files.minio.bucket", () -> MINIO_BUCKET);
        registry.add("antflow.mobile.files.minio.create-bucket", () -> "true");
        registry.add("antflow.jwt.secret", () -> "test-secret-0123456789-test-secret-0123456789");
    }

    @Test
    void minioBackedAttachmentLifecycleCoversDeleteAndReworkReplacement() throws Exception {
        String adminToken = login("admin");
        String bobToken = login("bob");
        String formCode = "attach_" + UUID.randomUUID().toString().replace("-", "");
        publishSingleLevelFlow(adminToken, formCode);

        byte[] draftContent = pngBytes((byte) 0x01);
        byte[] originalContent = pngBytes((byte) 0x02);
        byte[] replacementContent = pngBytes((byte) 0x03);

        Map<String, Object> draftFile = upload(adminToken, "draft.png", draftContent,
            MediaType.IMAGE_PNG);
        UUID draftFileId = UUID.fromString((String) draftFile.get("id"));
        MobileFile draftRow = fileMapper.selectById(draftFileId);
        assertThat(draftRow.getStatus()).isEqualTo("READY");
        assertThat(readFile(adminToken, draftFileId)).isEqualTo(draftContent);

        deleteFile(adminToken, draftFileId);

        MobileFile deletedRow = fileMapper.selectById(draftFileId);
        assertThat(deletedRow.getStatus()).isEqualTo("DELETED");
        assertThatThrownBy(() -> readObject(deletedRow.getStorageKey()))
            .isInstanceOf(Exception.class);

        Map<String, Object> originalFile = upload(adminToken, "original.png", originalContent,
            MediaType.IMAGE_PNG);
        UUID originalFileId = UUID.fromString((String) originalFile.get("id"));
        Map<String, Object> startResult = startInstance(adminToken, formCode,
            Map.of("reason", "初次提交", "attachments", List.of(originalFile)),
            List.of(fileRef(originalFileId, "attachments", 0)));
        Long instanceId = asLong(startResult.get("instanceId"));
        String businessNo = (String) startResult.get("businessNo");

        Long approvalTaskId = firstId((List<?>) startResult.get("firstTaskIds"));
        post(bobToken, "/api/mobile/tasks/" + approvalTaskId + "/reject",
            Map.of("comment", "请补充附件"), "reject-" + approvalTaskId);

        Long reworkTaskId = pendingTaskIdForInstance(adminToken, instanceId, "REWORK");
        Map<String, Object> reworkDetail = get(adminToken,
            "/api/mobile/rework-tasks/" + reworkTaskId);
        assertThat(reworkDetail.get("businessNo")).isEqualTo(businessNo);
        assertFileIds((List<Map<String, Object>>) reworkDetail.get("files"), originalFileId);

        Map<String, Object> replacementFile = upload(adminToken, "replacement.png",
            replacementContent, MediaType.IMAGE_PNG);
        UUID replacementFileId = UUID.fromString((String) replacementFile.get("id"));
        Map<String, Object> reworkData = Map.of(
            "reason", "补充后提交",
            "attachments", List.of(replacementFile));
        Map<String, Object> reworkPayload = Map.of(
            "data", reworkData,
            "files", List.of(fileRef(replacementFileId, "attachments", 0)));

        put(adminToken, "/api/mobile/rework-tasks/" + reworkTaskId, reworkPayload);
        Map<String, Object> resubmit = post(adminToken,
            "/api/mobile/rework-tasks/" + reworkTaskId + "/resubmit",
            reworkPayload, "resubmit-" + reworkTaskId);
        assertThat(asLong(resubmit.get("instanceId"))).isEqualTo(instanceId);
        assertThat(resubmit.get("businessNo")).isEqualTo(businessNo);

        Map<String, Object> instanceDetail = get(adminToken, "/api/mobile/instances/" + instanceId);
        assertThat(instanceDetail.get("businessNo")).isEqualTo(businessNo);
        assertFileIds((List<Map<String, Object>>) instanceDetail.get("files"), replacementFileId);

        assertThat(readFile(adminToken, originalFileId)).isEqualTo(originalContent);
        assertThat(readFile(adminToken, replacementFileId)).isEqualTo(replacementContent);
    }

    private String login(String username) {
        Map<String, Object> response = post(null, "/api/auth/login",
            Map.of("username", username, "password", "ant.design"), null);
        return (String) response.get("accessToken");
    }

    private void publishSingleLevelFlow(String token, String formCode) {
        Map<String, Object> form = post(token, "/api/forms/definitions", Map.of(
            "code", formCode,
            "name", "附件验证",
            "description", "附件验证",
            "schema", List.of(
                Map.of("id", "reason", "type", "text", "label", "申请说明",
                    "props", Map.of("required", true)),
                Map.of("id", "attachments", "type", "image_upload", "label", "图片附件")
            ),
            "settings", Map.of("workflowEnabled", true)
        ), null);
        Long formId = asLong(form.get("id"));
        post(token, "/api/forms/definitions/" + formId + "/publish", Map.of(), null);

        Map<String, Object> process = post(token, "/api/processes/definitions", Map.of(
            "formDefId", formId,
            "process", Map.of(
                "id", "root",
                "type", "ROOT",
                "children", Map.of(
                    "id", "a1",
                    "type", "APPROVAL",
                    "props", Map.of(
                        "name", "主管审批",
                        "assignedType", "ASSIGN_USER",
                        "assignedUser", List.of(2)
                    )
                )
            )
        ), null);
        post(token, "/api/processes/definitions/" + asLong(process.get("id")) + "/publish",
            Map.of(), null);
    }

    private Map<String, Object> upload(String token, String fileName, byte[] content,
                                       MediaType contentType) {
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(contentType);
        body.add("file", new HttpEntity<>(resource, partHeaders));
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> response = rest.exchange(url("/api/mobile/files"), HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("contentType", contentType.toString());
        return response.getBody();
    }

    private byte[] readFile(String token, UUID fileId) {
        ResponseEntity<byte[]> response = rest.exchange(
            url("/api/mobile/files/" + fileId + "/content"),
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(token)),
            byte[].class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private void deleteFile(String token, UUID fileId) {
        ResponseEntity<Void> response = rest.exchange(
            url("/api/mobile/files/" + fileId),
            HttpMethod.DELETE,
            new HttpEntity<>(authHeaders(token)),
            Void.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private Map<String, Object> startInstance(String token, String formCode,
                                              Map<String, Object> data,
                                              List<Map<String, Object>> files) {
        return post(token, "/api/mobile/instances", Map.of(
            "formCode", formCode,
            "data", data,
            "selfSelected", Map.of(),
            "files", files
        ), "start-" + formCode);
    }

    private Long pendingTaskIdForInstance(String token, Long instanceId, String taskType) {
        Map<String, Object> page = get(token, "/api/mobile/tasks?view=pending&page=1&size=20");
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        Map<String, Object> task = items.stream()
            .filter(item -> instanceId.equals(asLong(item.get("instanceId"))))
            .filter(item -> taskType.equals(item.get("taskType")))
            .findFirst()
            .orElse(null);
        assertThat(task).as("pending %s task for instance %s", taskType, instanceId).isNotNull();
        return asLong(task.get("id"));
    }

    private Map<String, Object> get(String token, String path) {
        ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.GET,
            new HttpEntity<>(authHeaders(token)), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private Map<String, Object> post(String token, String path, Object body,
                                     String idempotencyKey) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private Map<String, Object> put(String token, String path, Object body) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(url(path), HttpMethod.PUT,
            new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private void assertFileIds(List<Map<String, Object>> files, UUID... expectedIds) {
        assertThat(files).extracting(file -> UUID.fromString((String) file.get("id")))
            .containsExactly(expectedIds);
    }

    private byte[] readObject(String storageKey) throws Exception {
        try (var response = minioClient().getObject(GetObjectArgs.builder()
            .bucket(MINIO_BUCKET)
            .object(storageKey)
            .build())) {
            return response.readAllBytes();
        }
    }

    private MinioClient minioClient() {
        return MinioClient.builder()
            .endpoint(MINIO_ENDPOINT)
            .credentials(MINIO_ACCESS_KEY, MINIO_SECRET_KEY)
            .build();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static Map<String, Object> fileRef(UUID fileId, String fieldId, int sortOrder) {
        return Map.of("fileId", fileId.toString(), "fieldId", fieldId, "sortOrder", sortOrder);
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static Long firstId(List<?> values) {
        assertThat(values).isNotNull().isNotEmpty();
        return asLong(values.get(0));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static byte[] pngBytes(byte marker) {
        return new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, marker
        };
    }
}
