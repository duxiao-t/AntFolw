import type { ReactNode } from 'react';

type NativeActionContentProps = {
  icon: ReactNode;
  title: ReactNode;
  hint: ReactNode;
};

export function NativeActionContent({ icon, title, hint }: NativeActionContentProps) {
  return (
    <>
      {icon}
      <span>
        <strong>{title}</strong>
        <small>{hint}</small>
      </span>
    </>
  );
}
