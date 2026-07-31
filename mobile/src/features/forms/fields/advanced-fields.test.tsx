import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { validateSchemaValues } from '../schema/fieldRegistry';
import type { MobileSchemaNode } from '../schema/types';
import { DeptPickerField } from './DeptPickerField';
import { FileUploadField } from './FileUploadField';
import { ImageUploadField } from './ImageUploadField';
import { SpanLayoutField } from './SpanLayoutField';
import { TableListField } from './TableListField';
import { UserPickerField } from './UserPickerField';
import { DynamicFormRenderer } from '../components/DynamicFormRenderer';

beforeEach(() => {
  vi.stubGlobal('XMLHttpRequest', undefined);
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('/api/mobile/users')) {
        return jsonResponse([{ id: 1001, displayName: '张三', username: 'zhangsan' }]);
      }
      if (url.startsWith('/api/mobile/departments')) {
        return jsonResponse([{ id: 2001, name: '研发部' }]);
      }
      if (url.startsWith('/api/mobile/files') && init?.method === 'POST') {
        const formData = init.body as FormData;
        const file = formData.get('file') as File;
        if (file.name.startsWith('oops.')) {
          return jsonResponse({ message: '上传失败' }, 500);
        }
        return jsonResponse({
          id: `remote-${file.name}`,
          name: file.name,
          contentUrl: `/api/mobile/files/remote-${file.name}/content`,
          contentType: file.type,
          size: file.size,
        });
      }
      if (url.startsWith('/api/mobile/files/') && url.endsWith('/content')) {
        return new Response(new Blob(['image'], { type: 'image/png' }), {
          status: 200,
          headers: { 'Content-Type': 'image/png' },
        });
      }
      if (url.startsWith('/api/mobile/files/') && init?.method === 'DELETE') {
        return new Response(null, { status: 204 });
      }
      return jsonResponse({ message: 'Not found' }, 404);
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  ManualXMLHttpRequest.latest = null;
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

class ManualXMLHttpRequest {
  static latest: ManualXMLHttpRequest | null = null;

  readonly upload: {
    onprogress: ((event: ProgressEvent) => void) | null;
    onload: ((event: ProgressEvent) => void) | null;
  } = { onprogress: null, onload: null };

  method = '';
  url = '';
  body: Document | XMLHttpRequestBodyInit | null = null;
  withCredentials = false;
  status = 0;
  statusText = '';
  responseText = '';
  readyState = 0;
  onload: (() => void) | null = null;
  onreadystatechange: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;

  private readonly headers = new Map<string, string>();

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  setRequestHeader(name: string, value: string) {
    this.headers.set(name.toLowerCase(), value);
  }

  getResponseHeader() {
    return null;
  }

  send(body: Document | XMLHttpRequestBodyInit | null) {
    this.body = body;
    ManualXMLHttpRequest.latest = this;
  }

  emitProgress(loaded: number, total = 100) {
    this.upload.onprogress?.(new ProgressEvent('progress', {
      lengthComputable: true,
      loaded,
      total,
    }));
  }

  emitUploadComplete() {
    this.upload.onload?.(new ProgressEvent('load'));
  }

  respond(status: number, body: unknown) {
    this.status = status;
    this.responseText = JSON.stringify(body);
    this.readyState = 4;
    this.onload?.();
  }
}

function baseProps(
  node: MobileSchemaNode,
  value: unknown,
  onValueChange: (fieldId: string, value: unknown) => void = vi.fn(),
) {
  return {
    node,
    value,
    values: { [node.id]: value },
    mode: 'fill' as const,
    error: undefined,
    onValueChange,
  };
}

describe('advanced mobile fields', () => {
  it('searches and selects a user by numeric id', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();

    render(
      <UserPickerField
        {...baseProps(
          {
            id: 'approver',
            type: 'user_picker',
            label: '审批人',
            props: {
              required: true,
              searchEndpoint: '/api/mobile/users',
            },
          },
          null,
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '选择审批人' }));
    await user.type(screen.getByPlaceholderText('搜索姓名或账号'), '张');

    expect(await screen.findByRole('option', { name: '张三 1001' })).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: '张三 1001' }));

    expect(onValueChange).toHaveBeenCalledWith('approver', 1001);
    expect(screen.getByText('张三')).toBeInTheDocument();
  });

  it('searches and selects a department by numeric id', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();

    render(
      <DeptPickerField
        {...baseProps(
          {
            id: 'deptId',
            type: 'dept_picker',
            label: '部门',
            props: {
              searchEndpoint: '/api/mobile/departments',
            },
          },
          null,
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '选择部门' }));
    await user.type(screen.getByPlaceholderText('搜索部门'), '研');

    expect(await screen.findByRole('option', { name: '研发部 2001' })).toBeInTheDocument();
    await user.click(screen.getByRole('option', { name: '研发部 2001' }));

    expect(onValueChange).toHaveBeenCalledWith('deptId', 2001);
  });

  it('uses mobile-safe default picker endpoints', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.mocked(fetch);

    render(
      <>
        <UserPickerField
          {...baseProps(
            { id: 'approver', type: 'user_picker', label: '审批人' },
            null,
          )}
        />
        <DeptPickerField
          {...baseProps(
            { id: 'deptId', type: 'dept_picker', label: '部门' },
            null,
          )}
        />
      </>,
    );

    await user.click(screen.getByRole('button', { name: '选择审批人' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/users',
      expect.objectContaining({ credentials: 'include' }),
    ));

    await user.click(screen.getByRole('button', { name: '选择部门' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/mobile/departments',
      expect.objectContaining({ credentials: 'include' }),
    ));
  });

  it('uploads, reports progress, and removes files', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();

    render(
      <FileUploadField
        {...baseProps(
          {
            id: 'attachments',
            type: 'file_upload',
            label: '附件',
            props: {
              required: true,
              uploadEndpoint: '/api/mobile/files',
            },
          },
          [],
          onValueChange,
        )}
      />,
    );

    await user.upload(screen.getByLabelText('附件'), new File(['%PDF-hello'], 'hello.pdf', { type: 'application/pdf' }));

    expect(screen.getByText('hello.pdf')).toBeInTheDocument();
    expect(screen.getByText('上传中')).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument());
    expect(onValueChange).toHaveBeenLastCalledWith('attachments', [
      expect.objectContaining({
        id: 'remote-hello.pdf',
        name: 'hello.pdf',
        contentUrl: '/api/mobile/files/remote-hello.pdf/content',
        size: 10,
      }),
    ]);

    await user.click(screen.getByRole('button', { name: '删除 hello.pdf' }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      '/api/mobile/files/remote-hello.pdf',
      expect.objectContaining({ method: 'DELETE' }),
    ));
    expect(onValueChange).toHaveBeenLastCalledWith('attachments', []);
  });

  it('uses backend-compatible default file type filters', () => {
    render(
      <>
        <FileUploadField
          {...baseProps(
            {
              id: 'attachments',
              type: 'file_upload',
              label: '附件',
            },
            [],
          )}
        />
        <ImageUploadField
          {...baseProps(
            {
              id: 'photo',
              type: 'image_upload',
              label: '图片',
            },
            [],
          )}
        />
      </>,
    );

    expect(screen.getByLabelText('附件')).toHaveAttribute(
      'accept',
      'image/jpeg,image/png,application/pdf',
    );
    expect(screen.getByLabelText('图片')).toHaveAttribute('accept', 'image/jpeg,image/png');
  });

  it('does not notify the parent form from a React state updater while uploading', async () => {
    const user = userEvent.setup();
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    function StatefulUpload() {
      const [value, setValue] = useState<unknown[]>([]);
      return (
        <FileUploadField
          {...baseProps(
            {
              id: 'attachments',
              type: 'file_upload',
              label: '附件',
              props: {
                uploadEndpoint: '/api/mobile/files',
              },
            },
            value,
            (_fieldId, nextValue) => setValue(nextValue as unknown[]),
          )}
        />
      );
    }

    const { container } = render(<StatefulUpload />);

    await user.upload(screen.getByLabelText('附件'), new File(['%PDF-hello'], 'hello.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument());
    expect(container.querySelector('.af-upload-list__progress')).toHaveClass(
      'af-upload-list__progress--success',
    );

    expect(consoleError.mock.calls.flat().join('\n')).not.toContain('Cannot update a component');
    consoleError.mockRestore();
  });

  it('keeps completed upload state when late progress events arrive', async () => {
    vi.stubGlobal('XMLHttpRequest', ManualXMLHttpRequest);
    const user = userEvent.setup();

    function StatefulUpload() {
      const [value, setValue] = useState<unknown[]>([]);
      return (
        <FileUploadField
          {...baseProps(
            {
              id: 'attachments',
              type: 'file_upload',
              label: '附件',
              props: {
                uploadEndpoint: '/api/mobile/files',
              },
            },
            value,
            (_fieldId, nextValue) => setValue(nextValue as unknown[]),
          )}
        />
      );
    }

    const { container } = render(<StatefulUpload />);
    await user.upload(screen.getByLabelText('附件'), new File(['%PDF-proof'], 'proof.pdf', { type: 'application/pdf' }));
    await waitFor(() => expect(ManualXMLHttpRequest.latest).not.toBeNull());
    const request = ManualXMLHttpRequest.latest;
    expect(request).not.toBeNull();

    await act(async () => {
      request?.emitProgress(10);
    });
    expect(screen.getByText('上传中 10%')).toBeInTheDocument();

    await act(async () => {
      request?.emitProgress(100);
      request?.emitUploadComplete();
    });
    expect(screen.getByText('处理中')).toBeInTheDocument();
    expect(container.querySelector('.af-upload-list__progress span')).toHaveStyle({ width: '96%' });

    await act(async () => {
      request?.respond(200, {
        id: 'remote-proof.pdf',
        name: 'proof.pdf',
        contentUrl: '/api/mobile/files/remote-proof.pdf/content',
        contentType: 'application/pdf',
        size: 10,
      });
    });
    await waitFor(() => expect(screen.getByText('已完成')).toBeInTheDocument());

    await act(async () => {
      request?.emitProgress(8);
    });
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.queryByText('上传中 8%')).not.toBeInTheDocument();
  });

  it('previews uploaded image files through the backend content URL', async () => {
    const user = userEvent.setup();
    const open = vi.fn();
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:http://localhost/preview-photo');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.stubGlobal('open', open);
    const { container } = render(
      <FileUploadField
        {...baseProps(
          {
            id: 'photo',
            type: 'image_upload',
            label: '图片',
            props: {
              uploadEndpoint: '/api/mobile/files',
              preview: true,
            },
          },
          [],
        )}
      />,
    );

    await user.upload(screen.getByLabelText('图片'), new File(['image'], 'photo.png', { type: 'image/png' }));
    const preview = await screen.findByRole('button', { name: '预览 photo.png' });
    expect(fetch).toHaveBeenCalledWith(
      '/api/mobile/files/remote-photo.png/content',
      expect.objectContaining({ credentials: 'include' }),
    );
    expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(container.querySelector('img')).toHaveAttribute(
      'src',
      'blob:http://localhost/preview-photo',
    );

    await user.click(preview);

    expect(open).toHaveBeenCalledWith(
      'blob:http://localhost/preview-photo',
      '_blank',
      'noopener,noreferrer',
    );
  });

  it('unlinks historical files from the form value without deleting backend content', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    const fetchMock = vi.mocked(fetch);

    render(
      <FileUploadField
        {...baseProps(
          {
            id: 'attachments',
            type: 'file_upload',
            label: '附件',
            props: {
              uploadEndpoint: '/api/mobile/files',
            },
          },
          [
            {
              id: 'submitted-file',
              name: 'history.png',
              contentUrl: '/api/mobile/files/submitted-file/content',
              contentType: 'image/png',
              size: 12,
            },
          ],
          onValueChange,
        )}
      />,
    );

    expect(await screen.findByText('history.png')).toBeInTheDocument();
    fetchMock.mockClear();

    await user.click(screen.getByRole('button', { name: '删除 history.png' }));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(onValueChange).toHaveBeenLastCalledWith('attachments', []);
  });

  it('shows upload failure state and blocks ready value replacement', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();

    const { container } = render(
      <FileUploadField
        {...baseProps(
          {
            id: 'attachments',
            type: 'file_upload',
            label: '附件',
            props: {
              uploadEndpoint: '/api/mobile/files',
            },
          },
          [],
          onValueChange,
        )}
      />,
    );

    await user.upload(screen.getByLabelText('附件'), new File(['%PDF-oops'], 'oops.pdf', { type: 'application/pdf' }));

    await waitFor(() =>
      expect(container.querySelector('.af-upload-list__status')).toHaveTextContent('上传失败'));
    expect(container.querySelector('.af-upload-list__progress')).toHaveClass(
      'af-upload-list__progress--error',
    );
    expect(container.querySelector('.af-upload-list__error')).toHaveTextContent('上传失败');
    expect(onValueChange).not.toHaveBeenCalledWith('attachments', expect.arrayContaining([expect.anything()]));
    expect(screen.getByRole('button', { name: '重试 oops.pdf' })).toBeInTheDocument();
  });

  it('exposes non-ready upload queue state to schema validation without storing it as ready files', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();
    const node: MobileSchemaNode = {
      id: 'attachments',
      type: 'file_upload',
      label: '附件',
      props: {
        uploadEndpoint: '/api/mobile/files',
      },
    };

    render(<FileUploadField {...baseProps(node, [], onValueChange)} />);

    await user.upload(screen.getByLabelText('附件'), new File(['%PDF-oops'], 'oops.pdf', { type: 'application/pdf' }));

    await waitFor(() => expect(screen.getAllByText('上传失败').length).toBeGreaterThan(0));
    const emittedValue = onValueChange.mock.calls.at(-1)?.[1];

    expect(Array.isArray(emittedValue)).toBe(true);
    expect(emittedValue).toHaveLength(0);
    expect(validateSchemaValues([node], { attachments: emittedValue })).toEqual({
      attachments: '仍有文件未完成上传',
    });
  });

  it('stacks span layout children below 600px', () => {
    vi.stubGlobal('innerWidth', 375);
    render(
      <SpanLayoutField
        {...baseProps(
          {
            id: 'layout',
            type: 'span_layout',
            props: { span: 2 },
            children: [
              { id: 'a', type: 'text', label: 'A' },
              { id: 'b', type: 'text', label: 'B' },
            ],
          },
          null,
        )}
      />,
    );

    expect(screen.getByTestId('span-layout')).toHaveStyle({ gridTemplateColumns: '1fr' });
  });

  it('renders table-list validation errors in the field UI', () => {
    const schema: MobileSchemaNode[] = [
      {
        id: 'rows',
        type: 'table_list',
        label: '明细',
        children: [
          { id: 'name', type: 'text', label: '名称', props: { required: true } },
        ],
      },
    ];

    render(
      <DynamicFormRenderer
        mode="fill"
        schema={schema}
        values={{ rows: [{ name: '' }] }}
        errors={{ rows: '第1行: 请填写名称' }}
        onValueChange={vi.fn()}
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('第1行: 请填写名称');
  });

  it('supports adding, editing, and deleting table list rows within bounds', async () => {
    const user = userEvent.setup();
    const onValueChange = vi.fn();

    render(
      <TableListField
        {...baseProps(
          {
            id: 'rows',
            type: 'table_list',
            label: '明细',
            props: { minRows: 1, maxRows: 2 },
            children: [
              { id: 'name', type: 'text', label: '名称' },
            ],
          },
          [{ name: '初始' }],
          onValueChange,
        )}
      />,
    );

    await user.click(screen.getByRole('button', { name: '新增明细' }));
    expect(onValueChange).toHaveBeenLastCalledWith('rows', [{ name: '初始' }, { name: '' }]);
    expect(screen.getByRole('button', { name: '新增明细' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '展开 第1行' }));
    await user.click(screen.getByRole('button', { name: '编辑 第1行' }));
    await user.clear(screen.getByLabelText('名称'));
    await user.type(screen.getByLabelText('名称'), '更新后');
    expect(onValueChange).toHaveBeenLastCalledWith('rows', [{ name: '更新后' }, { name: '' }]);

    await user.click(screen.getByRole('button', { name: '删除 第1行' }));
    expect(onValueChange).toHaveBeenLastCalledWith('rows', [{ name: '' }]);
  });

  it('keeps table row focus stable while typing and preserves expansion after deleting another row', async () => {
    const user = userEvent.setup();
    const node: MobileSchemaNode = {
      id: 'rows',
      type: 'table_list',
      label: '明细',
      props: { minRows: 1 },
      children: [
        { id: 'name', type: 'text', label: '名称' },
      ],
    };

    function StatefulTable() {
      const [value, setValue] = useState([{ name: '第一行' }, { name: '第二行' }]);
      return (
        <TableListField
          {...baseProps(node, value, (_fieldId, nextValue) => {
            setValue(nextValue as Array<{ name: string }>);
          })}
        />
      );
    }

    render(<StatefulTable />);

    await user.click(screen.getByRole('button', { name: '展开 第2行' }));
    const secondRowInput = screen.getByLabelText('名称');
    await user.click(secondRowInput);
    await user.type(secondRowInput, 'A');
    expect(screen.getByLabelText('名称')).toHaveFocus();

    await user.click(screen.getByRole('button', { name: '删除 第1行' }));
    expect(screen.getByLabelText('名称')).toBeVisible();
  });

  it('resyncs picker labels when the external value changes', async () => {
    const { rerender } = render(
      <UserPickerField
        {...baseProps(
          { id: 'approver', type: 'user_picker', label: '审批人' },
          1001,
        )}
      />,
    );

    await waitFor(() => expect(screen.getByRole('button', { name: '用户1001' })).toBeInTheDocument());

    rerender(
      <UserPickerField
        {...baseProps(
          { id: 'approver', type: 'user_picker', label: '审批人' },
          2002,
        )}
      />,
    );

    await waitFor(() => expect(screen.getByRole('button', { name: '用户2002' })).toBeInTheDocument());
  });

  it('treats malformed optional picker values as invalid while allowing empty values', () => {
    const optionalUser = {
      id: 'approver',
      type: 'user_picker',
      label: '审批人',
    } satisfies MobileSchemaNode;
    const optionalDept = {
      id: 'deptId',
      type: 'dept_picker',
      label: '部门',
    } satisfies MobileSchemaNode;

    expect(validateSchemaValues([optionalUser], { approver: '' })).toEqual({});
    expect(validateSchemaValues([optionalUser], { approver: 'bad-value' })).toEqual({
      approver: '请填写审批人',
    });
    expect(validateSchemaValues([optionalDept], { deptId: null })).toEqual({});
    expect(validateSchemaValues([optionalDept], { deptId: 'bad-value' })).toEqual({
      deptId: '请填写部门',
    });
  });
});
