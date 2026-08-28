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
    <FieldShell
      node={props.node}
      label={fieldLabel(props.node)}
      className="af-field--span-layout"
    >
      <section
        data-testid="span-layout"
        style={{
          display: 'grid',
          gap: 8,
          gridTemplateColumns:
            narrow && props.node.props?.mobileSingleColumn !== false
              ? '1fr'
              : `repeat(${Math.max(1, numberValue(props.node.props?.columns ?? props.node.props?.span, props.node.children?.length ?? 1))}, minmax(0, 1fr))`,
          borderBottom:
            props.node.props?.showBorder === false
              ? undefined
              : `1px solid ${stringValue(props.node.props?.dividerColor, '#d9d9d9')}`,
          paddingBottom: props.node.props?.showBorder === false ? undefined : 12,
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

function stringValue(value: unknown, fallback: string) {
  return typeof value === 'string' && value ? value : fallback;
}
