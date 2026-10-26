'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('Page lifecycle adapter preserves each route, exact ID, request inputs and unresolved response', async () => {
  const source = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  const start = source.indexOf('...orientLifecycle.methods(') + '...orientLifecycle.methods('.length;
  const end = source.indexOf('\n  ...r5PlayerRuntime.methods()', start);
  const calls = []; let resolve;
  const response = new Promise(done => { resolve = done; });
  const send = vm.runInNewContext('(' + source.slice(start, end).trim().slice(0, -2) + ')', {
    req: (...args) => { calls.push(args); return response; }
  });
  const cases = ['current', 'prepare', 'start', 'abandon', 'receipt'].map(action =>
    ['/api/play/orient/session/' + action, ['current', 'receipt'].includes(action) ? 'GET' : 'POST']);
  cases.push(['/api/play/orient/session/task/9007199254740993/result', 'POST'],
    ['/api/orient/scoring/session/9007199254740993', 'GET']);
  for (const [url, method] of cases) {
    const payload = { sessionId: '9007199254740993', idempotencyKey: 'original-request' };
    assert.equal(send(url, method, payload), response);
    const args = calls.at(-1);
    assert.equal(args[0], url); assert.equal(args[1], method); assert.equal(args[2], payload);
    assert.equal(args[3]['content-type'], 'application/x-www-form-urlencoded'); assert.equal(args[4], true);
  }
  const unknown = { code: 'fail', netFail: true }; resolve(unknown);
  assert.equal(await response, unknown);
  assert.throws(() => send('/api/unrelated', 'POST', {}), /Unknown orient lifecycle endpoint/);
  assert.equal(calls.length, 7);
});
