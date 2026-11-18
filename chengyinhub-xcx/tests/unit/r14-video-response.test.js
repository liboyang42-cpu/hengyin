'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// Run the real response consumer, buildChapter and story/video transitions; only platform I/O is stubbed.
function loadPlay() {
  const requests = [];
  global.getApp = () => ({ globalData: {}, getUserID: () => 9,
    sendRequest: options => requests.push(options) });
  global.wx = {
    getStorageSync() {}, setStorageSync() {}, setKeepScreenOn() {},
    getSystemInfoSync: () => ({ windowWidth: 390, statusBarHeight: 20 }),
    getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
    getImageInfo: options => options.success({ path: options.src }),
    createVideoContext: () => ({ stop() {} }),
  };
  let config;
  const file = path.resolve(__dirname, '../../pages/play/index.js');
  const localRequire = id => require(id.startsWith('.') ? path.resolve(path.dirname(file), id) : id);
  new Function('Page', 'getApp', 'wx', 'require', fs.readFileSync(file, 'utf8'))(
    value => { config = value; }, global.getApp, global.wx, localRequire);
  const page = Object.assign({}, config, { data: JSON.parse(JSON.stringify(config.data)) });
  page.setData = (patch, cb) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split(/[.[\]]+/).filter(Boolean); let target = page.data;   // 'a[2].b' 与小程序同口径
      for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
      target[parts[parts.length - 1]] = value;
    }
    if (cb) cb();
  };
  page._isPlayAccountCurrent = () => true;
  page._playOwner = 9; page._routeState = { sessionId: 70 };
  page.data.topicId = 10; page.data.activityId = 11;
  page.data.chapter = { id: 1, blocks: [] };
  page._coverOf = () => ''; page._chapterTime = () => ''; page._themePalette = () => 'night';
  page._inlineKit = () => false; page._textFx = () => ({});
  for (const method of ['measureChapter', 'showChapterGate', '_resumeStory', '_enterThemeChapter', 'stopAudio']) page[method] = () => {};
  page.narrationRequests = 0;
  page.playChapterAudio = () => { page.narrationRequests++; };
  page._showEnding = value => { page.endingCard = value; };
  return { page, requests };
}

const chapter = { id: 88, name: '尾声', atmospherePreset: 'night',
  audioUrl: 'https://example.test/end.mp3', videoUrl: 'https://example.test/end.mp4',
  videoPosterUrl: 'https://example.test/end.jpg', blocks: [{ type: 'text', content: '雨停了。' }] };
const flush = () => new Promise(resolve => setImmediate(resolve));
async function deliver(page, requests, data) {
  page._loadLifeEnding(() => { page.finishedWithoutEnding = true; });
  assert.equal(requests.at(-1).url, '/api/play/journey/ending');
  requests.at(-1).success({ code: 200, data });
  await flush();
}

test('live and archived ending responses retain all media through buildChapter and real story opening', async () => {
  for (const archived of [false, true]) for (const alreadyOpen of [false, true]) {
    const { page, requests } = loadPlay();
    page.data.chapterFull = alreadyOpen;
    const payload = { title: '尾声', code: 'done', ...(archived ? { story: { chapter } } : { chapter }) };
    await deliver(page, requests, payload);
    assert.equal(page.data.chapter.ending, true);
    for (const field of ['audioUrl', 'videoUrl', 'videoPosterUrl']) assert.equal(page.data.chapter[field], chapter[field]);
    assert.equal(page.data.chapterFull, true);
    assert.ok(page.data.chapterParas.some(p => p.text === '雨停了。'));
    assert.equal(page.data.chapterVideo.show, true);
    assert.equal(page.data.chapterVideo.playing, false, 'an autoplay request proves no decoded frame');
    assert.equal(page.data.chapterVideoMuted, true, 'F06: chapter video autoplays muted');
    page.finishChapterVideo();
    assert.equal(page.narrationRequests, 1);
    page._afterEndingChapter();
    assert.equal(page.endingCard.list[0].title, '尾声');
  }
});

