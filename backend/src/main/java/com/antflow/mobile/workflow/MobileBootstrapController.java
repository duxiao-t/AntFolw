package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileBootstrapController {
    private final MobileBootstrapService bootstrapService;

    @GetMapping("/bootstrap")
    public MobileBootstrapDto bootstrap() {
        PrincipalHolder.Principal principal = principal();
        return bootstrapService.bootstrap(principal.userId(), principal.roles());
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}

record MobileBootstrapDto(MobileUserDto user, int pendingCount, int draftCount,
                          int unreadNotificationCount,
                          List<MobileAppDto> favoriteApps,
                          List<RecentProcessDto> recentProcesses,
                          String brandingVersion) {
}

record MobileUserDto(Long id, String username, String displayName, String department,
                     String employeeNo, List<String> roles) {
}

record MobileAppDto(Long formId, String code, String name, String iconUrl,
                    String category, String categoryLabel, String description) {
}

record RecentProcessDto(Long instanceId, String formCode, String formTitle,
                        String status, java.time.OffsetDateTime updatedAt) {
}
