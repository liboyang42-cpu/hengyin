const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const upload = require('../../utils/transport/upload-client.js');
const tick = () => new Promise(resolve => setImmediate(resolve));

function fixture(kind) {
  let member = '7', definition;
  const state = { choices: [], crops: [], images: [], requests: [], uploads: [], timers: [], starts: 0, patches: [] };
  const audio = { duration: 15, stop() {}, play() {}, onEnded() {}, onStop() {}, onError() {} };
  const recorder = { onStop(fn) { state.stop = fn; }, onError(fn) { state.recordError = fn; },
    start() { state.starts++; }, resume() { state.starts++; }, pause() {} };
  const client = upload.createUploadClient({ getUserID: () => member,
    getAuthorization: () => 'fixture', getBaseUrl: () => 'https://fixture',
    wxUploadFile(options) { state.uploads.push(options); options.success({ data: '{"code":200,"assetId":73,"url":"https://fixture/media"}' }); return { abort() {} }; },
    setTimer: () => 1, clearTimer() {} });
  const app = { requestSeparateConsent: () => Promise.resolve(), globalData: {}, getUserID: () => member, getUploadClient: () => client,
    sendRequest(options) { state.requests.push(options); if (!state.holdRequest) options.success({ code: 200, data: { voiceStatus: 2 } }); } };
  const wx = { chooseMedia: o => state.choices.push(o), chooseMessageFile: o => state.choices.push(o),
    cropImage: o => state.crops.push(o), createInnerAudioContext: () => audio, getRecorderManager: () => recorder,
    authorize: () => state.permission, canvasToTempFilePath: o => { state.export = o; } };
  const file = path.join(__dirname, '../../', kind === 'merchant'
    ? 'pages/merchant/decor/ai-npc/index.js' : 'pages/topic/components/cy/node-npc-form/index.js');
  require('../helpers/voice-enrollment-environment.cjs').install(app, wx);
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, wx, Page: d => { definition = d; }, Component: d => { definition = d; },
    setTimeout(fn) { state.timers.push(fn); return state.timers.length; }, clearTimeout() {}, console,
    require(id) {
      if (id.endsWith('voice-enrollment-journal.js')) return require('../../utils/voice-enrollment-journal.js');
      if (id.endsWith('pixel-avatar.js')) return { AVATAR_IDS: ['one'], stringifyCode: () => 'px1:one', isPixelAvatar: () => false };
      if (id.endsWith('pixel-avatar-data.js')) return { AVATARS: { one: { name: 'fixture' } } };
      if (id.endsWith('pixel-portrait.js')) return { squareCrop: () => ({ size: 32 }), buildPortrait: () => ({ fixture: true }), drawPortrait() {} };
      if (id.endsWith('merchant-theme.js')) return { merchantPageShow() {}, merchantPageRestore() {} };
      if (id.endsWith('upload-client.js')) return upload;
      if (id.endsWith('toast.js')) return Object.assign(() => {}, { success() {} });
      return {};
    }
  }, { filename: file });
  const target = Object.assign({}, kind === 'merchant' ? definition : definition.methods);
  target.data = JSON.parse(JSON.stringify(definition.data));
  // Real node voice commands require an open form and a concrete node scope.
  if (kind === 'node') Object.assign(target.data, { show: true, nodeId: 1 });
  target.setData = (patch, callback) => {
    state.patches.push(patch);
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.'); let cursor = target.data;
      for (const part of parts.slice(0, -1)) cursor = cursor[part] || (cursor[part] = {});
      cursor[parts[parts.length - 1]] = value;
    }
    if (callback) callback();
  };
  target._drawPreview = target.updateDirty = target._renderVoiceStatus = target.refreshVoice = target._ok = target._fail = () => {};
  target._canvas = () => Promise.resolve({
    getContext: () => ({ clearRect() {}, drawImage() {}, fillRect() {}, getImageData: () => ({ data: [] }) }),
    createImage() { const image = {}; state.images.push(image); return image; }
  });
  target._photoMember = target._voiceMember = '7';
  return { ...state, state, target, audio, wx, original: kind === 'merchant' ? definition : definition.methods, switchOwner: () => { member = '8'; }, sameOwnerNumber: () => { member = 7; } };
}

