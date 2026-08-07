package com.antflow.auth;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

public final class PrincipalHolder {
    public record Principal(long userId, String username, String displayName,
                            Set<String> roles, Set<String> permissions,
                            long authzVersion, Long departmentId, UUID sessionId) {
        public Principal(long userId, String username, List<String> roles) {
            this(userId, username, username, Set.copyOf(roles), Set.of(), 0L, null, null);
        }

        public boolean isAdmin() {
            return roles.contains("admin");
        }
    }

    private static final ThreadLocal<Principal> CTX = new ThreadLocal<>();

    private PrincipalHolder() {}

    public static void set(Principal p) { CTX.set(p); }
    public static void clear() { CTX.remove(); }
    public static Optional<Principal> current() { return Optional.ofNullable(CTX.get()); }
}
