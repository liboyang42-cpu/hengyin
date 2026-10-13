const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ROOT = path.resolve(__dirname, '../..');

function load(file, wx, app = {}, asyncLoad) {
  let definition;
  vm.runInNewContext(fs.readFileSync(path.join(ROOT, file), 'utf8'), {
    Component: (value) => { definition = value; }, Page: (value) => { definition = value; },
    wx, getApp: () => app,
    require: (id, success, fail) => success ? asyncLoad(id, success, fail)
      : id.includes('font-scale') ? { readPageStyle: () => '' }
        : require(path.resolve(path.dirname(path.join(ROOT, file)), id))
  }, { filename: file });
  const instance = Object.assign({}, definition.methods || definition, {
    data: Object.assign({}, definition.data),
    setData(value) { Object.assign(this.data, value); }
  });
  return { definition, instance };
}

function placeholder(overrides = {}, asyncLoad) {
  const requests = [], listeners = new Set();
  let role = 'player';
  const wx = Object.assign({
    onLazyLoadError: (listener) => listeners.add(listener),
    offLazyLoadError: (listener) => listeners.delete(listener)
  }, overrides);
  const result = load('components/cy/profile-loading/index.js', wx, {
    getUserRole: () => role, getUserType: () => 1,
    sendRequest: () => { throw new Error('占位不能请求业务数据'); }
  }, asyncLoad || ((id, success, fail) => requests.push({ id, success, fail })));
  result.definition.lifetimes.attached.call(result.instance);
  return Object.assign(result, { requests, listeners, wx, setRole(value) { role = value; } });
}

test('主页冷加载保留真实身份导航，只请求现有会员分包且不重复提交下载', () => {
  const p = placeholder();
  assert.equal(p.requests[0].id, '../../../subpackageMember/profile-load.js');
  assert.equal(p.instance.data.navMode, 'consumer');
  p.setRole('merchant');
  p.definition.pageLifetimes.show.call(p.instance);
  assert.equal(p.instance.data.navMode, 'merchant');
  p.setRole('club');
  p.definition.pageLifetimes.show.call(p.instance);
  assert.equal(p.instance.data.navMode, 'consumer');
  p.instance.retryLoad();
  assert.equal(p.requests.length, 1, '下载进行中不能重复启动');
});

test('下载失败能重试；超时后旧回包不能覆盖新尝试，其他包错误不能污染主页', () => {
  const p = placeholder();
  const error = [...p.listeners][0];
  error({ type: 'subpackage', subpackage: ['subpackagePlay'] });
  error(null);
  assert.equal(p.instance.data.failed, false);
  error({ type: 'subpackage', subpackage: ['subpackageMember'], errMsg: 'timeout' });
  assert.equal(p.instance.data.failed, true);
  p.instance.retryLoad();
  assert.equal(p.requests.length, 2);
  p.requests[0].fail();
  assert.equal(p.instance.data.failed, false, '旧下载的失败不能中断新尝试');
  assert.equal(p.instance.data.loading, true);
  p.requests[1].fail();
  assert.equal(p.instance.data.failed, true);
  p.instance.retryLoad();
  p.requests[2].success();
  assert.equal(p.instance.data.failed, false);
  assert.equal(p.instance.data.loading, false);
});

test('占位被替换或离开后注销监听，旧失败/回包不能写回', () => {
  const p = placeholder();
  const error = [...p.listeners][0];
  p.definition.lifetimes.detached.call(p.instance);
  assert.equal(p.listeners.size, 0);
  p.instance.setData = () => { throw new Error('卸载后不允许写回'); };
  error({ type: 'subpackage', subpackage: ['subpackageMember'] });
  p.requests[0].fail();
  p.requests[0].success();
  p.instance.retryLoad();
  assert.equal(p.requests.length, 1);
});

test('异步加载立即抛错会显示可离开的失败态；他人主页返回失败时回到本人主页', () => {
  let fallback;
  const p = placeholder({ navigateBack: (options) => options.fail(),
    switchTab: (options) => { fallback = options.url; } }, () => { throw new Error('加载失败'); });
  assert.equal(p.instance.data.failed, true);
  assert.equal(p.instance.data.loading, false);
  p.instance.goBack();
  assert.equal(fallback, '/pages/member/index/index');
});

test('本人占位无业务方法时下拉立即收圈、分享采用原默认；真实组件继续委托', async () => {
  let stopped = 0, refreshed = 0;
  const p = load('pages/member/index/index.js', { stopPullDownRefresh: () => { stopped++; } }).instance;
  p.selectComponent = () => ({});
  p.onPullDownRefresh();
  assert.equal(stopped, 1);
  assert.equal(p.onShareAppMessage({}).path, '/pages/member/index/index');
  p.selectComponent = () => ({ refreshAll: (force) => {
    assert.equal(force, true); refreshed++; return Promise.resolve();
  }, getSharePayload: () => ({ title: '真实分享', path: '/real' }) });
  p.onPullDownRefresh();
  await Promise.resolve();
  assert.equal(refreshed, 1);
  assert.equal(stopped, 2);
  assert.equal(p.onShareAppMessage({}).path, '/real');
  p.selectComponent = () => ({ refreshAll: () => Promise.reject(new Error('network')) });
  p.onPullDownRefresh();
  await Promise.resolve();
  assert.equal(stopped, 3);
});

test('他人占位分享保留用户深链；真实分享参数原样交给实际组件', () => {
  const p = load('pages/userinfo/userinfo.js', {}).instance;
  p.data.userId = '42';
  p.selectComponent = () => ({});
  assert.equal(p.onShareAppMessage({}).path, '/pages/userinfo/userinfo?userId=42');
  const event = { from: 'button' };
  p.selectComponent = () => ({ getSharePayload: (received) => {
    assert.equal(received, event); return { path: '/actual' };
  } });
  assert.equal(p.onShareAppMessage(event).path, '/actual');
});

test('占位有失败重试/返回与本人导航，六组件跨包注册不得依赖同步加载', () => {
  const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8');
  const wxml = read('components/cy/profile-loading/index.wxml');
  assert.match(wxml, /wx:if="\{\{failed\}\}"[^>]*bind:retry="retryLoad"/);
  assert.match(wxml, /bind:back="goBack"/);
  assert.match(wxml, /tabBar wx:if="\{\{viewer === 'self'\}\}"/);
  assert.match(wxml, /aria-live="polite"/);
  assert.equal(require(path.join(ROOT, 'subpackageMember/profile-load.js')), true);
  for (const route of ['pages/member/index/index', 'pages/userinfo/userinfo']) {
    const config = JSON.parse(read(route + '.json'));
    assert.equal(config.usingComponents['cy-profile'], '/subpackageMember/components/cy/profile/index');
    assert.equal(config.usingComponents['cy-profile-loading'], '/components/cy/profile-loading/index');
    assert.equal(config.componentPlaceholder['cy-profile'], 'cy-profile-loading');
  }
});
