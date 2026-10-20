// NOT_RUN: execution deferred to local verification.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPublishWorkflow } = require('../../pages/publish/utils/publish/publish-workflow.js');
test('synchronous request exception becomes UNKNOWN and a retained callback cannot later settle it', () => {
  let callback;
  let calls = 0;
  let failures = 0;
  const workflow = createPublishWorkflow({ request(payload, cb) { calls++; callback=cb; throw new Error('after possible dispatch'); } });
  assert.doesNotThrow(() => workflow.submit({}, { onFail:res=>{assert.equal(res.networkError,true);failures++;} }));
  assert.equal(workflow.getState(), 'unknown');
  assert.equal(workflow.isBusy(), false);
  callback({ ok:true, data:77 });
  assert.equal(workflow.getState(), 'unknown');
  assert.equal(failures, 1);
  assert.equal(workflow.submit({}), false);
  assert.equal(calls, 1);
});
test('exception after a completed callback does not replace the completed state', () => {
  const workflow = createPublishWorkflow({ request(payload, cb) { cb({ ok:true }); throw new Error('late throw'); } });
  assert.throws(() => workflow.submit({}), /late throw/);
  assert.equal(workflow.getState(), 'done');
});
