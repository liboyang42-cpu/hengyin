// 第十轮 f3 第 1 项 j5 H1 / H2:审核中、下架、驳回的主题被分享链接打开 —— 他人看到原因 + 返回(不是空白);作者看到自己的主题没公开。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..', '..');
const js = fs.readFileSync(path.join(root, 'pages/topic/index/index.js'), 'utf8');
const wxml = fs.readFileSync(path.join(root, 'pages/topic/index/index.wxml'), 'utf8');

function pick(name) {
  const start = js.indexOf('function ' + name + '(');
  assert.ok(start >= 0, name);
  let depth = 0;
  for (let i = js.indexOf('{', start); i < js.length; i++) {
    if (js[i] === '{') depth++;
    else if (js[i] === '}' && --depth === 0) return new Function(js.slice(start, i + 1) + '\nreturn ' + name + ';')();
  }
  throw new Error('unterminated ' + name);
}

test('H1:「主题活动不存在」是业务拒绝 → 可见原因 + 返回;网络 / 系统繁忙仍留白', () => {
  const refusalOf = pick('refusalOf');
  assert.equal(refusalOf({ code: 500, msg: '主题活动不存在' }), '主题活动不存在');
  assert.equal(refusalOf({ code: 500, msg: '系统繁忙，请稍后重试' }), '');
  assert.match(js, /loadRefused: !!refused,\s*\n\s*loadErrorTitle: refused \|\| '主题暂时无法打开'/);
  assert.match(wxml, /<cy-empty wx:elif="\{\{loadRefused\}\}" kind="not-found" title="\{\{loadErrorTitle\}\}" cta="返回"[^>]*bind:cta="goBack"/);
});

test('H2:作者看自己未公开的主题 → 审核中 / 未通过 / 已下架;公开的或别人的不显示', () => {
  const ownerStateText = pick('ownerStateText');
  const held = { isOwner: 1, status: 1, userStatus: 0, rejectReason: '内容安全：自动检测未完成，暂时仅自己可见，检测通过后自动上架' };
  assert.equal(ownerStateText(held), '审核中');
  assert.equal(ownerStateText({ isOwner: 1, status: 0, userStatus: 0 }), '审核中');
  assert.equal(ownerStateText({ isOwner: 1, status: 2, userStatus: 0 }), '未通过');
  assert.equal(ownerStateText({ isOwner: 1, status: 1, userStatus: 0 }), '已下架');
  assert.equal(ownerStateText({ isOwner: 1, status: 1, userStatus: 1 }), '');
  assert.equal(ownerStateText(Object.assign({}, held, { isOwner: 0 })), '');
  assert.match(wxml, /<text wx:if="\{\{ownerStateText\}\}" class="hero-type hero-type--held">\{\{ownerStateText\}\}<\/text>/);
});
