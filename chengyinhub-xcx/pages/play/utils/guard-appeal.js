'use strict';
const { id } = require('../../../utils/orient-score-view.js');
const copy = value => JSON.parse(JSON.stringify(value));
function create({ storage, owner, session, send, current, changed, keyFactory }) {
  const key = 'play-guard-appeal:' + encodeURIComponent(String(owner)) + ':' + encodeURIComponent(session);
  let entry = storage.getStorageSync(key) || null, busy = false;
  if (entry && (entry.owner !== owner || entry.session !== session || !entry.reference || !id(entry.reference.guardLogId))) throw Error('守卫申诉恢复记录不完整');
  function save() {
    storage.setStorageSync(key, copy(entry));
    if (JSON.stringify(storage.getStorageSync(key)) !== JSON.stringify(entry)) throw Error('申诉记录未保存，不能提交');
    if (current()) changed(copy(entry));
  }
  function reference(value) {
    if (!current()) return;
    if (entry && entry.command && entry.state !== 'FOUND') return; // never discard an unresolved intent
    if (!value || !id(value.guardLogId) || !id(value.routeSessionId) || !id(value.nodeId)
      || !/^[A-Za-z0-9:_-]{1,80}$/.test(value.arrivalRequestId || '')) throw Error('原到达引用不完整');
    entry = { owner, session, reference: copy(value), state: 'DRAFT', reason: '' }; save();
  }
  async function read() {
    if (!entry || busy || !current()) return;
    busy = true;
    try {
      const command = entry.command;
      const response = await send(command ? '/api/audit/guard/appeal/receipt' : '/api/audit/guard/reference', 'GET', command
        ? { guardLogId: command.guardLogId, requestId: command.requestId }
        : { guardLogId: entry.reference.guardLogId, arrivalRequestId: entry.reference.arrivalRequestId });
      if (!current()) return;
      if (!response || response.code !== 200 || !response.data) throw Error(response && response.msg || '读取未确认');
      const fact = response.data;
      if (command) {
        const intent = fact.intent;
        if (fact.state !== 'FOUND' || !intent || intent.requestId !== command.requestId || intent.memberId !== String(owner)
          || intent.bizId !== command.guardLogId || intent.reason !== command.reason
          || intent.evidence !== null || !Array.isArray(intent.attachments) || intent.attachments.length !== 0
          || !intent.guardReference || intent.guardReference.guardLogId !== command.guardLogId
          || intent.guardReference.arrivalRequestId !== command.arrivalRequestId) {
          entry.state = 'UNKNOWN'; entry.message = '尚未取得原请求结果，保留原号，不重新提交'; save(); return;
        }
        entry.state = 'FOUND'; entry.result = fact; entry.message = fact.decisionReason || '申诉已提交，等待处理';
      } else {
        if (!fact.guardOrigin || fact.guardOrigin.guardLogId !== entry.reference.guardLogId
          || fact.guardOrigin.routeSessionId !== entry.reference.routeSessionId) throw Error('原局引用不匹配');
        entry.eligibility = fact; entry.message = fact.canSubmit ? '可提交原局守卫申诉' : '原局不可新提交，仅保留查询';
      }
      save();
    } catch (error) { if (current()) { entry.message = error.message; if (entry.command) entry.state = 'UNKNOWN'; save(); } }
    finally { busy = false; }
  }
  async function submit(reason) {
    if (!entry || entry.command || busy || !current()) return;
    reason = String(reason || '').trim();
    if (!reason || reason.length > 500) throw Error('请填写1至500字申诉理由');
    if (!entry.eligibility || !entry.eligibility.canSubmit) throw Error('请先读取原局申诉资格');
    entry.command = { guardLogId: entry.reference.guardLogId, arrivalRequestId: entry.reference.arrivalRequestId,
      requestId: keyFactory(), reason, evidence: null, attachments: [] };
    entry.reason = reason; entry.state = 'UNKNOWN';
    try { save(); } catch (error) { entry.command = null; entry.state = 'DRAFT'; throw error; }
    busy = true;
    try { await send('/api/audit/guard/appeal', 'POST', copy(entry.command)); }
    catch (error) { /* Transport failure does not prove no write. Query only with the frozen key. */ }
    finally { busy = false; }
    if (current()) await read();
  }
  return { reference, read, submit, view: () => entry && copy(entry) };
}
module.exports = { create };
