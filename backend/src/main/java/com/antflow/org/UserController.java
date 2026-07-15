package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('admin')")
public class UserController {
    private final UserMapper userMapper;
    private final UserService userService;

    @GetMapping
    public List<User> list(@RequestParam(required = false) String keyword) {
        var q = new QueryWrapper<User>();
        if (keyword != null && !keyword.isBlank()) q.like("username", keyword);
        return userMapper.selectList(q);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        User u = new User();
        u.setUsername((String) body.get("username"));
        u.setDisplayName((String) body.get("displayName"));
        u.setEmail((String) body.get("email"));
        if (body.get("deptId") != null) {
            u.setDeptId(((Number) body.get("deptId")).longValue());
        }
        Long id = userService.create(u, toLongList(body.get("roleIds")));
        return Map.of("id", id);
    }

    @PutMapping("/{id}/roles")
    public void setRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.setRoles(id, roleIds);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) return List.of();
        return ((List<Number>) o).stream().map(Number::longValue).toList();
    }
}
