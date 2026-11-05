// 第十二轮 k3(p3 N6):模板「应用」出的草稿进编辑器,只有一个俱乐部时「主题详情」里那一行显示已选,
// 点完成却 toast「请选择路线归属的俱乐部」。根因:loadMyClubs 先把唯一俱乐部写进 formData.clubId,
// 随后草稿回填把它覆盖成 ''(模板复制件没有 clubId),clubIndex 仍是 0。
// reconcileClubPick(文件尾函数)让「看上去选中」与「提交值」重新对齐;经由 submit 前的同一调用验证。
const { test } = require('node:test');
const assert = require('node:assert/strict');

global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest() {}, tips() {},
  getRequestErrorMessage: (r, f) => f, getUserID: () => 7, getToken: () => '' });
global.wx = { getStorageSync() {}, setStorageSync() {}, removeStorageSync() {}, getSystemInfoSync: () => ({ windowWidth: 375 }),
  showToast() {}, showLoading() {}, hideLoading() {}, showModal() {}, navigateTo() {}, redirectTo() {}, pageScrollTo() {}, nextTick: (f) => f() };
let cfg = null;
global.Page = (c) => { cfg = c; };
require('../../pages/publish/fabu/index.js');

function page(data) {
  const p = Object.assign({}, cfg);
  p.data = JSON.parse(JSON.stringify(Object.assign({ formData: {}, myClubs: [], clubIndex: -1 }, data)));
  p.setData = function (patch, cb) {
    Object.keys(patch).forEach((k) => {
      if (k.startsWith('formData.')) this.data.formData[k.slice(9)] = patch[k];
      else this.data[k] = patch[k];
    });
    if (cb) cb();
  };
  p.refreshPrimaryActionState = () => {};
  p._reconcileClubPick = () => reconcileClubPick(p);
  return p;
}

// 函数在 fabu/index.js 文件尾(不挪上方按行号锚定的基线),这里从源码取出同一份函数体来测,
// 并钉住两处调用点都在。
const fabuSrc = require('fs').readFileSync(require('path').join(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
const fnSrc = fabuSrc.slice(fabuSrc.indexOf('function reconcileClubPick(page) {'));
// eslint-disable-next-line no-new-func
const reconcileClubPick = new Function(fnSrc + '\nreturn reconcileClubPick;')();
test('草稿回填之后与提交之前都调用 reconcileClubPick', () => {
  assert.match(fabuSrc, /this\.refreshRouteGraph\(\); reconcileClubPick\(this\);/);
  assert.match(fabuSrc, /const isCityOrienteering = Number\(this\.data\.formData\.productType\) === 1; reconcileClubPick\(this\);/);
});

test('只有一个俱乐部、草稿回填把 clubId 清空:重新选中它,提交值与显示一致', () => {
  const p = page({ myClubs: [{ id: 7001, name: '城瘾跑团' }], clubIndex: 0, formData: { clubId: '' } });
  p._reconcileClubPick();
  assert.equal(p.data.formData.clubId, 7001);
  assert.equal(p.data.clubIndex, 0);
});

test('多个俱乐部且没选:显示回到「未选择」,不替主理人决定', () => {
  const p = page({ myClubs: [{ id: 1 }, { id: 2 }], clubIndex: 1, formData: { clubId: '' } });
  p._reconcileClubPick();
  assert.equal(p.data.formData.clubId, '');
  assert.equal(p.data.clubIndex, -1);
});

test('已有归属:下标跟着 id 走', () => {
  const p = page({ myClubs: [{ id: 1 }, { id: 2 }], clubIndex: 0, formData: { clubId: 2 } });
  p._reconcileClubPick();
  assert.equal(p.data.clubIndex, 1);
  assert.equal(p.data.formData.clubId, 2);
});

// 第十二轮 k3(p3 N5):团码场次列表不列主题自办壳。
test('团码场次列表跳过 selfHostShell,只剩真实场次', () => {
  const { listGroupCodeActivities } = require('../../utils/group-code-session.js');
  const out = listGroupCodeActivities([
    { id: 15, name: '人民广场', startDate: '2026-11-05 22:00:00' },
    { id: 9, name: '人民广场', startDate: '2026-11-05 10:18:00', selfHostShell: true },
  ]);
  assert.deepEqual(out.map((a) => a.id), [15]);
});

// 第十二轮 k3(p2 N-7):审核已结束、商家没留意见 → 不写「等待商家意见」。
test('退款详情:审核结束且无商家意见时显示「商家未提交意见」;仍可回应时照旧「等待商家意见」', () => {
  const { shapeAftercareDetail } = require('../../pages/merchant/aftercare/detail/view-model.js');
  const closed = shapeAftercareDetail({ refundId: 81, processing: 'REFUNDED', merchantOpinion: 'PENDING',
    canRespond: false, allowedDecisions: [], responses: [] });
  assert.equal(closed.merchantOpinionText, '商家未提交意见');
  const open = shapeAftercareDetail({ refundId: 82, processing: 'WAITING_PLATFORM_REVIEW', merchantOpinion: 'PENDING',
    canRespond: true, allowedDecisions: ['AGREE'], responses: [] });
  assert.equal(open.merchantOpinionText, '等待商家意见');
});

// 第十二轮 k3(p3 N8):开一场默认集合时间不能是已经过去的「今天 09:00」。
test('开一场默认集合时间晚于现在(上海钟),晚上就给明天 09:00', () => {
  const realNow = Date.now;
  let cfgEvent = null;
  const prevPage = global.Page;
  global.Page = (c) => { cfgEvent = c; };
  global.getApp = () => ({ globalData: {}, sendRequest() {}, getUserID: () => 9003 });
  global.wx = Object.assign({}, global.wx, { setNavigationBarTitle() {}, stopPullDownRefresh() {} });
  try {
    for (const [iso, date, time] of [
      ['2026-11-05T02:20:00Z', '2026-11-05', '12:00'],   // 上海 10:20 → 12:00
      ['2026-11-05T13:30:00Z', '2026-11-06', '09:00'],   // 上海 21:30 → 明天 09:00
    ]) {
      Date.now = () => Date.parse(iso);
      delete require.cache[require.resolve('../../pages/club/event-ops/index.js')];
      require('../../pages/club/event-ops/index.js');
      const p = Object.assign({}, cfgEvent);
      p.data = JSON.parse(JSON.stringify(cfgEvent.data));
      p.data.minDate = '2026-11-05';
      p.setData = function (patch) { Object.assign(this.data, patch); };
      p.loadAccess = () => {};
      p.onLoad({ clubId: '7001' });
      assert.equal(p.data.startDate, date, iso);
      assert.equal(p.data.startTime, time, iso);
    }
  } finally {
    Date.now = realNow;
    global.Page = prevPage;
  }
});
