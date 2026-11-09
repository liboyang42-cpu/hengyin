const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '../..');
const SIGNED = 'chengyin-oss.oss-cn-shanghai.aliyuncs.com/upload/a.jpg?Expires=1&OSSAccessKeyId=k&Signature=s%3D';

function loadApp() {
  let config = null;
  const sandbox = {
    App: (c) => { config = c; },
    getApp: () => ({}),
    wx: {},
    require: (request) => (request.startsWith('.') ? require(path.join(root, request)) : require(request)),
    console,
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(root, 'app.js'), 'utf8'), sandbox, { filename: 'app.js' });
  return config;
}

function loadImgWxs() {
  const sandbox = { module: { exports: {} }, getRegExp: (p, f) => new RegExp(p, f) };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(root, 'utils/wxs/img.wxs'), 'utf8'), sandbox);
  return sandbox.module.exports;
}

test('自有 OSS / 站点的 http 签名链接改走 https，签名参数原样保留；外域仍拒绝', () => {
  const app = loadApp();
  assert.equal(app.getImgUrl('http://' + SIGNED), 'https://' + SIGNED);
  assert.equal(app.getImgUrl('https://' + SIGNED), 'https://' + SIGNED);
  assert.equal(app.getImgUrl('http://www.chengyinhub.com/prod-api/profile/a.png'), 'https://www.chengyinhub.com/prod-api/profile/a.png');
  assert.equal(app.getImgUrl('http://evil.example.com/a.jpg'), '');
  assert.equal(app.getImgUrl('/images/route_city_cover.png'), '/images/route_city_cover.png');
});

test('img.wxs first/list 同口径升级自有域名，不碰外域与本地图', () => {
  const img = loadImgWxs();
  assert.equal(img.first('http://' + SIGNED + ',http://' + SIGNED), 'https://' + SIGNED);
  assert.deepEqual(Array.from(img.list('http://' + SIGNED + ',/images/x.png')), ['https://' + SIGNED, '/images/x.png']);
  assert.equal(img.first('http://other.example.com/a.jpg'), 'http://other.example.com/a.jpg');
  // 生产 imgArr 用分号分隔:不能把整串当一张图(实走 A1:详情第二屏 403 整块黑)
  assert.deepEqual(Array.from(img.list('http://' + SIGNED + ';http://' + SIGNED)), ['https://' + SIGNED, 'https://' + SIGNED]);
  assert.equal(img.first('http://' + SIGNED + ';/images/x.png'), 'https://' + SIGNED);
});

for (const kind of ['public', 'mine']) {
  test(`模板选择器 ${kind} 复用实际首图与可信资源规范化，空源不造图且原记录不变`, () => {
    let definition;
    const app = loadApp(), sent = [];
    app.globalData = { ...app.globalData, assetBaseUrl: 'https://www.chengyinhub.com/prod-api/' };
    app.getUserID = () => '7'; app.getAuthorization = () => 'actor-7'; app.sendRequest = request => sent.push(request);
    global.getApp = () => app;
    global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({}) };
    global.Page = value => { definition = value; };
    delete require.cache[require.resolve('../../pages/publish/fabu/index')];
    require('../../pages/publish/fabu/index');
    const page = { ...definition, data: structuredClone(definition.data),
      setData(patch) { Object.assign(this.data, patch); }, _refreshSupportMatrix() {} };
    const rows = [
      { imgUrl: 'http://' + SIGNED }, { imgUrl: 'https://' + SIGNED },
      { imgUrl: '2026/a.jpg' }, { imgUrl: 'http://evil.example.com/a.jpg' },
      { imgUrl: `;http://${SIGNED};https://www.chengyinhub.com/second.jpg` },
      { imgUrl: '' }, { imgUrl: null }, {}, { imgUrl: '/images/route_city_cover.png' },
    ].map((item, i) => ({ ...item, id: String(900 + i), title: '真实来源', draftStatus: 1, status: 1, sourceVersion: 'original' }));
    const before = structuredClone(rows);
    if (kind === 'public') page.getPublicTempList(); else page.getTempList();
    assert.equal(sent.length, 1);
    sent[0].success({ code: 200, data: { rows, total: rows.length } });
    const projected = kind === 'public' ? page.data.publicTempList : page.data.tempList;
    assert.deepEqual(projected.map(row => row.imgUrl), ['https://' + SIGNED, 'https://' + SIGNED,
      'https://www.chengyinhub.com/prod-api/2026/a.jpg', '', 'https://' + SIGNED, '', null, undefined, '/images/route_city_cover.png']);
    assert.deepEqual(rows, before);
    assert.deepEqual(projected.map(row => [row.id, row.title, row.sourceVersion]), rows.map(row => [row.id, row.title, row.sourceVersion]));
    assert.equal(sent.length, 1, '只做列表读取，不触发采用或保存');
  });
}

function playHost() {
  let definition;
  const app = {
    globalData: {}, _loginEpoch: 1, _featureFlagsEpoch: 1, owner: '7', auth: 'token-a', respond: null,
    getUserID: () => app.owner,
    getAuthorization: () => app.auth,
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    sendRequest: (options) => app.respond(options),
  };
  global.getApp = () => app;
  global.Page = (value) => { definition = value; };
  global.wx = { getStorageSync: () => '', showToast() {}, nextTick(fn) { fn(); } };
  const file = require.resolve('../../pages/play/index');
  delete require.cache[file];
  require(file);
  const page = Object.assign({}, definition, {
    data: { ...definition.data, topicId: '990069', loading: true },
    setData(patch) { Object.assign(this.data, patch); },
  });
  page._playOwner = page._playMember();
  page._playLoginContext = page._capturePlayLoginContext();
  return { app, page };
}

test('灰度开关重拉、401 静默续期换 token 仍是同一账号：play 首屏不被丢弃、不停在黑屏 loading', async () => {
  const { app, page } = playHost();
  app.respond = (options) => {
    // 回包前：开关重拉(_featureFlagsEpoch++) + 续期换 token，账号与登录代次不变
    app._featureFlagsEpoch++;
    app.auth = 'token-b';
    options.success({ code: 402, msg: '请先购买自玩通行证' });
  };
  page.loadData(true);
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(page._isPlayAccountCurrent(), true);
  assert.equal(page.data.loading, false);
  assert.equal(page.data.emptyKind, 'needPass');
});

test('真换号（会话清除推进登录代次 / 换成员）仍丢弃在途回包', async () => {
  for (const change of [(app) => { app._loginEpoch++; }, (app) => { app.owner = '8'; }]) {
    const { app, page } = playHost();
    app.respond = (options) => { change(app); options.success({ code: 402, msg: '请先购买自玩通行证' }); };
    page.loadData(true);
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(page._isPlayAccountCurrent(), false);
    assert.equal(page.data.emptyKind, '');
  }
});
