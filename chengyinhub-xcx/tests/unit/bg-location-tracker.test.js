const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const MOD = path.join(__dirname, '../../utils/location/bg-tracker.js');
function fresh(wxStub) {
  delete require.cache[require.resolve(MOD)];
  global.wx = wxStub;
  return require(MOD);
}
function stub(opts) {
  opts = opts || {};
  const calls = { bg: 0, fg: 0, stop: 0 };
  return {
    calls,
    wx: {
      startLocationUpdateBackground: opts.noBg ? undefined : function (o) {
        calls.bg++; opts.bgFail ? o.fail({ errMsg: 'denied' }) : o.success();
      },
      startLocationUpdate: function (o) { calls.fg++; opts.fgFail ? o.fail({ errMsg: 'no' }) : o.success(); },
      stopLocationUpdate: function (o) {
        calls.stop++;
        if (o && o.success) o.success();
      },
    },
  };
}

function deferredStub(opts) {
  opts = opts || {};
  const calls = { bg: 0, fg: 0, stop: 0 };
  const pending = { bg: [], fg: [], stop: [] };
  return {
    calls,
    pending,
    wx: {
      startLocationUpdateBackground: opts.noBg ? undefined : function (o) {
        calls.bg++; pending.bg.push(o);
      },
      startLocationUpdate: function (o) { calls.fg++; pending.fg.push(o); },
      stopLocationUpdate: function (o) {
        calls.stop++;
        if (opts.deferStop) pending.stop.push(o);
        else if (o && o.success) o.success();
      },
    },
  };
}

test('优先用后台版；拿不到时降级前台，行程不中断', () => {
  let s = stub(); let t = fresh(s.wx); let got = null;
  t.acquire('play:1', { success: (bg) => { got = bg; } });
  assert.equal(s.calls.bg, 1); assert.equal(got, true, '后台可用时必须走后台版');

  s = stub({ bgFail: true }); t = fresh(s.wx); got = null;
  t.acquire('play:1', { success: (bg) => { got = bg; } });
  assert.equal(s.calls.fg, 1, '后台被拒必须降级到前台版');
  assert.equal(got, false);
});

test('★ 引用计数归零才 stop —— 漏关就是对用户的长期位置采集', () => {
  const s = stub(); const t = fresh(s.wx);
  t.acquire('play:1', {}); t.acquire('roam', {});
  t.release('play:1');
  assert.equal(s.calls.stop, 0, '还有一个持有者时不能停');
  assert.equal(t.isRunning(), true);
  t.release('roam');
  assert.equal(s.calls.stop, 1, '最后一个释放后必须真的 stop');
  assert.equal(t.isRunning(), false);
});

test('负控：把 release 改成「无条件不停」必须判红', () => {
  const s = stub(); const t = fresh(s.wx);
  t.acquire('play:1', {});
  t.release('play:1');
  // 若有人把 release 改成永不 stop,这条就会红 —— 这正是要守的那件事
  assert.equal(s.calls.stop, 1, 'release 到零必须调用 stopLocationUpdate');
});

test('force 兜底：一次收干净所有持有者', () => {
  const s = stub(); const t = fresh(s.wx);
  t.acquire('play:1', {}); t.acquire('roam', {});
  t.release('play:1', true);
  assert.equal(s.calls.stop, 1, 'force 必须无视其他持有者直接停');
  assert.equal(t.isRunning(), false);
});

test('重复 acquire 同一个 key 不会叠加启动', () => {
  const s = stub(); const t = fresh(s.wx);
  t.acquire('play:1', {}); t.acquire('play:1', {}); t.acquire('play:1', {});
  assert.equal(s.calls.bg, 1, '已启动后不再重复调起');
  t.release('play:1');
  assert.equal(s.calls.stop, 1);
});

test('启动中的同 key 重复 acquire 只保留一个 waiter，不能重复注册定位监听', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  let ready = 0;
  t.acquire('roam', { success: () => { ready++; } });
  t.acquire('roam', { success: () => { ready++; } });
  assert.equal(s.calls.bg, 1);

  s.pending.bg[0].success();

  assert.equal(ready, 1, '同一个 owner 的成功回调只能触发一次');
});

