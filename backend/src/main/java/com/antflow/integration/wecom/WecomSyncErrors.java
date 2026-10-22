package com.antflow.integration.wecom;

import com.antflow.integration.wecom.WecomClient.WecomUser;
import com.antflow.integration.wecom.WecomMappingRules.SyncUserException;
import java.util.List;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 同步错误 → 用户可读文案，以及错误列表的截断策略。
 *
 * <p>为什么独立：这是唯一一处"把底层异常翻译成人话"的地方，而它的调用方分散在
 * {@code WecomService.run}（批次失败）和成员写入路径上。混在 1300 行的服务里时，
 * 改一句提示语要翻三层 catch。
 *
 * <p>两条约定：
 * <ol>
 *   <li>**文案不带技术细节**：约束名映射成人话（如"企微工号与本地账号冲突"），
 *       原始 SQL 错误只进日志，不进界面。</li>
 *   <li>**未知异常兜底**：宁可给一句笼统的"写入失败"，也不要把堆栈或约束名丢给用户。</li>
 * </ol>
 */
final class WecomSyncErrors {

    /** 一次同步最多留几条错误摘要：太多会刷屏，也会把 error_summary 撑大。 */
    private static final int MAX_SUMMARIES = 10;

    private WecomSyncErrors() { }

    /** 追加一条错误：超上限就丢弃，过长则截断——保证入库的 JSON 体积可控。 */
    static void addError(List<String> errors, String value) {
        if (errors.size() < MAX_SUMMARIES) errors.add(value.length() > 160
            ? value.substring(0, 160) : value);
    }

    /** 整轮失败的原因：只有"业务异常"能直接透出文案，其余一律换成可操作的建议。 */
    static String fatalError(RuntimeException exception) {
        if (exception instanceof WecomClient.WecomApiException
            || exception instanceof SyncUserException) return exception.getMessage();
        return "同步任务执行失败，请检查配置后重试";
    }

    /** 单个成员失败：带上成员标识，否则用户不知道该找谁。 */
    static String memberError(WecomUser user, RuntimeException exception) {
        return "成员 " + WecomMappingRules.displayName(user) + "（工号 " + user.userId() + "）同步失败："
            + userError(exception);
    }

    /**
     * 成员写入异常的分类。包内可见：{@code WecomServiceRulesTest} 直接断言这段映射。
     *
     * <p>顺序有意义：具体异常放前面，{@code DataAccessException} 兜住其余数据库错误。
     */
    static String userError(RuntimeException exception) {
        return exception instanceof SyncUserException ? exception.getMessage()
            : exception instanceof WecomClient.WecomApiException ? exception.getMessage()
            : exception instanceof DataIntegrityViolationException ? integrityError(exception)
            : exception instanceof DataAccessException ? "本地成员数据写入失败，请检查字段长度或数据库约束"
            : "本地成员数据写入失败";
    }

    /** 唯一约束冲突的具体原因：把数据库约束名翻成人话，找不到就退回笼统描述。 */
    private static String integrityError(RuntimeException exception) {
        String detail = String.valueOf(NestedExceptionUtils.getMostSpecificCause(exception).getMessage());
        if (detail.contains("t_user_username_key")) return "企微工号与本地账号冲突";
        if (detail.contains("uk_user_employee_no")) return "企微工号与本地工号冲突";
        if (detail.contains("t_user_dept_id_fkey")) return "所属部门映射无效";
        if (detail.contains("value too long")) return "成员字段长度超过本地限制";
        return "本地成员数据违反唯一性或关联约束";
    }
}