for (const kind of ['merchant', 'node']) for (const changed of [false, true]) {
  const label = kind + (changed ? '换号' : '同会员对照');
  test(label + '旧原生选图回调', () => {
    const f = fixture(kind); let processed = 0;
    f.target._cropThenProcess = f.target._processPhoto = () => processed++;
    f.target.choosePhoto(); if (changed) f.switchOwner(); else f.sameOwnerNumber();
    f.state.choices[0].success({ tempFiles: [{ tempFilePath: 'fixture-A' }] });
    assert.equal(f.state.crops.length + processed, changed ? 0 : 1);
  });
  test(label + '旧原生裁剪回调', () => {
    const f = fixture(kind); let processed = 0;
    f.target._processPhoto = () => processed++;
    f.target.choosePhoto(); f.state.choices[0].success({ tempFiles: [{ tempFilePath: 'fixture-A' }] });
    if (changed) f.switchOwner();
    f.state.crops[0].success({ tempFilePath: 'fixture-A-crop' });
    assert.equal(processed, changed ? 0 : 1);
  });
  test(label + '旧画布处理结果', async () => {
    const f = fixture(kind); f.target._processPhoto('fixture-A'); await tick();
    if (changed) f.switchOwner();
    f.state.images[0].onload(); await tick();
    if (f.state.export) { f.state.export.success({ tempFilePath: 'fixture-A-export' }); await tick(); }
    assert.equal(Boolean(f.target._portrait), !changed);
    assert.equal(f.target.data.source === 'photo', !changed);
  });
  test(label + '已选择的旧照片不能发起上传保存', async () => {
    const f = fixture(kind); f.target.data.source = 'photo'; f.target._photoPath = 'fixture-A';
    f.target.data.name = 'fixture'; f.target.data.photoReady = true; f.target._portrait = {};
    f.target.data.form = { name: 'fixture', avatar: '', greeting: '' }; f.target.data.avatarPreview = 'fixture-A';
    f.target._exportPortrait = () => Promise.resolve('fixture-A-export');
    if (changed) f.switchOwner(); f.target.save(); await tick();
    assert.equal(f.state.uploads.length, changed ? 0 : 1);
    assert.equal(f.state.requests.length, changed ? 0 : 1);
  });
  test(label + '照片保存异步导出期间不能切换账号提交', async () => {
    const f = fixture(kind); let resolve;
    f.target.data.source = 'photo'; f.target._photoPath = 'fixture-A'; f.target._portrait = {};
    f.target.data.name = 'fixture'; f.target.data.photoReady = true;
    f.target.data.form = { name: 'fixture', avatar: '', greeting: '' }; f.target.data.avatarPreview = 'fixture-A';
    if (kind === 'merchant') f.target._exportPortrait = () => new Promise(r => { resolve = r; });
    else f.target._upload = () => new Promise(r => { resolve = r; });
    f.target.save(); if (changed) f.switchOwner(); resolve('https://fixture/photo'); await tick();
    assert.equal(f.state.requests.length, changed ? 0 : 1);
    if (kind === 'merchant') assert.equal(f.state.uploads.length, changed ? 0 : 1);
  });
  test(label + '已选择的旧声音不能发起上传注册', async () => {
    const f = fixture(kind); f.target.data.voiceConsent = true;
    f.target.data.voiceFile = { path: 'fixture-A.mp3', size: 1024 };
    if (changed) f.switchOwner(); f.target.uploadVoice(); await tick();
    assert.equal(f.state.uploads.length, changed ? 0 : 1);
    assert.equal(f.state.requests.length, changed ? 0 : 1);
  });
  test(label + '声音已有URL的异步注册不能换号', async () => {
    const f = fixture(kind); f.target.data.voiceConsent = true;
    f.target.data.voiceFile = { path: 'fixture-A.mp3', size: 1024, url: 'https://fixture/voice' };
    f.target.uploadVoice(); if (changed) f.switchOwner(); await tick();
    assert.equal(f.state.requests.length, changed ? 0 : 1);
  });
}
for (const changed of [false, true]) {
  test('节点声音选择器' + (changed ? '换号' : '正常'), () => {
    const f = fixture('node'); f.target.chooseVoice(); if (changed) f.switchOwner();
    f.state.choices[0].success({ tempFiles: [{ name: 'fixture.mp3', path: 'fixture-A', size: 1024 }] });
    assert.equal(Boolean(f.target.data.voiceFile), !changed);
  });
  test('节点声音时长异步读取' + (changed ? '换号' : '正常'), () => {
    const f = fixture('node'); f.audio.duration = 0; f.target.chooseVoice();
    f.state.choices[0].success({ tempFiles: [{ name: 'fixture.mp3', path: 'fixture-A', size: 1024 }] });
    if (changed) f.switchOwner(); f.audio.duration = 15; f.state.timers[0]();
    assert.equal(Boolean(f.target.data.voiceFile), !changed);
  });
  test('门店录音权限异步返回' + (changed ? '换号' : '正常'), async () => {
    const f = fixture('merchant'); let resolve; f.state.permission = new Promise(r => { resolve = r; });
    f.target.data.voiceConsent = true; f.target.onVoiceHoldStart();
    if (changed) f.switchOwner(); resolve(); await tick();
    assert.equal(f.state.starts, changed ? 0 : 1);
  });
  test('门店旧录音结束回调' + (changed ? '换号' : '正常'), async () => {
    const f = fixture('merchant'); f.state.permission = Promise.resolve();
    f.target.data.voiceConsent = true; f.target.onVoiceHoldStart(); await tick();
    f.target.data.voiceSentenceConfirmed = 3; if (changed) f.switchOwner();
    f.state.stop({ tempFilePath: 'fixture-A', duration: 15000, fileSize: 1024 });
    assert.equal(Boolean(f.target.data.voiceFile), !changed);
  });
}

