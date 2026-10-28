// 专业编辑器本地草稿：一个 storage key 一次写入完整信封。
// 新草稿按 draftUuid 分桶；既有主题按 topicId 分桶。memberId 只用于恢复前防串号，
// 不能用“当前 key 是我算出来的”替代对信封归属的校验。
// 发布分包专用（fabu 页）：按账号分桶 + 读失败返回 storage_unavailable。主包 utils/publish/pro-editor-draft.js
// 是广场 post-compose 在用的旧版（主包余量不够，不往主包加字节）；fabu 仍能读回旧版写下的未分桶 key。

var DRAFT_PREFIX = 'pro_editor_draft_';
var ACTIVE_PREFIX = 'pro_editor_active_';

function value(value) {
  return String(value == null ? '' : value).trim();
}

function clone(input) {
  return JSON.parse(JSON.stringify(input == null ? null : input));
}

function createDraftUuid(now, randomValue) {
  var ts = Number(now == null ? Date.now() : now).toString(36);
  var random = Number(randomValue == null ? Math.random() : randomValue);
  var entropy = Math.floor(Math.abs(random % 1) * 0x100000000).toString(36);
  return ts + '-' + entropy;
}

function draftKeyFor(identity, memberId, operationScope) {
  identity = identity || {};
  var prefix = DRAFT_PREFIX;
  if (value(memberId)) prefix += 'member_' + encodeURIComponent(value(memberId)) + '_';
  if (operationScope === 'MERCHANT') prefix += 'merchant_';
  var topicId = value(identity.topicId);
  if (topicId) return prefix + 'topic_' + encodeURIComponent(topicId);
  var draftUuid = value(identity.draftUuid);
  if (draftUuid) return prefix + 'new_' + encodeURIComponent(draftUuid);
  throw new Error('topicId 或 draftUuid 至少提供一个');
}

function activeKeyFor(memberId, operationScope) {
  var id = value(memberId);
  if (!id) return '';
  return ACTIVE_PREFIX + encodeURIComponent(id) + (operationScope === 'MERCHANT' ? '_merchant' : '');
}

function rememberActiveNewDraft(storage, memberId, draftUuid, operationScope) {
  var key = activeKeyFor(memberId, operationScope);
  var uuid = value(draftUuid);
  if (!key || !uuid || !storage || typeof storage.setStorageSync !== 'function') return false;
  storage.setStorageSync(key, uuid);
  return true;
}

function readActiveNewDraft(storage, memberId, operationScope) {
  var key = activeKeyFor(memberId, operationScope);
  if (!key || !storage || typeof storage.getStorageSync !== 'function') return '';
  var uuid = value(storage.getStorageSync(key));
  if (!uuid && operationScope === 'MERCHANT') uuid = value(storage.getStorageSync(activeKeyFor(memberId)));
  function ownedDraft(candidate) {
    if (!candidate) return null;
    var result = loadDraft(storage, { draftUuid: candidate }, { memberId: memberId, operationScope: operationScope });
    if (result.status === 'storage_unavailable') throw new Error('本地草稿暂时无法读取');
    return result.status === 'ready' && value(result.envelope.draftUuid) === candidate
      ? result.envelope : null;
  }
  var best = ownedDraft(uuid);
  // The pointer is only an index. Recover from existing envelopes after an index write failure.
  if (typeof storage.getStorageInfoSync === 'function') {
    var keys = storage.getStorageInfoSync().keys || [];
    keys.forEach(function (draftKey) {
      if (draftKey.indexOf(DRAFT_PREFIX + 'member_' + encodeURIComponent(value(memberId)) + '_') !== 0 && draftKey.indexOf(DRAFT_PREFIX + 'new_') !== 0) return;
      var envelope = storage.getStorageSync(draftKey);
      if (!envelope || value(envelope.memberId) !== value(memberId) || !value(envelope.draftUuid)) return;
      if (envelope.operationScope !== undefined && value(envelope.operationScope) !== value(operationScope)) return;
      if (!best || Number(envelope.savedAt || 0) > Number(best.savedAt || 0)) best = envelope;
    });
  }
  var recovered = best ? value(best.draftUuid) : '';
  try {
    if (recovered) rememberActiveNewDraft(storage, memberId, recovered, operationScope);
    else if (uuid && typeof storage.removeStorageSync === 'function') storage.removeStorageSync(key);
  } catch (error) {} // Index maintenance cannot invalidate an already durable envelope.
  return recovered;
}

