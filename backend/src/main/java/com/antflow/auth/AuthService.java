package com.antflow.auth;

import com.antflow.org.Role;
import com.antflow.org.RoleMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.antflow.org.UserRole;
import com.antflow.org.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public Optional<Authenticated> authenticate(String username, String password) {
        Optional<User> opt = Optional.ofNullable(
            userMapper.selectOne(new QueryWrapper<User>().eq("username", username)));
        if (opt.isEmpty()) return Optional.empty();
        User u = opt.get();
        if (!"ACTIVE".equals(u.getStatus())) return Optional.empty();
        if (!encoder.matches(password, u.getPasswordHash())) {
            return Optional.empty();
        }
        List<String> roles = rolesOf(u.getId());
        String token = jwtService.issue(u.getId(), u.getUsername(), roles);
        return Optional.of(new Authenticated(token, u, roles));
    }

    public Optional<Authenticated> resume(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) return Optional.empty();
        List<String> roles = rolesOf(user.getId());
        return Optional.of(new Authenticated(
            jwtService.issue(user.getId(), user.getUsername(), roles), user, roles));
    }

    public List<String> rolesOf(Long userId) {
        return userRoleMapper.selectList(new QueryWrapper<UserRole>().eq("user_id", userId))
            .stream()
            .map(ur -> roleMapper.selectById(ur.getRoleId()))
            .filter(java.util.Objects::nonNull)
            .filter(role -> Boolean.TRUE.equals(role.getEnabled()))
            .map(Role::getCode)
            .toList();
    }

    public record Authenticated(String accessToken, User user, List<String> roles, UUID sessionId) {
        public Authenticated(String accessToken, User user, List<String> roles) {
            this(accessToken, user, roles, null);
        }
    }
}
