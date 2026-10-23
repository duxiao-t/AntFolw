import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportOutcome } from './importContract';
import ImportWizardModal from './ImportWizardModal';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));

const SCHEMA = [{
  id: 'title',
  type: 'text',
  label: '标题',
  props: { required: true, placeholder: '请输入标题' },
}];

const DEFINITION = {
  id: 44,
  name: '巡检登记表',
  code: 'INSPECT',
  version: 3,
  schema: JSON.stringify(SCHEMA),
};

function outcome(overrides: Partial<ImportOutcome> = {}): ImportOutcome {
  return {
    formId: 44, batchId: 'm-1', atomic: true, totalRows: 1,
    successCount: 1, failureCount: 0, skippedCount: 0, createdIds: [9],
    failures: [], failuresTruncated: false, warnings: [],
    ...overrides,
  };
}

function renderModal(defaultFormId: number | undefined = 44) {
  return render(
    <App>
      <ImportWizardModal defaultFormId={defaultFormId} onClose={() => {}} />
    </App>,
  );
}

/** 弹窗里的异步状态更新不在事件里，必须用 act 把微任务刷完，否则断言会读到上一帧。 */
async function click(element: HTMLElement) {
  await act(async () => { fireEvent.click(element); });
}

async function enterManual() {
  await click(screen.getByRole('button', { name: /在线填写一条/ }));
  await click(screen.getByRole('button', { name: /下一步/ }));
  await screen.findByPlaceholderText('请输入标题');
}

async function fillAndSubmit(value = '巡检通过') {
  await act(async () => {
    fireEvent.change(screen.getByPlaceholderText('请输入标题'), { target: { value } });
  });
  await click(screen.getByRole('button', { name: /提交导入/ }));
}

beforeEach(() => {
  request.mockReset();
  request.mockImplementation((url: string) => {
    if (url === '/api/forms/definitions') {
      return Promise.resolve({ records: [{ id: 44, name: '巡检登记表' }] });
    }
    if (url === '/api/forms/definitions/44') return Promise.resolve(DEFINITION);
    if (url === '/api/forms/data/import/manual') return Promise.resolve(outcome());
    return Promise.resolve({});
  });
});

describe('在线填写导入', () => {
  it('选择「在线填写」后能从下拉选表单并渲染出字段', async () => {
    renderModal(undefined);
    await click(screen.getByRole('button', { name: /在线填写一条/ }));

    // antd 的 Select 不是原生 <select>：按下展开、点选项。
    await act(async () => {});
    fireEvent.mouseDown(screen.getByLabelText('在线填写表单'));
    // 已选中项的 label 也带 title，必须点下拉里的那个选项节点。
    fireEvent.click(await screen.findByText('巡检登记表', { selector: '.ant-select-item-option-content' }));
    await click(screen.getByRole('button', { name: /下一步/ }));

    expect(await screen.findByText('正在填写：巡检登记表')).toBeInTheDocument();
    expect(screen.getByText(/标题/)).toBeInTheDocument();
    expect(screen.getByPlaceholderText('请输入标题')).toBeInTheDocument();
  });

  it('提交体只包含 formId 与表单字段，没有文件或多余字段', async () => {
    renderModal();
    await enterManual();
    await fillAndSubmit();

    const call = request.mock.calls.find(([url]) => url === '/api/forms/data/import/manual');
    expect(call?.[1]).toEqual(expect.objectContaining({
      method: 'POST',
      data: { formId: 44, data: { title: '巡检通过' } },
    }));
    const body = call?.[1]?.data as Record<string, unknown>;
    expect(Object.keys(body)).toEqual(['formId', 'data']);
    expect(Object.keys(body.data as Record<string, unknown>)).toEqual(['title']);
    expect(body).not.toHaveProperty('confirmPassword');
    expect(body).not.toHaveProperty('file');
  });

  it('200 时展示成功 / 重复跳过 / 失败三个数字', async () => {
    renderModal();
    await enterManual();
    await fillAndSubmit();

    expect(await screen.findByRole('group', { name: '成功' })).toHaveTextContent('1');
    expect(screen.getByRole('group', { name: '重复跳过' })).toHaveTextContent('0');
    expect(screen.getByRole('group', { name: '失败' })).toHaveTextContent('0');
    expect(screen.getByText('导入完成')).toBeInTheDocument();
  });

  it('422 校验失败时逐条展示字段级 message', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/definitions') {
        return Promise.resolve({ records: [{ id: 44, name: '巡检登记表' }] });
      }
      if (url === '/api/forms/definitions/44') return Promise.resolve(DEFINITION);
      if (url === '/api/forms/data/import/manual') {
        return Promise.reject({
          response: {
            data: {
              code: 'IMPORT_VALIDATION_FAILED',
              message: '校验未通过',
              failures: [{ fieldId: 'title', fieldLabel: '标题', message: '标题不能为空' }],
            },
          },
        });
      }
      return Promise.resolve({});
    });

    renderModal();
    await enterManual();
    await fillAndSubmit();

    expect(await screen.findByText('存在校验不通过的行')).toBeInTheDocument();
    expect(screen.getByText('标题不能为空')).toBeInTheDocument();
    expect(screen.getByText('字段明细')).toBeInTheDocument();
  });

  it('「再填一条」清空内容但保留所选表单', async () => {
    renderModal();
    await enterManual();
    await fillAndSubmit();

    await screen.findByRole('group', { name: '成功' });
    await click(screen.getByRole('button', { name: /再填一条/ }));

    await waitFor(() => {
      expect((screen.getByPlaceholderText('请输入标题') as HTMLInputElement).value).toBe('');
    });
    expect(screen.getByText('正在填写：巡检登记表')).toBeInTheDocument();
  });
});