for (const kind of ['merchant', 'node']) for (const changed of [false, true]) {
  test(kind + '导出画布返回' + (changed ? '换号' : '正常'), async () => {
    const f = fixture(kind); let resolve; let painted = 0;
    f.target._portrait = {};
    f.target._canvas = () => new Promise(r => { resolve = r; });
    const pending = f.target._exportPortrait(); if (changed) f.switchOwner();
    resolve({ getContext: () => ({ fillRect() { painted++; } }) }); await tick();
    assert.equal(painted, changed ? 0 : 1);
    if (f.state.export) f.state.export.success({ tempFilePath: 'fixture-export' });
    await pending;
  });
  for (const phase of ['success', 'failure']) test(kind + '保存回包' + phase + (changed ? '换号' : '正常'), async () => {
    const f = fixture(kind); f.state.holdRequest = true;
    f.target.data.form = { name: 'fixture', avatar: 'px1:one', greeting: '' };
    f.target.data.avatarPreview = 'px1:one';
    if (kind === 'merchant') f.target.persistProfile({ name: 'fixture', avatar: 'px1:one', greeting: '' });
    else f.target.save();
    await tick(); const before = f.state.patches.length;
    if (changed) f.switchOwner();
    if (phase === 'success') f.state.requests[0].success({ code: 200, data: { name: 'reply', avatar: 'px1:one' } });
    else f.state.requests[0].successStatusAbnormal({ code: 500, msg: 'fixture failure' });
    await tick();
    assert.equal(f.state.patches.length > before, !changed);
  });
}
for (const changed of [false, true]) test('门店已有录音不能跨账号继续录制' + (changed ? '换号' : '正常'), async () => {
  const f = fixture('merchant'); f.state.permission = Promise.resolve(); f.target.data.voiceConsent = true;
  f.target.onVoiceHoldStart(); await tick(); f.target.onVoiceHoldEnd(); f.target.confirmVoiceSentence();
  if (changed) f.switchOwner(); f.target.onVoiceHoldStart(); await tick();
  assert.equal(f.state.starts, changed ? 1 : 2);
});

