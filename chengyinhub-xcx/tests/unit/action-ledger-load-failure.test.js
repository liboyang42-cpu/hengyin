const test = require('node:test')
const assert = require('node:assert/strict')
const { loadFailureEventVisibility } = require('../../scripts/uiaudit/build-action-ledger')

test('only actual shared blank error components exclude constant hidden events', () => {
  for (const component of ['components/cy/error/index', 'components/cy/inline-error/index']) {
    for (const [tag, attrs] of [
      ['<cy-error load-failure bind:retry="retry" />', {}],
      ['<cy-error load-failure="{{true}}" />', { 'load-failure': '{{true}}' }],
      ['<cy-error load-failure="true" />', { 'load-failure': 'true' }],
    ]) assert.equal(loadFailureEventVisibility(component, tag, attrs).excluded, true)
    for (const [tag, attrs] of [
      ['<cy-error bind:retry="retry" />', {}],
      ['<cy-error load-failure="{{false}}" />', { 'load-failure': '{{false}}' }],
      ['<cy-error data-load-failure />', {}],
    ]) assert.equal(loadFailureEventVisibility(component, tag, attrs).excluded, false)
  }
  assert.equal(loadFailureEventVisibility('components/cy/custom-error/index',
    '<cy-error load-failure />', {}).excluded, false)
  assert.equal(loadFailureEventVisibility(null, '<view load-failure />', {}).excluded, false)
})

test('dynamic operation visibility remains in the actionable inventory with its condition', () => {
  const condition = '{{!loadErrorIsOperation}}'
  assert.deepEqual(loadFailureEventVisibility('components/cy/error/index',
    '<cy-error load-failure="{{!loadErrorIsOperation}}" bind:retry="retrySubmit" />',
    { 'load-failure': condition }), { excluded: false, condition })
  assert.equal(loadFailureEventVisibility('components/cy/error/index',
    '<cy-error load-failure = "{{permissionDenied}}" />',
    { 'load-failure': '{{permissionDenied}}' }).excluded, false)
})
