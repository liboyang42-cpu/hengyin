/** Exact rendered button text, independent of decorative CSS icon accessibility glyphs. */
function exactButtonText(label) {
  if (typeof label !== 'string' || !label || label.trim() !== label || /[\x00-\x1f\x7f]/.test(label)) throw new Error('Explicit nonempty button label required')
  return new RegExp(`^\\s*${label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*$`, 'u')
}
function buttonByText(scope, label) {
  // Preserve Playwright's button-role and strictness checks. Never pick first(), force a click,
  // match a substring, or include hidden controls to make a missing action look present.
  return scope.getByRole('button').filter({ hasText: exactButtonText(label) })
}
module.exports = { exactButtonText, buttonByText }