function saveDraft(storage, input) {
  input = input || {};
  if (!storage || typeof storage.setStorageSync !== 'function') {
    throw new Error('storage 不可用');
  }
  var memberId = value(input.memberId);
  if (!memberId) throw new Error('memberId 不能为空');

  var identity = { topicId: input.topicId, draftUuid: input.draftUuid };
  var key = draftKeyFor(identity, memberId, input.operationScope);
  var formData = clone(input.formData || { chapters: input.chapters || [] }) || { chapters: [] };
  var envelope = {
    memberId: input.memberId,
    operationScope: input.operationScope === 'MERCHANT' ? 'MERCHANT' : '',
    baseRevision: value(input.baseRevision),
    formData: formData,
    chapters: clone(formData.chapters || []),
    pendingMaterials: clone(input.pendingMaterials || []),
    savedAt: input.savedAt == null ? Date.now() : input.savedAt,
  };
  if (input.publishAttempt && input.publishAttempt.status === 'UNKNOWN') {
    envelope.publishAttempt = { status: 'UNKNOWN', topicId: value(input.publishAttempt.topicId) };
    if (input.publishAttempt.kind === 'SERVER_DRAFT') envelope.publishAttempt.kind = 'SERVER_DRAFT';
    ['configVersion', 'registryVersion'].forEach(function(field) {
      var version = input.publishAttempt[field];
      if (typeof version === 'string' && /^[1-9][0-9]{0,18}$/.test(version)
          && (version.length < 19 || version <= '9223372036854775807')) envelope.publishAttempt[field] = version;
    });
  }
  if (input.serverDraftAttempt && input.serverDraftAttempt.status === 'UNKNOWN') {
    envelope.serverDraftAttempt = {
      status: 'UNKNOWN',
      topicId: value(input.serverDraftAttempt.topicId),
      payload: clone(input.serverDraftAttempt.payload),
    };
  }
  if (input.editorMeta) envelope.editorMeta = clone(input.editorMeta);
  if (value(input.topicId)) envelope.topicId = input.topicId;
  else envelope.draftUuid = value(input.draftUuid);

  // 唯一一次写入：章节和待编排素材来自同一个页面快照。
  storage.setStorageSync(key, clone(envelope));
  if (envelope.draftUuid) {
    try { rememberActiveNewDraft(storage, memberId, envelope.draftUuid, input.operationScope); } catch (error) {}
  }
  return key;
}

function loadDraft(storage, identity, options) {
  options = options || {};
  if (!storage || typeof storage.getStorageSync !== 'function') {
    return { status: 'storage_unavailable', envelope: null };
  }
  var stored;
  try {
    stored = storage.getStorageSync(draftKeyFor(identity, options.memberId, options.operationScope));
    if (!stored && options.operationScope === 'MERCHANT') stored = storage.getStorageSync(draftKeyFor(identity, options.memberId));
    // A failed read is not proof that a previous UNKNOWN envelope does not exist.
    if (!stored) stored = storage.getStorageSync(draftKeyFor(identity));
  } catch (error) {
    return { status: 'storage_unavailable', envelope: null };
  }
  if (!stored || typeof stored !== 'object') return { status: 'missing', envelope: null };
  var envelope = clone(stored);
  if (value(envelope.memberId) !== value(options.memberId)) {
    return { status: 'member_mismatch', envelope: null };
  }
  if (envelope.operationScope !== undefined && value(envelope.operationScope) !== value(options.operationScope)) {
    return { status: 'scope_mismatch', envelope: null };
  }
  var scopeUnknown = envelope.operationScope === undefined;
  var storedRevision = value(envelope.baseRevision);
  var currentRevision = value(options.currentBaseRevision);
  var checkingTopicRevision = value(identity && identity.topicId)
    && Object.prototype.hasOwnProperty.call(options, 'currentBaseRevision');
  if (checkingTopicRevision && (!storedRevision || !currentRevision || storedRevision !== currentRevision)) {
    return { status: 'revision_conflict', envelope: envelope, scopeUnknown: scopeUnknown };
  }
  return { status: 'ready', envelope: envelope, scopeUnknown: scopeUnknown };
}

function removeDraft(storage, identity, options) {
  options = options || {};
  if (!storage || typeof storage.removeStorageSync !== 'function'
      || typeof storage.getStorageSync !== 'function' || !value(options.memberId)) return false;
  var removed = false;
  var keys = [draftKeyFor(identity, options.memberId, options.operationScope), draftKeyFor(identity, options.memberId), draftKeyFor(identity)];
  keys.forEach(function (key) {
    var envelope = storage.getStorageSync(key);
    if (envelope && value(envelope.memberId) === value(options.memberId)
        && envelope.operationScope !== undefined && value(envelope.operationScope) === value(options.operationScope)) {
      storage.removeStorageSync(key);
      removed = true;
    }
  });
  var uuid = value(identity && identity.draftUuid);
  if (removed && uuid && readActiveNewDraft(storage, options.memberId, options.operationScope) === uuid) {
    var activeKey = activeKeyFor(options.memberId, options.operationScope);
    if (activeKey) storage.removeStorageSync(activeKey);
  }
  return removed;
}

module.exports = {
  createDraftUuid: createDraftUuid,
  draftKeyFor: draftKeyFor,
  loadDraft: loadDraft,
  readActiveNewDraft: readActiveNewDraft,
  rememberActiveNewDraft: rememberActiveNewDraft,
  removeDraft: removeDraft,
  saveDraft: saveDraft,
};
