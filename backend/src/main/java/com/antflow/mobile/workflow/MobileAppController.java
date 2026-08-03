package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileAppController {
    private final MobileAppService mobileAppService;

    @GetMapping("/apps")
    public List<MobileAppDto> apps(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category) {
        principal();
        return mobileAppService.list(keyword, category);
    }

    @PutMapping("/preferences/apps")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveFavorites(@RequestBody FavoriteAppsRequest request) {
        mobileAppService.saveFavorites(principal().userId(), request.formIds());
    }

    private PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }

    public record FavoriteAppsRequest(List<Long> formIds) {
    }
}
