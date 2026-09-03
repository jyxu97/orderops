interface Props {
  label: string;
  value: number;
  /** Drives a small colored mark beside the value; the value itself stays in primary ink. */
  tone?: 'neutral' | 'active' | 'success' | 'danger';
  hint?: string;
}

/**
 * A headline number.
 *
 * The value wears primary ink rather than the tone colour, with the tone carried by a mark
 * beside it. Tinting the number itself would put meaning in colour alone and drag small text
 * onto colours picked for marks, where a 3:1 mark contrast is fine but AA body text is not.
 *
 * Proportional figures on purpose: `tabular-nums` gives every digit the width of a zero, which
 * makes a number like 121 look gappy at display size. Tabular figures belong in table columns
 * that must align vertically, not here.
 */
export function StatTile({ label, value, tone = 'neutral', hint }: Props) {
  return (
    <div className="stat" title={hint}>
      <span className="stat__label">{label}</span>
      <span className="stat__value">
        {tone !== 'neutral' && <span className={`stat__mark stat__mark--${tone}`} aria-hidden="true" />}
        {value.toLocaleString()}
      </span>
      {hint && <span className="stat__hint">{hint}</span>}
    </div>
  );
}
