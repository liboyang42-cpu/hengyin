const test = require('node:test');
const assert = require('node:assert/strict');

global.getApp = () => ({ globalData: { features: {} }, isDevEnv: () => false, sendRequest() {} });
global.wx = { getStorageSync: () => '', getWindowInfo: () => ({ statusBarHeight: 20 }),
  getSystemInfoSync: () => ({ statusBarHeight: 20 }) };
let definition;
global.Page = value => { definition = value; };
require('../../pages/play/index.js');
const audioSession = require('../../pages/play/utils/audio-session.js');

test('回前台只恢复原在播的站点音轨，不抢播主题曲、手动暂停的旁白或重复重启', () => {
  const context = playing => ({ paused: !playing, calls: [],
    play() { this.paused = false; this.calls.push('play'); },
    pause() { this.paused = true; this.calls.push('pause'); } });
  const theme = context(false), station = context(true), narration = context(false);
  [theme, station, narration].forEach(ctx => audioSession.register(ctx));
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)), _themeBgm: theme });
  page.data.isPreview = true;
  for (const name of ['restoreGuardAppeal', '_syncCommunityEntry', '_startThemePoll', '_flushTrail',
    '_startZeroHpTimer', '_resetPackFx', '_consumePendingStart']) page[name] = () => {};
  try {
    page.pauseAudio();
    page.onShow();
    assert.deepEqual(station.calls, ['pause', 'play']);
    assert.deepEqual(theme.calls, []);
    assert.deepEqual(narration.calls, []);
    page.onShow();
    assert.deepEqual(station.calls, ['pause', 'play']);
    assert.deepEqual(theme.calls, []);
  } finally {
    [theme, station, narration].forEach(ctx => audioSession.unregister(ctx));
  }
});
