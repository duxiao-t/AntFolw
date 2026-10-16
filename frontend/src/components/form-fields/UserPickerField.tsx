import { Space, Checkbox, InputNumber, Input, Typography } from 'antd';
import { PanelField } from './panelField';
import { AssigneePicker } from '../AssigneePicker';
import { Select, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request, useModel } from '@umijs/max';
import { useEffect, useState } from 'react';
import { isEmptyValue } from '../../registry/displayConditions';
import type { FieldType } from '../../registry/types';
import { currentPickerDefault } from './pickerDefaults';

export const UserPickerField: FieldType = {
  type: 'user_picker',
  label: '用户选择',
  icon: 'user',
  defaultProps: { required: false, multiple: false, scopeType: 'all' },
  ConfigPanel: ({ node, updateProps }) => {
    const props = node.props ?? {};
    return (
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        <Checkbox
          checked={!!props.multiple}
          onChange={(event) => updateProps({ multiple: event.target.checked })}
        >
          允许多选
        </Checkbox>
        <PanelField label="最多选择人数">
          <InputNumber
            min={1}
            disabled={!props.multiple}
            style={{ width: '100%' }}
            value={props.maxCount}
            onChange={(value) => updateProps({ maxCount: value })}
          />
        </PanelField>
        <Checkbox
          checked={props.defaultToCurrent === true}
          onChange={(event) =>
            updateProps({ defaultToCurrent: event.target.checked })
          }
        >
          默认填充当前用户
        </Checkbox>
        <UserPickerScopeFields props={props} updateProps={updateProps} />
      </Space>
    );
  },
  Component: ({ node, mode, value, onChange }) => {
    const [kw, setKw] = useState('');
    // 一次只用一个维度：指定部门（含下级）/ 指定职位 / 部门领导 / 指定人员 / 全部。
    // 后端把这些条件当作相互独立的收窄，所以将来要组合也不必改这里。
    const scopeType = node.props?.scopeType;
    // 始终带上范围类型：空名单会被序列化丢掉，只有 scopeType 能让服务端分清"没配范围"和
    // "配了指定人员但名单是空的"（后者应当零候选，不能退化成全员）。
    const scopeParams: Record<string, unknown> = { scopeType };
    if (scopeType === 'department' && node.props?.scopeDeptId) {
      scopeParams.deptId = node.props.scopeDeptId;
      scopeParams.includeDescendants = true;
    } else if (scopeType === 'position' && node.props?.scopePosition?.trim()) {
      scopeParams.position = node.props.scopePosition.trim();
    } else if (scopeType === 'leader') {
      // 职务口径（含「部长」）留在服务端，前端只说明是哪个预设。
      scopeParams.leaderOnly = true;
    } else if (scopeType === 'user' && node.props?.scopeUserIds?.length) {
      // 逗号串而不是数组：axios 会把数组序列化成 `userIds[]=3`，裸方括号会被 Tomcat 按
      // RFC 7230 拒掉 → 请求还没进 Spring 就 400（移动端 files.api.ts 早就是 join(',') 的写法）。
      scopeParams.userIds = node.props.scopeUserIds.join(',');
    }
    const scopeKey = JSON.stringify(scopeParams);
    const { data, isFetching } = useQuery({
      queryKey: ['users', 'field', kw, scopeKey],
      queryFn: () =>
        // 走选择器专用端点（只要求"已进管理端"）：/api/users 还要 org:user:read，
        // 于是没有该能力的普通提交人在运行时填表单时拉不到任何候选。
        request<any[]>('/api/pickers/users', { params: { keyword: kw, ...scopeParams } }),
    });
    const multi = !!node.props?.multiple;
    // 「默认填充当前用户」= 真的把值写进表单（不是只做个显示兜底），否则用户不改它、
    // 直接提交时这个字段是空的。只在运行态、且字段还空着时写一次。
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
        <Select
          mode={multi ? 'multiple' : undefined}
          showSearch
          disabled={mode !== 'runtime-fill'}
          value={value}
          loading={isFetching}
          onSearch={setKw}
          onChange={(v) => onChange?.(v)}
          filterOption={false}
          // 列表端点不再下发登录账号（那是唯一能被关键字枚举的入口），所以没有 username 可退，
          // 用**工号**补区分度：同名的两个人（张三 / 张三）只看姓名分不出来。
          options={(data ?? []).map((u: any) => ({
            value: u.id,
            label: u.employeeNo
              ? `${u.displayName ?? `#${u.id}`}(${u.employeeNo})`
              : (u.displayName ?? `#${u.id}`),
          }))}
          notFoundContent={isFetching ? <Spin size="small" /> : '无匹配用户'}
          placeholder={node.props?.placeholder}
          maxCount={node.props?.maxCount}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
};

function UserPickerScopeFields({
  props,
  updateProps,
}: {
  props: Record<string, any>;
  updateProps: (patch: Record<string, any>) => void;
}) {
  const scopeType = props.scopeType ?? 'all';
  const departments = useQuery({
    queryKey: ['picker-departments'],
    queryFn: () => request<any[]>('/api/pickers/departments'),
    // 只有选了「指定部门」才需要这份清单，不选就不请求。
    enabled: scopeType === 'department',
  });
  // 切换维度时清掉其它维度的值，避免残留一个用不上的配置。
  const switchTo = (next: string) => updateProps({
    scopeType: next,
    scopeDeptId: next === 'department' ? props.scopeDeptId : undefined,
    scopePosition: next === 'position' ? props.scopePosition : undefined,
    scopeUserIds: next === 'user' ? props.scopeUserIds : undefined,
  });
  return (
    <>
      <PanelField label="选择范围">
        <Select
          value={scopeType}
          options={[
            { label: '全部用户', value: 'all' },
            { label: '指定部门（含下级）', value: 'department' },
            { label: '指定职位', value: 'position' },
            { label: '部门领导', value: 'leader' },
            { label: '指定人员', value: 'user' },
          ]}
          onChange={switchTo}
        />
      </PanelField>
      {scopeType === 'department' && (
        <PanelField label="限定部门（含所有下级）">
          <Select
            showSearch
            allowClear
            style={{ width: '100%' }}
            placeholder="选择部门"
            loading={departments.isFetching}
            value={props.scopeDeptId}
            options={(departments.data ?? []).map((department: any) => ({
              value: department.id,
              label: department.name,
            }))}
            onChange={(value) => updateProps({ scopeDeptId: value })}
          />
        </PanelField>
      )}
      {scopeType === 'position' && (
        <PanelField label="职务包含">
          <Input
            allowClear
            placeholder="例如：工程师"
            value={props.scopePosition}
            onChange={(event) => updateProps({ scopePosition: event.target.value })}
          />
        </PanelField>
      )}
      {scopeType === 'user' && (
        <PanelField label="限定人员（候选只限这些人）">
          <AssigneePicker
            mode="user"
            value={props.scopeUserIds ?? []}
            onChange={(ids) => updateProps({ scopeUserIds: ids })}
          />
        </PanelField>
      )}
      <Typography.Text type="secondary">
        {scopeType === 'leader'
          ? '「部门领导」即职务带「部长」的人员。'
          : '候选人的最终结果还会按你的数据范围收窄。'}
      </Typography.Text>
    </>
  );
}
