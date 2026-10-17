package com.antflow.integration.storage;

import com.antflow.attachment.MobileFileProperties;
import com.antflow.authz.AuthorizationService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 存储配置视图与自检的测试。除脱敏正确性外，专门覆盖两条**不碰网络**的路径
 * （非对象存储实现、端点非法），让这两个分支在 CI 里无需 MinIO 也能被验证。
 */
class StorageConfigControllerTest {

    @Test
    void viewMasksAccessKeyAndNeverReturnsTheSecret() {
        MobileFileProperties props = properties();
        props.getMinio().setAccessKey("AKIAEXAMPLE123");
        props.getMinio().setSecretKey("super-secret-value");

        StorageConfigController.StorageConfigView view = controller(props).view();

        assertThat(view.accessKeyMasked()).isEqualTo("AKIA****");
        assertThat(view.accessKeyMasked()).doesNotContain("EXAMPLE123");
        assertThat(view.secretConfigured()).isTrue();
        assertThat(view.endpoint()).isEqualTo("http://minio:9000");
        assertThat(view.bucket()).isEqualTo("antflow-files");
        assertThat(view.createBucket()).isTrue();
    }

    @Test
    void viewReportsMissingSecretInsteadOfBlankingSilently() {
        MobileFileProperties props = properties();
        props.getMinio().setAccessKey("");
        props.getMinio().setSecretKey("");

        StorageConfigController.StorageConfigView view = controller(props).view();

        assertThat(view.accessKeyMasked()).isEmpty();
        assertThat(view.secretConfigured()).isFalse();
    }

    @Test
    void probeSkipsNonObjectStorageWithoutTouchingNetwork() {
        MobileFileProperties props = properties();
        props.setStorage("local");

        StorageConfigController.StorageProbeResult result = controller(props).test();

        assertThat(result.ok()).isFalse();
        assertThat(result.elapsedMs()).isZero();
        assertThat(result.detail()).contains("local");
    }

    @Test
    void probeFailsFastWithReadableMessageWhenEndpointIsInvalid() {
        MobileFileProperties props = properties();
        props.getMinio().setEndpoint("not-a-url");

        StorageConfigController.StorageProbeResult result = controller(props).test();

        assertThat(result.ok()).isFalse();
        assertThat(result.bucketExists()).isFalse();
        assertThat(result.detail()).startsWith("连接失败");
    }

    private static MobileFileProperties properties() {
        MobileFileProperties props = new MobileFileProperties();
        props.getMinio().setEndpoint("http://minio:9000");
        props.getMinio().setBucket("antflow-files");
        props.getMinio().setAccessKey("antflow-local");
        props.getMinio().setSecretKey("secret");
        return props;
    }

    private static StorageConfigController controller(MobileFileProperties props) {
        return new StorageConfigController(props, mock(AuthorizationService.class));
    }
}