test('基础库没有后台版时直接走前台，不抛', () => {
  const s = stub({ noBg: true }); const t = fresh(s.wx); let got = null;
  assert.doesNotThrow(() => t.acquire('roam', { success: (bg) => { got = bg; } }));
  assert.equal(s.calls.fg, 1); assert.equal(got, false);
});

test('释放发生在后台定位成功回调之前：迟到成功必须立即停掉，不能留下后台采集', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  let succeeded = 0;
  t.acquire('roam', { success: () => { succeeded++; } });
  t.release('roam');

  s.pending.bg[0].success();

  assert.equal(succeeded, 0, '已退出的页面不能再收到开启成功');
  assert.equal(t.isRunning(), false, '已释放后迟到成功不能把 tracker 改回运行态');
  assert.equal(s.calls.stop, 1, '迟到成功代表系统定位可能已开启，必须补偿 stop');
});

test('释放发生在后台失败回调之前：不得再降级启动前台定位', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  t.acquire('roam', {});
  t.release('roam');

  s.pending.bg[0].fail({ errMsg: 'denied' });

  assert.equal(s.calls.fg, 0, '持有者已释放，迟到失败不能再开启前台定位');
  assert.equal(t.isRunning(), false);
});

test('已进入前台降级启动后再释放：迟到的前台 success 也必须补偿停止', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  t.acquire('roam', {});
  s.pending.bg[0].fail({ errMsg: 'background denied' });
  assert.equal(s.calls.fg, 1);

  t.release('roam');
  s.pending.fg[0].success();

  assert.equal(t.isRunning(), false);
  assert.equal(s.calls.stop, 1, '前台能力迟到开启后也必须 stop');
});

test('并发 acquire 共用一次启动；释放其中一个不会取消仍在等待的持有者', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  let playReady = 0; let roamReady = 0;
  t.acquire('play:1', { success: () => { playReady++; } });
  t.acquire('roam', { success: () => { roamReady++; } });
  assert.equal(s.calls.bg, 1, '全局定位启动必须 single-flight');

  t.release('play:1');
  s.pending.bg[0].success();

  assert.equal(playReady, 0, '已释放的持有者不能收到成功');
  assert.equal(roamReady, 1, '仍持有的调用方必须收到成功');
  assert.equal(t.isRunning(), true);
  assert.equal(s.calls.stop, 0, '仍有持有者时不能停');
  t.release('roam');
  assert.equal(s.calls.stop, 1);
});

test('并发启动失败只通知仍持有的调用方，已释放页面不能收到迟到失败', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  let playFailed = 0; let roamFailed = 0;
  t.acquire('play:1', { fail: () => { playFailed++; } });
  t.acquire('roam', { fail: () => { roamFailed++; } });
  t.release('play:1');

  s.pending.bg[0].fail({ errMsg: 'background denied' });
  s.pending.fg[0].fail({ errMsg: 'foreground denied' });

  assert.equal(playFailed, 0, '已释放页面不能被迟到失败回调写 UI');
  assert.equal(roamFailed, 1, '仍持有的调用方必须收到失败');
  assert.equal(t.isRunning(), false);
});

test('force 可取消启动中的定位；迟到成功只做补偿停止', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  t.acquire('play:1', {});
  t.acquire('roam', {});
  t.release('play:1', true);

  s.pending.bg[0].success();

  assert.equal(t.isRunning(), false);
  assert.equal(s.calls.stop, 1);
});

