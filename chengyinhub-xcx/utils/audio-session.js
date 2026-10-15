'use strict';
// 音频会话:把页面里散落的 InnerAudioContext 收到一处管。
//  - 来电 / 闹钟 / 其它 App 抢占音频时(wx.onAudioInterruptionBegin/End),统一暂停,
//    结束后只恢复「被打断前正在播」的那几个,不会把用户已手动暂停的又放出来。
//  - keepAwake(owner,on):按 owner 引用计数包 wx.setKeepScreenOn,全屏故事/长音频期间不熄屏,
//    owner 全部释放才真的关;API 不存在时静默降级。
// 全局的打断监听只注册一次(第一次 register 时),不随页面卸载反复挂。
function createAudioSession(wxApi) {
  const api = wxApi || (typeof wx !== 'undefined' ? wx : {});
  const entries = new Map();   // ctx -> { kind, wasPlaying, interrupted }
  const awake = new Set();
  let hooked = false;
  let desiredKeepOn = false;
  let confirmedKeepOn = false;
  let keepRequest = null;

  const isPlaying = (ctx) => ctx.paused === false;

  function pauseEntry(ctx, ent, byInterrupt) {
    // 页面主动暂停优先于系统打断，即使 context 已被打断暂停也要撤销自动恢复。
    if (!byInterrupt) ent.interrupted = false;
    if (!isPlaying(ctx)) return;
    ent.wasPlaying = true;
    if (byInterrupt) ent.interrupted = true;
    try { ctx.pause(); } catch (e) {}
  }
  function resumeEntry(ctx, ent) {
    ent.wasPlaying = false;
    ent.interrupted = false;
    try { ctx.play(); } catch (e) {}
  }

  function hook() {
    if (hooked) return;
    hooked = true;
    if (typeof api.onAudioInterruptionBegin === 'function') {
      api.onAudioInterruptionBegin(() => entries.forEach((ent, ctx) => pauseEntry(ctx, ent, true)));
    }
    if (typeof api.onAudioInterruptionEnd === 'function') {
      // 只恢复这一次打断造成的暂停;页面 onHide 的 pauseAll 不会被它顶回来
      api.onAudioInterruptionEnd(() => entries.forEach((ent, ctx) => { if (ent.interrupted) resumeEntry(ctx, ent); }));
    }
  }

  function register(ctx, opts) {
    if (!ctx) return ctx;
    hook();
    entries.set(ctx, { kind: (opts && opts.kind) || 'audio', wasPlaying: false, interrupted: false });
    return ctx;
  }
  function unregister(ctx) { entries.delete(ctx); }
  function pauseAll() { entries.forEach((ent, ctx) => pauseEntry(ctx, ent, false)); }
  function resumeAll() { entries.forEach((ent, ctx) => { if (ent.wasPlaying) resumeEntry(ctx, ent); }); }

  function apply(on) {
    desiredKeepOn = on;
    if (typeof api.setKeepScreenOn !== 'function') return;
    if (keepRequest && keepRequest.on === desiredKeepOn) return;
    if (!keepRequest && confirmedKeepOn === desiredKeepOn) return;
    // An opposite request supersedes the old one immediately: releasing the
    // final owner must still request off while an earlier on is pending.
    const request = { on: desiredKeepOn };
    keepRequest = request;
    const finish = (ok) => {
      if (keepRequest !== request) return;
      keepRequest = null;
      // Failure leaves the platform state unknown. A later owner operation
      // may retry; this callback never starts an automatic retry loop.
      confirmedKeepOn = ok ? request.on : null;
    };
    try {
      api.setKeepScreenOn({ keepScreenOn: request.on,
        success() { finish(true); }, fail() { finish(false); } });
    } catch (e) { finish(false); }
  }
  function keepAwake(owner, on) {
    if (owner == null) return;
    if (on) awake.add(owner); else awake.delete(owner);
    apply(awake.size > 0);
  }
  function releaseAwake() { awake.clear(); apply(false); }

  return {
    register, unregister, pauseAll, resumeAll, keepAwake, releaseAwake,
    _size: () => entries.size,
  };
}

module.exports = createAudioSession();
module.exports.createAudioSession = createAudioSession;
