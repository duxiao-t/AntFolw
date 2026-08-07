import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { nanoid } from '@reduxjs/toolkit';
import { Alert, Button, Form, Input, Radio, Select, Space } from 'antd';
import type { TreeNode } from '../types';
import { useProcessDesignerStore } from '../useProcessDesignerStore';

type FieldDef = { id: string; label: string; type: string };
type HeaderRow = { id: string; key: string; value: string };
type ParameterRow = {
  id: string;
  key: string;
  source: 'FIXED' | 'FIELD';
  value?: string;
  fieldId?: string;
};

export function TriggerNodeConfig({
  node,
  formFields,
}: {
  node: TreeNode;
  formFields: FieldDef[];
}) {
  const updateProps = useProcessDesignerStore((state) => state.updateProps);
  const updateName = useProcessDesignerStore((state) => state.updateName);
  const props = node.props ?? {};
  const set = (patch: Record<string, any>) =>
    updateProps(node.id, { ...props, ...patch });
  const headers: HeaderRow[] = props.headers ?? [];
  const parameters: ParameterRow[] = props.parameters ?? [];
  const patchHeader = (id: string, patch: Partial<HeaderRow>) =>
    set({
      headers: headers.map((row) =>
        row.id === id ? { ...row, ...patch } : row,
      ),
    });
  const patchParameter = (id: string, patch: Partial<ParameterRow>) =>
    set({
      parameters: parameters.map((row) =>
        row.id === id ? { ...row, ...patch } : row,
      ),
    });

  return (
    <Form layout="vertical" className="pt-config-form">
      <Form.Item label="节点名称">
        <Input
          value={node.name ?? ''}
          onChange={(event) => updateName(node.id, event.target.value)}
        />
      </Form.Item>
      <Space.Compact block>
        <Select
          value={props.method ?? 'POST'}
          style={{ width: 110 }}
          onChange={(value) => set({ method: value })}
          options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((value) => ({
            value,
            label: value,
          }))}
        />
        <Input
          value={props.url ?? ''}
          placeholder="https://api.example.com/hooks/approval"
          onChange={(event) => set({ url: event.target.value })}
        />
      </Space.Compact>
      <Form.Item label="内容类型" style={{ marginTop: 20 }}>
        <Select
          value={props.contentType ?? 'application/json'}
          onChange={(value) => set({ contentType: value })}
          options={[
            { value: 'application/json', label: 'JSON' },
            { value: 'application/x-www-form-urlencoded', label: '表单编码' },
          ]}
        />
      </Form.Item>
      <Form.Item
        label="HMAC 签名密钥"
        extra="请求携带 X-AntFlow-Signature 与唯一投递 ID。"
      >
        <Input.Password
          value={props.secret ?? ''}
          placeholder="至少 8 个字符"
          onChange={(event) => set({ secret: event.target.value })}
        />
      </Form.Item>

      <ConfigRows
        title="请求头"
        onAdd={() =>
          set({ headers: [...headers, { id: nanoid(), key: '', value: '' }] })
        }
      >
        {headers.map((row) => (
          <Space.Compact block key={row.id} className="pt-config-row">
            <Input
              value={row.key}
              placeholder="Header"
              onChange={(event) =>
                patchHeader(row.id, { key: event.target.value })
              }
            />
            <Input
              value={row.value}
              placeholder="值"
              onChange={(event) =>
                patchHeader(row.id, { value: event.target.value })
              }
            />
            <Button
              aria-label="删除请求头"
              icon={<DeleteOutlined />}
              onClick={() =>
                set({ headers: headers.filter((item) => item.id !== row.id) })
              }
            />
          </Space.Compact>
        ))}
      </ConfigRows>

      <ConfigRows
        title="请求参数"
        onAdd={() =>
          set({
            parameters: [
              ...parameters,
              { id: nanoid(), key: '', source: 'FIXED', value: '' },
            ],
          })
        }
      >
        {parameters.map((row) => (
          <div className="pt-parameter-row" key={row.id}>
            <Space.Compact block>
              <Input
                value={row.key}
                placeholder="参数名"
                onChange={(event) =>
                  patchParameter(row.id, { key: event.target.value })
                }
              />
              <Select
                value={row.source}
                style={{ width: 128 }}
                onChange={(value) => patchParameter(row.id, { source: value })}
                options={[
                  { value: 'FIXED', label: '固定值' },
                  { value: 'FIELD', label: '表单字段' },
                ]}
              />
              <Button
                aria-label="删除请求参数"
                icon={<DeleteOutlined />}
                onClick={() =>
                  set({
                    parameters: parameters.filter((item) => item.id !== row.id),
                  })
                }
              />
            </Space.Compact>
            {row.source === 'FIELD' ? (
              <Select
                showSearch={{ optionFilterProp: 'label' }}
                value={row.fieldId || undefined}
                placeholder="选择表单字段"
                style={{ width: '100%', marginTop: 8 }}
                onChange={(value) => patchParameter(row.id, { fieldId: value })}
                options={formFields.map((field) => ({
                  value: field.id,
                  label: field.label,
                }))}
              />
            ) : (
              <Input
                value={row.value ?? ''}
                placeholder="固定值"
                style={{ marginTop: 8 }}
                onChange={(event) =>
                  patchParameter(row.id, { value: event.target.value })
                }
              />
            )}
          </div>
        ))}
      </ConfigRows>

      <Form.Item label="流程继续方式">
        <Radio.Group
          block
          optionType="button"
          buttonStyle="solid"
          value={props.continueMode ?? 'ON_SUCCESS'}
          onChange={(event) => set({ continueMode: event.target.value })}
          options={[
            { value: 'ON_SUCCESS', label: '成功后继续' },
            { value: 'AFTER_SEND', label: '发送后继续' },
          ]}
        />
      </Form.Item>
      <Alert
        showIcon
        type="info"
        title="触发器只发送请求，不会执行脚本，也不会用响应内容修改表单。"
      />
    </Form>
  );
}

function ConfigRows({
  title,
  onAdd,
  children,
}: {
  title: string;
  onAdd: () => void;
  children: React.ReactNode;
}) {
  return (
    <section className="pt-config-section">
      <div className="pt-config-section__head">
        <strong>{title}</strong>
        <Button
          type="text"
          size="small"
          icon={<PlusOutlined />}
          onClick={onAdd}
        >
          添加
        </Button>
      </div>
      {children}
    </section>
  );
}
