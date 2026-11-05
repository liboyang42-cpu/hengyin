// 第十一轮 i1(业主规则:站点永远不许把玩家困住):弹球、安静挑战、喊一嗓子、计步同罗盘一条规则 ——
// 读不出数两次或拒了权限,服务端给「跳过这一站」(0 分);客户端只报失败、只发跳过、只照服务端的 canSkip 显示。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const read = (p) => fs.readFileSync(path.join(__dirname, '../../', p), 'utf8');
const { pickPlayKit, serverAction, serverPayload } = require('../../pages/play/utils/playkit-view.js');

const GAMES = { ballshake: 'ballShake', quiethold: 'quietHold', shout: 'shout', walk: 'steps' };

test('四个传感器屏的读取失败与跳过各对应服务端动作,只带本段 game', () => {
  for (const [type, game] of Object.entries(GAMES)) {
    assert.equal(serverAction(type, 'readfail'), 'REPORT_SENSOR_FAILURE', type);
    assert.equal(serverAction(type, 'skip'), 'SKIP_SENSOR_STATION', type);
    assert.deepEqual(serverPayload(type, 'readfail', { x: 1 }), { game }, type);
    assert.deepEqual(serverPayload(type, 'skip', {}), { game }, type);
  }
  // 拒麦克风 = 拒权限:一次就报到门槛
  for (const type of ['quiethold', 'shout']) {
    assert.equal(serverAction(type, 'micdenied'), 'REPORT_SENSOR_FAILURE');
    assert.deepEqual(serverPayload(type, 'micdenied', {}), { game: GAMES[type], denied: true });
  }
});

test('跳过按钮只听服务端的 canSkip;跳过的段算走完', () => {
  const kit = (name, seg) => pickPlayKit({ sessionId: 5, version: 2, playKit: { [name]: seg } });
  for (const name of ['ballShake', 'quietHold', 'shout']) {
    assert.equal(kit(name, { seconds: 5, failedReads: 2 }).canSkip, false, name + ' 服务端没说能跳就不能跳');
    assert.equal(kit(name, { seconds: 5, canSkip: true }).canSkip, true, name);
    assert.equal(kit(name, { seconds: 5, skipped: true }).skipped, true, name);
  }
  const walk = kit('steps', { goal: 6000, canSkip: true, failedReads: 2 });
  assert.equal(walk.canSkip, true);
  assert.equal(walk.readError, '需要授权微信运动才能计步', '拒了微信运动要在屏上说');
  assert.equal(kit('steps', { goal: 6000, failedReads: 2, todaySteps: 1200 }).readError, '', '同步到了就不再说读不到');
});

test('组件:读不出数报 readfail,跳过只发请求;分发器把两件事都接到页面', () => {
  for (const c of ['ballshake', 'quiethold', 'shout']) {
    const js = read(`pages/play/components/playkit-${c}/index.js`);
    assert.match(js, /triggerEvent\('readfail'\)/, c);
    assert.match(js, /onSkip\(\)/, c);
    assert.match(read(`pages/play/components/playkit-${c}/index.wxml`), /bindtap="onSkip"[\s\S]*跳过这一站/, c);
  }
  assert.match(read('pages/play/components/playkit-walk/index.wxml'), /<cy-inline-error[^>]*kind="field" title="\{\{readError\}\}"/);
  assert.match(read('pages/play/components/playkit-walk/index.wxml'), /bindtap="onSkip"/);
  const dispatcher = read('pages/play/components/playkit/index.wxml');
  for (const tag of ['cy-playkit-ballshake', 'cy-playkit-quiethold', 'cy-playkit-shout', 'cy-playkit-walk']) {
    const block = dispatcher.slice(dispatcher.indexOf('<' + tag), dispatcher.indexOf('/>', dispatcher.indexOf('<' + tag)));
    assert.match(block, /can-skip="\{\{kit\.canSkip\}\}"/, tag);
    assert.match(block, /bind:skip="onSkip"/, tag);
  }
});

test('计步:跳过与报失败不走微信运动同步那条路;读不到微信运动在屏上说并报给服务端', () => {
  const page = read('pages/play/index.js');
  assert.match(page, /kit\.type === 'walk' && action !== 'readfail' && action !== 'skip' \|\| action === 'refresh'/);
  assert.match(page, /'playKit\.kit\.readError': '需要授权微信运动才能计步'/);
  assert.match(page, /REPORT_SENSOR_FAILURE', \{ game: 'steps', denied: true \}/);
});
