/** Shared keyboard focus ring for chrome controls (drawer, toolbar, settings rows). */
export const CHROME_FOCUS =
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary';

/**
 * Focus for a text field: the same 2px ring as chrome controls. A field used to signal focus by
 * tinting its 1px border at 40–50% opacity, a change too faint to find the caret's field by.
 */
export const INPUT_FOCUS =
  'outline-none focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-primary focus:border-brand-primary';
