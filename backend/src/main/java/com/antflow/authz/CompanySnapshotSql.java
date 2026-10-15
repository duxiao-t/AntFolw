package com.antflow.authz;

/** Shared snapshot eligibility for dedicated owner/task SQL; company membership is checked separately. */
public final class CompanySnapshotSql {
    public static final String ADMIN = """
        EXISTS (SELECT 1 FROM t_user_role company_user_role
          JOIN t_role company_role ON company_role.id = company_user_role.role_id
          WHERE company_user_role.user_id = #{userId}
            AND company_role.code = 'admin' AND company_role.enabled = true)
        """;
    public static final String INSTANCE = """
        (pi.company_id IS NOT NULL AND pi.company_inferred = false)
        """;
    public static final String DATA = """
        (data.company_id IS NOT NULL AND data.company_inferred = false)
        """;
    public static final String INSTANCE_FOR_USER = "(" + ADMIN + " OR " + INSTANCE + ")";
    public static final String DATA_FOR_USER = "(" + ADMIN + " OR " + DATA + ")";

    private CompanySnapshotSql() { }
}
