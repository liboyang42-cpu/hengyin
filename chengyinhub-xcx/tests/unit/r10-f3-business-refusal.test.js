// 第十轮 f3 第 1 项(p3 F3 + j6 缺参空白):服务端明确拒绝(不存在 / 不是你的 / 没有记录)与缺参链接讲原因并给一个返回;
// 网络失败、系统繁忙仍按「加载失败留白」(owner 既有规则)。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');

const REFUSAL_PAGES = [
  'pages/templatedetail/templatedetail',
  'pages/templatedetail/topic-detail/index',
  'pages/templatedetail/topic-preview/index',
  'pages/club/topic-detail/index',
  'pages/club/checkin-detail/index',
  'pages/club/customer-detail/index',
];

function refusalOf(rel) {
  const src = read(rel + '.js');
  const m = src.match(/function refusalOf\(res\) \{[^\n]*\}\n/);
  assert.ok(m, rel + ' 缺少 refusalOf');
  return new Function(m[0] + 'return refusalOf;')();
}

test('业务拒绝与加载失败分得开:有原因的非 200 → 原因;401/系统繁忙/网络 → 空(继续留白)', () => {
  for (const rel of REFUSAL_PAGES) {
    const f = refusalOf(rel);
    assert.equal(f({ code: 500, msg: '这位客户没有在本俱乐部的成交记录' }), '这位客户没有在本俱乐部的成交记录', rel);
    assert.equal(f({ code: 500, msg: '系统繁忙，请稍后重试' }), '', rel);
    assert.equal(f({ code: 401, msg: '登录状态已失效' }), '', rel);
    assert.equal(f({ code: 200, msg: '操作成功' }), '', rel);
    assert.equal(f(undefined), '', rel);
  }
});

test('拒绝态是可见的原因 + 返回,不是 load-failure 空白', () => {
  assert.match(read('pages/templatedetail/templatedetail.wxml'), /<cy-empty wx:if="\{\{refusal\}\}"[^>]*title="\{\{refusal\}\}" cta="返回" bind:cta="goBack"/);
  assert.match(read('pages/templatedetail/topic-detail/index.wxml'), /<cy-empty wx:if="\{\{refusal\}\}"[^>]*title="\{\{refusal\}\}" cta="返回" bind:cta="goBack"/);
  assert.match(read('pages/templatedetail/topic-preview/index.wxml'), /wx:if="\{\{ errorRefused \}\}"[^>]*bindtap="goBack"[^>]*>返回</);
  assert.match(read('pages/club/topic-detail/index.wxml'), /<cy-empty wx:if="\{\{loadError && \(missingParam \|\| loadRefusal\)\}\}"[^>]*\n[^>]*cta="返回" bind:cta="goBack"/);
  assert.match(read('pages/club/checkin-detail/index.wxml'), /<cy-empty wx:elif="\{\{state === 'missing'\}\}" kind="not-found"[^>]*cta="返回"/);
  assert.match(read('pages/club/customer-detail/index.wxml'), /<cy-empty wx:elif="\{\{state === 'refused'\}\}" kind="not-found"[^>]*cta="返回"/);
  for (const rel of ['pages/templatedetail/templatedetail.json', 'pages/templatedetail/topic-detail/index.json']) {
    assert.equal(JSON.parse(read(rel)).usingComponents['cy-empty'], '/components/cy/empty/index', rel);
  }
});

test('缺参链接不再落加载失败类 kind:官方活动、俱乐部详情', () => {
  const od = read('pages/activity/official-detail/index.js');
  assert.match(od, /loadErrKind: 'missing-param', loadErrTitle: '链接已失效'/);
  assert.match(od, /loadErrKind: 'missing-param',[^\n]*\n\s*loadErrTitle: '活动内容不可用'/);
  assert.match(read('pages/club/detail/index.js'), /this\.setData\(\{ detailLoaded: true, clubGoneText: '链接已失效' \}\);/);
});