for (const transition of ['normal', 'same-node', 'other-node', 'hide', 'account']) {
  function reopen(f) {
    if (transition === 'account') f.switchOwner();
    if (transition === 'hide') { f.target._onHide(); f.target.data.show = false; }
    if (transition === 'same-node' || transition === 'other-node') {
      f.target._onHide();
      if (transition === 'other-node') f.target.data.nodeId = 2;
      f.target._onOpen();
    }
  }
  function form() {
    const f = fixture('node');
    f.state.holdRequest = true;
    f.target.data.show = true;
    f.target.data.nodeId = 1;
    f.target.refreshVoice = f.original.refreshVoice;
    f.state.errors = [];
    f.target._fail = message => f.state.errors.push(message);
    return f;
  }
  for (const outcome of ['success', 'failure']) {
    test('节点详情回填 ' + transition + ' ' + outcome, async () => {
      const f = form(); f.target._onOpen(); const old = f.state.requests[0];
      reopen(f); f.target.data.form.name = 'current-form';
      if (outcome === 'success') old.success({ code: 200, data: { name: 'old-form', avatar: 'px1:one' } });
      else old.successStatusAbnormal({ code: 500, msg: 'old-failure' });
      await tick();
      assert.equal(f.target.data.form.name, transition === 'normal' && outcome === 'success' ? 'old-form' : 'current-form');
      assert.equal(f.state.errors.length, transition === 'normal' && outcome === 'failure' ? 1 : 0);
    });
    test('节点清除声音回包 ' + transition + ' ' + outcome, async () => {
      const f = form(); f.target.resetVoice(); const old = f.state.requests[0];
      reopen(f); f.target.data.voiceSample = 'current-voice';
      if (outcome === 'success') old.success({ code: 200 });
      else old.successStatusAbnormal({ code: 500, msg: 'old-failure' });
      await tick();
      assert.equal(f.target.data.voiceSample, transition === 'normal' && outcome === 'success' ? '' : 'current-voice');
      assert.equal(f.state.errors.length, transition === 'normal' && outcome === 'failure' ? 1 : 0);
    });
  }
  test('节点声音状态回包 ' + transition, async () => {
    const f = form(); f.target.refreshVoice(); const old = f.state.requests[0];
    reopen(f); f.target.data.voiceSample = 'current-voice';
    old.success({ code: 200, data: { voiceStatus: 1, voiceSample: 'old-voice' } }); await tick();
    assert.equal(f.target.data.voiceSample, transition === 'normal' ? 'old-voice' : 'current-voice');
    assert.equal(f.state.timers.length, transition === 'normal' ? 1 : 0);
  });
  test('节点原生选图回调 ' + transition, () => {
    const f = form(); let processed = 0; f.target._processPhoto = () => processed++;
    f.target.choosePhoto(); const old = f.state.choices[0]; reopen(f);
    old.success({ tempFiles: [{ tempFilePath: 'old-photo' }] });
    assert.equal(f.state.crops.length + processed, transition === 'normal' ? 1 : 0);
  });
  test('节点保存回包 ' + transition, async () => {
    const f = form(); f.target.data.form.name = 'current-name'; f.target.data.avatarPreview = 'px1:one';
    f.target.save(); await tick();
    const old = f.state.requests[0]; reopen(f);
    f.target.data.form.name = 'current-form';
    let accepted = 0; f.target._ok = () => accepted++;
    old.success({ code: 200 }); await tick();
    assert.equal(accepted, transition === 'normal' ? 1 : 0);
    assert.equal(f.target.data.form.name, transition === 'normal' ? 'current-name' : 'current-form');
  });
}
test('节点重开清理旧表单忙状态', () => {
  const f = fixture('node'); f.state.holdRequest = true;
  f.target.data.processing = f.target.data.saving = f.target.data.voiceBusy = true;
  f.target._voiceSubmitted = f.target._resultSaved = true;
  f.target._onHide(); f.target._onOpen();
  assert.equal(f.target.data.processing, false);
  assert.equal(f.target.data.saving, false);
  assert.equal(f.target.data.voiceBusy, false);
  assert.equal(f.target._voiceSubmitted, false);
  assert.equal(f.target._resultSaved, false);
});

