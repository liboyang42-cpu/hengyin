'use strict';
const methods = {
  playChapterVideo() {
    const chapter = this.data.chapter || {};
    if (this.data.chapterVideo && (this.data.chapterVideo.show || this.data.chapterVideo.pending)) return true;
    if (!chapter.videoUrl || !chapter.videoPosterUrl) return false;
    const key = String(chapter.id) + '|' + String(chapter.videoUrl).split('?')[0];
    this._seenChapterVideos = this._seenChapterVideos || new Set();
    if (this._seenChapterVideos.has(key)) return false;
    this._seenChapterVideos.add(key);
    const token = this._chapterVideoToken = (this._chapterVideoToken || 0) + 1;
    this.setData({ chapterVideo: { pending: true, show: false, token } });
    const current = () => this.data.chapterVideo && this.data.chapterVideo.pending && this.data.chapterVideo.token === token;
    this._chapterVideoTimer = setTimeout(() => { if (current()) this.finishChapterVideo(); }, 5000);
    // Keep chapter text visible until the poster is cached; never reveal an empty video surface.
    wx.getImageInfo({ src: chapter.videoPosterUrl, success: image => {
      if (!current()) return;
      if (!this._isPlayAccountCurrent() || String((this.data.chapter || {}).id) !== String(chapter.id)) { this.finishChapterVideo(); return; }
      clearTimeout(this._chapterVideoTimer);
      this._videoPausedAudio = [this._themeBgm, this._stationBgm, this._audio].filter(ctx => ctx && ctx.paused === false);
      this._videoPausedAudio.forEach(ctx => { try { ctx.pause(); } catch (_) {} });
      this.setData({ chapterVideo: { show: true, playing: false, token, url: chapter.videoUrl, poster: image.path || chapter.videoPosterUrl } });
      this._chapterVideoTimer = setTimeout(() => this.finishChapterVideo(), 5000);
    }, fail: () => { if (current()) this.finishChapterVideo(); } });
    return true;
  },
  onChapterVideoPlay(e) {
    if (!this._currentChapterVideo(e)) return;
    clearTimeout(this._chapterVideoTimer);
    this._chapterVideoTimer = setTimeout(() => this.finishChapterVideo(), 5000);
  },
  onChapterVideoWaiting(e) {
    if (!this._currentChapterVideo(e)) return;
    clearTimeout(this._chapterVideoTimer);
    this._chapterVideoTimer = setTimeout(() => this.finishChapterVideo(), 5000);
  },
  onChapterVideoTime(e) {
    if (!this._currentChapterVideo(e) || !(Number(e.detail && e.detail.currentTime) > 0)) return;
    if (Number(e.detail.currentTime) >= 30) { this.finishChapterVideo(); return; }
    clearTimeout(this._chapterVideoTimer);
    this._chapterVideoTimer = setTimeout(() => this.finishChapterVideo(), 5000);
    if (!this.data.chapterVideo.playing) this.setData({ 'chapterVideo.playing': true });
  },
  _currentChapterVideo(e) {
    return !!(this.data.chapterVideo && this.data.chapterVideo.show
      && e && String(e.currentTarget.dataset.token) === String(this.data.chapterVideo.token));
  },
  finishChapterVideo(e, resume = true) {
    if (e && !this._currentChapterVideo(e)) return;
    if (!this.data.chapterVideo || (!this.data.chapterVideo.show && !this.data.chapterVideo.pending)) return;
    clearTimeout(this._chapterVideoTimer);
    try { wx.createVideoContext('chapter-video', this).stop(); } catch (_) {}
    const paused = this._videoPausedAudio || [];
    this._videoPausedAudio = [];
    this.setData({ chapterVideo: { show: false } }, () => {
      if (!resume || this._playDisposed || !this._isPlayAccountCurrent()) return;
      paused.forEach(ctx => {
        if ([this._themeBgm, this._stationBgm, this._audio].includes(ctx)) { try { ctx.play(); } catch (_) {} }
      });
      this.playChapterAudio(true);
    });
  }
};
module.exports = { methods };
