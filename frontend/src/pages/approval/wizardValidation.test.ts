import { describe, expect, it } from 'vitest';
import {
  buildPublishChecks,
  getSteps,
  getWorkflowEnabled,
  hasApprovalNode,
  stepFromSearch,
} from './wizardValidation';

describe('wizardValidation', () => {
  it('未启用审批流程时不出现「流程设计」这一步，落在该步的 URL 回落到第一步', () => {
    const steps = getSteps(false);
    expect(steps.map((step) => step.key)).toEqual(['basic', 'designer', 'publish']);
    expect(stepFromSearch('?step=process', steps)).toBe(0);
    expect(stepFromSearch('?step=publish', steps)).toBe(2);
  });

  it('发布检查的顺序固定：错误项排在前面才能作为「未通过原因」展示', () => {
    const checks = buildPublishChecks({
      definition: { id: 1, name: '  ' , schema: '[]', settings: '{}' },
      schemaLength: 0,
      optionErrors: ['单选A'],
      uploadWarnings: ['附件B'],
      workflowEnabled: false,
      approvalNodeReady: false,
      processIssues: [],
    });
    expect(checks.map((item) => item.key)).toEqual([
      'definition', 'name', 'schema', 'options', 'upload', 'workflow',
    ]);
    expect(checks.filter((item) => item.status === 'error').map((item) => item.key))
      .toEqual(['name', 'schema', 'options']);
    expect(checks[4].status).toBe('warning');
  });

  it('启用审批流程但流程树里没有审批节点时，发布检查给出后端同样的提示', () => {
    const checks = buildPublishChecks({
      definition: { id: 1, name: '请假', schema: '[]', settings: '{}' },
      schemaLength: 1,
      optionErrors: [],
      uploadWarnings: [],
      workflowEnabled: true,
      processDefinitionId: 9,
      approvalNodeReady: false,
      processIssues: [],
    });
    expect(checks[5]).toMatchObject({
      key: 'workflow',
      status: 'error',
      description: '启用审批流程后，必须至少配置一个审批节点',
    });
  });

  it('settings 是字符串时也能读出审批流程开关', () => {
    expect(getWorkflowEnabled('{"workflowEnabled":true}')).toBe(true);
    expect(getWorkflowEnabled('not-json')).toBe(false);
    expect(hasApprovalNode({ type: 'ROOT', children: { type: 'APPROVAL' } })).toBe(true);
    expect(hasApprovalNode({ type: 'ROOT', children: { type: 'END' } })).toBe(false);
  });
});
