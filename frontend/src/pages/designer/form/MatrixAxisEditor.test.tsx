import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MatrixAxisEditor } from './MatrixAxisEditor';

describe('MatrixAxisEditor', () => {
  it('keeps existing ids by position during batch editing', () => {
    const onChange = vi.fn();
    render(
      <MatrixAxisEditor
        axis="row"
        max={4}
        items={[
          { id: 'row_a', label: '原行A' },
          { id: 'row_b', label: '原行B' },
        ]}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '批量编辑' }));
    fireEvent.change(screen.getByLabelText('批量行名称'), {
      target: { value: '新行A\n新行B\n新行C' },
    });
    fireEvent.click(screen.getByRole('button', { name: /应\s*用/ }));

    const next = onChange.mock.calls[0]?.[0];
    expect(next[0]).toEqual({ id: 'row_a', label: '新行A' });
    expect(next[1]).toEqual({ id: 'row_b', label: '新行B' });
    expect(next[2].id).toMatch(/^row_/);
  });

  it('rejects duplicate batch labels', () => {
    render(
      <MatrixAxisEditor
        axis="column"
        max={3}
        items={[{ id: 'col_a', label: '列A' }]}
        onChange={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: '批量编辑' }));
    fireEvent.change(screen.getByLabelText('批量列名称'), {
      target: { value: '重复\n重复' },
    });
    expect(screen.getByText('名称不能重复')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /应\s*用/ })).toBeDisabled();
  });
});
