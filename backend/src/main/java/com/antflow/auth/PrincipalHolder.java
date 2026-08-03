package com.antflow.auth;

import java.util.List;
import java.util.Optional;

public final class PrincipalHolder {
    public record Principal(long userId, String username, List<String> roles) {}

    private static final ThreadLocal<Principal> CTX = new ThreadLocal<>();

    private PrincipalHolder() {}

    public static void set(Principal p) { CTX.set(p); }
    public static void clear() { CTX.remove(); }
    public static Optional<Principal> current() { return Optional.ofNullable(CTX.get()); }
}
