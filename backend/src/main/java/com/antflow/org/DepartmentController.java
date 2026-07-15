package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class DepartmentController {
    private final DepartmentMapper mapper;
    private final DepartmentService service;

    @GetMapping
    public List<Department> tree(@RequestParam Long companyId) {
        return service.tree(companyId);
    }

    @PostMapping
    public Department create(@RequestBody Department d) {
        return service.create(d);
    }
}
