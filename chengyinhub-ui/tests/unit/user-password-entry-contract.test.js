const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/system/user/index.vue'), 'utf8');

test('opening user administration loads users and departments without requesting a sensitive default password', () => {
  const created = source.match(/created\(\)\s*\{([\s\S]*?)\n  \},\n  methods/);
  assert.ok(created);
  const calls = [];
  new Function(created[1]).call({ getList() { calls.push('users'); }, getDeptTree() { calls.push('departments'); },
    getConfigKey() { throw new Error('sensitive configuration must not be read'); } });
  assert.deepEqual(calls, ['users', 'departments']);
  assert.doesNotMatch(source, /initPassword|sys\.user\.initPassword/);
});

test('adding a user leaves password empty while preserving roles, posts and required length validation', async () => {
  const handler = source.match(/handleAdd\(\)\s*\{([\s\S]*?)\n    \},/);
  assert.ok(handler);
  const actor = { form: { password: 'previous entry' }, reset() { this.form = {}; }, open: false };
  new Function('getUser', handler[1]).call(actor, () => Promise.resolve({ posts: [{ id: 3 }], roles: [{ id: 9 }] }));
  await Promise.resolve();
  assert.equal(actor.form.password, '');
  assert.equal(actor.open, true);
  assert.deepEqual(actor.postOptions, [{ id: 3 }]);
  assert.deepEqual(actor.roleOptions, [{ id: 9 }]);
  const rules = source.match(/password:\s*\[([\s\S]*?)\n        \]/);
  assert.ok(rules);
  assert.match(rules[1], /required:\s*true/);
  assert.match(rules[1], /min:\s*5,\s*max:\s*20/);
  assert.match(source, /v-model="form\.password"[^>]*type="password"/);
});
