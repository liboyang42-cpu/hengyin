const app = getApp();
const subscribe = require('../../../../utils/subscribe.js');
const toast = require('../../../../utils/toast.js');
const { createVerificationWorkflow } = require('../../../../utils/verification-readback.js');
const { parseInviteId } = require('../../../../utils/coop-invite-view.js');
const inviteRequest = require('../../../../utils/coop-invite-request.js');

function cards(rows, imageField) {
  return rows.filter(t => t && t.id && typeof t.name === 'string').map((t, index) => ({
    id: t.id, name: t.name, number: String(index + 1).padStart(2, '0'), tone: index % 3,
    cover: t[imageField] ? app.getImgUrl(String(t[imageField]).split(/[;,]/)[0]) : '',
  }));
}
function ymd(date) {
  const pad = n => String(n).padStart(2, '0');
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
}
// 拍板 2026-10-22:邀约只能挑还在卖的主题 —— 已结束(endDate 早于今天)或已下架/未过审的不出现在列表里。
// 字段缺省(旧回包没带)按可用处理,不凭空吞掉主题。
function isInvitableTopic(t, today) {
  if (!t) return false;
  if (t.status != null && Number(t.status) !== 1) return false;
  if (t.userStatus != null && Number(t.userStatus) !== 1) return false;
  const end = t.endDate ? String(t.endDate).slice(0, 10) : '';
  return !end || end >= today;
}
Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    show: { type: Boolean, value: false }, targetId: { type: null, value: null },
    targetName: { type: String, value: '' }, inviteType: { type: Number, value: 0 },
    topicId: { type: null, value: null }, topicName: { type: String, value: '' },
    chapterId: { type: null, value: null }, operationScope: { type: String, value: '' },
  },
  data: { topics: [], chapters: [], selectedId: null, selectedChapterId: null, selectedTopicName: '',
    message: '', loading: false, chaptersLoading: false, submitting: false, error: '', unknown: false,
    receiptRows: [], receiptLoading: false, receiptError: '', receiptHasMore: false },
  observers: {
    show(show) {
      this._epoch = (this._epoch || 0) + 1;
      this.stopRecovery();
      if (!show) return;
      this._identity = app.getUserID();
      this._entry = JSON.stringify([this.data.targetId, this.data.inviteType, this.data.operationScope, this.data.topicId, this.data.chapterId]);
      this._page = 0;
      this._sent = false;
      this._hasMore = false;
      this.setData({ loading: false, chaptersLoading: false, submitting: false, topics: [], chapters: [],
        selectedId: this.data.topicId, selectedChapterId: null, selectedTopicName: this.data.topicName,
        message: '', error: '', unknown: false, receiptRows: [], receiptLoading: false, receiptError: '', receiptHasMore: false });
      if (this.data.topicId) this.loadChapters(); else this.loadTopics();
    },
  },
  lifetimes: { detached() { this._epoch = (this._epoch || 0) + 1; this.stopRecovery(); } },
  methods: {
    current(epoch) { return epoch === this._epoch && app.getUserID() === this._identity
      && this._entry === JSON.stringify([this.data.targetId, this.data.inviteType, this.data.operationScope, this.data.topicId, this.data.chapterId]); },
    stopRecovery() {
      if (this._workflow) this._workflow.destroy();
      if (this._reader) this._reader.destroy();
      this._workflow = this._reader = this._resumable = null;
      this._confirmed = {};
    },
    workflow() {
      if (!this._workflow) this._workflow = createVerificationWorkflow({ storage: wx,
        getOwner: () => String(app.getUserID() || ''), requestIdentity: true });
      return this._workflow;
    },
    requestPayload() { return { inviteType: 1, topicId: this.data.selectedId, toType: 'club',
      toId: this.data.targetId, scope: this.data.operationScope }; },
    requestKey() { return inviteRequest.writeKey(String(app.getUserID() || ''), this.requestPayload()); },
    readback() {
      if (!this._reader) {
        const epoch = this._epoch, payload = this.requestPayload(), authorization = app.getAuthorization && app.getAuthorization();
        const key = this.requestKey();
        this._reader = inviteRequest.createReadback({
          current: () => this.current(epoch) && key === this.requestKey() && authorization === (app.getAuthorization && app.getAuthorization()),
          requestExact: request => app.sendRequest({
            url: '/api/coop/invite/request', method: 'GET', data: request.data,
            hideLoading: true, autoErrorToast: false, success: request.success,
            fail: request.fail, successStatusAbnormal: request.successStatusAbnormal,
          }),
          requestList: request => app.sendRequest({
            url: '/api/coop/invite/requests', method: 'GET', data: request.data,
            hideLoading: true, autoErrorToast: false, success: request.success,
            fail: request.fail, successStatusAbnormal: request.successStatusAbnormal,
          }),
          change: patch => this.setData({
            receiptLoading: patch.receiptLoading === undefined ? this.data.receiptLoading : patch.receiptLoading,
            receiptError: patch.receiptError === undefined ? this.data.receiptError : patch.receiptError,
            receiptRows: patch.receiptRows === undefined ? this.data.receiptRows : patch.receiptRows,
            receiptHasMore: patch.receiptHasMore === undefined ? this.data.receiptHasMore : patch.receiptHasMore,
          }),
          received: row => {
            if (!inviteRequest.matches(row, payload)) return this.settleOriginal(row, key);
            let pending;
            try { pending = this.workflow().pendingRequest(key); }
            catch (error) { return '本机待办无法读取，原结果仍待确认'; }
            if (pending && (pending.legacy || pending.requestId !== row.requestId || !this.workflow().confirmRequest(key, row.requestId))) return '本机原请求仍待确认，请勿重复发送';
            this._confirmed[key] = true;
            this._sent = true;
            this.setData({ unknown: false, error: '原请求已创建邀请 #' + row.inviteId + '，等待对方确认' });
          },
        });
      }
      return this._reader;
    },
    // 精确回读本机原请求号：NOT_FOUND ⇒ 允许沿用同一请求号再发（服务端按请求号去重）；
    // UNAVAILABLE ⇒ 原请求已受理（回执存在）但当前账号不可查看结果，展示后清除本机待办。
    settleOriginal(row, key) {
      if (row.state !== 'NOT_FOUND' && row.state !== 'UNAVAILABLE') return;
      let pending;
      try { pending = this.workflow().pendingRequest(key); } catch (error) { return; }
      if (!pending || pending.legacy || pending.requestId !== row.requestId) return;
      if (row.state === 'NOT_FOUND') {
        this._resumable = { key, requestId: row.requestId };
        this.setData({ unknown: false, error: '未查到原请求，可再次发送；沿用原请求号，不会重复创建' });
        return;
      }
      if (!this.workflow().confirmRequest(key, row.requestId)) return '本机原请求仍待确认，请勿重复发送';
      this._confirmed[key] = true;
      this._sent = true;
      this.setData({ unknown: false, error: '原请求已受理，但当前账号无法查看结果，请到邀约记录核对' });
    },
    queryOriginalInvite() {
      if (this.data.submitting || !this.current(this._epoch) || this.data.inviteType !== 1) return;
      try {
        const pending = this.workflow().pendingRequest(this.requestKey());
        if (!pending || pending.legacy) { this.setData({ receiptError: '旧待确认记录没有原请求号，请保留记录人工核对，勿重复发送' }); return; }
        this.readback().exact(pending.requestId);
      } catch (error) { this.setData({ receiptError: '本机待办无法读取，请勿重复发送' }); }
    },
    discoverOriginalInvites() {
      if (!this.data.submitting && this.current(this._epoch) && this.data.inviteType === 1) this.readback().list();
    },
    moreOriginalInvites() { if (!this.data.submitting && this.data.inviteType === 1) this.readback().more(); },
    loadTopics() {
      if (this.data.loading) return;
      const epoch = this._epoch, page = this._page + 1;
      this.setData({ loading: true, error: '' });
      app.sendRequest({
        url: '/api/topic/list', method: 'POST', hideLoading: true, autoErrorToast: false,
        data: { is_my: 1, scope: this.data.operationScope, pageNum: page, pageSize: 20 },
        success: res => {
          if (!this.current(epoch)) return;
          if (res && res.code == 200 && res.data && Array.isArray(res.data.rows)) {
            const seen = new Set(this.data.topics.map(t => String(t.id)));
            const today = ymd(new Date());
            const live = res.data.rows.filter(t => isInvitableTopic(t, today));
            const added = cards(live, 'imgUrl').filter(t => !seen.has(String(t.id)));
            this._page = page;
            this._hasMore = page * 20 < Number(res.data.total || 0);
            this.setData({ topics: this.data.topics.concat(added), loading: false });
            // 整页都被过滤掉时接着翻下一页,别让「还有更多」藏在一页过期主题后面
            if (!added.length && this._hasMore) this.loadTopics();
          } else this.setData({ loading: false, error: (res && res.msg) || '主题加载失败，请重试' });
        },
        fail: () => { if (this.current(epoch)) this.setData({ loading: false, error: '主题加载失败，请重试' }); },
      });
    },
    moreTopics() { if (this._hasMore) this.loadTopics(); },
    pickTopic(e) {
      const topic = this.data.topics.find(t => String(t.id) === String(e.currentTarget.dataset.id));
      if (!topic || this.data.submitting || String(topic.id) === String(this.data.selectedId)) return;
      if (this._reader) this._reader.destroy();
      this._reader = null; this._sent = false;
      this.setData({ unknown: false, receiptRows: [], receiptHasMore: false, receiptError: '', receiptLoading: false, selectedId: topic.id, selectedTopicName: topic.name, selectedChapterId: null, chapters: [], error: '' });
      this.loadChapters();
    },
    loadChapters() {
      const topicId = this.data.selectedId;
      if (!topicId) return;
      const epoch = this._epoch, request = this._chapterRequest = (this._chapterRequest || 0) + 1;
      const current = () => this.current(epoch) && request === this._chapterRequest;
      this.setData({ chaptersLoading: true, selectedChapterId: null, chapters: [], error: '' });
      app.sendRequest({
        url: '/api/topic/invite-chapters', method: 'POST', hideLoading: true, autoErrorToast: false,
        data: { id: topicId, scope: this.data.operationScope },
        success: res => {
          if (!current()) return;
          if (res && res.code == 200 && res.data && Array.isArray(res.data.chapters)) {
            const chapters = cards(res.data.chapters, 'imgArr');
            const fixed = this.data.chapterId && chapters.find(c => String(c.id) === String(this.data.chapterId));
            this.setData({ chapters, chaptersLoading: false, selectedChapterId: fixed ? fixed.id : null,
              error: this.data.chapterId && !fixed ? '当前章节不可用，请返回导览台刷新' : '' });
          } else this.setData({ chaptersLoading: false, error: (res && res.msg) || '章节加载失败，请重试' });
        },
        fail: () => { if (current()) this.setData({ chaptersLoading: false, error: '章节加载失败，请重试' }); },
      });
    },
    pickChapter(e) {
      const chapter = this.data.chapters.find(c => String(c.id) === String(e.currentTarget.dataset.id));
      if (chapter && !this.data.submitting && !this.data.chapterId) this.setData({ selectedChapterId: chapter.id, error: '' });
    },
    onMessage(e) { this.setData({ message: e.detail.value }); },
    retry() { if (this.data.selectedId) this.loadChapters(); else this.loadTopics(); },
    close() { if (!this.data.submitting) this.triggerEvent('close'); },
    send() {
      if (this.data.submitting || this._sent || this.data.chaptersLoading) return;
      if (!this.current(this._epoch)) { this.setData({ error: '账号已切换，请关闭后重新发起邀约' }); return; }
      const d = this.data, chapter = d.chapters.find(c => String(c.id) === String(d.selectedChapterId));
      if (!inviteRequest.context(this.requestPayload()).topicId || !inviteRequest.context(this.requestPayload()).toId || !chapter || !parseInviteId(chapter.id)) { this.setData({ error: '请先选择主题和章节' }); return; }
      // 俱乐部仍带完整主题，章节仅写入邀约展示，不作为权限或合同范围。
      const message = '「' + d.selectedTopicName + ' · ' + chapter.name + '」' + (d.message.trim() || '你好，想邀请你们一起参与这次城市探索。');
      const club = d.inviteType === 1;
      const payload = club
        ? { inviteType: 1, toType: 'club', toId: d.targetId, topicId: d.selectedId, message }
        : { chapterId: chapter.id, merchantMemberId: d.targetId, message };
      if (d.operationScope) payload.scope = d.operationScope;
      const epoch = this._epoch;
      if (club && this._confirmed[this.requestKey()]) return;
      this.setData({ submitting: true, error: '' });
      subscribe.request(['coopStatus']);
      if (club) {
        const key = this.requestKey(), frozen = Object.assign({}, payload);
        const current = () => this.current(epoch) && key === this.requestKey();
        const resume = this._resumable && this._resumable.key === key ? this._resumable : null;
        this._resumable = null;
        const start = (done, identity) => {
          const body = JSON.stringify(Object.assign({}, frozen, { requestId: identity.requestId }));
          return app.sendRequest({ url: '/api/coop/invite', method: 'POST',
            header: { 'Content-Type': 'application/json' }, data: body, autoErrorToast: false,
            success: res => done(current() ? inviteRequest.classify(res, 200, identity.requestId) : { status: 'unknown' }),
            fail: (res, status) => done(current() && status ? inviteRequest.classify(res, status, identity.requestId) : { status: 'unknown' }),
            successStatusAbnormal: (res, status) => done(current() ? inviteRequest.classify(res, status, identity.requestId) : { status: 'unknown' }),
          });
        };
        const settle = result => {
          if (!current()) return;
          if (result.status === 'success') {
            this._sent = true; this.setData({ submitting: false, unknown: false });
            toast('邀约已发送');
            this.triggerEvent('sent', { topicId: frozen.topicId, chapterId: chapter.id, inviteType: 1 });
          } else this.setData({ submitting: false, unknown: result.status === 'unknown',
            error: result.status === 'unknown' ? '发送结果待确认，请查询原请求，勿重复发送'
              : result.status === 'blocked' ? '本次未提交，请检查本机待办、存储和登录后主动再试'
                : result.response && result.response.msg || '邀约未发送，请重试' });
        };
        if (!resume) this.workflow().run(key, start, settle);
        else if (!this.workflow().resumeRequest(key, resume.requestId, start, settle)) {
          this.setData({ submitting: false, unknown: true, error: '本次未提交，请重新查询原请求后再试' });
        }
        return;
      }
      app.sendRequest({
        url: '/api/merchant/chapter-application/invite', method: 'POST',
        header: { 'Content-Type': 'application/json' }, data: JSON.stringify(payload), autoErrorToast: false,
        success: res => {
          if (!this.current(epoch)) return;
          if (res && res.code == 200) {
            this._sent = true;
            this.setData({ submitting: false });
            toast('邀约已发送');
            this.triggerEvent('sent', { topicId: d.selectedId, chapterId: chapter.id, inviteType: d.inviteType });
          } else this.setData({ submitting: false, error: (res && res.msg) || '邀约未发送，请重试' });
        },
        fail: () => { if (this.current(epoch)) this.setData({ submitting: false, error: '发送结果未确认，请先查看邀约记录，避免重复发送' }); },
      });
    },
  },
});
