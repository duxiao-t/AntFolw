package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class CompanyController {
    private final CompanyMapper mapper;

    @GetMapping
    public List<Company> all() {
        return mapper.selectList(null);
    }

    @PostMapping
    public Company create(@RequestBody Company c) {
        mapper.insert(c);
        return c;
    }
}
