import type { MobileFieldProps } from '../schema/types';
import { fieldLabel } from './fieldShared';

export function SectionField(props: MobileFieldProps) {
  const description = props.node.props?.description;

  return (
    <section className="af-nested-section" aria-label={fieldLabel(props.node)}>
      <header className="af-nested-section__head">
        <strong>{fieldLabel(props.node)}</strong>
        {typeof description === 'string' && description.trim() ? (
          <small>{description}</small>
        ) : null}
      </header>
      <div className="af-nested-section__body">
        {props.renderChildren?.(props.node.children ?? [])}
      </div>
    </section>
  );
}

export default SectionField;
