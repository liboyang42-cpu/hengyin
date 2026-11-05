const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view');
const ROOT = path.resolve(__dirname, '../..');
function component(name, data) {
  global.Behavior = x => x;
  let def; global.Component = x => { def = x; };
  const file = path.join(ROOT, 'pages/play/components', name, 'index.js');
  delete require.cache[require.resolve(file)]; require(file);
  const instance = Object.assign({}, def.methods, { data: { ...def.data, ...data },
    setData(patch) { Object.assign(this.data, patch); }, selectComponent: () => null,
    triggerEvent(name, detail) { this.events.push({ name, detail }); }, events: [] });
  return { def, instance };
}
test('daily sign launch claims only after reveal and sends no note or photo', () => {
  global.wx = { vibrateShort() {} };
  const { instance: c } = component('playkit-dailysign', { claimed: false, writing: false, reducedMotion: true });
  c.onAccept(); assert.deepEqual(c.events, []);
  c._unlock(); c.onAccept(); assert.deepEqual(c.events, [{ name: 'accept', detail: {} }]);
  assert.deepEqual(serverPayload('dailysign', 'accept', { text: 'discarded', photoUrl: '/discarded' }), {});
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-dailysign/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /textarea|onTextInput|onShoot|myText|myPhotoUrl/);
});
test('guess-image replays only server verdict and keeps retry available until finished', () => {
  const { def, instance: c } = component('playkit-pricepair', { items: [{ id: 'a' }, { id: 'b' }],
    attempts: 1, finished: false, passed: false, lastPickId: 'b', answerId: '' });
  const observe = Object.values(def.observers)[0];
  observe.call(c, true, c.data.items);
  assert.equal(c.data.posters[1].mark, '不是'); assert.equal(c.data.revealed, false);
  c.data.finished = true; c.data.answerId = 'a'; observe.call(c, true, c.data.items);
  assert.equal(c.data.posters[0].mark, '正确答案'); assert.equal(c.data.revealed, true);
  assert.equal(pickPlayKit({ playKit: { pricePair: { lastPickId: 'b' } } }).lastPickId, 'b');
});
test('legacy album precedes random card; acknowledged album yields to card even after all draws', () => {
  const view = { sessionId: 9, version: 2, playKit: { album: { images: [{ url: '/a', caption: 'old' }], done: false },
    random: { drawCount: 1, drawn: [] } } };
  const first = pickPlayKit(view); assert.equal(first.type, 'album'); assert.equal(first.completionEnabled, false);
  assert.equal(first.beforeCard, true); assert.deepEqual(first.images, view.playKit.album.images);
  assert.equal(serverAction('album', 'ack'), 'SUBMIT_ALBUM'); assert.deepEqual(serverPayload('album', 'ack'), { viewed: true });
  view.playKit.album.done = true; assert.equal(pickPlayKit(view).type, 'random');
  view.playKit.random.drawn = [{ id: 'card', label: 'name', content: 'real content' }];
  assert.equal(pickPlayKit(view).type, 'random');
});
test('drawn card immediately opens its server content and unseen cards stay hidden', () => {
  const { def, instance: c } = component('playkit-random', { cards: [], drawCount: 2,
    drawn: [{ id: 'a', label: 'title', content: 'server content' }] });
  Object.values(def.observers)[0].call(c, true);
  assert.equal(c.data.detailOn, true); assert.deepEqual(c.data.detail, { title: 'title', body: 'server content' });
  assert.equal(c.data.deck[1].opened, false); assert.equal(c.data.deck[1].body, '');
  c._clear();
});
