const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const xcxRoot = path.resolve(__dirname, '../..');
const read = (relativePath) => fs.readFileSync(path.join(xcxRoot, relativePath), 'utf8');

function assertIndexHeroContract({ js, wxml, wxss }) {
  assert.match(js, /city:\s*''/, '首页首帧不能把城市设成“获取中”这类常驻 loading 文案');
  assert.match(wxml, /wx:if="\{\{city\}\}"/, '城市信息只在实际值存在时渲染');
  assert.doesNotMatch(wxml, /city\s*\|\|\s*'上海'/, '未取得城市时不能伪造默认城市');
  assert.doesNotMatch(wxml, /v3-hero-bell-dot/, '没有未读数据时不能常驻通知点');
  const feed = wxml.indexOf('<view id="sec-feed"');
  assert.ok(feed > 0, '找不到 sec-feed 锚点(页面结构已改?)');
  const aboveFeed = wxml.slice(0, feed);
  // 首次读取失败用完整错误态；已有附近主题保留卡片，仅显示当前查询的行内重试。
  const failures = [
    ['recommendedTopicList', 'recoHero'],
    ['nearbyTopicList', 'nearbyTopicList.length'],
    ['upcomingActivityList', 'upcomingActivityList.length'],
  ];
  for (const [list, content] of failures) {
    const guard = 'wx:elif="{{listErr.' + list + ' && !' + content + '}}"';
    const start = aboveFeed.indexOf(guard);
    assert.ok(start >= 0, '完整错误态必须只在无旧内容时出现：' + list);
    const branch = aboveFeed.slice(start, aboveFeed.indexOf('</view>', start));
    assert.match(branch, /<cy-error load-failure/);
    assert.ok(branch.includes('data-list="' + list + '" bind:retry="retryList"'));
  }
  const inline = aboveFeed.match(/<cy-inline-error\b[^>]*\/>/g) || [];
  assert.equal(inline.length, 1, '只为用户主动定位后的附近查询保留行内重试');
  assert.match(inline[0], /wx:if="\{\{listErr\.nearbyTopicList && nearbyTopicList\.length\}\}" load-failure/);
  assert.match(inline[0], /title="附近主题更新失败" action="重试" data-list="nearbyTopicList" bind:action="retryList"/);
  assert.match(aboveFeed, /<swiper class="v3-reco-swiper" wx:if="\{\{recoCards\.length\}\}"/);
  for (const list of ['nearbyTopicList', 'upcomingActivityList']) {
    assert.ok(aboveFeed.includes('<scroll-view wx:if="{{' + list + '.length}}"'), '刷新错误不能遮掉已有卡片：' + list);
  }
}

function assertAddressErrorContract({ js, wxml, json }) {
  assert.match(js, /loadState:\s*'loading'/, '地址页需要显式加载状态');
  assert.match(js, /loadState:\s*'ready'/, '成功响应才能进入可新增的 ready 状态');
  assert.match(js, /loadState:\s*'error'/, '请求失败必须进入错误状态');
  assert.match(js, /getRequestErrorMessage/, '错误文案必须经统一映射');
  assert.doesNotMatch(js, /res\s*&&\s*res\.msg/, '不能把服务端原始错误直接展示给用户');
  assert.match(json, /"cy-error"\s*:/, '地址页错误态使用统一 cy-error');
  assert.match(wxml, /<cy-error load-failure\s+wx:if="\{\{loadState === 'error'\}\}"/, '读取错误态使用留白契约');
  assert.match(wxml, /<cy-footer-bar\s+wx:if="\{\{loadState === 'ready'\}\}"/, '新增地址 CTA 只能在 ready 状态出现');
}

test('Q118：首页首帧不展示伪 loading 或无条件蓝点', () => {
  let source = {
    js: read('pages/index/index.js'),
    wxml: read('pages/index/index.wxml'),
    wxss: read('pages/index/index.wxss'),
  };
  if (process.env.Q118_NEGATIVE === 'index') {
    const original = source.wxml;
    source.wxml = source.wxml.replace('<view id="sec-hero"', '<view class="v3-hero-bell-dot"></view><view id="sec-hero"');
    assert.notEqual(source.wxml, original, '负控必须实际注入蓝点');
  }
  assertIndexHeroContract(source);
});

test('Q118负控：常驻错误、丢失重试、隐藏旧内容或伪造蓝点必须判红', () => {
  const source = {
    js: read('pages/index/index.js'),
    wxml: read('pages/index/index.wxml'),
    wxss: read('pages/index/index.wxss'),
  };
  for (const mutated of [
    source.wxml.replace('listErr.nearbyTopicList && nearbyTopicList.length', 'listErr.nearbyTopicList'),
    source.wxml.replace('listErr.nearbyTopicList && !nearbyTopicList.length', 'listErr.nearbyTopicList'),
    source.wxml.replace('bind:action="retryList"', 'bind:action="noop"'),
    source.wxml.replace('<scroll-view wx:if="{{nearbyTopicList.length}}"', '<scroll-view wx:if="{{nearbyTopicList.length && !listErr.nearbyTopicList}}"'),
    source.wxml.replace('<view id="sec-hero"', '<view class="v3-hero-bell-dot"></view><view id="sec-hero"'),
  ]) {
    assert.notEqual(mutated, source.wxml, '负控必须真实变异');
    assert.throws(() => assertIndexHeroContract({ ...source, wxml: mutated }));
  }
});

test('Q118：地址加载失败时不保留新增参与人信息 CTA', () => {
  let source = {
    js: read('pages/address/address.js'),
    wxml: read('pages/address/address.wxml'),
    json: read('pages/address/address.json'),
  };
  if (process.env.Q118_NEGATIVE === 'address') {
    source.wxml = source.wxml.replace('<cy-footer-bar wx:if="{{loadState === \'ready\'}}"', '<cy-footer-bar');
  }
  assertAddressErrorContract(source);
});