test('missing, failed and slow ending media all leave text and the ending-card continuation usable', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const mode of ['missing', 'poster-failed', 'video-failed', 'slow']) {
    const { page, requests } = loadPlay();
    if (mode === 'poster-failed') wx.getImageInfo = options => options.fail();
    await deliver(page, requests, { title: '尾声', chapter: mode === 'missing'
      ? { ...chapter, videoUrl: '', videoPosterUrl: '' } : chapter });
    if (mode === 'video-failed') page.finishChapterVideo({ currentTarget: { dataset: { token: page.data.chapterVideo.token } } });
    if (mode === 'slow') t.mock.timers.tick(5000);
    assert.equal(!!page.data.chapterVideo?.show, false, mode);
    assert.ok(page.data.chapterParas.some(p => p.text === '雨停了。'), mode);
    assert.equal(page.narrationRequests, 1, mode);
    page._afterEndingChapter();
    assert.equal(page.endingCard.show, true, mode);
  }
});

test('a late ending response cannot replace a new session or start its media', async () => {
  const { page, requests } = loadPlay();
  page._loadLifeEnding(() => { throw new Error('stale finish'); });
  page._routeState.sessionId = 71;
  requests.at(-1).success({ code: 200, data: { title: '尾声', chapter } });
  await flush();
  assert.equal(page.data.chapter.id, 1);
  assert.equal(!!page.data.chapterVideo?.show, false);
  assert.equal(page.narrationRequests, 0);
});

test('F06: a muted ending video leaves the playing theme music alone; unmuting pauses it, finishing resumes it', async () => {
  const { page, requests } = loadPlay();
  const bgm = { paused: false, plays: 0, play() { this.plays++; this.paused = false; }, pause() { this.paused = true; } };
  page._themeBgm = bgm;
  await deliver(page, requests, { title: '尾声', code: 'done', chapter });
  assert.equal(page.data.chapterVideo.show, true);
  assert.equal(bgm.paused, false);
  page.toggleChapterVideoSound({ currentTarget: { dataset: { token: page.data.chapterVideo.token } } });
  assert.equal(bgm.paused, true);
  page.finishChapterVideo();
  assert.equal(bgm.paused, false);
  assert.equal(page.narrationRequests, 1);
  page._themeBgm = null;
});

test('optional text blocks render as one collapsed 「延伸阅读」 card (who as subtitle); tap toggles; plain text unchanged', () => {
  const { page } = loadPlay();
  page.data.chapter = { id: 5, blocks: [
    { type: 'text', content: '示例站点的正文。' },
    { type: 'text', optional: true, who: '示例站点的来历', content: '第一段。\n第二段。' },
    { type: 'text', optional: true, content: '没有副标题的延伸。' },
    { type: 'text', optional: false, who: '旁人', content: '照常一段。' },
  ] };
  page.openChapterFull();
  const paras = page.data.chapterParas;
  const opts = paras.filter(p => p.kind === 'optional');
  assert.equal(opts.length, 2, 'each optional block is one paragraph');
  assert.deepEqual(opts.map(p => [p.who, p.open, p.lines]), [['示例站点的来历', false, ['第一段。', '第二段。']], ['', false, ['没有副标题的延伸。']]]);
  assert.ok(paras.some(p => p.kind === 'text' && p.text === '示例站点的正文。'));
  assert.ok(paras.some(p => p.kind === 'text' && p.text === '旁人：照常一段。'), 'non-optional blocks unchanged');
  assert.ok(!paras.some(p => p.kind === 'text' && /第一段/.test(p.text)), 'collapsed text is not leaked into the plain flow');
  const i = opts[0].i;
  page.toggleOptionalRead({ currentTarget: { dataset: { i } } });
  assert.equal(page.data.chapterParas[i].open, true);
  page.toggleOptionalRead({ currentTarget: { dataset: { i } } });
  assert.equal(page.data.chapterParas[i].open, false);
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/play/index.wxml'), 'utf8');
  assert.match(wxml, /<view wx:elif="\{\{item\.kind === 'optional'\}\}" class="chfull__opt[^"]*" data-i="\{\{item\.i\}\}" bindtap="toggleOptionalRead"/);
  assert.match(wxml, /<text class="chfull__opt-t">延伸阅读<\/text><text class="chfull__opt-s" wx:if="\{\{item\.who\}\}">\{\{item\.who\}\}<\/text>/);
  assert.match(wxml, /<view wx:if="\{\{item\.open\}\}" class="chfull__opt-b">/, 'body only rendered when expanded (default collapsed)');
});