test('异步 stop 未完成前的新 acquire 必须等待，禁止旧 stop 迟到关闭新会话', () => {
  const s = deferredStub({ deferStop: true }); const t = fresh(s.wx);
  let roamReady = 0;
  t.acquire('play:1', {});
  s.pending.bg[0].success();
  t.release('play:1');
  assert.equal(s.calls.stop, 1);

  t.acquire('roam', { success: () => { roamReady++; } });
  assert.equal(s.calls.bg, 1, 'stop complete 前不能发起新 start');
  assert.equal(t.isRunning(), false);

  s.pending.stop[0].success();
  assert.equal(s.calls.bg, 2, 'stop 完成后才允许重启');
  s.pending.bg[1].success();
  assert.equal(roamReady, 1);
  assert.equal(t.isRunning(), true);
});

test('stopping 中 acquire 后又 release，旧 waiter 不能污染下一次同 key 会话', () => {
  const s = deferredStub({ deferStop: true }); const t = fresh(s.wx);
  let staleReady = 0; let freshReady = 0;
  t.acquire('play:1', {});
  s.pending.bg[0].success();
  t.release('play:1');

  t.acquire('roam', { success: () => { staleReady++; } });
  t.release('roam');
  s.pending.stop[0].success();
  assert.equal(s.calls.bg, 1, 'stop 完成时已无 owner，不应自动重启');

  t.acquire('roam', { success: () => { freshReady++; } });
  s.pending.bg[1].success();
  assert.equal(staleReady, 0, '已释放会话的 waiter 必须被清除');
  assert.equal(freshReady, 1);
});

test('明确撤回清理用途和观察者状态，新授权必须实际重新启动', () => {
  const s = stub(); const t = fresh(s.wx);
  let revoked = 0; const off = t.observeRevocation('roam', () => { revoked++; });
  t.acquire('roam', {});
  t.revoke('roam');
  assert.equal(revoked, 1);
  assert.equal(t.revision('roam'), 1);
  assert.equal(t.isRunning(), false);
  assert.equal(s.calls.stop, 1);
  t.acquire('roam', {});
  assert.equal(s.calls.bg, 2, '不能保留旧owner而让新授权幂等短路');
  off(); t.revoke('roam');
  assert.equal(revoked, 1, '已离开页面的观察者不能再收到撤回');
});

test('漫游撤回不取消独立获准的游玩持有者和待启成功回调', () => {
  const s = deferredStub(); const t = fresh(s.wx);
  let playReady = 0; let roamReady = 0;
  t.acquire('play:31', { success() { playReady++; } });
  t.acquire('roam', { success() { roamReady++; } });
  t.revoke('roam');
  s.pending.bg[0].success();
  assert.equal(roamReady, 0);
  assert.equal(playReady, 1);
  assert.equal(t.isRunning(), true);
  assert.equal(s.calls.stop, 0, '仍有独立用途授权时不得全局停定位');
  t.release('play:31');
  assert.equal(s.calls.stop, 1);
});

test('撤回前迟到系统success不能冒充新授权ready，补停后必须真正重启', () => {
  const s = deferredStub({ deferStop: true }); const t = fresh(s.wx);
  let oldReady = 0; let newReady = 0;
  t.acquire('roam', { success() { oldReady++; } });
  t.revoke('roam');
  t.acquire('roam', { success() { newReady++; } });
  assert.equal(s.calls.bg, 1);
  s.pending.stop[0].success();
  assert.equal(s.calls.bg, 2);
  s.pending.bg[0].success();
  assert.equal(newReady, 0, '旧授权success不能交给新的waiter');
  assert.equal(s.calls.stop, 2);
  s.pending.bg[1].success();
  assert.equal(newReady, 0, '补停中的旧新start也不能提前交付ready');
  s.pending.stop[1].success();
  assert.equal(s.calls.bg, 3);
  s.pending.bg[2].success();
  assert.equal(oldReady, 0);
  assert.equal(newReady, 1);
  assert.equal(t.isRunning(), true);
});

test('无系统持有者时撤回也作废正在说明或留痕中的用途授权', () => {
  const s = stub(); const t = fresh(s.wx);
  let notified = 0;
  t.observeRevocation('roam', () => { notified++; });
  const before = t.revision('roam');
  t.revoke('roam');
  assert.equal(t.revision('roam'), before + 1);
  assert.equal(notified, 1);
  assert.equal(t.isRunning(), false);
});

