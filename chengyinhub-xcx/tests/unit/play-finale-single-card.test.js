// g20:最后一站不再叠六层 —— 先放尾声(openFinish 先走 _loadLifeEnding),再一张通关卡。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');
const flipWxml = fs.readFileSync(path.join(__dirname, '../../pages/play/components/chapter-flip/index.wxml'), 'utf8');

test('最后一站:不弹解锁徽章卡、不弹名次勋章屏、不放勋章庆祝', () => {
  assert.match(js, /if \(newBadges\.length && !completedNow\) \{/);
  assert.match(js, /if \(hasMedalArt && medalRank && completedNow\) \{/);
  assert.match(js, /if \(!completedNow && \(newBadges\.length \|\| \(hasMedalArt/);
});

test('通关过场就是那一张卡:开了过场就不再垫勋章页', () => {
  assert.match(js, /if \(!this\._openFinale\(isShopDay\)\) this\._openMedalScreen\(\);/);
  const finale = js.slice(js.indexOf('  _openFinale(isShopDay) {'), js.indexOf('  closeFinale()'));
  assert.match(finale, /return false;/);
  assert.match(finale, /return true;/);
});

test('赛后三个动作按真实去向命名，开场与跨章不受影响', () => {
  assert.match(wxml, /bindtap="continueLifeEnding"[^>]*aria-label="查看本局回顾"[^>]*>查看本局回顾<\/view>/);
  assert.match(flipWxml, /catchtap="onClose"[^>]*aria-label="查看通关卡"[^>]*>查看通关卡<\/view>/);
  const closeLabel = "{{allDone || topicCompleted ? '收起通关卡' : '收起本局回顾'}}";
  const closeButton = wxml.match(/<view class="finsheet__cta"[^>]*>[\s\S]*?<\/view>/)[0];
  assert.ok(closeButton.includes('aria-label="' + closeLabel + '"'));
  assert.ok(closeButton.includes('>' + closeLabel + '</view>'));
  const label = new Function('allDone', 'topicCompleted', 'return ' + closeLabel.slice(2, -2));
  assert.equal(label(true, false), '收起通关卡');
  assert.equal(label(false, true), '收起通关卡');
  assert.equal(label(false, false), '收起本局回顾', '未完成不能叫通关卡');
  assert.match(flipWxml, /mode === 'gate' && start/);
  assert.match(flipWxml, /aria-label="读下去"[^>]*>读下去<\/view>/);
  assert.match(flipWxml, /endCard.next \? '下一站：' \+ endCard.next : '继续'/);
});

test('赛后动作只关闭当前展示并使用既有回顾/返回回调，不补完成或重复领奖', () => {
  const method = (start, end) => new Function('return function() {' +
    js.slice(js.indexOf(start) + start.length, js.indexOf(end, js.indexOf(start))) + '}')();
  const ending = method('  continueLifeEnding() {', '\n  },\n  _finishKey()');
  const close = method('  closeFinish() {', '\n  },\n  _hideFinish(');
  const finale = method('  closeFinale() {', ' },\n  saveFinale()');
  const events = [];
  const page = {
    data: { reducedMotion: true, previewEnding: { show: true }, finale: { show: true }, allDone: true },
    _lifeEndingShownScope: 'run', _playOwner: 'owner', _routeState: { sessionId: 'session' },
    _boundPlayOwner: () => 'owner', _sessionKey: () => 'key', _isPlayAccountCurrent: () => true,
    setData(patch, callback) { for (const [key, value] of Object.entries(patch)) {
      const [parent, child] = key.split('.');
      if (child) this.data[parent][child] = value; else this.data[key] = value;
    } if (callback) callback(); },
    openFinish() { events.push('review'); },
    _hideFinish(callback) { events.push('hide'); callback(); },
    _canContinueMerchantGames: () => false,
    _reviewAfterFinish() { events.push('story-review'); return true; },
    goBackDetail() { events.push('back'); },
  };
  ending.call(page);
  assert.equal(page.data.previewEnding.show, false);
  assert.deepEqual(events, ['review']);
  page._isPlayAccountCurrent = () => false;
  ending.call(page);
  assert.deepEqual(events, ['review'], '旧owner不得打开回顾');
  finale.call(page);
  assert.equal(page.data.finale.show, false);
  assert.deepEqual(events, ['review'], '揭开通关卡不另发业务动作');
  close.call(page);
  assert.deepEqual(events, ['review', 'hide', 'story-review']);
  page._reviewAfterFinish = () => false;
  close.call(page);
  assert.equal(events.at(-1), 'back', '没有可回看故事时沿原返回栈退出');
  page.data.allDone = false;
  const before = events.length;
  close.call(page);
  assert.deepEqual(events.slice(before), ['hide'], '非整局完成只收起展示');
});

test('尾声在完成页之前:openFinish 先读结局', () => {
  const open = js.slice(js.indexOf('  openFinish() {'), js.indexOf('    this.loadEnding();', js.indexOf('  openFinish() {')));
  assert.match(open, /this\._loadLifeEnding\(\(\) => this\.openFinish\(\)\);/);
});
