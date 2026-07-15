package com.antflow.org;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {
    private final DepartmentMapper mapper;

    @Transactional
    public Department create(Department d) {
        if (d.getParentId() == null) {
            d.setPath(slugOf(d.getName()));
        } else {
            Department parent = mapper.selectById(d.getParentId());
            d.setPath(parent.getPath() + "." + slugOf(d.getName()));
        }
        mapper.insert(d);
        return d;
    }

    /**
     * Returns the root department of a company + all descendants via ltree subtree operator.
     */
    public List<Department> tree(long companyId) {
        Department root = mapper.selectOne(new QueryWrapper<Department>()
            .eq("company_id", companyId).isNull("parent_id"));
        if (root == null) return List.of();
        return mapper.subtree(root.getPath());
    }

    private String slugOf(String name) {
        return name.toLowerCase()
                   .replaceAll("[^a-z0-9]+", "_")
                   .replaceAll("^_|_$", "");
    }
}
