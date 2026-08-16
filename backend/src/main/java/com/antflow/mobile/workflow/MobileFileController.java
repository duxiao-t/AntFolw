package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import com.antflow.authz.AuthorizationService;
import com.antflow.authz.PermissionCodes;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/mobile/files")
@RequiredArgsConstructor
public class MobileFileController {
    private final MobileFileService fileService;
    private final AuthorizationService authorizationService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MobileFileDto upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam(name = "watermark", defaultValue = "false") boolean watermark,
        @RequestParam(name = "watermarkText", required = false) String watermarkText) {
        authorizationService.requirePermission(PermissionCodes.FILE_UPLOAD);
        PrincipalHolder.Principal principal = principal();
        return fileService.upload(file, principal.userId(), watermark, watermarkText);
    }

    @GetMapping("/{id}")
    public MobileFileDto metadata(@PathVariable UUID id) {
        authorizationService.requirePermission(PermissionCodes.FILE_READ);
        PrincipalHolder.Principal principal = principal();
        return fileService.getMetadata(id, principal.userId(), principal.roles());
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable UUID id) {
        authorizationService.requirePermission(PermissionCodes.FILE_READ);
        PrincipalHolder.Principal principal = principal();
        MobileFileContent content = fileService.readContent(id, principal.userId(), principal.roles());
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.metadata().contentType()))
            .contentLength(content.metadata().size())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(content.metadata().name(), StandardCharsets.UTF_8)
                .build()
                .toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store, no-transform")
            .body(content.resource());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        authorizationService.requirePermission(PermissionCodes.FILE_UPLOAD);
        fileService.delete(id, principal().userId());
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}
