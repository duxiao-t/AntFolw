import { describe, expect, it } from 'vitest';
import {
  buildActivityItems,
  buildProgressStages,
  formatDuration,
  instanceStatus,
  type ProcessNode,
} from './detailPresentation';

const root: ProcessNode = {
  id: 'root',
  type: 'ROOT',
  name: '发起人',
  children: {
    id: 'approval',
    type: 'APPROVAL',
    name: '部门审批',
    children: {
      id: 'parallel',
      type: 'PARALLEL',
      name: '并行复核',
      branchs: [
        { id: 'branch-a', type: 'BRANCH', name: '财务线', children: { id: 'cc-a', type: 'CC', name: '抄送财务' } },
        { id: 'branch-b', type: 'BRANCH', name: '行政线', children: { id: 'cc-b', type: 'CC', name: '抄送行政' } },
      ],
    },
  },
};

describe('desktop process detail presentation', () => {
  it('builds a compact business progress rail from runtime state', () => {
    const stages = buildProgressStages({
      root,
      instanceStatus: 'RUNNING',
      currentNodeId: 'parallel',
      nodeInstances: [
        { id: 1, nodeId: 'approval', nodeType: 'APPROVAL', roundNo: 1, attemptNo: 1, status: 'PASSED' },
        { id: 2, nodeId: 'parallel', nodeType: 'PARALLEL', roundNo: 1, attemptNo: 1, status: 'ACTIVE' },
        { id: 3, nodeId: 'cc-a', nodeType: 'CC', roundNo: 1, attemptNo: 1, status: 'PASSED' },
      ],
    });

    expect(stages.map(({ label, state }) => [label, state])).toEqual([
      ['发起人', 'done'],
      ['部门审批', 'done'],
      ['并行复核', 'current'],
      ['流程完成', 'waiting'],
    ]);
    expect(stages[2]?.detail).toContain('1/1 个节点完成');
  });

  it('deduplicates task history and aggregates repeated CC recipients', () => {
    const items = buildActivityItems({
      root,
      applicantName: '张三',
      people: { 7: { id: 7, displayName: '张三', department: '研发部', employeeNo: '0007' } },
      approvalRecords: [
        {
          id: 'submission', nodeId: 'root', nodeName: '提交申请', recordKind: 'SUBMISSION',
          status: 'SUBMITTED', operatorName: '张三', receivedAt: '2026-09-09T09:00:00+08:00',
        },
        {
          id: 'task-1', taskId: 1, nodeId: 'approval', nodeName: '部门审批', recordKind: 'APPROVAL',
          status: 'APPROVED', operatorName: '李经理', department: '管理部', employeeNo: '0010',
          receivedAt: '2026-09-09T09:01:00+08:00', completedAt: '2026-09-09T09:02:00+08:00',
        },
        {
          id: 'cc-1', taskId: 81, nodeId: 'cc-a', nodeName: '抄送财务', recordKind: 'CC',
          status: 'APPROVED', operatorName: '王一', department: '财务部', employeeNo: '0020',
          receivedAt: '2026-09-09T09:03:00+08:00', completedAt: '2026-09-09T09:04:00+08:00',
        },
        {
          id: 'cc-2', taskId: 82, nodeId: 'cc-a', nodeName: '抄送财务', recordKind: 'CC',
          status: 'PROCESSING', operatorName: '王二', department: '财务部', employeeNo: '0021',
          receivedAt: '2026-09-09T09:03:00+08:00',
        },
      ],
      history: [
        { id: 1, taskId: 1, action: 'ARRIVE', toNodeId: 'approval', createdAt: '2026-09-09T09:01:00+08:00' },
        { id: 2, taskId: 1, action: 'APPROVE', toNodeId: 'approval', operatorId: 10, createdAt: '2026-09-09T09:02:00+08:00' },
        { id: 3, action: 'CC', toNodeId: 'cc-a', operatorId: 7, createdAt: '2026-09-09T09:03:00+08:00' },
        { id: 4, action: 'AUTO_PASS', toNodeId: 'cc-b', operatorId: 7, comment: 'same approver policy', createdAt: '2026-09-09T09:05:00+08:00' },
        { id: 5, action: 'COMPLETE', fromNodeId: 'cc-b', operatorId: 7, createdAt: '2026-09-09T09:06:00+08:00' },
      ],
    });

    expect(items).toHaveLength(5);
    expect(items.filter((item) => item.title === '部门审批')).toHaveLength(1);
    expect(items.find((item) => item.category === 'cc')).toMatchObject({
      person: '王一、王二',
      status: '待查收',
    });
    expect(items.find((item) => item.status === '自动通过')).toMatchObject({
      person: '系统',
      comment: expect.stringContaining('自动通过'),
    });
    expect(items.find((item) => item.status === '流程完成')?.title).toBe('流程完成');
  });

  it('uses Chinese status labels and stable durations', () => {
    expect(instanceStatus('RUNNING')).toEqual({ label: '审批中', tone: 'processing' });
    expect(instanceStatus('RUNNING', '__rework__')).toEqual({ label: '待修改', tone: 'warning' });
    expect(formatDuration('2026-09-09T08:00:00+08:00', '2026-09-10T10:30:00+08:00'))
      .toBe('1天 2小时');
  });

  it('does not treat a stale current node id as active after completion', () => {
    const stages = buildProgressStages({
      root,
      instanceStatus: 'APPROVED',
      currentNodeId: 'parallel',
      nodeInstances: [
        { id: 1, nodeId: 'approval', nodeType: 'APPROVAL', roundNo: 1, attemptNo: 1, status: 'PASSED' },
        { id: 2, nodeId: 'parallel', nodeType: 'PARALLEL', roundNo: 1, attemptNo: 1, status: 'PASSED' },
      ],
    });

    expect(stages.every((stage) => stage.state === 'done')).toBe(true);
  });

  it('uses safe business fallbacks when the viewer cannot receive the process snapshot', () => {
    const stages = buildProgressStages({
      root: null,
      instanceStatus: 'RUNNING',
      currentNodeId: 'private-node-id',
      currentNodeName: '部门审批',
    });
    const items = buildActivityItems({
      root: null,
      history: [{
        id: 9,
        action: 'AUTO_PASS',
        toNodeId: 'private-node-id',
        createdAt: '2026-09-09T09:00:00+08:00',
      }],
    });

    expect(stages.map((stage) => stage.label)).toContain('部门审批');
    expect(items[0]?.title).toBe('自动审批节点');
  });
});
