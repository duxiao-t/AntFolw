import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportInspection, ImportOutcome } from './importContract';
import { failuresToCsv } from './importFailures';
import ImportWizardModal from './ImportWizardModal';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));

function inspection(overrides: Partial<ImportInspection> = {}): ImportInspection {
  return {
    templateVersion: 'v1',
    formId: 5,
    formCode: 'F-001',
    formName: '登记表',
    formVersion: 1,
    sheets: ['Sheet1'],
    sheetName: 'Sheet1',
    columns: [{ fieldId: 'amount', label: '金额', type: 'number', required: true, supported: true, order: 1 }],
    missingColumns: [],
    rowCount: 3,
    importableCount: 3,
    errorCount: 0,
    skippedCount: 0,
    issues: [],
    issuesTruncated: false,
    previews: [{ 金额: 100 }],
    warnings: [],
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

function renderModal() {
  return render(
    <App>
      <ImportWizardModal defaultFormId={5} onClose={() => {}} />
    </App>,
  );
}

const nextButton = () => screen.getByRole('button', { name: /下一步/ });

/** 弹窗里的异步状态更新不在事件里，必须用 act 把微任务刷完，否则断言会读到上一帧。 */
async function click(element: HTMLElement) {
  await act(async () => { fireEvent.click(element); });
}

async function pickFile(name = 'data.xlsx') {
  await act(async () => {
    fireEvent.change(screen.getByLabelText('选择导入文件'), {
      target: { files: [new File(['x'], name)] },
    });
  });
}

/** 从第 1 步走到「上传文件」，选好文件并等预检落定。 */
async function uploadAndWait() {
  await click(nextButton());
  await pickFile();
  await waitFor(() => expect(nextButton()).toBeEnabled());
}

beforeEach(() => {
  request.mockReset();
  request.mockImplementation((url: string) => {
    if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
    return Promise.resolve(inspection());
  });
});

describe('表单数据导入向导', () => {
  it('换文件后旧预检响应不回写（竞态）', async () => {
    const first = deferred<ImportInspection>();
    const second = deferred<ImportInspection>();
    let calls = 0;
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      if (url === '/api/forms/data/import/inspect') {
        calls += 1;
        return calls === 1 ? first.promise : second.promise;
      }
      return Promise.resolve({});
    });

    renderModal();
    await click(nextButton());
    await pickFile('a.xlsx');
    await pickFile('b.xlsx');

    // 后发的 B 先回：界面应该按 B 展示。
    await act(async () => {
      second.resolve(inspection({ formCode: 'B', rowCount: 7, importableCount: 7 }));
    });
    await waitFor(() => expect(nextButton()).toBeEnabled());

    // A 迟到的响应必须被序号挡掉，否则会盖成 A 的内容。
    await act(async () => {
      first.resolve(inspection({ formCode: 'A', rowCount: 99, importableCount: 99 }));
    });
    await click(nextButton());

    expect(await screen.findByText(/共 7 行/)).toBeInTheDocument();
    expect(screen.queryByText(/共 99 行/)).toBeNull();
  });

  it('有错误行且未勾「跳过」时不让提交', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      if (url === '/api/forms/data/import/inspect') {
        return Promise.resolve(inspection({
          rowCount: 3, importableCount: 2, errorCount: 1,
          issues: [{ row: 2, fieldId: 'amount', fieldLabel: '金额', code: 'IMPORT_VALIDATION_FAILED', message: '金额必须大于 0' }],
        }));
      }
      return Promise.resolve({});
    });

    renderModal();
    await uploadAndWait();
    await click(nextButton());
    await click(nextButton());

    const submit = screen.getByRole('button', { name: /开始导入/ });
    expect(submit).toBeDisabled();

    await click(screen.getByRole('checkbox', { name: /跳过错误行/ }));
    expect(submit).toBeEnabled();
    expect(screen.getByText('将导入 2 行，丢弃 1 行')).toBeInTheDocument();
  });

  it('预检错误表带出行号与字段', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      if (url === '/api/forms/data/import/inspect') {
        return Promise.resolve(inspection({
          rowCount: 3, importableCount: 2, errorCount: 1,
          issues: [{ row: 5, fieldId: 'amount', fieldLabel: '金额', code: 'IMPORT_VALIDATION_FAILED', message: '金额必须大于 0' }],
        }));
      }
      return Promise.resolve({});
    });

    renderModal();
    await uploadAndWait();
    await click(nextButton());

    expect(await screen.findByText('金额必须大于 0')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    // 字段列与列对账表都叫「金额」，只要求至少出现一次。
    expect(screen.getAllByText('金额').length).toBeGreaterThan(0);
  });

  it('提交后展示成功 / 重复跳过 / 失败三个数字', async () => {
    const outcome: ImportOutcome = {
      formId: 5, batchId: 'b-1', atomic: false, totalRows: 6,
      successCount: 3, failureCount: 1, skippedCount: 2, createdIds: [1, 2, 3],
      failures: [{ row: 4, fieldId: 'amount', fieldLabel: '金额', code: 'IMPORT_VALIDATION_FAILED', message: '金额非法' }],
      failuresTruncated: false, warnings: [],
    };
    request.mockImplementation((url: string) => {
      if (url === '/api/forms/definitions') return Promise.resolve({ records: [] });
      if (url === '/api/forms/data/import/inspect') return Promise.resolve(inspection());
      return Promise.resolve(outcome);
    });

    renderModal();
    await uploadAndWait();
    await click(nextButton());
    await click(nextButton());
    await click(screen.getByRole('button', { name: /开始导入/ }));

    expect(await screen.findByRole('group', { name: '成功' })).toHaveTextContent('3');
    expect(screen.getByRole('group', { name: '重复跳过' })).toHaveTextContent('2');
    expect(screen.getByRole('group', { name: '失败' })).toHaveTextContent('1');
    expect(screen.getByRole('button', { name: /下载失败明细/ })).toBeEnabled();
  });

  it('失败明细 CSV 带 BOM、表头与转义', () => {
    const csv = failuresToCsv([{
      row: 5, fieldId: 'amount', fieldLabel: '金额', code: 'IMPORT_VALIDATION_FAILED', message: '金额,必须大于 0',
    }]);

    expect(csv.charCodeAt(0)).toBe(0xfeff);
    expect(csv.startsWith('\uFEFF行号,字段,原因编码,原因')).toBe(true);
    expect(csv).toContain('5,金额,IMPORT_VALIDATION_FAILED,"金额,必须大于 0"');
  });
});
