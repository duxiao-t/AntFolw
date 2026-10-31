import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { nanoid } from '@reduxjs/toolkit';
import { Alert, Button, Form, Input, Radio, Select, Space } from 'antd';
import { request } from '@umijs/max';
import { useState } from 'react';
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
/** 「测试发送」接口返回shape，用于替代 any 保持类型安全。 */
type TestResult = {
  successful?: boolean;
  warning?: string | null;
  statusCode?: number | null;
  error?: string | null;
  responseBody?: string | null;
  durationMs?: number | null;
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
  const bodyMode = props.bodyMode ?? 'PARAMETERS';
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<TestResult | null>(null);

  async function testSend() {
    setTesting(true);
    try {
      const sample: Record<string, unknown> = {};
      formFields.forEach((field) => {
        sample[field.id] = field.type === 'number' ? 0 : '示例';
      });
      const result = await request('/api/automation/webhook/test', {
        method: 'POST',
        data: { props, formData: sample },
      });
      setTestResult(result);
    } catch (error) {
      setTestResult({ successful: false, error: error instanceof Error ? error.message : '测试发送失败' });
    } finally {
      setTesting(false);
    }
  }
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
        label="请求体模式"
        extra="参数模式拼扁平 {key:value}；JSON 模板支持嵌套结构（企业微信/钉钉等）。占位符 ${字段ID}：放在引号内按文本插入（可拼固定文案），放在引号外按 JSON 值插入。"
      >
        <Radio.Group
          optionType="button"
          buttonStyle="solid"
          value={props.bodyMode ?? 'PARAMETERS'}
          onChange={(event) => set({ bodyMode: event.target.value })}
          options={[
            { value: 'PARAMETERS', label: '参数（扁平）' },
            { value: 'TEMPLATE', label: 'JSON 模板（嵌套）' },
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

      {bodyMode === 'PARAMETERS' ? (
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
      ) : (
        <Form.Item
          label="请求体模板"
          extra="占位符 ${字段ID} 在引号内按文本插入、引号外按 JSON 值插入；渲染结果必须是合法 JSON。"
        >
          <Input.TextArea
            rows={8}
            style={{ fontFamily: 'monospace' }}
            value={props.bodyTemplate ?? ''}
            placeholder={`{"msgtype":"text","text":{"content":"审批 \${title} 已通过"}}`}
            onChange={(event) => set({ bodyTemplate: event.target.value })}
          />
        </Form.Item>
      )}

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

      <div style={{ marginTop: 8, marginBottom: 12 }}>
        <Space>
          <Button onClick={testSend} loading={testing}>测试发送</Button>
          <span style={{ color: '#999', fontSize: 12 }}>
            用当前（未保存）配置真实发送一次，并回显对方响应
          </span>
        </Space>
        {testResult ? (
          <Alert
            style={{ marginTop: 12 }}
            showIcon
            type={testResult.successful
              ? (testResult.warning ? 'warning' : 'success') : 'error'}
            title={testResult.statusCode != null ? `HTTP ${testResult.statusCode}` : '测试未完成'}
            description={
              <div>
                {testResult.error ? <div>{testResult.error}</div> : null}
                {testResult.warning ? <div>{testResult.warning}</div> : null}
                {testResult.responseBody ? (
                  <pre style={{ maxHeight: 180, overflow: 'auto', margin: '8px 0 0', fontSize: 12 }}>
                    {testResult.responseBody}
                  </pre>
                ) : null}
                {testResult.durationMs != null ? (
                  <div style={{ color: '#999', fontSize: 12 }}>耗时 {testResult.durationMs} ms</div>
                ) : null}
              </div>
            }
          />
        ) : null}
      </div>

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
