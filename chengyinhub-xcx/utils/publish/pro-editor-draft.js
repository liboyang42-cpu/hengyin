// 专业编辑器本地草稿：一个 storage key 一次写入完整信封。
// 新草稿按 draftUuid 分桶；既有主题按 topicId 分桶。memberId 只用于恢复前防串号，
// 不能用“当前 key 是我算出来的”替代对信封归属的校验。

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

function draftKeyFor(identity, memberId) {
  identity = identity || {};
  var prefix = DRAFT_PREFIX;
  if (value(memberId)) prefix += 'member_' + encodeURIComponent(value(memberId)) + '_';
  var topicId = value(identity.topicId);
  if (topicId) return prefix + 'topic_' + encodeURIComponent(topicId);
  var draftUuid = value(identity.draftUuid);
  if (draftUuid) return prefix + 'new_' + encodeURIComponent(draftUuid);
  throw new Error('topicId 或 draftUuid 至少提供一个');
}

function activeKeyFor(memberId) {
  var id = value(memberId);
  if (!id) return '';
  return ACTIVE_PREFIX + encodeURIComponent(id);
}

function rememberActiveNewDraft(storage, memberId, draftUuid) {
  var key = activeKeyFor(memberId);
  var uuid = value(draftUuid);
  if (!key || !uuid || !storage || typeof storage.setStorageSync !== 'function') return false;
  storage.setStorageSync(key, uuid);
  return true;
}

function readActiveNewDraft(storage, memberId) {
  var key = activeKeyFor(memberId);
  if (!key || !storage || typeof storage.getStorageSync !== 'function') return '';
  return value(storage.getStorageSync(key));
}

function saveDraft(storage, input) {
  input = input || {};
  if (!storage || typeof storage.setStorageSync !== 'function') {
    throw new Error('storage 不可用');
  }
  var memberId = value(input.memberId);
  if (!memberId) throw new Error('memberId 不能为空');

  var identity = { topicId: input.topicId, draftUuid: input.draftUuid };
  var key = draftKeyFor(identity, memberId);
  var formData = clone(input.formData || { chapters: input.chapters || [] }) || { chapters: [] };
  var envelope = {
    memberId: input.memberId,
    baseRevision: value(input.baseRevision),
    formData: formData,
    chapters: clone(formData.chapters || []),
    pendingMaterials: clone(input.pendingMaterials || []),
    savedAt: input.savedAt == null ? Date.now() : input.savedAt,
  };
  if (input.publishAttempt && input.publishAttempt.status === 'UNKNOWN') {
    envelope.publishAttempt = { status: 'UNKNOWN', topicId: value(input.publishAttempt.topicId) };
  }
  if (input.editorMeta) envelope.editorMeta = clone(input.editorMeta);
  if (value(input.topicId)) envelope.topicId = input.topicId;
  else envelope.draftUuid = value(input.draftUuid);

  // 唯一一次写入：章节和待编排素材来自同一个页面快照。
  storage.setStorageSync(key, clone(envelope));
  if (envelope.draftUuid) rememberActiveNewDraft(storage, memberId, envelope.draftUuid);
  return key;
}

function loadDraft(storage, identity, options) {
  options = options || {};
  if (!storage || typeof storage.getStorageSync !== 'function') {
    return { status: 'missing', envelope: null };
  }
  var stored = storage.getStorageSync(draftKeyFor(identity, options.memberId));
  // Older installs used an unscoped key; read it without moving or deleting another account's data.
  if (!stored) stored = storage.getStorageSync(draftKeyFor(identity));
  if (!stored || typeof stored !== 'object') return { status: 'missing', envelope: null };
  var envelope = clone(stored);
  if (value(envelope.memberId) !== value(options.memberId)) {
    return { status: 'member_mismatch', envelope: null };
  }
  var storedRevision = value(envelope.baseRevision);
  var currentRevision = value(options.currentBaseRevision);
  if (storedRevision && currentRevision && storedRevision !== currentRevision) {
    return { status: 'revision_conflict', envelope: envelope };
  }
  return { status: 'ready', envelope: envelope };
}

function removeDraft(storage, identity, options) {
  options = options || {};
  if (!storage || typeof storage.removeStorageSync !== 'function'
      || typeof storage.getStorageSync !== 'function' || !value(options.memberId)) return false;
  var removed = false;
  var keys = [draftKeyFor(identity, options.memberId), draftKeyFor(identity)];
  keys.forEach(function (key) {
    var envelope = storage.getStorageSync(key);
    if (envelope && value(envelope.memberId) === value(options.memberId)) {
      storage.removeStorageSync(key);
      removed = true;
    }
  });
  var uuid = value(identity && identity.draftUuid);
  if (removed && uuid && readActiveNewDraft(storage, options.memberId) === uuid) {
    var activeKey = activeKeyFor(options.memberId);
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
