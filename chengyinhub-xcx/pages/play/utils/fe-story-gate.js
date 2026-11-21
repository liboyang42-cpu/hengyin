'use strict';
// 自由探索(mode 2)「只读已到访地点的章节」:锁卡上的「我已到达 / 扫码解锁」。
//   · 定位只在玩家点按钮时前台取一次,第一次先说明用途;
//   · GPS 走 /api/play/arrive(带坐标与精度),扫码走 /api/play/checkin;到没到、读不读得到正文由服务端定;
//   · 没发出去(intentNotExecuted)可以再点;结果未知只回读 /api/play/nodes 与原命令回执,不自动重发;
//   · 服务端确认之后才翻卡、触感一次、打开该章。城市定向(mode 1)不进这里任何分支。
const cyModal = require('../../../utils/modal.js');
const cyToast = require('../../../utils/toast.js');
const playAccuracy = require('./play-accuracy.js');
const { deniedBySystem } = require('./play-location-once.js');
const visit = require('./fe-story-visit.js');
const feStoryCache = require('./fe-story-cache.js');
const feExpired = require('./fe-story-expired.js');

const PURPOSE_TITLE = '用一次位置确认你到了这里';

function methods(req) {
  const api = {
    _feNode(id) {
      return (this.data.nodes || []).find((n) => n && String(n.nodeId) === String(id)) || null;
    },
    /** 锁卡主按钮(地点格 / 锁卡大卡 / 选章面板打开的锁卡) */
    onVisitTap(e) {
      const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
      const id = ds.id != null ? ds.id : (this.data.hero && this.data.hero.node && this.data.hero.node.nodeId);
      const node = this._feNode(id);
      if (!node || Number(this.data.mode) !== 2 || !this.data.storyGate || node.storyLocked !== true) return;
      if (this.data.isPreview || this.data.isMock || this._visitBusy || !this._isPlayAccountCurrent()) return;
      // 票过期 / 场次结束(FE-STORY 7.4):只读集章册,不再新增到访,也绝不调 /api/play/arrive
      if (this.data.storyExpired || this._playable === false) return;
      if (this.data.storyOffline) { cyToast('联网后才能解锁'); return; }   // 到访必须联网,不排队、不补发
      // 上一次结果未知:只核对,不重发
      if (this._visitUnknown) { this._visitReadback(); return; }
      if (visit.normCheck(node.visitCheck) === 'SCAN') { this._visitScan(node); return; }
      this._visitGps(node);
    },
    _visitGps(node) {
      if (this._visitPurposeShown) { this._visitLocate(node); return; }
      cyModal.show({ title: PURPOSE_TITLE, content: '', confirmText: '确认位置', cancelText: '取消',
        success: (r) => {
          if (!r || !r.confirm || !this._isPlayAccountCurrent()) return;
          this._visitPurposeShown = true;
          this._visitLocate(node);
        } });
    },
    _visitLocate(node) {
      const key = this._sessionKey();
      this._visitBusy = true;
      wx.getLocation({ type: 'gcj02', isHighAccuracy: true,
        success: (loc) => {
          this._visitBusy = false;
          if (!this._isPlayAccountCurrent() || key !== this._sessionKey()) return;
          this._sendVisit(node, '/api/play/arrive', { nodeId: node.nodeId, longitude: loc.longitude, latitude: loc.latitude,
            accuracy: playAccuracy.accuracyOf(loc) });
        },
        fail: (err) => {
          this._visitBusy = false;
          if (!this._isPlayAccountCurrent() || key !== this._sessionKey()) return;
          this._visitNoLocation(node, err);
        } });
    },
    /** 取不到定位:权限被拒就引到设置;都可以改用扫码(地点有码时扫码同样算到访) */
    _visitNoLocation(node, err) {
      const denied = deniedBySystem(err);
      cyModal.show({ title: denied ? '定位没有开启' : '没有取到定位', content: '',
        confirmText: denied ? '去设置' : '重新定位', cancelText: '改用扫码',
        success: (r) => {
          if (!r || !this._isPlayAccountCurrent()) return;
          if (r.confirm) {
            if (!denied) { this._visitLocate(node); return; }
            if (typeof wx.openSetting === 'function') wx.openSetting({ fail() {} });
            return;
          }
          if (r.cancel) this._visitScan(node);
        } });
    },
    _visitScan(node) {
      const key = this._sessionKey();
      wx.scanCode({ onlyFromCamera: true, scanType: ['qrCode'],
        success: (r) => {
          if (!this._isPlayAccountCurrent() || key !== this._sessionKey()) return;
          if (!r || !r.result) { cyToast('没有识别到码'); return; }
          this._sendVisit(node, '/api/play/checkin', { nodeId: node.nodeId, code: r.result, registrationId: this.data.regId || undefined });
        },
        fail: (error) => { if (!/cancel/i.test((error && error.errMsg) || '')) cyToast('扫码失败，请重试'); } });
    },
    _sendVisit(node, url, input) {
      const kind = url === '/api/play/checkin' ? 'checkin' : 'arrive';
      this._visitBusy = true;
      this.setData({ visitBusyId: node.nodeId });
      let sent;
      try { sent = this._sendBasicRouteIntent(url, this._routeWritePayload(kind, node.nodeId, input)); }
      catch (error) { sent = Promise.resolve({ code: 520, httpFail: true, msg: error.message }); }
      return Promise.resolve(sent)
        .catch(() => ({ code: 'fail', netFail: true }))
        .then((r) => this._onVisitResult(node, kind, r));
    },
    _onVisitResult(node, kind, r) {
      this._visitBusy = false;
      this.setData({ visitBusyId: null });
      const o = visit.outcome(r);
      if (o === 'obsolete' || !this._isPlayAccountCurrent()) return;
      if (o === 'ok') { this._resolveRouteAction(kind, node.nodeId); this._visitLanded(node, r.data); return; }
      if (this._handleRouteConflict(r, kind, node.nodeId)) return;
      if (o === 'notSent') {
        // 证明没送达:原意图作废,下一次点击用新的定位重新发
        this._resolveRouteAction(kind, node.nodeId);
        cyToast('网络异常，请重试');
        return;
      }
      if (o === 'unknown') {
        this._visitUnknown = { kind, nodeId: node.nodeId };
        cyToast('结果待确认，正在核对');
        this._visitOpenNodeId = node.nodeId;
        this.loadData();
        return;
      }
      this._resolveRouteAction(kind, node.nodeId);
      const why = visit.rejection(r);
      if (why.kind === 'scan') {
        cyModal.show({ title: why.title, content: '', confirmText: why.retry, cancelText: '取消',
          success: (m) => { if (m && m.confirm && this._isPlayAccountCurrent()) this._visitScan(node); } });
        return;
      }
      if (why.retry) {
        cyModal.show({ title: why.title, content: '', confirmText: why.retry, cancelText: '知道了',
          success: (m) => { if (m && m.confirm && this._isPlayAccountCurrent()) this._visitLocate(node); } });
        return;
      }
      cyToast(why.title);
    },
    /** 结果未知时再点:读原命令回执;仍未知就回读 /nodes,不重发 */
    _visitReadback() {
      const u = this._visitUnknown;
      if (!u || this._visitBusy) return;
      this._visitBusy = true;
      Promise.resolve(this._readBasicRouteIntent(u.kind, u.nodeId)).catch(() => null).then((receipt) => {
        this._visitBusy = false;
        if (!receipt || receipt.obsolete || !this._isPlayAccountCurrent()) return;
        if (receipt.intentTerminal) {
          this._resolveRouteAction(u.kind, u.nodeId);
          this._visitUnknown = null;
          const node = this._feNode(u.nodeId);
          if (visit.outcome(receipt) === 'ok' && node) { this._visitLanded(node, receipt.data); return; }
          cyToast(receipt.msg || '这次没有解锁，可以再试一次');
          return;
        }
        cyToast('结果待确认，稍后再看');
        this._visitOpenNodeId = u.nodeId;
        this.loadData();
      });
    },
    /** 服务端确认到访:先用回包把这张卡翻成章卡,再回读 /nodes 拿到本章正文后打开一次 */
    _visitLanded(node, data) {
      const patch = data || {};
      const nodes = (this.data.nodes || []).map((n) => (String(n.nodeId) === String(node.nodeId)
        ? Object.assign({}, n, { visited: true, storyLocked: false, arrived: patch.arrived != null ? !!patch.arrived : n.arrived,
          visitTime: patch.visitTime || n.visitTime || null })
        : n));
      this._visitOpenNodeId = node.nodeId;
      this.setData({ nodes }, () => {
        this.rebuild();
        this.loadData();
      });
    },
    /** loadData 落定后调用:等的那张卡已解锁就打开该章一次(触感一次);仍锁着就放下,不再自动打开 */
    _consumeVisitOpen() {
      const id = this._visitOpenNodeId;
      if (id == null) return;
      this._visitOpenNodeId = null;
      const node = this._feNode(id);
      if (!node || node.storyLocked === true || Number(this.data.mode) !== 2) return;
      if (this._visitUnknown && String(this._visitUnknown.nodeId) === String(id)) {
        this._resolveRouteAction(this._visitUnknown.kind, id);
        this._visitUnknown = null;
      }
      const tile = (this.data.seatTiles || []).find((t) => String(t.nodeId) === String(id)) || {};
      try { wx.vibrateShort({ type: 'medium' }); } catch (e) {}
      this.setData({ 'hero.node': Object.assign({}, tile, this._heroView(node)) }, () => this.openChapStory());
    },
    _saveFeStoryCache(d) {
      if (this.data.isPreview || this.data.isMock) return;
      feStoryCache.save(this._runSessionScope(), d);
    },
    /** 断网打不开 /nodes 时:有本局缓存就只读显示已解锁章节,锁卡写「联网后才能解锁」。没有缓存返回 false。
     *  expired:已知票过期(402)时,锁卡改写「已过期，不能再解锁」;缓存本身就是过期只读册时同样如此。 */
    _applyFeOfflineCache(expired) {
      if (this.data.isPreview || this.data.isMock) return false;
      const cached = feStoryCache.read(this._runSessionScope());
      if (!cached) return false;
      return this._applyFeStoryBook(cached, { offline: true, expired: expired === true || cached.readOnly === true });
    },
    /** 把 /nodes 同形的集章册(缓存或过期历史)落到页面。只读:不出到访按钮,锁卡只写一句 */
    _applyFeStoryBook(book, opts) {
      const o = opts || {};
      const expired = o.expired === true;
      if (typeof this._noteStationImageContext === 'function') this._noteStationImageContext(book);
      const nodes = book.nodes.map((n, i) => this.normNode(n, i, 2));
      const chapterCards = this.buildChapterCards({ chapters: book.chapters }, this.data.chapter);
      if (typeof this._fillStationPhotos === 'function') this._fillStationPhotos([nodes], { chapter: this.data.chapter, chapterCards, topicCover: book.topicCover || '' });
      if (expired) this._playable = false;
      this.setData({ loading: false, emptyTip: '', emptyKind: '', mode: 2, serverMode: 2, modeLabel: '自由探索',
        topicName: book.topicName || this.data.topicName, topicCover: book.topicCover || '',
        storyGate: true, storyOffline: !expired && o.offline === true, storyExpired: expired,
        storyUnlocked: book.storyUnlocked, storyTotal: book.storyTotal,
        nodes, total: nodes.length, doneCount: nodes.filter((n) => n.done).length, packOpen: true, headIn: true,
        chapterCards }, () => this.rebuild());
      return true;
    },
    /**
     * 票过期(/nodes 回 402 或 playable:false)的自由探索:读 /api/play/history(不带 routeSessionId),
     * 只显示已到访的章节,其余锁定且写「已过期，不能再解锁」。结果也存进 fe-story-cache,断网时同样可读。
     * 返回 Promise<boolean>:false = 不是自由探索只读历史 / 读不到,调用方照旧落「获取通行证」。
     * 这里只读,不调 /api/play/arrive,也不重发任何写。
     */
    _loadFeExpiredHistory(isCurrent) {
      if (this.data.isPreview || this.data.isMock) return Promise.resolve(false);
      const query = feExpired.historyQuery(this.data.activityId, this.data.topicId);
      if (!query || typeof req !== 'function') return Promise.resolve(false);
      const current = typeof isCurrent === 'function' ? isCurrent : () => this._isPlayAccountCurrent();
      return Promise.resolve(req('/api/play/history', 'GET', query, undefined, true)).catch(() => ({ netFail: true })).then((res) => {
        if (!current()) return true;   // 已换局/换号:旧结果作废,也不再落空态
        const ok = res && (res.code == 200 || res.code == '200');
        const scope = this._runSessionScope();
        const book = ok ? feExpired.merge(res.data, feStoryCache.read(scope)) : null;
        if (!book) return !ok && (res && res.netFail) ? this._applyFeOfflineCache(true) : false;
        feStoryCache.save(scope, book);
        return this._applyFeStoryBook(book, { expired: true });
      });
    },
    /** 选章面板里点了锁定的章:打开该章第一个地点的锁卡 */
    _openChapterLockedCard(card) {
      const nodes = (this.data.nodes || []).filter((n) => n && String(n.chapterId) === String(card.id));
      const node = nodes.find((n) => n.storyLocked === true) || nodes[0];
      this.setData({ 'chapterPick.show': false });
      if (!node) return;
      const idx = (this.data.seatTiles || []).findIndex((t) => String(t.nodeId) === String(node.nodeId));
      this.setData({ packOpen: true }, () => this._openHero(node, idx));
    },
  };
  return api;
}

module.exports = { methods, PURPOSE_TITLE };
