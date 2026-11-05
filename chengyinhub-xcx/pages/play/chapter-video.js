'use strict';
const methods = {
  _chapterVideoSessionScope() {
    return JSON.stringify([this._playOwner, this.data.topicId, this.data.activityId, (this._routeState || {}).sessionId]);
  },
  _chapterVideoScope() {
    const chapter = this.data.chapter || {};
    return this._chapterVideoSessionScope() + '|' + JSON.stringify([chapter.id, String(chapter.videoUrl || '').split('?')[0]]);
  },
  _isChapterVideoCurrent(token) {
    return !this._playDisposed && this._isPlayAccountCurrent()
      && this.data.chapterVideo && this.data.chapterVideo.token === token
      && this._chapterVideoScopeKey === this._chapterVideoScope();
  },
  _armChapterVideoTimeout(token) {
    clearTimeout(this._chapterVideoTimer);
    this._chapterVideoTimer = setTimeout(() => {
      if (this.data.chapterVideo && this.data.chapterVideo.token === token) this.finishChapterVideo();
    }, 5000);
  },
  playChapterVideo(reopened = false) {
    const chapter = this.data.chapter || {};
    if (this.data.chapterVideo && (this.data.chapterVideo.show || this.data.chapterVideo.pending)) {
      if (this._isChapterVideoCurrent(this.data.chapterVideo.token)) return true;
      const sameSession = this._chapterVideoSessionKey === this._chapterVideoSessionScope();
      const paused = this._videoPausedAudio || [];
      this.finishChapterVideo(null, false);
      if (sameSession && !this._playDisposed && this._isPlayAccountCurrent()) paused.forEach(ctx => {
        if ([this._themeBgm, this._stationBgm].includes(ctx)) { try { ctx.play(); } catch (_) {} }
      });
    }
    if (this._playDisposed || !this._isPlayAccountCurrent()) return false;
    if (!chapter.videoUrl || !chapter.videoPosterUrl) return false;
    const key = this._chapterVideoScope();
    this._seenChapterVideos = this._seenChapterVideos || new Set();
    if (reopened) this._seenChapterVideos.delete(key);
    if (this._seenChapterVideos.has(key)) return false;
    this._seenChapterVideos.add(key);
    const token = this._chapterVideoToken = (this._chapterVideoToken || 0) + 1;
    this._chapterVideoScopeKey = key;
    this._chapterVideoSessionKey = this._chapterVideoSessionScope();
    this._chapterVideoTime = 0;
    this.setData({ chapterVideo: { pending: true, show: false, token } });
    const current = () => this.data.chapterVideo && this.data.chapterVideo.pending && this.data.chapterVideo.token === token;
    this._armChapterVideoTimeout(token);
    // Keep chapter text visible until the poster is cached; never reveal an empty video surface.
    wx.getImageInfo({ src: chapter.videoPosterUrl, success: image => {
      if (!current()) return;
      if (!this._isChapterVideoCurrent(token)) { this.finishChapterVideo(null, false); return; }
      clearTimeout(this._chapterVideoTimer);
      this._videoPausedAudio = [this._themeBgm, this._stationBgm, this._audio].filter(ctx => ctx && ctx.paused === false);
      this._videoPausedAudio.forEach(ctx => { try { ctx.pause(); } catch (_) {} });
      this.setData({ chapterVideo: { show: true, playing: false, token, url: chapter.videoUrl, poster: image.path || chapter.videoPosterUrl } });
      this._armChapterVideoTimeout(token);
    }, fail: () => { if (current()) this.finishChapterVideo(); } });
    return true;
  },
  onChapterVideoPlay(e) {
    if (!this._currentChapterVideo(e)) return;
    // Only advancing frames extend the deadline; repeated play/waiting events are not progress.
  },
  onChapterVideoWaiting(e) {
    if (!this._currentChapterVideo(e)) return;
  },
  onChapterVideoTime(e) {
    if (!this._currentChapterVideo(e) || !(Number(e.detail && e.detail.currentTime) > 0)) return;
    if (Number(e.detail.currentTime) >= 30) { this.finishChapterVideo(); return; }
    if (Number(e.detail.currentTime) <= this._chapterVideoTime) return;
    this._chapterVideoTime = Number(e.detail.currentTime);
    this._armChapterVideoTimeout(this.data.chapterVideo.token);
    if (!this.data.chapterVideo.playing) this.setData({ 'chapterVideo.playing': true });
  },
  _currentChapterVideo(e) {
    return !!(this.data.chapterVideo && this.data.chapterVideo.show
      && this._isChapterVideoCurrent(this.data.chapterVideo.token)
      && e && e.currentTarget && e.currentTarget.dataset
      && String(e.currentTarget.dataset.token) === String(this.data.chapterVideo.token));
  },
  finishChapterVideo(e, resume = true) {
    if (e && !this._currentChapterVideo(e)) return;
    if (!this.data.chapterVideo || (!this.data.chapterVideo.show && !this.data.chapterVideo.pending)) return;
    const token = this.data.chapterVideo.token;
    const current = this._isChapterVideoCurrent(token);
    clearTimeout(this._chapterVideoTimer);
    try { wx.createVideoContext('chapter-video', this).stop(); } catch (_) {}
    const paused = this._videoPausedAudio || [];
    this._videoPausedAudio = [];
    this.setData({ chapterVideo: { show: false } }, () => {
      if (!resume || !current || this._playDisposed || !this._isPlayAccountCurrent()
          || this._chapterVideoToken !== token || this._chapterVideoScopeKey !== this._chapterVideoScope()) return;
      paused.forEach(ctx => {
        if ([this._themeBgm, this._stationBgm, this._audio].includes(ctx)) { try { ctx.play(); } catch (_) {} }
      });
      this.playChapterAudio(true);
    });
  }
};
module.exports = { methods };
