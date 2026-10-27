'use strict';
const { exactId, reader, sameReader } = require('./user-character.js');
const { newSessionKey } = require('./roam-recovery.js');
const TERMINAL = ['APPLIED', 'REJECTED', 'CANCELLED'];

// One frozen intention per actor/domain/target. Absence from the server never clears it.
function createRecovery({ app, platform, domain, targetId, scopeKey, changed, terminal }) {
  let target = exactId(targetId);
  const actor = reader(app);
  const key = 'cy-team-event-command-v1:' + actor.id + ':' + domain + ':' + (scopeKey ? 'scope:' + encodeURIComponent(scopeKey) : target);
  let record = null, busy = false, disposed = false, storageError = '';
  const current = () => !disposed && sameReader(app, actor);
  const emit = () => {
    if (!current()) return;
    changed({ state: storageError ? 'STORAGE_ERROR' : record ? record.result && record.result.state || 'UNKNOWN' : '',
      requestId: record ? record.command.requestId : '', action: record ? record.command.action : '',
      input: record ? JSON.parse(JSON.stringify(record.command)) : null,
      receipt: record && record.result ? JSON.parse(JSON.stringify(record.result)) : null,
      label: record ? ({ 'attendance-config': '保存联络与签到配置', 'attendance-verify': '核验到场', 'attendance-claim': '领取签到码', 'attendance-rotate': '更换签到码', join: '接受邀请入队', apply: '申请入队', signup: '报名活动', quit: '退出队伍', kick: '移出队员', disband: '解散队伍', 'join-mode': '修改招募方式', withdraw: '撤回申请', handle: '处理入队申请', schedule: '发布活动', pause: '暂停活动', resume: '恢复活动' }[record.command.action] || '原操作') : '正在保存原请求',
      busy, message: storageError || (record && record.result && record.result.message) || '原请求结果待确认；输入已保留，请查询或按原请求重试' });
  };
  // Another page can settle or replace this slot while this instance is alive.
  // Never overwrite its durable intention or resurrect an acknowledged receipt.
  function ownsSnapshot() {
    try {
      const saved = platform.getStorageSync(key);
      if (JSON.stringify(saved || null) === JSON.stringify(record)) return true;
      storageError = '原请求已在其他页面更新，请重新进入后读取';
    } catch (_) { storageError = '原请求记录不可读，请重新进入后查询'; }
    emit();
    return false;
  }
  function save(next) {
    platform.setStorageSync(key, next);
    const check = platform.getStorageSync(key);
    if (JSON.stringify(check) !== JSON.stringify(next)) throw new Error('原请求保存未确认');
    record = next;
  }
  try {
    const saved = platform.getStorageSync(key);
    if (saved) {
      if (!target && scopeKey) target = exactId(saved.targetId);
      if (saved.version !== 1 || saved.actor !== actor.id || saved.domain !== domain || !target || saved.targetId !== target
          || !saved.command || !/^[a-f0-9]{32}$/.test(saved.command.requestId)
          || saved.command.targetId !== target || typeof saved.command.action !== 'string') throw new Error('原请求记录无法识别，请保留记录');
      if (saved.result && (saved.result.requestId !== saved.command.requestId || !TERMINAL.includes(saved.result.state))) throw new Error('结果记录无法识别');
      record = saved;
    }
  } catch (error) { storageError = '原请求记录不可读，不能安全提交新操作'; }
  emit();
  function send(mode) {
    if (!current() || !record || storageError || busy || record.result) return;
    if (!ownsSnapshot()) return;
    busy = true; emit();
    const command = JSON.parse(JSON.stringify(record.command));
    let settled = false;
    const finish = result => {
      if (settled) return;
      settled = true; busy = false;
      if (!current() || !ownsSnapshot()) return;
      if (result && result.requestId === command.requestId && TERMINAL.includes(result.state)) {
        try { save({ ...record, result }); }
        catch (error) { storageError = '结果保存失败，原请求仍保留；请重进后查询'; emit(); return; }
        emit(); terminal(result, command); return;
      }
      // Includes 4xx/5xx, malformed JSON and UNKNOWN. They do not prove rollback or non-arrival.
      emit();
    };
    try {
      const request = {
        method: 'POST', header: { 'Content-Type': 'application/json' }, hideLoading: true, autoErrorToast: false,
        data: JSON.stringify(mode === 'query' ? { requestId: command.requestId } : command),
        success: res => finish(res && res.code == 200 ? res.data : null),
        successStatusAbnormal: () => finish(null), fail: () => finish(null) };
      // Literal paths keep every domain/mode visible to the existing route scanner.
      if (domain === 'TEAM') {
        if (mode === 'query') app.sendRequest({ ...request, url: '/api/team/command/query' });
        else if (mode === 'cancel') app.sendRequest({ ...request, url: '/api/team/command/cancel' });
        else app.sendRequest({ ...request, url: '/api/team/command' });
      } else if (domain === 'SIGNUP') {
        if (mode === 'query') app.sendRequest({ ...request, url: '/api/official/signup-command/query' });
        else if (mode === 'cancel') app.sendRequest({ ...request, url: '/api/official/signup-command/cancel' });
        else app.sendRequest({ ...request, url: '/api/official/signup-command' });
      } else {
        if (mode === 'query') app.sendRequest({ ...request, url: '/api/official/command/query' });
        else if (mode === 'cancel') app.sendRequest({ ...request, url: '/api/official/command/cancel' });
        else app.sendRequest({ ...request, url: '/api/official/command' });
      }
    } catch (error) { finish(null); }
  }
  return {
    setTarget(value) { if (!record && !busy && current()) target = exactId(value); },
    async start(action, payload) {
      if (!current() || record || storageError || busy || !actor.id || !actor.token || !target) { emit(); return; }
      if (!ownsSnapshot()) return;
      busy = true; emit();
      try {
        const requestId = await newSessionKey(platform);
        if (!current()) { busy = false; return; }
        if (!ownsSnapshot()) { busy = false; emit(); return; }
        // Persistence precedes dispatch. Keep the exact original payload, including null/empty values.
        const command = { ...JSON.parse(JSON.stringify(payload || {})), targetId: target, action, requestId };
        save({ version: 1, actor: actor.id, domain, targetId: target, command });
      } catch (error) {
        storageError = '无法保存原请求，尚未发送操作'; busy = false; emit(); return;
      }
      busy = false; send('submit');
    },
    query: () => send('query'), retry: () => send('submit'), cancel: () => send('cancel'),
    acknowledge() {
      if (!current() || !record || !record.result || busy) return;
      if (!ownsSnapshot()) return;
      try {
        platform.removeStorageSync(key);
        if (platform.getStorageSync(key)) throw new Error('clear failed');
        record = null; emit();
      } catch (error) { storageError = '结果未能归档，请稍后重试'; emit(); }
    },
    pending: () => !!record || !!storageError || busy,
    dispose() { disposed = true; },
  };
}
module.exports = { createRecovery };
