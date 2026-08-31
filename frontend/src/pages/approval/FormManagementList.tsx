import { PlusOutlined, UploadOutlined } from '@ant-design/icons';
import {
  type ActionType,
  PageContainer,
  ProTable,
} from '@ant-design/pro-components';
import { history, request, useModel } from '@umijs/max';
import { App, Button, Input, Modal, Popconfirm, Space, Tag } from 'antd';
import { useRef, useState } from 'react';
import type { SchemaNode } from '../../registry/types';
import {
  buildFormTemplate,
  type FormTemplate,
  parseFormTemplate,
} from './formTemplate';

type FormDefinition = {
  id: number;
  code: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  version: number;
  updatedAt?: string;
};

type FormDefinitionDetail = FormDefinition & { schema: SchemaNode[] | string };

type PageResult<T> = {
  records?: T[];
  total?: number;
};

const statusMap = {
  DRAFT: { color: 'default', text: '草稿' },
  PUBLISHED: { color: 'green', text: '已发布' },
  DEPRECATED: { color: 'red', text: '已停用' },
};

export default function FormManagementList() {
  const actionRef = useRef<ActionType | undefined>(undefined);
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const [template, setTemplate] = useState<FormTemplate | null>(null);
  const [importName, setImportName] = useState('');
  const [importCode, setImportCode] = useState('');
  const [importing, setImporting] = useState(false);
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser as any;
  const isAdmin = (currentUser?.roles ?? []).includes('admin');
  const can = (permission: string) =>
    isAdmin || (currentUser?.permissions ?? []).includes(permission);

  const handleDelete = async (record: FormDefinition) => {
    try {
      await request(`/api/forms/definitions/${record.id}`, {
        method: 'DELETE',
      });
      message.success('已删除');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message ?? '删除失败');
    }
  };

  const handleDisable = async (record: FormDefinition) => {
    try {
      await request(`/api/forms/definitions/${record.id}/disable`, {
        method: 'POST',
      });
      message.success('已停用');
      actionRef.current?.reload();
    } catch (error: any) {
      message.error(error?.message ?? '停用失败');
    }
  };

  const handleExport = async (record: FormDefinition) => {
    try {
      const detail = await request<FormDefinitionDetail>(
        `/api/forms/definitions/${record.id}`,
      );
      const content = JSON.stringify(buildFormTemplate(detail), null, 2);
      const url = URL.createObjectURL(
        new Blob([content], { type: 'application/json' }),
      );
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = `${safeFileName(record.name)}.antflow-form.json`;
      anchor.click();
      URL.revokeObjectURL(url);
      message.success('表单模板已导出');
    } catch (error: any) {
      message.error(error?.message ?? '导出失败');
    }
  };

  const handleImportFile = async (file: File | undefined) => {
    if (!file) return;
    try {
      const parsed = parseFormTemplate(await file.text());
      setTemplate(parsed);
      setImportName(`${parsed.name}-副本`);
      setImportCode(`form_${Date.now()}`);
    } catch (error: any) {
      message.error(error?.message ?? '模板读取失败');
    } finally {
      if (importInputRef.current) importInputRef.current.value = '';
    }
  };

  const handleImport = async () => {
    if (!template || !importName.trim() || !importCode.trim()) return;
    setImporting(true);
    try {
      const saved = await request<FormDefinition>('/api/forms/definitions', {
        method: 'POST',
        data: {
          code: importCode.trim(),
          name: importName.trim(),
          description: template.description ?? '',
          schema: template.schema,
          settings: { workflowEnabled: false },
        },
      });
      message.success('模板已导入为新草稿');
      setTemplate(null);
      actionRef.current?.reload();
      history.push(`/approval/forms/${saved.id}/wizard?step=designer`);
    } catch (error: any) {
      message.error(error?.message ?? '导入失败');
    } finally {
      setImporting(false);
    }
  };

  return (
    <PageContainer title={false}>
      <ProTable<FormDefinition>
        actionRef={actionRef}
        rowKey="id"
        columns={[
          { title: '表单名称', dataIndex: 'name' },
          { title: '表单编码', dataIndex: 'code' },
          { title: '版本', dataIndex: 'version', width: 80 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 120,
            render: (_, record) => {
              const status = statusMap[record.status] ?? {
                color: 'default',
                text: record.status,
              };
              return <Tag color={status.color}>{status.text}</Tag>;
            },
          },
          {
            title: '操作',
            key: 'op',
            width: 260,
            render: (_, record) => {
              const deprecated = record.status === 'DEPRECATED';
              return (
                <Space>
                  {can('form.definition.design') && (
                    <a
                      onClick={() =>
                        history.push(
                          `/approval/forms/${record.id}/wizard?step=basic`,
                        )
                      }
                    >
                      编辑
                    </a>
                  )}
                  <a onClick={() => void handleExport(record)}>导出</a>
                  {can('form.definition.publish') && (
                    <Popconfirm
                      title="确认停用该表单？"
                      description="停用后表单将不再可发起填报。"
                      okText="停用"
                      okButtonProps={{ danger: true }}
                      disabled={deprecated}
                      onConfirm={() => handleDisable(record)}
                    >
                      <a
                        style={{
                          color: deprecated ? 'rgba(0, 0, 0, 0.25)' : undefined,
                          cursor: deprecated ? 'not-allowed' : 'pointer',
                        }}
                      >
                        {deprecated ? '已停用' : '停用'}
                      </a>
                    </Popconfirm>
                  )}
                  {can('form.definition.delete') && (
                    <Popconfirm
                      title="确认删除该表单？"
                      description="删除后列表不再展示，历史提交数据仍会保留。"
                      okText="删除"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => handleDelete(record)}
                    >
                      <a style={{ color: '#ff4d4f' }}>删除</a>
                    </Popconfirm>
                  )}
                  {can('form.data.read') && (
                    <a
                      onClick={() =>
                        history.push(
                          `/approval/form-data?formDefId=${record.id}`,
                        )
                      }
                    >
                      数据
                    </a>
                  )}
                </Space>
              );
            },
          },
        ]}
        request={async (params) => {
          const result = await request<PageResult<FormDefinition>>(
            '/api/forms/definitions',
            {
              params: {
                page: params.current,
                size: params.pageSize,
              },
            },
          );
          return {
            data: result.records ?? [],
            success: true,
            total: result.total ?? 0,
          };
        }}
        search={false}
        options={false}
        toolBarRender={() =>
          can('form.definition.create')
            ? [
                <Button
                  key="import"
                  icon={<UploadOutlined />}
                  onClick={() => importInputRef.current?.click()}
                >
                  导入模板
                </Button>,
                <Button
                  key="new"
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={() => history.push('/approval/forms/new')}
                >
                  新建表单
                </Button>,
              ]
            : []
        }
      />
      <input
        ref={importInputRef}
        type="file"
        accept="application/json,.json"
        hidden
        onChange={(event) => void handleImportFile(event.target.files?.[0])}
      />
      <Modal
        title="导入表单模板"
        open={template != null}
        okText="创建草稿"
        cancelText="取消"
        confirmLoading={importing}
        okButtonProps={{ disabled: !importName.trim() || !importCode.trim() }}
        onCancel={() => setTemplate(null)}
        onOk={() => void handleImport()}
      >
        <div style={{ display: 'grid', gap: 16, width: '100%', paddingTop: 8 }}>
          <label>
            <div style={{ marginBottom: 6 }}>表单名称</div>
            <Input
              value={importName}
              maxLength={100}
              onChange={(event) => setImportName(event.target.value)}
            />
          </label>
          <label>
            <div style={{ marginBottom: 6 }}>表单编码</div>
            <Input
              value={importCode}
              maxLength={100}
              onChange={(event) => setImportCode(event.target.value)}
            />
          </label>
          <span style={{ color: 'rgba(0, 0, 0, 0.45)' }}>
            只导入表单结构，不包含流程、历史数据和权限。
          </span>
        </div>
      </Modal>
    </PageContainer>
  );
}

function safeFileName(name: string) {
  return name.replace(/[\\/:*?"<>|]/g, '_').trim() || '表单模板';
}