test('hasOwner只读真实用途持有状态，不触发SDK或授予新用途', () => {
  const s = stub(); const tracker = fresh(s.wx);
  assert.equal(tracker.hasOwner('roam'), false);
  tracker.acquire('roam'); tracker.acquire('play:12');
  assert.equal(tracker.hasOwner('roam'), true);
  assert.equal(tracker.hasOwner('play:12'), true);
  const before = { ...s.calls };
  assert.equal(tracker.hasOwner('unknown'), false);
  assert.equal(tracker.hasOwner('__proto__'), false);
  assert.deepEqual(s.calls, before);
  tracker.revoke('roam');
  assert.equal(tracker.hasOwner('roam'), false);
  assert.equal(tracker.hasOwner('play:12'), true);
  assert.equal(tracker.isRunning(), true);
  tracker.release('play:12');
  assert.equal(tracker.hasOwner('play:12'), false);
  assert.equal(tracker.isRunning(), false);
});

test('native stop failure remains explicit and cannot silently restart; explicit retry confirms stop', async () => {
  let fail = true; const s = stub();
  s.wx.stopLocationUpdate = o => { s.calls.stop++; fail ? o.fail({ errMsg: 'stopLocationUpdate:fail denied' }) : o.success(); };
  const tracker = fresh(s.wx); tracker.acquire('roam', {});
  const result = await tracker.revoke('roam'); assert.equal(result.ok, false); assert.equal(tracker.stopState(), 'failed');
  let rejected = 0; tracker.acquire('new', { fail() { rejected++; } });
  assert.equal(rejected, 1); assert.equal(s.calls.bg, 1); assert.equal(tracker.hasOwner('new'), false);
  fail = false; assert.equal((await tracker.retryStop()).ok, true); assert.equal(tracker.stopState(), 'confirmed');
  assert.equal(s.calls.bg, 1, 'stop retry never starts collection');
});
test('native stop without any callback becomes recoverable UNKNOWN instead of success', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const s = stub(); s.wx.stopLocationUpdate = () => { s.calls.stop++; };
  const tracker = fresh(s.wx); tracker.acquire('roam', {}); const result = tracker.revoke('roam');
  assert.equal(tracker.stopState(), 'stopping'); t.mock.timers.tick(10000);
  assert.equal((await result).ok, false); assert.equal(tracker.stopState(), 'failed');
});

test('purpose revoke upgrades a regular in-flight stop; failure cannot restart the revoked waiter', async () => {
  const s = deferredStub({ deferStop: true }); const t = fresh(s.wx); const seen = [];
  t.acquire('roam', { success: () => seen.push('old') }); s.pending.bg[0].success();
  t.release('roam'); assert.equal(s.calls.stop, 1);
  t.acquire('roam', { success: () => seen.push('replacement') });
  const stopped = t.revoke('roam'); assert.equal(s.calls.stop, 1, 'same native stop must be shared');
  s.pending.stop[0].fail({ errMsg: 'stopLocationUpdate:fail denied' });
  assert.equal((await stopped).ok, false); assert.equal(t.stopState(), 'failed');
  assert.deepEqual(seen, ['old']); assert.equal(s.calls.bg, 1); assert.equal(t.hasOwner('roam'), false);
  let rejected = 0; t.acquire('new-purpose', { fail: () => rejected++ });
  assert.equal(rejected, 1); assert.equal(s.calls.bg, 1);
  s.pending.stop[0].success(); assert.equal(t.stopState(), 'failed', 'late contradictory callback is ignored');
  const retry = t.retryStop(); assert.equal(s.calls.stop, 2); s.pending.stop[1].success();
  assert.equal((await retry).ok, true); assert.equal(s.calls.bg, 1, 'stop retry does not activate collection');
  t.acquire('new-purpose', {}); assert.equal(s.calls.bg, 2);
  s.pending.bg[1].success(); t.release('new-purpose'); s.pending.stop[2].success();
});
