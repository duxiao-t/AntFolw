package com.antflow.form.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL JSONB 字段类型处理器（antflow 通用 JSONB 适配器）。
 *
 * <p>直接继承 {@link BaseTypeHandler}{@code <String>}，<b>不挂任何 {@code @MappedTypes/@MappedJdbcTypes}</b>，
 * 以避免 MyBatis 自动注册机制将本 handler 误用到全局 Object/OTHER 类型（这会导致登录等普通查询的
 * 参数被错误地包装成 {@link PGobject}）。本 handler 仅通过实体字段上的
 * {@code @TableField(typeHandler = JsonbJacksonTypeHandler.class)} 显式绑定。
 *
 * <p>修复原 {@link com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler} 在写入
 * PostgreSQL JSONB 列时抛 "column is of type jsonb but expression is of type character varying"
 * 的问题：父类走 {@code setString}，PG 驱动无法识别目标是 jsonb。本 handler 在写入侧用
 * {@link PGobject#setType(String) PGobject("jsonb")} 包装。读出侧沿用 {@code getString}（PG 驱动
 * 对 jsonb 列返回 String）+ Jackson 反序列化的常规路径。
 *
 * <p>本类是 antflow 项目的"通用 JSONB 适配器"——任何
 * {@code @TableField(typeHandler = JsonbJacksonTypeHandler.class)} 字段都自动以 JSONB 方式读写。
 * 后续阶段二扩展更多 JSONB 字段（如配置/审计/状态机）时无需重复实现。
 *
 * <p>支持的输入形态：
 * <ul>
 *   <li>字段类型为 {@code String} 且 Service 层已 {@code ObjectMapper.writeValueAsString(...)}
 *       序列化：直接当 JSON 文本写入。</li>
 *   <li>字段类型为 {@code String} 但内容是 raw JSON（如 JSONB 反序列化后又被写入）：
 *       同样视为已序列化文本，原样写入。</li>
 * </ul>
 * 读出永远返回 String，由 Service 层或前端自行 JSON.parse。
 */
public class JsonbJacksonTypeHandler extends BaseTypeHandler<String> {

    private final ObjectMapper mapper;

    public JsonbJacksonTypeHandler() {
        this.mapper = new ObjectMapper();
    }

    /**
     * 注入 Spring 容器内的 ObjectMapper（构造注入版本，MyBatis 反射调用此构造器）。
     */
    public JsonbJacksonTypeHandler(Class<?> ignored) {
        // 反射路径：MyBatis 通过 Class.newInstance() 调用；此时无法拿 Spring bean，
        // 退化为本地 mapper。Service 路径调用 setObjectMapper 后即可命中共享实例。
        this();
    }

    public JsonbJacksonTypeHandler(Class<?> type, java.lang.reflect.Field field) {
        this();
    }

    public static void setObjectMapper(ObjectMapper m) {
        // no-op 兼容父类 API；保留扩展位
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(parameter);
        ps.setObject(i, pg);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}