import { TreeSelect } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request, useModel } from '@umijs/max';
import { useEffect, useState } from 'react';
import { isEmptyValue } from '../../registry/displayConditions';
import type { FieldType } from '../../registry/types';
import { currentPickerDefault } from './pickerDefaults';

export const DeptPickerField: FieldType = {
  type: 'dept_picker',
  label: '部门选择',
  icon: 'apartment',
  defaultProps: {
    required: false,
    multiple: false,
    companyId: 1,
    selectableParent: true,
    leafOnly: false,
  },
  Component: ({ node, mode, value, onChange }) => {
    const [companyId] = useState<number>(node.props?.companyId ?? 1);
    const { data, isFetching } = useQuery({
      queryKey: ['departments', companyId],
      queryFn: () => request<any[]>('/api/departments', { params: { companyId } }),
    });

    const shouldDisable = (children: any[]) =>
      (node.props?.leafOnly || node.props?.selectableParent === false) &&
      children.length > 0;
    const toTreeNode = (item: any, rows: any[]): any => {
      const children = buildChildren(item.id, rows);
      return {
        value: item.id,
        title: item.name,
        disabled: shouldDisable(children),
        children,
      };
    };
    const toTree = (rows: any[]): any[] => {
      const rootDeptId = node.props?.rootDeptId;
      if (rootDeptId) {
        const root = rows.find((row) => row.id === rootDeptId);
        return root ? [toTreeNode(root, rows)] : [];
      }
      return rows
        .filter((r) => !r.parentId)
        .map((root) => toTreeNode(root, rows));
    };
    const buildChildren = (parentId: any, rows: any[]): any[] =>
      rows
        .filter((r) => r.parentId === parentId)
        .map((r) => toTreeNode(r, rows));

    const treeData = toTree(data ?? []);
    // 同 UserPickerField：默认值是**写进表单的值**，不是显示兜底（见 pickerDefaults 注释）。
    const currentUser = useModel('@@initialState')?.initialState?.currentUser as
      | { id?: number; departmentId?: number }
      | undefined;
    const defaultValue = currentPickerDefault(node, currentUser);
    useEffect(() => {
      if (
        mode !== 'runtime-fill' ||
        defaultValue === undefined ||
        !isEmptyValue(value)
      ) {
        return;
      }
      onChange?.(defaultValue);
    }, [mode, defaultValue, value, onChange]);

    return (
      <div data-field-id={node.id}>
        <div style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </div>
        <TreeSelect
          disabled={mode !== 'runtime-fill'}
          treeData={treeData}
          loading={isFetching}
          multiple={!!node.props?.multiple}
          value={value}
          onChange={(v) => onChange?.(v)}
          treeDefaultExpandAll
          showCheckedStrategy={node.props?.multiple ? 'SHOW_ALL' : 'SHOW_PARENT'}
          placeholder={node.props?.placeholder}
          maxCount={node.props?.maxCount}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>标签</div>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>
        <input type="checkbox" checked={!!node.props?.multiple}
          onChange={(e) => onChange({ ...node, props: { ...node.props, multiple: e.target.checked } })} />
        {' '}允许多选
      </label>
    </div>
  ),
};
