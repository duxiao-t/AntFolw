package com.antflow.mobile.workflow;

import com.antflow.org.Department;
import com.antflow.org.DepartmentMapper;
import com.antflow.org.User;
import com.antflow.org.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MobileOrgService {
    private static final int SEARCH_LIMIT = 20;

    private final UserMapper userMapper;
    private final DepartmentMapper departmentMapper;

    public List<MobilePickerUserDto> searchUsers(String keyword) {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.select("id", "username", "display_name");
        String trimmedKeyword = normalizeKeyword(keyword);
        if (!trimmedKeyword.isEmpty()) {
            query.and(wrapper -> wrapper.like("username", trimmedKeyword)
                .or()
                .like("display_name", trimmedKeyword));
        }
        query.orderByAsc("display_name").last("LIMIT " + SEARCH_LIMIT);
        return userMapper.selectList(query).stream()
            .map(user -> new MobilePickerUserDto(user.getId(), user.getUsername(), user.getDisplayName()))
            .toList();
    }

    public List<MobilePickerDepartmentDto> searchDepartments(String keyword) {
        QueryWrapper<Department> query = new QueryWrapper<>();
        query.select("id", "name");
        String trimmedKeyword = normalizeKeyword(keyword);
        if (!trimmedKeyword.isEmpty()) {
            query.like("name", trimmedKeyword);
        }
        query.orderByAsc("name").last("LIMIT " + SEARCH_LIMIT);
        return departmentMapper.selectList(query).stream()
            .map(department -> new MobilePickerDepartmentDto(department.getId(), department.getName()))
            .toList();
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.trim();
    }
}
