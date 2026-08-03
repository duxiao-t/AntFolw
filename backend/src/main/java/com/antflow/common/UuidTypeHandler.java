package com.antflow.common;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

/**
 * MyBatis handler for {@code java.util.UUID} <-> Postgres {@code uuid} column.
 *
 * <p>{@code BaseMapper.insert} cannot auto-resolve {@link UUID} because no
 * JDBC type maps directly. We bind explicitly to {@link JdbcType#OTHER} and
 * wrap with {@link PGobject} so the Postgres driver sends/receives the
 * canonical 8-byte hex form. Required by {@code t_mobile_file.id} (UUID PK).
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(java.sql.PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("uuid");
        pg.setValue(parameter.toString());
        ps.setObject(i, pg);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object o = rs.getObject(columnName);
        return o == null ? null : toUuid(o);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object o = rs.getObject(columnIndex);
        return o == null ? null : toUuid(o);
    }

    @Override
    public UUID getNullableResult(java.sql.CallableStatement cs, int columnIndex) throws SQLException {
        Object o = cs.getObject(columnIndex);
        return o == null ? null : toUuid(o);
    }

    private static UUID toUuid(Object o) {
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
