const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');

function readCard(attributes, definitionSource = 'TEMPLATE_LIVE') {
  let definition, request;
  const filename = path.join(root, 'subpackageMember/character/index.js');
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    getApp: () => ({ globalData: {}, getUserID: () => '7', getAuthorization: () => 'fixture',
      sendRequest: options => { request = options; } }),
    Page: value => { definition = value; },
    require: id => id.endsWith('auth/require-login.js') ? {} : require(path.resolve(path.dirname(filename), id)),
  }, { filename });
  const page = Object.assign({}, definition, { data: {}, setData(patch) { Object.assign(this.data, patch); } });
  page.onLoad({ topicId: '901' }); page.onShow();
  const created = definitionSource === 'SESSION_SNAPSHOT_V1';
  request.success({ code: 200, data: { topicId: '901', created, running: false, definitionSource, attributes } });
  assert.equal(page.data.state, created ? 'ready' : 'template');
  return page.data.card;
}
const row = extra => ({ stateKey: 'counter.hp', label: '体力', value: 3, min: 0, max: 5,
  displayType: 'bar', persistScope: 'topic', color: '#12abEF', icon: 'heart', ...extra });

test('actual character Page preserves authored appearance, stable keys and server ordering', () => {
  const card = readCard([row({ stateKey: 'counter.second', label: '新名' }), row({ displayType: 'stars' })]);
  assert.deepEqual(card.attributes.map(a => a.stateKey), ['counter.second', 'counter.hp']);
  assert.equal(card.attributes[0].label, '新名');
  assert.equal(card.attributes[0].color, '#12abEF');
  assert.equal(card.attributes[0].icon, 'heart');
  assert.equal(card.attributes[1].stars.filter(s => s.filled).length, 3);
  const markup = fs.readFileSync(path.join(root, 'subpackageMember/character/index.wxml'), 'utf8');
  assert.match(markup, /name="\{\{item.icon\}\}"/);
  assert.match(markup, /background-color: ' \+ item.color/);
});

test('actual character Page excludes untrusted styles and hidden appearance', () => {
  const card = readCard([row({ color: 'red; background:url(secret)', icon: 'https://secret/image' }),
    row({ stateKey: 'counter.secret', visible: false, icon: 'secret' })]);
  assert.equal(card.attributes.length, 1);
  assert.equal(card.attributes[0].color, '');
  assert.equal(card.attributes[0].icon, '');
  assert.doesNotMatch(JSON.stringify(card), /secret|url\(/);
});

test('partial snapshot never acquires appearance or persistence from supplied live metadata', () => {
  const card = readCard([row({ displayType: null, persistScope: null })], 'SESSION_SNAPSHOT_PARTIAL');
  assert.equal(card.attributes[0].color, '');
  assert.equal(card.attributes[0].icon, '');
  assert.equal(card.attributes[0].scopeText, '保留范围未记录');
});

test('completed versioned character uses the frozen appearance and explicit definition note', () => {
  const card = readCard([row({ value: 2 })], 'SESSION_SNAPSHOT_V1');
  assert.equal(card.created, true);
  assert.equal(card.running, false);
  assert.equal(card.attributes[0].color, '#12abEF');
  assert.equal(card.attributes[0].persistScope, 'topic');
  assert.equal(card.attributes[0].value, 2);
  assert.match(card.definitionNote, /随本局冻结/);
});
