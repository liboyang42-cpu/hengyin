const test = require('node:test');
const assert = require('node:assert/strict');
const media = require('../../pages/publish/fabu/theme-media.js');
const { methods } = require('../../pages/play/chapter-video.js');
function page() {
  const audio = { paused: false, play() { this.paused = false; }, pause() { this.paused = true; } };
  global.wx = { createVideoContext: () => ({ stop() {} }), getImageInfo: o => o.success({path:o.src}) };
  return Object.assign({}, methods, {
    data: { chapter: { id: 2, videoUrl: 'clip.mp4', videoPosterUrl: 'poster.jpg' } },
    _themeBgm: audio, narrations: 0, _isPlayAccountCurrent: () => true,
    playChapterAudio() { this.narrations++; },
    setData(patch, cb) { for (const [k, v] of Object.entries(patch)) {
      if (k === 'chapterVideo.playing') this.data.chapterVideo.playing = v; else this.data[k] = v;
    } if (cb) cb(); }
  });
}
test('video picker refuses wrong type, unknown limits, >30s and >10MB before any upload', () => {
  const file = { tempFilePath: 'clip.mp4', thumbTempFilePath: 'p.jpg', duration: 30, size: 10 * 1024 * 1024 };
  assert.equal(media.videoError(file), '');
  assert.match(media.videoError({ ...file, duration: 30.01 }), /30 秒/);
  assert.match(media.videoError({ ...file, size: file.size + 1 }), /10 MB/);
  assert.match(media.videoError({ ...file, duration: NaN }), /30 秒/);
  assert.match(media.videoError({ ...file, tempFilePath: 'clip.mov' }), /mp4/);
});
test('video pauses music; decoded frames replace poster; skip restores music and chapter narration once', () => {
  const p = page();
  assert.equal(p.playChapterVideo(), true);
  assert.equal(p._themeBgm.paused, true);
  const e = { currentTarget: { dataset: { token: p.data.chapterVideo.token } }, detail: { currentTime: 1 } };
  p.onChapterVideoPlay(e);
  assert.equal(p.data.chapterVideo.playing, false, 'play event is not a decoded frame');
  p.onChapterVideoTime(e);
  assert.equal(p.data.chapterVideo.playing, true);
  p.finishChapterVideo(e);
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p._themeBgm.paused, false);
  assert.equal(p.narrations, 1);
  assert.equal(p.playChapterVideo(), false, 'same mounted chapter does not loop');
  assert.equal(p.playChapterVideo(true), true, 'explicitly reopening this chapter replays its clip');
  p.finishChapterVideo();
  const reopened = page();
  assert.equal(reopened.playChapterVideo(), true, 'fresh page replays on reopen');
  reopened.finishChapterVideo();
  p.finishChapterVideo();
});
test('a slow network falls back in five seconds; stale video events cannot close the next chapter', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const p = page(); p.playChapterVideo();
  const token = p.data.chapterVideo.token;
  p.onChapterVideoPlay({currentTarget:{dataset:{token}}});
  t.mock.timers.tick(5000);
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p._themeBgm.paused, false);
  p.data.chapter = { id: 3, videoUrl: 'end.mp4', videoPosterUrl: 'end.jpg' };
  p.playChapterVideo();
  p.finishChapterVideo({ currentTarget: { dataset: { token } } });
  assert.equal(p.data.chapterVideo.show, true);
  p.finishChapterVideo(null, false);
  assert.equal(p._themeBgm.paused, true, 'unload does not start background music');
});
test('missing poster or video leaves chapter text available immediately', () => {
  const p = page(); p.data.chapter.videoPosterUrl = '';
  assert.equal(p.playChapterVideo(), false);
  assert.equal(p._themeBgm.paused, false);
});

test('poster must load before video appears; poster failure returns to text without pausing music', () => {
  const p = page(); let poster;
  wx.getImageInfo = options => { poster = options; };
  p.playChapterVideo();
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p._themeBgm.paused, false);
  poster.fail();
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p.narrations, 1);
  poster.success({path:'late.jpg'});
  assert.equal(p.data.chapterVideo.show, false);
});

