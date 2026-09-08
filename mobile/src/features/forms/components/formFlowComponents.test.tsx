import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MobileSchemaNode } from '../schema/types';
import { ConfirmSummaryList } from './ConfirmSummaryList';

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.endsWith('/users/1001')) {
      return jsonResponse({ id: 1001, displayName: '张三', department: '研发部', employeeNo: '000101' });
    }
    if (url.endsWith('/users/1002')) {
      return jsonResponse({ id: 1002, displayName: '李四', department: '财务部', employeeNo: '000102' });
    }
    if (url.endsWith('/departments/2001')) {
      return jsonResponse({ id: 2001, name: '研发部' });
    }
    if (url.includes('/content')) {
      return new Response(new Blob(['media'], { type: 'image/png' }), { status: 200 });
    }
    return jsonResponse({ message: 'not found' }, 404);
  }));
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn(() => 'blob:summary-media'),
    revokeObjectURL: vi.fn(),
  });
});

afterEach(() => vi.unstubAllGlobals());

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('form flow components', () => {
  it('renders compact summary rows without description nodes', () => {
    const schema: MobileSchemaNode[] = [
      { id: 'desc', type: 'description', label: '说明', props: { text: '请核对' } },
      { id: 'reason', type: 'text', label: '请假事由' },
      { id: 'days', type: 'number', label: '请假天数' },
    ];

    render(<ConfirmSummaryList schema={schema} values={{ reason: '回家探亲', days: 2 }} />);

    expect(screen.queryByText('说明')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('summary-reason')).getByText('回家探亲')).toBeInTheDocument();
    expect(within(screen.getByTestId('summary-days')).getByText('2')).toBeInTheDocument();
  });

  it('hides conditional fields and expands long text without rendering the full value first', async () => {
    const longText = '这是一个非常长的填写内容，用于确认页面的文本截断和展开查看回归测试，完整内容必须在展开后才能出现。';
    const schema: MobileSchemaNode[] = [
      { id: 'kind', type: 'select', label: '类型', props: { options: [{ label: '公开', value: 'public' }] } },
      { id: 'reason', type: 'textarea', label: '详细说明' },
      { id: 'secret', type: 'text', label: '隐藏字段', props: { displayCondition: { fieldId: 'kind', operator: 'eq', value: 'private' } } },
    ];

    render(<ConfirmSummaryList schema={schema} values={{ kind: 'public', reason: longText, secret: '不应展示' }} />);

    expect(screen.queryByText('隐藏字段')).not.toBeInTheDocument();
    expect(screen.queryByText(longText)).not.toBeInTheDocument();
    const row = screen.getByRole('button', { name: /详细说明/ });
    expect(row).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(row);
    expect(screen.getByText(longText)).toBeInTheDocument();
    expect(row).toHaveAttribute('aria-expanded', 'true');
  });

  it('defers media loading until the compact media row is expanded', async () => {
    render(<ConfirmSummaryList schema={[{ id: 'photos', type: 'image_upload', label: '现场图片' }]} values={{
      photos: [{ id: 'photo-1', name: '现场.png', contentType: 'image/png', size: 10, contentUrl: '/api/mobile/files/photo-1/content' }],
    }} />);

    expect(screen.getByText('1张图片')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /现场图片/ }));
    expect(await screen.findByRole('img', { name: '现场.png' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下载现场.png' })).toBeInTheDocument();
  });

  it('renders checklist entry descriptions and media only after expansion without question text', async () => {
    render(<ConfirmSummaryList schema={[{
      id: 'inspection',
      type: 'checklist',
      label: '检查项',
      props: {
        questionDescription: '此题干不能显示',
        items: [{ id: 'appearance', label: '设备外观' }],
        results: [{ id: 'bad', label: '异常', color: '#D93025' }, { id: 'ok', label: '合格' }],
      },
    }]} values={{
      inspection: [{
        itemId: 'appearance', result: 'bad', remark: '外壳有划痕',
        photos: [{ id: 'photo-2', name: '划痕.png', contentType: 'image/png', size: 10, contentUrl: '/api/mobile/files/photo-2/content' }],
      }],
    }} />);

    expect(screen.getByText('1项异常')).toBeInTheDocument();
    expect(screen.queryByText('此题干不能显示')).not.toBeInTheDocument();
    expect(screen.queryByText('外壳有划痕')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /检查项/ }));
    expect(screen.getByText('设备外观')).toBeInTheDocument();
    expect(screen.getByText('外壳有划痕')).toBeInTheDocument();
    expect(await screen.findByRole('img', { name: '划痕.png' })).toBeInTheDocument();
  });

  it('resolves every selected user and department before showing their expanded identities', async () => {
    render(<ConfirmSummaryList schema={[
      { id: 'reviewers', type: 'user_picker', label: '复核人', props: { multiple: true } },
      { id: 'department', type: 'dept_picker', label: '所属部门' },
    ]} values={{ reviewers: [1001, 1002], department: 2001 }} />);

    await waitFor(() => expect(screen.getByText('张三、李四')).toBeInTheDocument());
    expect(screen.getByText('研发部')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /复核人/ }));
    expect(screen.getByText('研发部 · 工号 000101')).toBeInTheDocument();
    expect(screen.getByText('财务部 · 工号 000102')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /所属部门/ }));
    expect(screen.getByText('部门编号')).toBeInTheDocument();
    expect(screen.getByText('2001')).toBeInTheDocument();
  });

  it('falls back to a stable department identifier when its historical target no longer exists', async () => {
    render(<ConfirmSummaryList schema={[{ id: 'department', type: 'dept_picker', label: '所属部门' }]} values={{ department: 404 }} />);

    await waitFor(() => expect(screen.getByText('部门 #404')).toBeInTheDocument());
    await userEvent.click(screen.getByRole('button', { name: /所属部门/ }));
    expect(screen.getByText('部门编号')).toBeInTheDocument();
  });

  it('uses a single expandable other-files row for unlinked direct-submission attachments', async () => {
    render(<ConfirmSummaryList schema={[]} values={{}} extraFiles={[
      { id: 'unlinked-1', name: '历史附件.pdf', contentType: 'application/pdf', size: 1024, contentUrl: '/api/mobile/files/unlinked-1/content' },
    ]} />);

    expect(screen.getByText('1个附件')).toBeInTheDocument();
    expect(screen.queryByText('历史附件.pdf')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /其他附件/ }));
    expect(screen.getByText('历史附件.pdf')).toBeInTheDocument();
  });

  it('keeps hidden table columns out of both row summaries and expanded rows', async () => {
    render(<ConfirmSummaryList schema={[{
      id: 'lines', type: 'table_list', label: '采购明细', children: [
        { id: 'item', type: 'text', label: '物品' },
        { id: 'internal', type: 'text', label: '内部备注' },
      ],
    }]} values={{ lines: [{ item: '显示器', internal: '不得展示' }] }} fieldModes={{ internal: 'hidden' }} />);

    await userEvent.click(screen.getByRole('button', { name: /采购明细/ }));
    expect(screen.getByText('第 1 行')).toBeInTheDocument();
    expect(screen.queryByText('内部备注')).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /第 1 行/ }));
    expect(screen.getByText('显示器')).toBeInTheDocument();
    expect(screen.queryByText('不得展示')).not.toBeInTheDocument();
  });

  it('does not treat a hidden field attachment as a linked direct-submission file', async () => {
    render(<ConfirmSummaryList schema={[{
      id: 'hiddenPhoto', type: 'image_upload', label: '内部图片', props: { hidden: true },
    }]} values={{ hiddenPhoto: [{ id: 'hidden-photo', contentUrl: '/api/mobile/files/hidden-photo/content', contentType: 'image/png', size: 1 }] }} extraFiles={[
      { id: 'hidden-photo', name: '内部图片.png', contentUrl: '/api/mobile/files/hidden-photo/content', contentType: 'image/png', size: 1 },
    ]} />);

    expect(screen.queryByText('内部图片')).not.toBeInTheDocument();
    expect(screen.getByText('1个附件')).toBeInTheDocument();
  });

  it('does not duplicate checklist media into other direct-submission attachments', () => {
    render(<ConfirmSummaryList schema={[{
      id: 'inspection', type: 'checklist', label: '检查项', props: {
        items: [{ id: 'appearance', label: '设备外观' }],
      },
    }]} values={{ inspection: [{
      id: 'appearance', status: 'pass', images: [{ id: 'check-photo', contentUrl: '/api/mobile/files/check-photo/content', contentType: 'image/png', size: 1 }],
    }] }} extraFiles={[
      { id: 'check-photo', name: '检查图片.png', contentUrl: '/api/mobile/files/check-photo/content', contentType: 'image/png', size: 1 },
    ]} />);

    expect(screen.getByText('1项通过')).toBeInTheDocument();
    expect(screen.queryByText('其他附件')).not.toBeInTheDocument();
  });

  it('contains malformed historical values without breaking sibling summary rows', async () => {
    render(<ConfirmSummaryList schema={[
      { id: 'legacy', type: 'legacy_field', label: '历史字段' },
      { id: 'reason', type: 'text', label: '事由' },
    ]} values={{ legacy: { nested: ['a', 'b'] }, reason: '正常内容' }} />);

    expect(screen.getByText('已填写')).toBeInTheDocument();
    expect(screen.getByText('正常内容')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /历史字段/ }));
    expect(screen.getByText(/"nested"/)).toBeInTheDocument();
  });

  it('summarizes every media category by count and expands the full list on demand', async () => {
    render(<ConfirmSummaryList schema={[
      { id: 'images', type: 'image_upload', label: '图片' },
      { id: 'videos', type: 'video_upload', label: '视频' },
      { id: 'audio', type: 'audio_upload', label: '录音' },
      { id: 'files', type: 'file_upload', label: '附件' },
    ]} values={{
      images: [{ id: 'i1', contentUrl: '/api/mobile/files/i1/content', contentType: 'image/png', size: 1 }, { id: 'i2', contentUrl: '/api/mobile/files/i2/content', contentType: 'image/png', size: 1 }],
      videos: [{ id: 'v1', contentUrl: '/api/mobile/files/v1/content', contentType: 'video/mp4', size: 1 }],
      audio: [{ id: 'a1', contentUrl: '/api/mobile/files/a1/content', contentType: 'audio/mpeg', size: 1 }],
      files: [{ id: 'f1', contentUrl: '/api/mobile/files/f1/content', contentType: 'application/pdf', size: 1 }],
    }} />);

    expect(screen.getByText('2张图片')).toBeInTheDocument();
    expect(screen.getByText('1个视频')).toBeInTheDocument();
    expect(screen.getByText('1段录音')).toBeInTheDocument();
    expect(screen.getByText('1个附件')).toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /^附件/ }));
    expect(screen.getByRole('button', { name: '下载附件' })).toBeInTheDocument();
  });

  it('expands a matrix with complete headers and safely renders empty cells', async () => {
    render(<ConfirmSummaryList schema={[{
      id: 'matrix', type: 'matrix_fill', label: '巡检矩阵', props: {
        rows: [{ id: 'r1', label: '设备A' }],
        columns: [{ id: 'c1', label: '状态' }, { id: 'c2', label: '备注' }],
      },
    }]} values={{ matrix: { customRows: [], customColumns: [], cells: { r1: { c1: '正常' } } } }} />);

    expect(screen.getByText('1 行 × 2 列')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /巡检矩阵/ }));
    expect(screen.getByText('设备A')).toBeInTheDocument();
    expect(screen.getByText('正常')).toBeInTheDocument();
    expect(screen.getByText('未填写')).toBeInTheDocument();
  });
});
