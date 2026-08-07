package com.antflow.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditQueryService queryService;
    private final AuditArchiveService archiveService;
    private final AuditService auditService;

    @GetMapping("/events")
    public AuditQueryService.AuditPage events(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String ip,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryService.search(search(from, to, operatorId, action, resourceType, resourceId,
            result, riskLevel, ip, page, size));
    }

    @GetMapping("/events/{id}")
    public AuditQueryService.AuditEventDto detail(@PathVariable long id) {
        return queryService.detail(id);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) Long operatorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String ip) {
        byte[] body = queryService.export(search(from, to, operatorId, action, resourceType,
            resourceId, result, riskLevel, ip, 1, 10000));
        auditService.success("security.audit.export", "AUDIT_EVENT", null,
            AuditService.RiskLevel.HIGH, java.util.Map.of(),
            java.util.Map.of("recordLimit", 10000, "bytes", body.length));
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("antflow-audit.ndjson").build().toString())
            .body(body);
    }

    @GetMapping("/archives")
    public List<AuditArchiveService.ArchiveDto> archives() {
        return archiveService.list();
    }

    @GetMapping("/archives/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        AuditArchiveService.ArchiveDownload archive = archiveService.download(id);
        auditService.success("security.audit.archive.download", "AUDIT_ARCHIVE", id,
            AuditService.RiskLevel.CRITICAL, java.util.Map.of(),
            java.util.Map.of("objectKey", archive.objectKey(), "bytes", archive.content().length));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(archive.fileName()).build().toString())
            .body(archive.content());
    }

    private static AuditQueryService.AuditSearch search(
            OffsetDateTime from, OffsetDateTime to, Long operatorId, String action,
            String resourceType, String resourceId, String result, String riskLevel,
            String ip, int page, int size) {
        return new AuditQueryService.AuditSearch(from, to, operatorId, action, resourceType,
            resourceId, result, riskLevel, ip, page, size);
    }
}
