'use strict';
// Original page fixtures gain the real durable journal and real session generation; provider/HTTP stubs stay theirs.
const { createSessionStore } = require('../../utils/session/session-store.js');
const journal = require('../../utils/voice-enrollment-journal.js');
function install(app, storage) {
  const values = new Map();
  if (!storage.getStorageSync) storage.getStorageSync = key => values.get(key);
  if (!storage.setStorageSync) storage.setStorageSync = (key, value) => values.set(key, JSON.parse(JSON.stringify(value)));
  if (!storage.removeStorageSync) storage.removeStorageSync = key => values.delete(key);
  if (!app.getSession) {
    const session = createSessionStore({ storage, target: {} });
    app.getSession = () => session;
  }
  if (!app.getAuthorization) app.getAuthorization = () => 'fixture-current-token';
  return journal;
}
module.exports = { install, journal };

// These existing page tests mock HTTP. Echo the immutable request identity their real page now sends;
// preserve each fixture's original code, status, sample, error and callback scheduling.
const basicInstall = module.exports.install;
const submittedByApp = new WeakMap();
module.exports.install = function (app, storage) {
  const result = basicInstall(app, storage), original = app.sendRequest, submitted = new Map();
  submittedByApp.set(app, submitted);
  if (original) app.sendRequest = function (options) {
    const data = typeof options.data === 'string' ? JSON.parse(options.data || '{}') : options.data || {};
    if (/\/voice\/enroll$/.test(options.url) && data.voiceRequestId) submitted.set(data.voiceRequestId, data);
    if (/\/voice\/(enroll|status)$/.test(options.url) && data.voiceRequestId && submitted.has(data.voiceRequestId)) {
      const callback = options.success, command = submitted.get(data.voiceRequestId);
      options.success = response => {
        if (response && String(response.code) === '200') {
          const nested = response.data && typeof response.data === 'object', value = nested ? response.data : response;
          const extended = Object.assign({ voiceRequestId: data.voiceRequestId, voiceIntentId: 17,
            voiceIntentState: value.voiceStatus === 2 ? 'READY' : value.voiceStatus === 1 ? 'PENDING' : value.voiceStatus === 3 && /\/status$/.test(options.url) ? 'NOT_SENT' : 'UNKNOWN',
            voiceSelected: true, voiceSample: command.voiceSample }, value);
          response = nested ? Object.assign({}, response, { data: extended }) : extended;
        }
        return callback(response);
      };
    }
    return original.call(this, options);
  };
  return result;
};

// Seed an actual prior UNKNOWN journal through its production transitions, not by assigning page flags/storage shapes.
// This is a controlled old HTTP fixture, not evidence of provider execution.
module.exports.seedUnknown = function (app, storage, scope, sample) {
  const member = String(app.getUserID());
  const row = journal.begin(storage, member, scope, null, false);
  journal.dispatched(storage, member, scope, row);
  journal.observe(storage, member, scope, row.requestId, { voiceRequestId: row.requestId,
    voiceIntentId: 17, voiceIntentState: 'UNKNOWN', voiceStatus: 1, voiceSample: sample, voiceSelected: true });
  const submitted = submittedByApp.get(app);
  if (!submitted) throw Error('Install the real voice environment before seeding an original request');
  submitted.set(row.requestId, { voiceSample: sample });
  return row;
};
