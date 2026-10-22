import { Input, Modal, Select, Space } from 'antd';
import { ApprovalCommentEditor } from '../../components/ApprovalCommentEditor';
import type { DetailActions } from './useDetailActions';

/** 同意 / 驳回 / 紧急介入 / 撤回 四个流程动作弹窗。状态与提交都在 `useDetailActions`。 */
export function DetailModals({
  actions,
  rejectTargets,
  pendingTaskId,
}: {
  actions: DetailActions;
  rejectTargets: Array<{ id: string; name: string }>;
  pendingTaskId?: number;
}) {
  return (
    <>
      <Modal
        title="同意审批"
        open={actions.approveOpen}
        onCancel={() => actions.setApproveOpen(false)}
        onOk={() => pendingTaskId != null && actions.doApprove(pendingTaskId)}
        okText="确认同意"
      >
        <ApprovalCommentEditor
          action="approve"
          presets={actions.presets}
          value={actions.approveComment}
          onChange={actions.setApproveComment}
        />
      </Modal>

      <Modal
        title="驳回"
        open={!!actions.rejectFor}
        onCancel={() => actions.setRejectFor(null)}
        onOk={actions.doReject}
        okText="确定驳回"
        okButtonProps={{ danger: true }}
      >
        {!actions.fixedRejectTarget && (
          <div style={{ marginBottom: 12 }}>
            <div>驳回到</div>
            <Select
              style={{ width: '100%' }}
              value={actions.rejectFor?.targetNodeId ?? '__END__'}
              onChange={(v) =>
                actions.setRejectFor(
                  actions.rejectFor
                    ? {
                        ...actions.rejectFor,
                        targetNodeId: v === '__END__' ? null : v,
                      }
                    : null,
                )
              }
              options={[
                { value: '__END__', label: '结束流程（驳回=终止）' },
                ...rejectTargets.map((t) => ({
                  value: t.id,
                  label: `${t.name} (${t.id})`,
                })),
              ]}
            />
          </div>
        )}
        <div>
          <div>意见</div>
          <ApprovalCommentEditor
            action="reject"
            presets={actions.presets}
            rows={3}
            value={actions.rejectComment}
            onChange={actions.setRejectComment}
          />
        </div>
      </Modal>

      <Modal
        title="紧急介入"
        open={actions.overrideOpen}
        onCancel={() => actions.setOverrideOpen(false)}
        onOk={actions.submitOverride}
        okText="执行介入"
        okButtonProps={{ danger: true }}
      >
        <Space orientation="vertical" size={12} style={{ width: '100%' }}>
          <div>
            <div>处理动作</div>
            <Select
              style={{ width: '100%' }}
              value={actions.overrideAction}
              onChange={(value) => actions.setOverrideAction(value)}
              options={[
                { value: 'APPROVE', label: '同意并继续流程' },
                { value: 'REJECT', label: '驳回' },
              ]}
            />
          </div>
          <div>
            <div>工单号</div>
            <Input
              value={actions.overrideTicket}
              onChange={(event) => actions.setOverrideTicket(event.target.value)}
            />
          </div>
          <div>
            <div>原因</div>
            <Input.TextArea
              rows={3}
              value={actions.overrideReason}
              onChange={(event) => actions.setOverrideReason(event.target.value)}
            />
          </div>
          {actions.overrideAction === 'REJECT' && (
            <div>
              <div>驳回目标</div>
              <Select
                allowClear
                style={{ width: '100%' }}
                value={actions.overrideTarget}
                onChange={actions.setOverrideTarget}
                placeholder="默认退回上一级"
                options={rejectTargets.map((target) => ({ value: target.id, label: target.name }))}
              />
            </div>
          )}
        </Space>
      </Modal>

      <Modal
        title="确认撤回流程？"
        open={actions.withdrawOpen}
        onCancel={() => actions.setWithdrawOpen(false)}
        onOk={actions.doWithdraw}
        okText="撤回"
        okButtonProps={{ danger: true }}
      >
        <p>
          撤回后流程将进入待修改，原实例、表单数据和单号都会保留，可在移动端修改后重新提交。
        </p>
        <p style={{ color: '#999', fontSize: 12 }}>
          当前审批轮次已有实际审批时不能撤回；已成功执行的外部 Webhook 无法回滚。
        </p>
      </Modal>
    </>
  );
}
