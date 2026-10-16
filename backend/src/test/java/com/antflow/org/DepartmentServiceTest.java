package com.antflow.org;

import com.antflow.engine.BizException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DepartmentServiceTest {
    @Test
    void crossCompanyParentAndRootSortAreRejectedBeforeWriting() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentService service = service(mapper, Mockito.mock(DepartmentLeaderMapper.class));
        Department first = dept(2L, null, "d_2", "甲公司");
        Department other = dept(3L, null, "d_3", "乙公司");
        other.setCompanyId(2L);
        when(mapper.selectById(2L)).thenReturn(first);
        when(mapper.selectById(3L)).thenReturn(other);

        assertEquals("DEPARTMENT_COMPANY_MISMATCH",
            assertThrows(BizException.class, () -> service.move(2L, 3L)).getCode());
        assertEquals("DEPARTMENT_COMPANY_MISMATCH",
            assertThrows(BizException.class, () -> service.movePosition(2L, 3L, "AFTER")).getCode());
        Department child = dept(null, 3L, null, "新部门");
        assertEquals("DEPARTMENT_COMPANY_MISMATCH",
            assertThrows(BizException.class, () -> service.create(child)).getCode());
        Mockito.verify(mapper, Mockito.never()).insert(any(Department.class));
        Mockito.verify(mapper, Mockito.never()).updateById(any(Department.class));
    }

    @Test
    void moveRejectsSelfAndMixedCompanySubtree() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentService service = service(mapper, Mockito.mock(DepartmentLeaderMapper.class));
        Department root = dept(2L, null, "d_2", "甲公司");
        Department child = dept(3L, 2L, "d_2.d_3", "错误归属");
        child.setCompanyId(2L);
        when(mapper.selectById(2L)).thenReturn(root);
        when(mapper.subtree("d_2")).thenReturn(List.of(child));

        assertEquals("BAD_MOVE", assertThrows(BizException.class, () -> service.move(2L, 2L)).getCode());
        assertEquals("DEPARTMENT_COMPANY_MISMATCH",
            assertThrows(BizException.class, () -> service.move(2L, null)).getCode());
        Mockito.verify(mapper, Mockito.never()).updateById(any(Department.class));
    }

    @Test
    void createUsesStableIdBasedPathSoChineseOrDuplicateNamesDoNotBreakLtree() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        when(leaderMapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectList(any())).thenReturn(List.of());
        DepartmentService service = service(mapper, leaderMapper);

        Department parent = dept(2L, null, "d_2", "研发中心");
        when(mapper.selectById(2L)).thenReturn(parent);
        Mockito.doAnswer(invocation -> {
            Department inserted = invocation.getArgument(0);
            inserted.setId(12L);
            return 1;
        }).when(mapper).insert(any(Department.class));

        Department created = new Department();
        created.setCompanyId(1L);
        created.setParentId(2L);
        created.setName("研发中心");
        created.setLeaderIds(null);

        service.create(created);

        assertEquals("d_2.d_12", created.getPath());
    }

    @Test
    void pathToRootFollowsParentIdsWhenMaterializedPathsAreDuplicated() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        when(leaderMapper.selectList(any())).thenReturn(List.of());
        DepartmentService service = service(mapper, leaderMapper);

        Department root = dept(2L, null, "tech", "Tech");
        Department devops = dept(4L, 2L, "tech.devops", "DevOps");
        Department duplicateNameA = dept(8L, 4L, "tech.devops.123", "123-A");
        Department duplicateNameB = dept(10L, 4L, "tech.devops.123", "123-B");
        Department child = dept(11L, 10L, "tech.devops.123.822", "822");

        when(mapper.selectById(11L)).thenReturn(child);
        when(mapper.selectById(10L)).thenReturn(duplicateNameB);
        when(mapper.selectById(4L)).thenReturn(devops);
        when(mapper.selectById(2L)).thenReturn(root);

        List<Department> path = service.pathToRoot(11L);

        assertEquals(List.of("Tech", "DevOps", "123-B", "822"),
            path.stream().map(Department::getName).toList());
    }

    @Test
    void setLeadersStoresMultipleLeadersAndKeepsFirstLeaderForCompatibility() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        DepartmentService service = service(mapper, leaderMapper);
        Department department = dept(4L, 2L, "d_2.d_4", "DevOps");
        when(mapper.selectById(4L)).thenReturn(department);

        Department updated = service.setLeaders(4L, List.of(5L, 6L, 5L));

        assertEquals(List.of(5L, 6L), updated.getLeaderIds());
        assertEquals(5L, updated.getLeaderId());
        verify(leaderMapper).insert(new DepartmentLeader(4L, 5L));
        verify(leaderMapper).insert(new DepartmentLeader(4L, 6L));
        verify(mapper).update(Mockito.isNull(),
            Mockito.<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Department>>any());
    }

    @Test
    void moveOrderSwapsWithPreviousSibling() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        DepartmentService service = service(mapper, leaderMapper);
        Department first = dept(2L, null, "d_2", "Tech");
        first.setSortOrder(1);
        Department second = dept(3L, null, "d_3", "HR");
        second.setSortOrder(2);
        when(mapper.selectById(3L)).thenReturn(second);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        service.moveOrder(3L, "UP");

        assertEquals(2, first.getSortOrder());
        assertEquals(1, second.getSortOrder());
        verify(mapper).updateById(first);
        verify(mapper).updateById(second);
    }

    @Test
    void moveOrderKeepsTopSiblingInPlace() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        DepartmentService service = service(mapper, leaderMapper);
        Department first = dept(2L, null, "d_2", "Tech");
        first.setSortOrder(1);
        Department second = dept(3L, null, "d_3", "HR");
        second.setSortOrder(2);
        when(mapper.selectById(2L)).thenReturn(first);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));

        Department result = service.moveOrder(2L, "UP");

        assertEquals(1, result.getSortOrder());
        Mockito.verify(mapper, Mockito.never()).updateById(first);
        Mockito.verify(mapper, Mockito.never()).updateById(second);
    }

    @Test
    void movePositionPlacesDraggedDepartmentAfterSameParentTarget() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        when(leaderMapper.selectList(any())).thenReturn(List.of());
        DepartmentService service = service(mapper, leaderMapper);
        Department first = dept(2L, null, "d_2", "Tech");
        first.setSortOrder(1);
        Department second = dept(3L, null, "d_3", "HR");
        second.setSortOrder(2);
        Department third = dept(4L, null, "d_4", "Ops");
        third.setSortOrder(3);
        when(mapper.selectById(2L)).thenReturn(first);
        when(mapper.selectById(4L)).thenReturn(third);
        when(mapper.selectList(any())).thenReturn(List.of(first, second, third));

        service.movePosition(2L, 4L, "AFTER");

        assertEquals(3, first.getSortOrder());
        assertEquals(1, second.getSortOrder());
        assertEquals(2, third.getSortOrder());
        verify(mapper).updateById(first);
        verify(mapper).updateById(second);
        verify(mapper).updateById(third);
    }

    @Test
    void deleteRejectsDepartmentThatStillHasMembers() {
        DepartmentMapper mapper = Mockito.mock(DepartmentMapper.class);
        DepartmentLeaderMapper leaderMapper = Mockito.mock(DepartmentLeaderMapper.class);
        DepartmentService service = service(mapper, leaderMapper);
        Department department = dept(4L, null, "d_4", "Operations");
        when(mapper.selectById(4L)).thenReturn(department);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.countUsers(4L)).thenReturn(2L);

        BizException error = assertThrows(BizException.class, () -> service.delete(4L));

        assertEquals("HAS_USERS", error.getCode());
        assertEquals("部门下仍有成员，请先移动或删除成员", error.getMessage());
        Mockito.verify(mapper, Mockito.never()).deleteById(4L);
    }
    private static DepartmentService service(DepartmentMapper mapper, DepartmentLeaderMapper leaders) {
        CompanyMapper companies = Mockito.mock(CompanyMapper.class);
        when(companies.selectById(1L)).thenReturn(new Company());
        UserMapper users = Mockito.mock(UserMapper.class);
        when(users.selectById(Mockito.anyLong())).thenAnswer(call -> {
            User user = new User();
            user.setId(call.getArgument(0));
            user.setDeptId(4L);
            return user;
        });
        return new DepartmentService(mapper, leaders, companies, users);
    }

    private static Department dept(Long id, Long parentId, String path, String name) {
        Department d = new Department();
        d.setId(id);
        d.setParentId(parentId);
        d.setPath(path);
        d.setName(name);
        d.setCompanyId(1L);
        return d;
    }
}
