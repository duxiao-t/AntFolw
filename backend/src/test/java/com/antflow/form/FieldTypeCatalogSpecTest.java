package com.antflow.form;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段类型生成物的**门禁**：`fixtures/field-semantics/field-types.json` 必须与
 * {@link FieldTypeCatalog} 完全一致。改了 catalog 忘了重新生成 → 这里红。
 *
 * <p>重新生成（在 {@code backend/} 下）：
 * <pre>mvn -B test -Dtest=FieldTypeCatalogSpecTest -Dantflow.fieldTypes.write=true</pre>
 * 生成物是发布产物，重新生成后要一起提交（CI 会验证"重新生成后没有 diff"）。
 */
class FieldTypeCatalogSpecTest {

    private static final Path SPEC = Path.of("..", "fixtures", "field-semantics", "field-types.json");
    private final ObjectMapper json = new ObjectMapper();

    @Test void generatedSpecMatchesTheCatalog() throws IOException {
        String rendered = FieldTypeCatalog.toJson(json);
        if (Boolean.getBoolean("antflow.fieldTypes.write")) {
            Files.createDirectories(SPEC.getParent());
            Files.writeString(SPEC, rendered, StandardCharsets.UTF_8);
            return;
        }
        assertThat(SPEC).as("生成物缺失——按类注释里的命令重新生成").exists();
        assertThat(Files.readString(SPEC, StandardCharsets.UTF_8)).isEqualTo(rendered);
    }

    @Test void specCarriesEveryTypeWithItsRole() throws IOException {
        JsonNode root = json.readTree(FieldTypeCatalog.toJson(json));
        assertThat(root.path("specVersion").asInt()).isEqualTo(FieldTypeCatalog.SPEC_VERSION);

        var declared = root.path("fieldTypes");
        assertThat(declared.size()).isEqualTo(FieldTypeCatalog.ALL.size());
        // 26 种：数字写死在这里是故意的——加类型必须同时改这条断言，改的人会看见"这是一个 spec 变更"。
        assertThat(declared.size()).isEqualTo(26);

        var ids = new java.util.ArrayList<String>();
        declared.forEach(entry -> {
            ids.add(entry.path("id").asText());
            assertThat(entry.path("kind").asText()).isNotBlank();
        });
        assertThat(ids).isSorted().doesNotHaveDuplicates();
        // 角色与服务端校验用的是同一份：FIELD_TYPES 必须等于 spec 里的 id 集合。
        assertThat(FieldTypeCatalog.ids()).containsExactlyInAnyOrderElementsOf(ids);
    }
}
