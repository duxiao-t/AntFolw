import { describe, expect, it } from 'vitest';
import {
  buildPublishChecks,
  collectStructureErrors,
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
      structureErrors: [],
      uploadWarnings: ['附件B'],
      workflowEnabled: false,
      approvalNodeReady: false,
      processIssues: [],
    });
    expect(checks.map((item) => item.key)).toEqual([
      'definition', 'name', 'schema', 'structure', 'options', 'upload', 'workflow',
    ]);
    expect(checks.filter((item) => item.status === 'error').map((item) => item.key))
      .toEqual(['name', 'schema', 'options']);
    expect(checks[5].status).toBe('warning');
  });

  it('启用审批流程但流程树里没有审批节点时，发布检查给出后端同样的提示', () => {
    const checks = buildPublishChecks({
      definition: { id: 1, name: '请假', schema: '[]', settings: '{}' },
      schemaLength: 1,
      optionErrors: [],
      structureErrors: [],
      uploadWarnings: [],
      workflowEnabled: true,
      processDefinitionId: 9,
      approvalNodeReady: false,
      processIssues: [],
    });
    expect(checks[6]).toMatchObject({
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

  /**
   * 回归护栏：矩阵缺 rows/columns 时后端返回 422 `BAD_SCHEMA: matrix_fill.rows is required`，
   * 但发布页曾经只检查「有没有组件」而显示全绿 —— 这个用例锁住「前端也会报 error」。
   */
  it('矩阵缺行列、明细表无列时 structure 项报错，与后端 422 对齐', () => {
    expect(collectStructureErrors([
      { id: 'm1', type: 'matrix_fill', label: '矩阵A', props: {} },
      { id: 't1', type: 'table_list', label: '明细B', props: {}, children: [] },
    ] as never)).toEqual([
      '矩阵A 缺少行',
      '矩阵A 缺少列',
      '矩阵A 的单元格类型需为文本或数字',
      '明细B 缺少明细列',
    ]);

    expect(collectStructureErrors([
      {
        id: 'm2',
        type: 'matrix_fill',
        label: '矩阵C',
        props: { rows: [{ id: 'r' }], columns: [{ id: 'c' }], cellType: 'number', precision: 2 },
      },
    ] as never)).toEqual([]);
  });
});
