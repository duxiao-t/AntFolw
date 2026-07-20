import { useEffect, useState } from 'react';
import type { MobileFieldProps } from '../schema/types';
import { fieldLabel, FieldShell } from './fieldShared';

export function SpanLayoutField(props: MobileFieldProps) {
  const [narrow, setNarrow] = useState(isNarrow());

  useEffect(() => {
    const onResize = () => setNarrow(isNarrow());
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  return (
    <FieldShell label={fieldLabel(props.node)}>
      <section
        data-testid="span-layout"
        style={{
          display: 'grid',
          gap: 8,
          gridTemplateColumns: narrow ? '1fr' : `repeat(${Math.max(1, numberValue(props.node.props?.span, props.node.children?.length ?? 1))}, minmax(0, 1fr))`,
        }}
      >
        {props.renderChildren?.(props.node.children ?? [])}
      </section>
    </FieldShell>
  );
}

function isNarrow() {
  return typeof window !== 'undefined' ? window.innerWidth < 600 : true;
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && value > 0 ? value : fallback;
}
