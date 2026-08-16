package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class MobileFileControllerTest {
    private final MobileFileService fileService = Mockito.mock(MobileFileService.class);
    private final AuthorizationService authorizationService = Mockito.mock(AuthorizationService.class);
    private final MobileFileController controller = new MobileFileController(fileService, authorizationService);

    @AfterEach
    void clearPrincipal() {
        PrincipalHolder.clear();
    }

    @Test
    void contentDownloadsOriginalBytesAsAttachmentWithoutTransform() throws Exception {
        UUID fileId = UUID.fromString("a4921c1a-a281-4a6d-be31-646d8f51b018");
        byte[] originalBytes = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02
        };
        PrincipalHolder.set(new PrincipalHolder.Principal(7L, "admin", List.of("admin")));
        Mockito.when(fileService.readContent(Mockito.eq(fileId), Mockito.eq(7L), Mockito.anyCollection()))
            .thenReturn(new MobileFileContent(
                new MobileFileDto(fileId, "原图.png", "image/png", originalBytes.length,
                    "/api/mobile/files/" + fileId + "/content"),
                new ByteArrayResource(originalBytes)));

        ResponseEntity<org.springframework.core.io.Resource> response = controller.content(fileId);

        assertThat(response.getHeaders().getContentType()).hasToString("image/png");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(originalBytes.length);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
            .startsWith("attachment;")
            .contains("filename*=");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
            .isEqualTo("no-store, no-transform");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo(originalBytes);
    }
}
