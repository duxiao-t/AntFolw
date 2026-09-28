import { App } from 'antd';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { OptionSourceImportModal } from './OptionSourceImportModal';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));

type PreviewResult = {
  sheets: string[];
  columns: string[];
  rowCount: number;
  rows: Array<Record<string, string>>;
};

function renderModal(onImported = vi.fn()) {
  return render(
    <App>
      <OptionSourceImportModal sourceId={7} open onClose={vi.fn()} onImported={onImported} />
    </App>,
  );
}

const previewOf = (rows: Array<Record<string, string>>): PreviewResult => ({
  sheets: [], columns: ['选项'], rowCount: rows.length, rows,
});

describe('导入新版本的弹窗', () => {
  beforeEach(() => { request.mockReset(); });

  it('预检还没回来就改了内容：旧预览不许写回（否则看着 A、导入的是 B）', async () => {
    let release!: (value: PreviewResult) => void;
    request.mockImplementation(() => new Promise((resolve) => { release = resolve; }));

    renderModal();
    const area = screen.getByLabelText('粘贴选项文本');
    fireEvent.change(area, { target: { value: '旧内容' } });
    fireEvent.click(screen.getByRole('button', { name: '检查导入内容' }));

    // 预检飞行中又改了内容。
    fireEvent.change(area, { target: { value: '新内容' } });

    // 旧请求这时才回来：不能把「旧内容」的预览写到「新内容」这一屏上。
    release(previewOf([{ 选项: '旧内容' }]));
    await waitFor(() => expect(screen.queryByText('旧内容')).not.toBeInTheDocument());
    // 没有预览 → 导入按钮保持不可用，必须重新检查。
    expect(screen.getByRole('button', { name: '导入为待发布版本' })).toBeDisabled();
  });

  it('切工作表失败后不留上一张表的预览', async () => {
    request
      .mockResolvedValueOnce({ ...previewOf([{ 选项: '甲表数据' }]), sheets: ['甲', '乙'] })
      .mockRejectedValueOnce(new Error('工作表读不出来'));

    renderModal();
    fireEvent.change(screen.getByLabelText('粘贴选项文本'), { target: { value: '甲' } });
    fireEvent.click(screen.getByRole('button', { name: '检查导入内容' }));

    expect(await screen.findByText('甲表数据')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByLabelText('选择工作表'));
    fireEvent.click(await screen.findByTitle('乙'));

    // 乙表预检失败 → 甲表的行必须消失（不然界面显示甲、导入按乙）。
    await waitFor(() => expect(screen.queryByText('甲表数据')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: '导入为待发布版本' })).toBeDisabled();
  });

  it('提交时把变更说明一并发出去', async () => {
    request
      .mockResolvedValueOnce(previewOf([{ 选项: 'a' }]))
      .mockResolvedValueOnce({});

    renderModal();
    fireEvent.change(screen.getByLabelText('粘贴选项文本'), { target: { value: 'a' } });
    fireEvent.click(screen.getByRole('button', { name: '检查导入内容' }));
    await screen.findByText('a');

    fireEvent.change(screen.getByLabelText('变更说明'), { target: { value: '新增港澳台' } });
    fireEvent.click(screen.getByRole('button', { name: '导入为待发布版本' }));

    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    const body = request.mock.calls[1][1].data as FormData;
    expect(body.get('note')).toBe('新增港澳台');
  });
});
