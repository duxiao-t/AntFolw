package com.antflow.org;

import com.antflow.auth.PrincipalHolder;
import com.antflow.engine.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * P4a：**公司归属解析**，一处实现、四条写路径共用（流程发起、无流程提交、草稿创建、草稿转正式）。
 *
 * <p>为什么按**身份**分档而不是"有没有部门"：只要能拿到一个 `departmentId` 参数当身份，
 * 无部门的普通账号直接调 API 就能把自己的单记到任意公司名下。前端不给公司列表**不是安全边界**。
 * 所以身份一律取自 {@link PrincipalHolder}，请求里的 `companyId` 只是"无部门的全局管理员"的选择。
 *
 * <table>
 *   <tr><td>有部门</td><td>按身份里的部门推导；请求若给了**不一致**的公司 → 拒绝</td></tr>
 *   <tr><td>无部门 + 全局管理员</td><td>**必须**显式指定一个存在的公司</td></tr>
 *   <tr><td>无部门 + 非管理员</td><td>拒绝创建</td></tr>
 * </table>
 */
@Component
@RequiredArgsConstructor
public class CompanyAttribution {

    private final DepartmentMapper departmentMapper;
    private final CompanyMapper companyMapper;

    /**
     * 发起 / 提交时的归属解析（**草稿转正式请用 {@link #forDraft}**）。
     *
     * @param explicitCompanyId 请求里显式指定的公司；只有无部门的全局管理员该用它
     */
    public Long resolve(Long explicitCompanyId) {
        PrincipalHolder.Principal principal = PrincipalHolder.current()
            .orElseThrow(() -> new AccessDeniedException("no principal"));

        Long departmentId = principal.departmentId();
        if (departmentId != null) {
            Department department = departmentMapper.selectById(departmentId);
            Long companyId = department == null ? null : department.getCompanyId();
            if (companyId == null) {
                throw new BizException("BAD_COMPANY", "所在部门不存在或未归属公司，无法确定归属");
            }
            // 有部门的调用者**只能**归属到自己公司：否则任何人都能把单记到别家公司名下。
            if (explicitCompanyId != null && !explicitCompanyId.equals(companyId)) {
                throw new BizException("BAD_COMPANY", "只能归属到本人所在部门所属的公司");
            }
            return companyId;
        }

        if (principal.isAdmin()) {
            if (explicitCompanyId == null) {
                throw new BizException("BAD_COMPANY", "无部门的账号发起时必须指定公司");
            }
            requireCompanyExists(explicitCompanyId);
            return explicitCompanyId;
        }
        throw new BizException("BAD_COMPANY", "当前账号没有部门，无法确定归属，请联系管理员");
    }

    /**
     * 草稿转正式 / 草稿改名归属校验：**只认草稿行里的快照，绝不按当前部门重新解析**。
     * 否则"A 公司建草稿 → 调到 B 公司 → 提交"会被改归属，或被莫名拒绝。
     *
     * @param storedCompanyId 草稿行自己的 {@code company_id}（**不是请求里的那个**）
     *
     * <p>历史草稿（V54 之前建的）没有归属快照：按已定口径**保留草稿、阻断转正式**，
     * 提交时**绝不猜公司**。
     */
    public Long forDraft(Long storedCompanyId, Long explicitCompanyId) {
        if (storedCompanyId == null) {
            throw new BizException("COMPANY_PENDING",
                "该草稿的归属尚未确认，暂时无法提交；请联系管理员确认归属后再提交");
        }
        if (explicitCompanyId != null && !explicitCompanyId.equals(storedCompanyId)) {
            throw new BizException("BAD_COMPANY", "草稿的归属已固定，不能改到别的公司");
        }
        return storedCompanyId;
    }

    private void requireCompanyExists(Long companyId) {
        if (companyMapper.selectById(companyId) == null) {
            throw new BizException("BAD_COMPANY", "指定的公司不存在：" + companyId);
        }
    }
}
