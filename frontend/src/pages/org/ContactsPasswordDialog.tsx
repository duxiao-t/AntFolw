import { Form, Input, Modal } from 'antd';
import type { FormInstance } from 'antd';
import type { MemberItem } from './Contacts.types';

/** 重置密码弹窗：两条校验（必填、8-64 位 + 两次一致）原样保留。 */
export function ContactsPasswordDialog({ target, form, saving, onOk, onCancel }: {
  target: MemberItem | null;
  form: FormInstance;
  saving: boolean;
  onOk: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal
      title={target ? `重置密码 · ${target.displayName}` : '重置密码'}
      open={!!target}
      width={440}
      confirmLoading={saving}
      onOk={onOk}
      onCancel={onCancel}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" preserve={false} className="ct-member-form">
        <Form.Item label="新密码" name="newPassword" rules={[
          { required: true, message: '请输入新密码' },
          { min: 8, max: 64, message: '密码长度为 8 到 64 位' },
        ]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item label="确认新密码" name="confirmPassword" dependencies={['newPassword']} rules={[
          { required: true, message: '请再次输入新密码' },
          ({ getFieldValue }) => ({
            validator(_, value) {
              return !value || getFieldValue('newPassword') === value
                ? Promise.resolve()
                : Promise.reject(new Error('两次输入的密码不一致'));
            },
          }),
        ]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
