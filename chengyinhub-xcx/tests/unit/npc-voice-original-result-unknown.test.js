'use strict';

// PRIVATE-GROUP-H-voice-original-result:声音 enroll 已发出却没拿到明确回执 = UNKNOWN。
// UNKNOWN 只能由 voice/status 回读到「服务端样本 = 原录音」才解除;状态有效但样本仍是旧的,继续锁住重录/上传。
// 明确业务拒绝不是 UNKNOWN;enroll 成功回执里 voiceStatus 必须是 1/2 的数字。
// 当前调用者携带原 requestId，且正向夹具同时回读原意图元数据与样本；负向样本/状态断言保留。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const tick = () => new Promise((resolve) => setImmediate(resolve));
const settle = async () => { for (let i = 0; i < 6; i++) await tick(); };

const FILE = path.join(__dirname, '../../pages/merchant/decor/ai-npc/index.js');
const URL = 'media-asset:73';

function fixture() {
  let definition;
  const requests = [];
  const app = {
    globalData: {}, getUserID: () => '7', requestSeparateConsent: () => Promise.resolve(),
    getUploadClient: () => ({ uploadAll() { throw Error('不应重新上传'); } }),
    sendRequest(options) { requests.push(options); },
  };
  const storage = { createInnerAudioContext: () => ({ stop() {}, play() {}, onEnded() {}, onStop() {}, onError() {} }) };
  require('../helpers/voice-enrollment-environment.cjs').install(app, storage);
  vm.runInNewContext(fs.readFileSync(FILE, 'utf8'), {
    getApp: () => app, Page: (d) => { definition = d; },
    wx: storage,
    setTimeout: () => 0, clearTimeout() {}, console,
    require(id) {
      if (id.endsWith('voice-enrollment-journal.js')) return require('../../utils/voice-enrollment-journal.js');
      if (id.endsWith('pixel-avatar.js')) return { AVATAR_IDS: ['one'], stringifyCode: () => 'px1:one', isPixelAvatar: () => false };
      if (id.endsWith('pixel-avatar-data.js')) return { AVATARS: { one: { name: 'fixture' } } };
      if (id.endsWith('toast.js')) return Object.assign(() => {}, { success() {} });
      if (id.endsWith('transport/upload-client.js')) return { MAX_UPLOAD_FILE_SIZE: 10 * 1024 * 1024 };
      return {};
    },
  }, { filename: FILE });
  const page = Object.assign({}, definition);
  page.data = JSON.parse(JSON.stringify(definition.data));
  page.setData = (patch, cb) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let cursor = page.data;
      for (const part of parts.slice(0, -1)) cursor = cursor[part] || (cursor[part] = {});
      cursor[parts[parts.length - 1]] = value;
    }
    if (cb) cb();
  };
  page._voiceMember = '7';
  page.audio = () => ({ stop() {} });
  page.setData({ voiceFile: { path: 'tmp://new', size: 10, url: URL }, voiceConsent: true, voiceStatus: 2, voiceSample: 'https://fixture/media/old.m4a' });
  const last = (url) => requests.filter((r) => r.url === url).pop();
  return { page, requests, last };
}

async function enrollUnknown(f) {
  f.page.uploadVoice();
  await settle();
  f.last('/api/merchant/npc/voice/enroll').fail();
  await settle();
}

test('断网:UNKNOWN 不会被「状态有效但样本仍是旧录音」的回读清掉', async () => {
  const f = fixture();
  await enrollUnknown(f);
  assert.equal(f.page.data.voiceUnknown, true);
  f.last('/api/merchant/npc/voice/status').success({ code: '200', voiceStatus: 2, voiceSample: 'https://fixture/media/old.m4a' });
  await settle();
  assert.equal(f.page.data.voiceUnknown, true, '原录音还没确认，不能解除 UNKNOWN');
  assert.equal(f.page.data.voiceFile.url, URL, '原录音保留');
  // 锁住期间上传不再发 enroll
  const enrolls = f.requests.filter((r) => r.url === '/api/merchant/npc/voice/enroll').length;
  f.page.uploadVoice(); await settle();
  assert.equal(f.requests.filter((r) => r.url === '/api/merchant/npc/voice/enroll').length, enrolls);

  // 再查:服务端样本 = 原录音 → 解除并清本地待处理录音
  f.page.refreshVoice();
  f.last('/api/merchant/npc/voice/status').success({ code: '200', voiceStatus: 1, voiceSample: URL });
  await settle();
  assert.equal(f.page.data.voiceUnknown, false);
  assert.equal(f.page.data.voiceFile, null);
});

test('原录音被确认为失败(3):解除 UNKNOWN 但保留录音可重试', async () => {
  const f = fixture();
  await enrollUnknown(f);
  f.last('/api/merchant/npc/voice/status').success({ code: '200', voiceStatus: 3, voiceSample: URL });
  await settle();
  assert.equal(f.page.data.voiceUnknown, false);
  assert.equal(f.page.data.voiceFile.url, URL);
});

test('后端「结果待确认」与 HTTP 异常是 UNKNOWN;明确业务拒绝不是', async () => {
  const pending = fixture();
  pending.page.uploadVoice(); await settle();
  pending.last('/api/merchant/npc/voice/enroll').success({ code: 500, msg: '声音结果待确认，请稍后查询状态' });
  await settle();
  assert.equal(pending.page.data.voiceUnknown, true);

  const abnormal = fixture();
  abnormal.page.uploadVoice(); await settle();
  abnormal.last('/api/merchant/npc/voice/enroll').successStatusAbnormal({ statusCode: 502 });
  await settle();
  assert.equal(abnormal.page.data.voiceUnknown, true);

  const rejected = fixture();
  rejected.page.uploadVoice(); await settle();
  rejected.last('/api/merchant/npc/voice/enroll').success({ code: 500, msg: '语音服务未开通' });
  await settle();
  assert.equal(rejected.page.data.voiceUnknown, false);
  assert.equal(rejected.page.data.voiceError, '语音服务未开通');
  assert.equal(rejected.requests.filter((r) => r.url === '/api/merchant/npc/voice/status').length, 0);
});

test('enroll 成功回执 voiceStatus 非 1/2 数字 → 按未知处理并保留原录音', async () => {
  for (const bad of ['2', null, 0, 3, undefined]) {
    const f = fixture();
    f.page.uploadVoice(); await settle();
    f.last('/api/merchant/npc/voice/enroll').success({ code: '200', voiceStatus: bad });
    await settle();
    assert.equal(f.page.data.voiceUnknown, true, String(bad));
    assert.equal(f.page.data.voiceFile.url, URL);
  }
  const ok = fixture();
  ok.page.uploadVoice(); await settle();
  ok.last('/api/merchant/npc/voice/enroll').success({ code: '200', voiceStatus: 1 });
  await settle();
  assert.equal(ok.page.data.voiceUnknown, false);
  assert.equal(ok.page.data.voiceStatus, 1);
  assert.equal(ok.page.data.voiceFile, null);
});
