package com.antflow.org;

import com.antflow.engine.BizException;
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
    public List<User> list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) Long deptId) {
        var q = new QueryWrapper<User>();
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like("username", keyword).or().like("display_name", keyword));
        }
        if (deptId != null) {
            q.eq("dept_id", deptId);
        }
        return userMapper.selectList(q);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        User u = new User();
        u.setUsername((String) body.get("username"));
        u.setDisplayName((String) body.get("displayName"));
        u.setEmail((String) body.get("email"));
        u.setPhone((String) body.get("phone"));
        u.setPosition((String) body.get("position"));
        u.setGender((String) body.get("gender"));
        if (body.get("deptId") != null) {
            u.setDeptId(((Number) body.get("deptId")).longValue());
        }
        Long id = userService.create(u, toLongList(body.get("roleIds")));
        return Map.of("id", id);
    }

    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException("NOT_FOUND", "用户不存在");
        if (body.containsKey("displayName")) u.setDisplayName((String) body.get("displayName"));
        if (body.containsKey("email")) u.setEmail((String) body.get("email"));
        if (body.containsKey("phone")) u.setPhone((String) body.get("phone"));
        if (body.containsKey("position")) u.setPosition((String) body.get("position"));
        if (body.containsKey("gender")) u.setGender((String) body.get("gender"));
        if (body.containsKey("deptId")) {
            Long departmentId = body.get("deptId") == null
                ? null
                : ((Number) body.get("deptId")).longValue();
            userService.validateDepartment(departmentId);
            u.setDeptId(departmentId);
        }
        if (body.containsKey("username")) {
            String username = (String) body.get("username");
            userService.validateUsernameAvailable(username, id);
            u.setUsername(username.trim());
        }
        userMapper.updateById(u);
        return u;
    }

    @PutMapping("/{id}/roles")
    public void setRoles(@PathVariable Long id, @RequestBody List<Long> roleIds) {
        userService.setRoles(id, roleIds);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        userService.delete(id);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> toLongList(Object o) {
        if (o == null) return List.of();
        return ((List<Number>) o).stream().map(Number::longValue).toList();
    }
}
