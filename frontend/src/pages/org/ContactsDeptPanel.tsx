import { Button, Dropdown, Input, Tree } from 'antd';
import {
  ArrowDownOutlined, ArrowUpOutlined, DeleteOutlined, EditOutlined, MoreOutlined,
  PlusOutlined, SearchOutlined, TeamOutlined,
} from '@ant-design/icons';
import { useEffect, useMemo, useState } from 'react';
import type { DataNode } from 'antd/es/tree';
import type { Dept } from './Contacts.types';
import { collectTreeKeys, filterDepartmentTree } from './Contacts.utils';
import type { DeptNodeActions } from './useContactsDepartments';

/**
 * 左栏：搜索框 + 新建一级部门 + 部门树。
 *
 * 过滤关键词与右栏的跨部门搜人是**同一个词**；部门一个都没匹配上时给一句提示，
 * 免得以为页面坏了（右边的人员结果可能已经有了）。
 */
export function ContactsDeptPanel({
  departments,
  treeData,
  search,
  onSearchChange,
  canCreateRoot,
  onAddRoot,
  selectedDeptId,
  onSelectDept,
  onDrop,
  actions,
}: {
  departments: Dept[];
  treeData: DataNode[];
  search: string;
  onSearchChange: (value: string) => void;
  canCreateRoot: boolean;
  onAddRoot: () => void;
  selectedDeptId: number | null;
  onSelectDept: (deptId: number) => void;
  onDrop: (info: any) => void;
  actions: DeptNodeActions;
}) {
  const [expandedKeys, setExpandedKeys] = useState<React.Key[]>([]);
  const filteredTree = useMemo(() => filterDepartmentTree(treeData, search), [treeData, search]);

  useEffect(() => {
    if (search.trim()) setExpandedKeys(collectTreeKeys(filteredTree));
  }, [filteredTree, search]);

  const titleRender = (node: DataNode) => {
    const currentDept = departments.find(x => x.id === node.key);
    const canManage = !!currentDept?.canManageDepartment;
    const siblings = departments.filter(x => (x.parentId ?? null) === (currentDept?.parentId ?? null));
    const siblingIndex = siblings.findIndex(x => x.id === node.key);
    const canMoveUp = siblingIndex > 0;
    const canMoveDown = siblingIndex >= 0 && siblingIndex < siblings.length - 1;
    const items = [
      { key: 'add', label: '添加子部门', icon: <PlusOutlined />,
        disabled: !canManage,
        onClick: () => actions.onAddChild(node.key as number) },
      { key: 'edit', label: '修改名称', icon: <EditOutlined />,
        disabled: !canManage,
        onClick: () => actions.onRename(node.key as number) },
      { key: 'leader', label: '设置负责人', icon: <TeamOutlined />,
        disabled: !canManage,
        onClick: () => actions.onSetLeader(node.key as number) },
      { key: 'up', label: '上移', icon: <ArrowUpOutlined />, disabled: !canManage || !canMoveUp,
        onClick: () => actions.onMoveUp(node.key as number) },
      { key: 'down', label: '下移', icon: <ArrowDownOutlined />, disabled: !canManage || !canMoveDown,
        onClick: () => actions.onMoveDown(node.key as number) },
      { key: 'del', label: '删除部门', icon: <DeleteOutlined />, danger: true, disabled: !canManage,
        onClick: () => actions.onRemove(node.key as number) },
      { type: 'divider' as const },
      { key: 'dept-id', label: `部门ID：${node.key}`, disabled: true },
    ];
    return (
      <div className="ct-tree-node" onClick={() => { if (!currentDept?.contextOnly) onSelectDept(node.key as number); }}>
        <span className="ct-tree-node__name">{node.title as string}</span>
        <Dropdown menu={{ items }} trigger={['click']}>
          <Button type="text" size="small" icon={<MoreOutlined />} onClick={e => e.stopPropagation()} />
        </Dropdown>
      </div>
    );
  };

  return (
    <aside className="ct-left">
      <div className="ct-left-top">
        <Input prefix={<SearchOutlined />} placeholder="搜索部门或人员" allowClear
          value={search} onChange={e => onSearchChange(e.target.value)} />
        {/* 这里只是"够不够格看到按钮"的近似：建一级部门后端还要求该能力具 ALL 数据范围
            （DepartmentController 的 requireAllDataScope），而前端看不到范围，越范围时由后端返回 403。 */}
        <Button aria-label="新建一级部门" icon={<PlusOutlined />} disabled={!canCreateRoot}
          onClick={onAddRoot} />
      </div>
      <div className="ct-tree-wrap">
        <Tree
          showIcon
          treeData={filteredTree}
          expandedKeys={expandedKeys}
          onExpand={keys => setExpandedKeys(keys)}
          onSelect={keys => {
            const id = keys[0] as number | undefined;
            if (id && !departments.find((department) => department.id === id)?.contextOnly) {
              onSelectDept(id);
            }
          }}
          selectedKeys={selectedDeptId ? [selectedDeptId] : []}
          titleRender={titleRender}
          draggable={{
            icon: false,
            nodeDraggable: (node) => !!(departments.find((department) => department.id === node.key)?.canManageDepartment),
          }}
          blockNode
          onDrop={onDrop}
        />
        {search.trim() && filteredTree.length === 0 && (
          <div className="ct-tree-empty">没有匹配的部门——右侧是人员搜索结果</div>
        )}
      </div>
    </aside>
  );
}
