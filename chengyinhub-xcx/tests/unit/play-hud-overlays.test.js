const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const overlays = require('../../pages/play/utils/play-hud-overlays.js');

const card = {
  attributes: [
    { stateKey: 'counter.observe', label: '观察', value: 3, max: 5, displayType: 'stars', percent: 60, stars: [] },
    { stateKey: 'counter.nerve', label: '胆量', value: 6, max: 10, displayType: 'bar', percent: 60, stars: [] },
    { stateKey: 'counter.body', label: '体魄', value: 4, max: 8, displayType: 'number', percent: 50, stars: [] },
    { stateKey: 'counter.wit', label: '灵感', value: 1, max: 5, displayType: 'number', percent: 20, stars: [] },
  ],
  rpg: { momentum: { value: 2, max: 6 }, clocks: [
    { key: 'clock.watch', label: '巡夜人警觉', value: 4, max: 6, percent: 67 },
    { key: 'clock.bill', label: '拼出货单', value: 4, max: 4, percent: 100 },
  ], facets: [], aspects: [{ key: 'aspect.night', label: '夜行者', desc: '' }] },
};

test('A4 状态栏第二行只取注册表顺序前 3 项', () => {
  assert.deepEqual(overlays.hudAttrs(card).map(row => row.label), ['观察', '胆量', '体魄']);
  assert.deepEqual(overlays.hudAttrs(null), []);
});

test('章节结算 RPG 小结:进度钟走满写已满,动量最后,最多三格', () => {
  assert.deepEqual(overlays.rpgCells(card).map(cell => [cell.label, cell.value]),
    [['巡夜人警觉', '4/6'], ['拼出货单', '已满'], ['动量', '2/6']]);
  assert.deepEqual(overlays.rpgCells({ rpg: null }), []);
});

test('角色卡:雷达只在 3 项以上画,特质合并,动量画格', () => {
  const view = overlays.charView(card, '雾港夜行');
  assert.equal(view.ava, '雾');
  assert.match(view.radar, /^data:image\/svg\+xml,/);
  assert.equal(overlays.radarUri(card.attributes.slice(0, 2)), '');
  assert.deepEqual(view.traits.map(t => t.label), ['夜行者']);
  assert.equal(view.momentum.pips.filter(p => p.on).length, 2);
});

test('本局成绩:进行中暂列,明细按维度,照片审核中单列,前 5 名', () => {
  const raw = { sessionId: '88', status: 0, totalCenti: 23000, pendingReviewCount: 1,
    byDimension: { TASK: 20000, PUZZLE: 3000, BONUS: 0, PENALTY: 0, ADJUSTMENT: 0 },
    timeline: [{ dimension: 'TASK' }, { dimension: 'TASK' }, { dimension: 'PUZZLE' }], disputes: [], startedAt: 1000 };
  const list = [1, 2, 3, 4, 5, 6].map(rank => ({ rowKey: String(rank), rank: String(rank), scoreCenti: 30000 - rank * 1000,
    elapsedMs: '2530000', mine: rank === 2 }));
  const view = overlays.scoreScreen(raw, { sessionId: '88', list, me: list[1] }, { done: 2, nodes: 5, now: 2000 });
  assert.equal(view.total, '230');
  assert.equal(view.rank, '2');
  assert.equal(view.final, false);
  assert.equal(view.elapsed, '42:10');
  assert.deepEqual(view.rows.map(row => [row.label, row.value]), [['到达节点 × 2', '+200'], ['解谜', '+30'], ['照片 × 1', '审核中']]);
  assert.equal(view.board.length, 5);
  assert.equal(view.board[1].name, '你');
  assert.equal(view.canDispute, false, '进行中不能提出成绩争议(后端要求局终)');
});

test('本局成绩:局终公示期内可申诉,已有待处理争议则不再出,公示结束为最终名次', () => {
  const base = { sessionId: '9', status: 1, totalCenti: 1250, byDimension: { TASK: 1250 }, timeline: [], disputes: [],
    startedAt: 0, endedAt: 60000, disputeWindowDeadline: 10000 };
  const open = overlays.scoreScreen(base, null, { now: 5000 });
  assert.equal(open.canDispute, true);
  assert.equal(open.final, false);
  assert.equal(open.total, '12.5');
  assert.equal(open.elapsed, '01:00');
  assert.equal(overlays.scoreScreen({ ...base, disputes: [{ status: 0 }] }, null, { now: 5000 }).canDispute, false);
  const closed = overlays.scoreScreen(base, null, { now: 20000 });
  assert.equal(closed.final, true);
  assert.equal(closed.canDispute, false);
  assert.equal(overlays.scoreScreen({ sessionId: 'x' }, null), null);
});

