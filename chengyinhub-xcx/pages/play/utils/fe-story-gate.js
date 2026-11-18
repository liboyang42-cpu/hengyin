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

const PURPOSE_TITLE = '用一次位置确认你到了这里';

function methods() {
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
