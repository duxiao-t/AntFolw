package com.antflow.backup;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/backups")
@RequiredArgsConstructor
public class BackupController {
    private final BackupService service;

    @GetMapping("/settings")
    public BackupService.Settings settings() { return service.settings(); }

    @PutMapping("/settings")
    public BackupService.Settings settings(@RequestBody BackupService.SettingsWrite request) {
        return service.updateSettings(request);
    }

    @GetMapping
    public java.util.List<BackupService.BackupFile> list() { return service.list(); }

    @PostMapping
    public BackupService.Status create() { return service.create(); }

    @GetMapping("/status")
    public BackupService.Status status() { return service.status(); }

    @GetMapping("/{name}/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable String name) {
        BackupService.Download download = service.download(name);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(download.bytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.name(), StandardCharsets.UTF_8).build().toString())
            .body(download.resource());
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable String name) { service.delete(name); }
}
