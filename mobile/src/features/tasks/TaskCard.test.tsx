import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { TaskCard } from './TaskCard';

describe('TaskCard', () => {
  it('shows unread copied tasks as pending review even when the workflow is complete', () => {
    render(
      <MemoryRouter>
        <TaskCard
          returnSearch="returnView=pending"
          item={{
            kind: 'task',
            view: 'pending',
            task: {
              id: 29,
              instanceId: 12,
              nodeId: 'cc1',
              formCode: 'change',
              formName: '本田变化点',
              businessNo: '000012',
              applicantName: 'test1',
              nodeName: '抄送人',
              taskType: 'APPROVAL',
              taskStatus: 'CC',
              instanceStatus: 'APPROVED',
              createdAt: '2026-08-24T15:22:00+08:00',
              readAt: null,
            },
          }}
        />
      </MemoryRouter>,
    );

    const card = screen.getByRole('link', { name: /本田变化点/ });
    expect(within(card).getByText('待查阅')).toBeInTheDocument();
    expect(card).toHaveAttribute('href', '/tasks/29?returnView=pending');
  });
});
