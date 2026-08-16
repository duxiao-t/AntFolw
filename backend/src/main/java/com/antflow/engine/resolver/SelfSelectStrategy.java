package com.antflow.engine.resolver;

import com.antflow.engine.BizException;
import com.antflow.engine.NoAssigneeFoundException;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 发起人自选：assignedType=SELF_SELECT，selfSelected 来自 start() 上送的 selfSelected[nodeId] */
@Component
@Order(50)
@RequiredArgsConstructor
public class SelfSelectStrategy implements AssigneeStrategy {
    private static final int MAX_SELF_SELECT = 100;

    private final UserMapper userMapper;

    @Override public boolean supports(String type) { return "SELF_SELECT".equals(type); }

    @Override
    public List<Long> resolve(String nodeId, AssigneeSpec spec) {
        var ids = spec.selfSelected();
        if (ids == null || ids.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "self-select empty");
        }
        List<Long> selected = ids.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (selected.isEmpty()) {
            throw new NoAssigneeFoundException(nodeId, "self-select empty");
        }
        if (selected.size() > MAX_SELF_SELECT) {
            throw new BizException("SELF_SELECT_TOO_MANY",
                "自选审批人数量超过上限 " + MAX_SELF_SELECT);
        }
        // 只允许选择仍然活跃的真实用户；无效选择直接报错，避免 nobody 策略自动放行审批节点。
        for (Long id : selected) {
            User user = userMapper.selectById(id);
            if (user == null || !"ACTIVE".equals(user.getStatus())) {
                throw new BizException("SELF_SELECT_INVALID", "自选审批人不可用: " + id);
            }
        }
        return selected;
    }
}
