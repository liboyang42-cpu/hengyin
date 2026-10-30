'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const file = path.resolve(__dirname, '../../pages/publish/fabu/index.js');
const source = fs.readFileSync(file, 'utf8');
const methods = ['_withDraftHydration', '_installDraftAutosave', '_applyDraftEnvelope', '_materializeCityStoryChapters',
  '_setSaveState', '_onDraftEdited', '_isDraftPatch', '_ensureLocalIds', '_genLocalId', '_draftContentRevision'];
function harness() {
  const callbacks = [], persisted = [], queued = [];
  const context = { SAVE_STATE_LABELS: { unknown: '待确认', local: '已存本机', saved: '已存云端' },
    topicRouteGraph: { normalizeTopicRoute: () => ({ graph: {} }) }, thoughtPatchOf: () => ({}), cyToast() {},
    proEditorStory: require('../../pages/publish/utils/publish/pro-editor-story.js') };
  const page = { data: { formData: { productType: 2, chapters: [] }, pendingMaterials: [] },
    _draftAutosaveReady: false, _invalidateCoverUpload() {}, _withEndingChapters: value => value, _parseImgArr: () => [],
    updateAllStatistics() {}, _cloudDraftEditorOpen: () => false,
    _persistDraftEnvelope() { persisted.push(JSON.parse(JSON.stringify(this._serverDraftAttempt || null))); return true; },
    _queueCloudDraftAutosave() { queued.push({ blockedByOriginal: !!this._serverDraftAttempt }); },
    setData(patch, cb) { for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let target = this.data;
      for (const part of parts.slice(0, -1)) target = target[part];
      target[parts.at(-1)] = value;
    } if (cb) callbacks.push(cb); },
  };
  for (const name of methods) {
    const start = source.indexOf('  ' + name + '('), end = source.indexOf('\n  },', start);
    assert.ok(start >= 0 && end > start, name + ' actual source method must exist');
    page[name] = vm.runInNewContext('({' + source.slice(start, end + 4).trim() + '})', context)[name];
  }
  page._installDraftAutosave();
  return { page, persisted, queued, flush() { let count = 0; while (callbacks.length) { assert.ok(count++ < 100); callbacks.shift()(); } } };
}
function original() {
  return { status: 'UNKNOWN', topicId: '9912617', payload: { id: '9912617', requestId: 'original-stable-key', name: 'Original', imgUrl: 'media-asset:15' } };
}
function restore(h, attempt = original()) {
  h.page._applyDraftEnvelope({ serverDraftAttempt: attempt, formData: { name: 'Original', productType: 2,
    chapters: [{ name: 'Original chapter', nodes: [{ name: 'Original node' }] }] }, pendingMaterials: [] });
  // Real bootstrap restores readiness and UNKNOWN before native asynchronous setData callbacks arrive.
  h.page._draftAutosaveReady = true;
  h.page._setSaveState(attempt ? 'unknown' : 'saved');
}
test('canonical UNKNOWN hydration stays UNKNOWN after real ensure-local-id/materialization asynchronous callbacks', () => {
  const h = harness(), expected = original(); restore(h, expected); h.flush();
  assert.equal(h.page.data.themeSaveState, 'unknown');
  assert.deepEqual(JSON.parse(JSON.stringify(h.page._serverDraftAttempt)), expected);
  assert.equal(h.persisted.length, 0, 'Internal hydration cannot create a dirty persistence notification');
  assert.equal(h.queued.length, 0, 'Internal hydration cannot queue a new cloud save');
  const revision = h.page._draftContentRevision(); h.flush(); assert.equal(h.page._draftContentRevision(), revision);
  assert.ok(h.page.data.formData.chapters[0]._localId); assert.ok(h.page.data.formData.chapters[0].nodes[0]._localId);
});
test('real user edit while hydration callbacks wait is still dirty and preserves exact original UNKNOWN payload', () => {
  const h = harness(), expected = original(); restore(h, expected);
  h.page.setData({ 'formData.name': 'Real user edit' }); h.flush();
  assert.equal(h.page.data.formData.name, 'Real user edit'); assert.equal(h.page.data.themeSaveState, 'local');
  assert.equal(h.persisted.length, 1); assert.deepEqual(h.persisted[0], expected);
  assert.ok(h.queued.length > 0); assert.ok(h.queued.every(row => row.blockedByOriginal));
});
test('confirmed restored draft remains saved, then a later real edit resumes normal autosave', () => {
  const h = harness(); restore(h, null); h.flush();
  assert.equal(h.page.data.themeSaveState, 'saved'); assert.equal(h.persisted.length, 0); assert.equal(h.queued.length, 0);
  h.page.setData({ 'formData.name': 'Later real edit' }); h.flush();
  assert.equal(h.page.data.themeSaveState, 'local'); assert.equal(h.persisted.length, 1);
  assert.ok(h.queued.length > 0); assert.ok(h.queued.every(row => !row.blockedByOriginal));
});

test('hydration scope is released after an internal error; it cannot suppress the next user edit', () => {
  const h = harness(); h.page._draftAutosaveReady = true;
  const error = new Error('internal materialization failed');
  assert.throws(() => h.page._withDraftHydration(() => { throw error; }), value => value === error);
  assert.equal(h.page._draftHydrationDepth, 0);
  h.page.setData({ 'formData.name': 'User can still edit' }); h.flush();
  assert.equal(h.persisted.length, 1); assert.equal(h.page.data.themeSaveState, 'local');
});