test('X8 申诉理由必填 1–500 字', () => {
  assert.equal(overlays.reasonError('  ', '请填写申诉理由'), '请填写申诉理由');
  assert.equal(overlays.reasonError('定位跳了一下', '请填写申诉理由'), '');
  assert.equal(overlays.reasonError('字'.repeat(501), '请填写申诉理由'), '最多 500 字');
});

test('B6/B7/B9 联系主办 / 商家:没有号码整行不出,号码不进文案', () => {
  assert.equal(overlays.contact(null), null);
  assert.equal(overlays.contact({ kind: 'CLUB', name: '城南徒步俱乐部', phone: '' }), null);
  assert.deepEqual(overlays.contact({ kind: 'CLUB', name: '城南徒步俱乐部', phone: '021-5555 0000' }),
    { label: '联系主办', name: '城南徒步俱乐部', phone: '021-5555 0000' });
  assert.equal(overlays.contact({ kind: 'MERCHANT', name: '示例咖啡', phone: '13800000000' }).label, '联系商家');
});

test('游玩页模板:两张全屏层挂在游玩页上,菜单第一行是联系,W8 对局面板与守卫读数已撤', () => {
  const dir = path.join(__dirname, '../../pages/play');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  const overlaysWxml = fs.readFileSync(path.join(dir, 'play-overlays.wxml'), 'utf8');
  assert.match(wxml, /<include src="play-overlays.wxml" \/>/);
  assert.doesNotMatch(wxml, /orient-lifecycle\.wxml/);
  assert.doesNotMatch(wxml, /原局守卫申诉|记录号/);
  const tools = wxml.slice(wxml.indexOf('<scroll-view class="tools tools--corner'));
  assert.ok(tools.indexOf('callPlayContact') < tools.indexOf('openThemeHistory'), '联系主办在菜单第一行');
  for (const word of ['我的角色', '本局用时', '当前得分', '同行者榜', '本次到达未记录', '暂列']) assert.ok(wxml.includes(word), word);
  assert.match(overlaysWxml, /aria-label="我的角色"/);
  assert.match(overlaysWxml, /aria-label="本局成绩"/);
  assert.match(overlaysWxml, />申诉<\/text>/);
  assert.doesNotMatch(wxml + overlaysWxml, /参与详情/);
});

test('成绩争议:原生输入框校验理由,同一理由重试复用同一幂等键,成功后不再出申诉', async () => {
  const modal = require('../../utils/modal.js');
  const original = modal.show;
  const prompts = [], calls = [];
  modal.show = options => prompts.push(options);
  global.wx = Object.assign(global.wx || {}, { showToast() {} });
  let reply = { code: 500, msg: '网络开小差' };
  const keys = ['k1', 'k2'];
  const handlers = overlays.methods((url, method, data) => { calls.push([url, method, data]); return Promise.resolve(reply); }, () => keys.shift());
  const page = Object.assign({ data: { mode: 1, isPreview: false, isMock: false,
    scoreOv: { show: true, state: 'ok', view: { sessionId: '88', canDispute: true, disputed: false } } },
    _routeState: { sessionId: '88' }, _isPlayAccountCurrent: () => true,
    setData(patch) { Object.keys(patch).forEach(key => { const parts = key.split('.'); let at = this.data; parts.slice(0, -1).forEach(p => { at = at[p]; }); at[parts.at(-1)] = patch[key]; }); } }, handlers);
  try {
    page.disputeScore();
    assert.equal(prompts[0].title, '申诉成绩');
    assert.equal(prompts[0].validate(''), '请填写申诉理由');
    prompts[0].success({ confirm: true, content: '第 3 个节点已到达但没有计分' });
    await new Promise(r => setImmediate(r));
    page.disputeScore();
    reply = { code: 200, data: {} };
    prompts[1].success({ confirm: true, content: '第 3 个节点已到达但没有计分' });
    await new Promise(r => setImmediate(r));
    assert.deepEqual(calls.map(c => [c[0], c[1], c[2].idempotencyKey]),
      [['/api/orient/scoring/dispute', 'POST', 'k1'], ['/api/orient/scoring/dispute', 'POST', 'k1']]);
    assert.equal(page.data.scoreOv.view.canDispute, false);
    assert.equal(page.data.scoreOv.view.disputed, true);
  } finally { modal.show = original; }
});
