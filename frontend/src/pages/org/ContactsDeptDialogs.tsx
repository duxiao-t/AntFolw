import { Form, Input, Modal, Select } from 'antd';
import type { FormInstance } from 'antd';
import { LeaderPicker } from './Contacts.components';
import type { Dept } from './Contacts.types';

/**
 * 部门域的三个弹窗。合成一个文件是因为它们共用同一个 `deptForm` 实例：
 * 新建与改名复用的是同一份字段定义，拆开反而要在两个文件之间对齐字段名。
 */
export function ContactsDeptDialogs({
  companyLabel,
  departments,
  form,
  addOpen,
  addParentId,
  createPending,
  onAddOk,
  onAddCancel,
  editOpen,
  editName,
  updatePending,
  onEditOk,
  onEditCancel,
  leaderOpen,
  leaderUsers,
  leaderCurrentIds,
  leaderSaving,
  onLeaderOk,
  onLeaderCancel,
}: {
  companyLabel?: string;
  departments: Dept[];
  form: FormInstance;
  addOpen: boolean;
  addParentId: number | null;
  createPending: boolean;
  onAddOk: () => void;
  onAddCancel: () => void;
  editOpen: boolean;
  editName?: string;
  updatePending: boolean;
  onEditOk: () => void;
  onEditCancel: () => void;
  leaderOpen: boolean;
  leaderUsers: { id: number; name: string }[];
  leaderCurrentIds: number[];
  leaderSaving: boolean;
  onLeaderOk: (userIds: number[]) => void;
  onLeaderCancel: () => void;
}) {
  return (
    <>
      {/* ===== Department Add Modal (top "+" or sub-dept) ===== */}
      <Modal
        title={addParentId ? '添加子部门' : '新建部门'}
        open={addOpen}
        confirmLoading={createPending}
        onOk={onAddOk}
        onCancel={onAddCancel}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item label="所属公司"><Input value={companyLabel} disabled /></Form.Item>
          <Form.Item label="部门名称" name="name" rules={[{ required: true, message: '请输入' }]}>
            <Input autoFocus />
          </Form.Item>
          {!addParentId && (
            <Form.Item label="所属部门" name="parentId">
              <Select allowClear placeholder="留空则为一级部门" options={
                departments.filter(d => d.canManageDepartment).map(d => ({ value: d.id, label: d.name }))
              } />
            </Form.Item>
          )}
        </Form>
      </Modal>

      {/* ===== Department Edit (name only) ===== */}
      <Modal
        title="修改部门"
        open={editOpen}
        confirmLoading={updatePending}
        onOk={onEditOk}
        onCancel={onEditCancel}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" preserve={false}
          initialValues={{ name: editName }}>
          <Form.Item label="所属公司"><Input value={companyLabel} disabled /></Form.Item>
          <Form.Item label="部门名称" name="name" rules={[{ required: true }]}>
            <Input autoFocus />
          </Form.Item>
        </Form>
      </Modal>

      {/* ===== Set Leader Modal ===== */}
      <Modal
        title="设置部门负责人"
        open={leaderOpen}
        onCancel={onLeaderCancel}
        footer={null}
        width={600}
        confirmLoading={updatePending}
        destroyOnHidden
      >
        <LeaderPicker
          users={leaderUsers}
          currentLeaderIds={leaderCurrentIds}
          onOk={onLeaderOk}
          onCancel={onLeaderCancel}
          saving={leaderSaving}
        />
      </Modal>
    </>
  );
}