test('repeated waiting/play and duplicate time events cannot extend a stalled video forever', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const p = page(); p.playChapterVideo();
  const e = { currentTarget: { dataset: { token: p.data.chapterVideo.token } }, detail: { currentTime: 1 } };
  p.onChapterVideoTime(e);
  for (let i = 0; i < 4; i++) {
    t.mock.timers.tick(1000);
    p.onChapterVideoWaiting(e); p.onChapterVideoPlay(e); p.onChapterVideoTime(e);
  }
  assert.equal(p.data.chapterVideo.show, true);
  t.mock.timers.tick(1000);
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p.narrations, 1);
});

test('only advancing frames renew the five-second progress deadline', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const p = page(); p.playChapterVideo();
  const e = { currentTarget: { dataset: { token: p.data.chapterVideo.token } }, detail: { currentTime: 1 } };
  t.mock.timers.tick(4000); p.onChapterVideoTime(e);
  t.mock.timers.tick(4000);
  assert.equal(p.data.chapterVideo.show, true);
  t.mock.timers.tick(1000);
  assert.equal(p.data.chapterVideo.show, false);
});

test('a chapter or route-session switch replaces pending video and ignores its late poster/events', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const switchSession of [false, true]) {
    const p = page(); const posters = [];
    wx.getImageInfo = o => posters.push(o);
    p._routeState = { sessionId: 10 }; p.playChapterVideo();
    const old = p.data.chapterVideo.token;
    if (switchSession) p._routeState.sessionId = 11;
    else p.data.chapter = { id: 3, videoUrl: 'ending.mp4', videoPosterUrl: 'ending.jpg' };
    assert.equal(p.playChapterVideo(), true);
    const token = p.data.chapterVideo.token;
    assert.notEqual(token, old);
    posters[0].success({ path: 'old.jpg' });
    assert.equal(p.data.chapterVideo.pending, true);
    posters[1].success({ path: 'new.jpg' });
    p.finishChapterVideo({ currentTarget: { dataset: { token: old } } });
    assert.equal(p.data.chapterVideo.token, token);
    assert.equal(p.data.chapterVideo.poster, 'new.jpg');
    p.finishChapterVideo();
    assert.equal(p.narrations, 1);
  }
});

test('lost account, disposed page or switched session never resumes old audio from timeout', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  for (const invalidate of [p => { p._isPlayAccountCurrent = () => false; },
    p => { p._playDisposed = true; }, p => { p._routeState = { sessionId: 2 }; }]) {
    const p = page(); p._routeState = { sessionId: 1 }; p.playChapterVideo();
    invalidate(p); t.mock.timers.tick(5000);
    assert.equal(p.data.chapterVideo.show, false);
    assert.equal(p._themeBgm.paused, true);
    assert.equal(p.narrations, 0);
  }
});

test('video error/skip is idempotent; async finish callback cannot resume audio in a new chapter', () => {
  const p = page(); p.playChapterVideo();
  const callbacks = [], set = p.setData;
  p.setData = function (patch, cb) { set.call(this, patch); if (cb) callbacks.push(cb); };
  p.finishChapterVideo(); p.finishChapterVideo();
  p.data.chapter = { id: 3, videoUrl: 'ending.mp4', videoPosterUrl: 'ending.jpg' };
  p.playChapterVideo();
  callbacks.forEach(cb => cb());
  assert.equal(p.narrations, 0);
  assert.equal(p._themeBgm.paused, true);
  p.setData = set; p.finishChapterVideo();
});

test('missing poster callback times out without hiding text or starting a stale narration', t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const p = page(); let poster;
  wx.getImageInfo = o => { poster = o; };
  p.playChapterVideo(); t.mock.timers.tick(5000);
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p.narrations, 1);
  poster.success({ path: 'late.jpg' });
  assert.equal(p.data.chapterVideo.show, false);
  assert.equal(p.narrations, 1);
});

test('switching chapters carries background music through the next video without reviving old narration', () => {
  const p = page();
  p._audio = { paused: false, plays: 0, play() { this.plays++; this.paused = false; }, pause() { this.paused = true; } };
  p.playChapterVideo();
  assert.equal(p._audio.paused, true);
  p.data.chapter = { id: 3, videoUrl: 'ending.mp4', videoPosterUrl: 'ending.jpg' };
  p.playChapterVideo();
  assert.equal(p._themeBgm.paused, true);
  assert.equal(p._audio.plays, 0);
  p.finishChapterVideo();
  assert.equal(p._themeBgm.paused, false);
  assert.equal(p._audio.plays, 0);
  assert.equal(p.narrations, 1);
});
