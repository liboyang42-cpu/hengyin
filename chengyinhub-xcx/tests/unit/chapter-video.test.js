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
