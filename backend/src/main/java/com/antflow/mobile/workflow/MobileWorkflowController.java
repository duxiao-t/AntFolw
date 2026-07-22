package com.antflow.mobile.workflow;

import com.antflow.auth.PrincipalHolder;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
public class MobileWorkflowController {
    private final MobileDraftService draftService;
    private final MobileWorkflowService workflowService;

    @PostMapping("/drafts")
    public Long createDraft(@RequestBody MobileDraftRequest request) {
        PrincipalHolder.Principal principal = principal();
        return draftService.create(request.formCode(), request.data(), principal.userId());
    }

    @PutMapping("/drafts/{id}")
    public MobileDraftDto updateDraft(@PathVariable Long id,
                                      @RequestBody MobileDraftRequest request) {
        PrincipalHolder.Principal principal = principal();
        return draftService.get(draftService.update(id, request.data(), principal.userId()).getId(),
            principal.userId());
    }

    @DeleteMapping("/drafts/{id}")
    public void deleteDraft(@PathVariable Long id) {
        draftService.delete(id, principal().userId());
    }

    @GetMapping("/drafts")
    public List<MobileDraftDto> drafts() {
        return draftService.list(principal().userId());
    }

    @GetMapping("/drafts/{id}")
    public MobileDraftDto draft(@PathVariable Long id) {
        return draftService.get(id, principal().userId());
    }

    @GetMapping("/forms/{code}")
    public MobileFormDto form(@PathVariable String code) {
        principal();
        return workflowService.getMobileForm(code);
    }

    @PostMapping("/instances")
    public MobileStartResult start(@RequestBody StartMobileInstanceRequest request) {
        return workflowService.start(request, principal().userId());
    }

    @GetMapping("/instances")
    public MobilePageDto<MobileInstanceDto> instances(@RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String status) {
        return workflowService.listInstances(principal().userId(), page, size, keyword, status);
    }

    @GetMapping("/instances/{id}")
    public MobileInstanceDetailDto instance(@PathVariable Long id) {
        PrincipalHolder.Principal principal = principal();
        return workflowService.getInstanceDetail(id, principal.userId(), principal.roles());
    }

    @PostMapping("/instances/{id}/withdraw")
    public void withdraw(@PathVariable Long id) {
        workflowService.withdraw(id, principal().userId());
    }

    @GetMapping("/tasks")
    public MobilePageDto<MobileTaskDto> tasks(@RequestParam(defaultValue = "pending") String view,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String status) {
        return workflowService.listTasks(view, principal().userId(), page, size, keyword, status);
    }

    @GetMapping("/tasks/{id}")
    public MobileTaskDetailDto task(@PathVariable Long id) {
        PrincipalHolder.Principal principal = principal();
        return workflowService.getTaskDetail(id, principal.userId(), principal.roles());
    }

    @PostMapping("/tasks/{id}/approve")
    public void approve(@PathVariable Long id,
                        @RequestBody(required = false) MobileTaskActionRequest request) {
        workflowService.approve(id, request, principal().userId());
    }

    @PostMapping("/tasks/{id}/reject")
    public void reject(@PathVariable Long id,
                       @RequestBody(required = false) MobileTaskActionRequest request) {
        workflowService.reject(id, request, principal().userId());
    }

    private static PrincipalHolder.Principal principal() {
        return PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("authentication required"));
    }
}