for (const kind of ['merchant', 'node']) for (const transition of ['normal', 'account', 'newer']) {
  for (const outcome of ['success', 'failure']) {
    test(kind + '声音状态请求 ' + transition + ' ' + outcome, async () => {
      const f = fixture(kind);
      f.state.holdRequest = true;
      f.target.data.show = true;
      f.target.data.voiceStatus = 2;
      f.target.data.voiceSample = 'current-voice';
      f.target.data.voiceError = '';
      f.target.refreshVoice = f.original.refreshVoice;
      f.target.refreshVoice();
      const old = f.state.requests[0];
      if (transition === 'account') f.switchOwner();
      else if (transition === 'newer') {
        f.target.refreshVoice();
        f.state.requests[1].success({ code: 200, data: { voiceStatus: 2, voiceSample: 'current-voice' } });
        await tick();
      } else f.sameOwnerNumber();
      if (outcome === 'success') old.success({ code: 200, data: { voiceStatus: 1, voiceSample: 'old-voice' } });
      else old.successStatusAbnormal({ code: 500, msg: 'old-failure' });
      await tick();
      const accepted = transition === 'normal' && outcome === 'success';
      assert.equal(f.target.data.voiceStatus, accepted ? 1 : 2);
      assert.equal(f.target.data.voiceSample, accepted ? 'old-voice' : 'current-voice');
      assert.equal(f.state.timers.length, accepted ? 1 : 0);
      assert.equal(f.target.data.voiceError, kind === 'merchant' && transition === 'normal' && outcome === 'failure' ? 'old-failure' : '');
    });
  }
}
for (const status of [1, 2]) {
  test('节点清除声音后旧状态回包不得复活状态 ' + status, async () => {
    const f = fixture('node');
    f.state.holdRequest = true;
    f.target.data.show = true;
    f.target.data.voiceStatus = 2;
    f.target.data.voiceSample = 'old-voice';
    f.target.refreshVoice = f.original.refreshVoice;
    f.target.refreshVoice();
    const old = f.state.requests[0];
    f.target.resetVoice();
    f.state.requests[1].success({ code: 200 });
    await tick();
    old.success({ code: 200, data: { voiceStatus: status, voiceSample: 'old-voice' } });
    await tick();
    assert.equal(f.target.data.voiceStatus, 0);
    assert.equal(f.target.data.voiceSample, '');
    assert.equal(f.state.timers.length, 0);
  });
}
for (const outcome of ['success', 'failure']) {
  test('门店隐藏后返回不接收旧声音状态 ' + outcome, async () => {
    const f = fixture('merchant');
    f.state.holdRequest = true;
    f.target.data.voiceStatus = 2;
    f.target.data.voiceSample = 'current-voice';
    f.target.data.voiceError = '';
    f.target.refreshVoice = f.original.refreshVoice;
    f.target.refreshVoice();
    const old = f.state.requests[0];
    f.target.onHide();
    f.target.onShow();
    if (outcome === 'success') old.success({ code: 200, data: { voiceStatus: 1, voiceSample: 'old-voice' } });
    else old.successStatusAbnormal({ code: 500, msg: 'old-failure' });
    await tick();
    assert.equal(f.target.data.voiceStatus, 2);
    assert.equal(f.target.data.voiceSample, 'current-voice');
    assert.equal(f.target.data.voiceError, '');
    assert.equal(f.state.timers.length, 0);
  });
}
for (const kind of ['merchant', 'node']) {
  test(kind + '声音状态刷新未配置时清除旧样本', async () => {
    const f = fixture(kind);
    f.state.holdRequest = true;
    f.target.data.show = true;
    f.target.data.voiceStatus = 2;
    f.target.data.voiceSample = 'old-voice';
    f.target.refreshVoice = f.original.refreshVoice;
    f.target.refreshVoice();
    f.state.requests[0].success({ code: 200, data: { voiceStatus: 0, voiceSample: null } });
    await tick();
    assert.equal(f.target.data.voiceStatus, 0);
    assert.equal(f.target.data.voiceSample, '');
  });
}

for (const kind of ['merchant', 'node']) {
  for (const pending of [false, true]) {
    test(kind + '当前声音状态查询失败 ' + (pending ? '继续轮询' : '非生成中不轮询'), async () => {
      const f = fixture(kind);
      f.state.holdRequest = true;
      f.target.data.show = true;
      f.target.data.voiceStatus = pending ? 1 : 2;
      f.target.refreshVoice = f.original.refreshVoice;
      f.target.refreshVoice();
      f.state.requests[0].successStatusAbnormal({ code: 500, msg: 'query-failure' });
      await tick();
      assert.equal(f.state.timers.length, pending ? 1 : 0);
      assert.equal(f.target.data.voiceStatus, pending ? 1 : 2);
    });
  }
  for (const transition of ['normal', 'account', 'hide', 'newer']) {
    test(kind + '排队声音轮询 ' + transition, async () => {
      const f = fixture(kind);
      f.state.holdRequest = true;
      f.target.data.show = true;
      f.target.refreshVoice = f.original.refreshVoice;
      f.target.refreshVoice();
      f.state.requests[0].success({ code: 200, data: { voiceStatus: 1, voiceSample: 'current-voice' } });
      await tick();
      assert.equal(f.state.timers.length, 1);
      const queued = f.state.timers[0];
      if (transition === 'account') f.switchOwner();
      else if (transition === 'hide') {
        if (kind === 'merchant') { f.target.onHide(); f.target.onShow(); }
        else { f.target._onHide(); f.target._onOpen(); }
      } else if (transition === 'newer') {
        f.target.refreshVoice();
        f.state.requests[1].success({ code: 200, data: { voiceStatus: 2, voiceSample: 'current-voice' } });
        await tick();
      } else f.sameOwnerNumber();
      const before = f.state.requests.length;
      queued();
      assert.equal(f.state.requests.length, before + (transition === 'normal' ? 1 : 0));
    });
  }
}
