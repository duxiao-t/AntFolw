package com.antflow.org;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {
    private final RoleMapper mapper;

    @GetMapping
    public List<Role> all() {
        return mapper.selectList(null);
    }
}
