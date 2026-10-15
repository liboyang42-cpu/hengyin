const communityApi = require('../../../../../utils/community-post-api.js');
const modal = require('../../../../../utils/modal.js');
const { geohash9 } = require('../../../../../utils/roam-geo.js');
// cy-post-compose —— 新建帖文弹窗(内容照 Figma 379:2484,呈现照 334:3563)
//
// ⚠️ 这是【组件】不是页面。2026-09-03 用户定:「新建帖文是弹窗,草稿就是弹窗叠弹窗」。
// 做成独立路由的话新路由下前一页不渲染,压暗底层是假的;挂在广场页上,压暗露出的
// 才是真的列表。顺带三个好处:行内 composer 展开不用跨页传值(同页同数据,不会丢字)、
// 不必再为「自绘顶栏」向标题体系申请例外、面板顶已在胶囊之下所以不用自己算 navRight。
//
// 新建和编辑复用社区客户端；编辑先读取作者权限及完整配置，再按版本保存。
const cyToast = require('../../../../../utils/toast.js');
const app = getApp();
const proEditorDraft = require('../../../../../utils/publish/pro-editor-draft.js');
const { chinaParts } = require('../../../../../utils/datetime.js');
const { resolveMenuChrome } = require('../../../../../utils/nav-safe-area.js');
const { pickLocation } = require('../../../../../utils/location/location-manager.js');

const MAX_LEN = 8000;
const MAX_PICS = 6;

function activityIdOf(row) {
  if (!row) return '';
  const id = row.activityId != null ? row.activityId : (row.id != null ? row.id : row.ownerId);
  return id == null || id === '' ? '' : id;
}

function mergeActivityOptions(joined, hosted) {
  const seen = {};
  const out = [];
  function add(item) {
    if (!item || item.activityId == null || item.activityId === '') return;
    const key = String(item.activityId);
    if (seen[key]) return;
    seen[key] = true;
    out.push(item);
  }
  (hosted || []).forEach(add);
  (joined || []).forEach(add);
  return out;
}

function activitiesFromJoined(data) {
  const rows = Array.isArray(data) ? data : ((data && data.rows) || []);
  const out = [];
  rows.forEach(function (row) {
    if (!row) return;
    if (row.ownerType != null && Number(row.ownerType) !== 2) return;
    const act = row.cmsActivity || {};
    const activityId = activityIdOf(act.id != null ? act : row);
    const name = String(act.name || row.activityTitle || row.name || '').trim();
    if (!activityId || !name) return;
    out.push({ activityId: activityId, name: name, dataType: 1, sourceLabel: '我参加的' });
  });
  return out;
}

function activitiesFromHosted(data) {
  const rows = Array.isArray(data) ? data : ((data && data.rows) || []);
  const out = [];
  rows.forEach(function (row) {
    if (!row) return;
    const activityId = activityIdOf(row);
    const name = String(row.name || row.title || '').trim();
    if (!activityId || !name) return;
    out.push({ activityId: activityId, name: name, dataType: 1, sourceLabel: '我主办的' });
  });
  return out;
}

function placeLine(poi) {
  const name = String((poi && poi.name) || '').trim();
  const addr = String((poi && poi.address) || '').trim();
  if (name && addr && name !== addr) return name + ' · ' + addr;
  return name || addr;
}

// 草稿 key 命名空间:借 pro-editor-draft 的 topicId 槽位,而不是 draftUuid 槽位。
// 为什么必须分开:saveDraft 一旦走 draftUuid 分支,就会顺手写 pro_editor_active_<memberId>
// —— 那是 pages/publish/fabu「继续上次新建」用的活动指针,全账号只有一个。
// 本页占了它,fabu 下次恢复会按这个 uuid 读到本页的信封(章节结构完全不同)= 串稿。
// 走 topicId 槽位则完全不碰活动指针,key 落在
//   pro_editor_draft_topic_square_post_<memberId>
// 与 fabu 的 pro_editor_draft_new_* 天然不同前缀。带 memberId 是防同机换号互相覆盖
// (loadDraft 本身也会按 memberId 判 member_mismatch,这里是第二道)。
// 四个调用点(show 观察器 / persist / readDrafts / onPublish)都先判过 memberId,
// 所以这里不再兜 falsy —— 兜了也永远走不到,只会让人以为「没登录也能存草稿」。
function draftIdentity(memberId) {
  return { topicId: 'square_post_' + String(memberId) };
}

// 面板顶 = 微信胶囊底 + 8px 视觉余量(留白值用户 2026-09-03 定)。
//
// ⚠️ 别用 --cy-comp-sheet-max-height 顶这个位置。它是
//   calc(100vh - env(safe-area-inset-top) - 88rpx - 16rpx),
// 只在「刘海机 + env() 真返回 47pt」时才刚好躲开胶囊。env() 返回 0 的场合
//   —— 开发者工具模拟器、以及安卓(状态栏约 24pt、胶囊落在 28~60pt)——
// 算出来的面板顶是 52pt,**正压在胶囊上**。2026-09-03 真机截图实拍到:
// 顶栏右侧「草稿 / 更多」两枚图标被胶囊盖住。
// 所以这里不推算安全区,直接问胶囊自己的坐标(先例:pages/publish/fabu/topic-detail-sheet.wxml)。
// 拿不到坐标才退回那个 token —— 有总比没有强,但别把它当主路径。
// T4 上层比下层再低 22pt(真源样张 25:6867 y=66 → 25:6869 y=88)。
const T4_STACK_OFFSET_PT = 22;

// 面板顶 = 胶囊底 + 全站统一的那 5px。
// ⚠️ **不要在这里另算一套胶囊**。仓库已有 utils/nav-safe-area.js 的 resolveMenuChrome():
// 它把「拿不到胶囊坐标时怎么兜」「为什么是 5px 不是 12px」都论证过了(用户 2026-08-10 定:
// 再高一点右上角就和微信胶囊挨着,手指一偏点到「关闭小程序」,未保存内容一起没)。
// 本文件第一版自己写了 bottom + 8,既是第三份重复实现,间距也和全站不一致。
// 现在由 sheet-top-safe-area-contract ⑤ 钉死:覆盖 --cy-comp-sheet-max-h 的地方
// 必须走这个共用工具,自己另算一套判红。
function sheetTopStyle(extraPt) {
  var g = app.globalData || {};
  var mb = g.menuButtonInfo;
  if (!mb) { try { mb = wx.getMenuButtonBoundingClientRect(); } catch (e) { mb = null; } }
  var win = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
  var top = resolveMenuChrome(win, mb).sheetTop + (extraPt || 0);
  return '--cy-comp-sheet-max-h: calc(100vh - ' + Math.round(top) + 'px);';
}

Component({
  options: { styleIsolation: 'isolated' },

  properties: {
    show: { type: Boolean, value: false },
    // 行内 composer 里已经写了一半的正文。同页传值,不经 storage ——
    // 经 storage 就会把上一条草稿覆盖掉(草稿只有一个槽位)。
    prefill: { type: String, value: '' },
    // 编辑态传入原帖摘要；保存前回读服务端完整配置，仅修改正文。
    editPost: { type: Object, value: null },
  },

  data: {
    sheetStyle: '',          // 面板顶让开胶囊,见 sheetTopStyle()
    draftsSheetStyle: '',    // T4 上层(草稿)的位置,比本层再低 22pt
    avatar: '',
    nickname: '',
    content: '',
    contentLen: 0,
    canSubmit: false,
    submitting: false,
    // 编辑态(editPost 非空):标题/提交键换文案,草稿与附件工具收起 —— wxml 直接消费它
    editMode: false,
    postAudience: 'PUBLIC',
    draftsShow: false,
    drafts: [],
    picList: [],
    address: '',
    selectedActivity: null,
    activityPickerShow: false,
    activityList: [],
    activityLoading: false,
    activityError: false,
  },

  lifetimes: {
    attached() {
      this._memberId = app.getUserID();
      this._submitting = false;
      this._published = false;
      this._composeEpoch = 0;
      this._composeOpen = !!this.data.show;
      this._detached = false;
      this.setData({
        avatar: app.getAvatar(),
        nickname: app.getNickname(),
        sheetStyle: sheetTopStyle(0),
        // 草稿(T4 上层)比本层再低一档,位置同样由这里算 —— 只有这里拿得到胶囊坐标。
        draftsSheetStyle: sheetTopStyle(T4_STACK_OFFSET_PT),
      });
    },
    // 组件被卸载(页面退出)时也要把没发出去的正文存下来
    detached() {
      this.persist();
      this._detached = true;
      if (this._community) this._community.dispose();
      this._composeEpoch += 1;
    },
  },

  observers: {
    // 每次拉起都重新取一次正文:优先用行内 composer 带过来的半截,
    // 没有才回 storage 捡草稿。关闭时存一次 —— 组件不卸载,所以不能只靠 detached。
    show(open) {
      this._composeEpoch = (this._composeEpoch || 0) + 1;
      this._composeOpen = !!open;
      this._submitting = false;
      this.setData({ submitting: false });
      if (!open) {
        // ⚠️ 草稿层是本组件的**子弹窗**,不能活得比宿主久。
        //    draftsShow 是内部 data,show 置 false 并不会自动收 ——
        //    不收的话新建帖文关掉后草稿面板会孤零零挂在广场页上(2026-09-03 截图看出来的,
        //    单测和全部门禁一条都没盖到,只有真截图看得见)。
        this.setData({ draftsShow: false, activityPickerShow: false });
        this.persist();
        return;
      }
      // 宿主页常驻不卸载:上一篇发布成功的标记只管那一次关闭,新拉起的这篇要照常关闭即存(3-19)。
      this._published = false;
      this._recoverPrevious = false;
      this._restartDiscarded = false;
      this._memberId = app.getUserID();
      this.communityClient();
      const edit = this.data.editPost;
      if (edit && edit.id != null && edit.id !== '') { this.applyEdit(edit); return; }
      this.setData({ editMode: false });
      const carried = String(this.data.prefill || '');
      if (carried.trim()) { this.applyContent(carried); return; }
      if (!this._memberId) return;
      // member_mismatch / revision_conflict 一律当没有草稿:
      // 宁可让用户少恢复一次,也不能把别人的文字塞进他的输入框。
      const res = proEditorDraft.loadDraft(wx, draftIdentity(this._memberId), { memberId: this._memberId });
      const saved = res.status === 'ready' && res.envelope && res.envelope.formData
        ? String(res.envelope.formData.contents || '')
        : '';
      this.setData({ postAudience: res.status === 'ready' && res.envelope && res.envelope.formData && res.envelope.formData.postAudience === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC' });
      this.applyContent(saved);
    },
  },

  methods: {
    communityClient() {
      const owner = String(app.getUserID() || '');
      if (!this._community || this._communityOwner !== owner) {
        if (this._community) this._community.dispose();
        this._community = communityApi.createClient(app, wx);
        this._communityOwner = owner;
      }
      return this._community;
    },
    communityCurrent() {
      const owner = String(app.getUserID() || ''), epoch = this._composeEpoch;
      return () => !this._detached && this._composeOpen && this._composeEpoch === epoch
        && String(this._memberId || '') === owner && String(app.getUserID() || '') === owner;
    },
    loadEditSnapshot() {
      const current = this.communityCurrent(), id = this._editId, initial = this.data.content;
      this._editReady = false;
      const fail = res => { if (current() && Number(res && res.code) !== 499) cyToast(app.getRequestErrorMessage(res, '帖文读取失败，请重试')); };
      this.communityClient().detail({ postId: id, current, autoErrorToast: false,
        success: res => {
          if (res.code !== 200) { fail(res); return; }
          const bundle = res.data;
          if (String(bundle.post.id) !== String(id) || bundle.viewerIsAuthor !== true
            || String(bundle.post.authorId) !== String(app.getUserID() || '')
            || !['DRAFT','CHECKING','PUBLISHED','LIMITED','HIDDEN'].includes(bundle.post.lifecycle)) {
            fail({ msg: '当前帖文不可编辑' }); return;
          }
          this._editBundle = bundle; this._editReady = true;
          this.setData({ postAudience: bundle.post.audience });
          if (this.data.content === initial) this.applyContent(bundle.post.body || '');
        }, fail
      });
    },

    // 关闭即存。已发布成功的不再存(草稿在 onPublish 里已删);编辑态不写草稿槽位
    // ——草稿是「新建」的存档,拿编辑中的正文去盖掉它 = 静默丢用户没发出去的那篇。
    persist() {
      if (this._published || this.data.editMode) return;
      const content = String(this.data.content || '').trim();
      if (!content || !this._memberId) return;
      try {
        proEditorDraft.saveDraft(wx, {
          memberId: this._memberId,
          topicId: draftIdentity(this._memberId).topicId,
          formData: { contents: this.data.content, postAudience: this.data.postAudience },
        });
      } catch (e) {
        // 存草稿失败(storage 满/被禁)不该在退出路径上再弹一层打断,静默放过。
      }
    },

    // 正文单一入口:输入、草稿恢复、发布后清空都走它,长度/可提交态不会分叉。
    applyContent(value) {
      const text = String(value == null ? '' : value).slice(0, MAX_LEN);
      this.setData({
        content: text,
        contentLen: text.length,
        canSubmit: text.trim().length > 0,
      });
    },

    // 编辑先显示摘要，再读取作者完整配置；共享客户端保留所有未修改关系。
    applyEdit(edit) {
      this._editId = String(edit.id);
      this._lng = '';
      this._lat = '';
      this.setData({
        editMode: true,
        draftsShow: false,
        activityPickerShow: false,
        picList: [],
        address: '',
        selectedActivity: null,
      });
      this.applyContent(edit.contents || '');
      this._retryEditSnapshot = null;
      this.loadEditSnapshot();
    },

    onInput(e) {
      this.applyContent(((e || {}).detail || {}).value || '');
    },

    // 关闭的所有权在广场页(show 是它给的),这里只上报。
    onCancel() {
      this.triggerEvent('close');
    },

    // 只有一份草稿(key 固定,见 draftIdentity),所以列表长度恒为 0 或 1。
    // 不做多份草稿:那要在 pro-editor-draft 之外再维护一张 key 索引表,
    // 而稿子只要求「离页存一份、回来能捡回来」。要多份就是另一张卡。
    readDrafts() {
      const memberId = this._memberId;
      if (!memberId) return [];
      const identity = draftIdentity(memberId);
      const res = proEditorDraft.loadDraft(wx, identity, { memberId: memberId });
      const envelope = res.status === 'ready' ? res.envelope : null;
      const contents = envelope && envelope.formData ? String(envelope.formData.contents || '') : '';
      if (!contents) return [];
      // 稿 379:885 的日期文本是「8月30日」—— 不带时分。本页只有一份草稿,
      // 时分对「是哪一条」毫无区分度,加了只是把这一行挤宽。
      const t = chinaParts(envelope.savedAt);
      return [{
        key: identity.topicId,
        avatar: this.data.avatar,
        nickname: this.data.nickname,
        date: t ? `${t.month}月${t.day}日` : '',
        summary: contents,
      }];
    },

    // 读 storage 是同步的(wx.getStorageSync),不存在「读到一半」,所以不传 loading —— 组件默认 false。
    // ⚠️ 后果要说清:cy-post-drafts 的 Loading 分支【当前不可达】,
    //    连带 --cy-color-skeleton-on-sheet 这个 token 的唯一消费者也永远不渲染。
    //    保留它不是摆设:草稿一旦改成走后端(跨设备,施工文档 §6.2 里"以后再加"的那条),
    //    这里传 loading 就能立刻用上,不必届时再补一套骨架。
    //    ★ 但在那之前,别把「Loading 已实现」读成「Loading 已验证」—— 它没被渲染过。
    onOpenDrafts() {
      this.setData({ drafts: this.readDrafts(), draftsShow: true });
    },

    onCloseDrafts() {
      this.setData({ draftsShow: false });
    },

    // e.detail = { index, key, item }。key 就是我们写进去的那个 topicId,拿它回 storage 重读 ——
    // 不用 item.summary 还原:那个字段按组件契约是「摘要」,拿摘要当正文迟早丢字。
    // ⚠️ cy-post-drafts 的 onSelect 是【先 close 再 select】,所以走到这里面板已经在收了。
    //    这意味着任何一条 return 都长成「面板关了、正文没变、什么都没说」——
    //    用户会以为自己点空了,而这恰恰是草稿读不出来(改过号 / 版本冲突)时最该说话的时刻。
    onPickDraft(e) {
      const key = ((e || {}).detail || {}).key;
      const fail = (msg) => cyToast(msg);
      if (!key || !this._memberId) return fail('草稿已不在了');
      const res = proEditorDraft.loadDraft(wx, { topicId: key }, { memberId: this._memberId });
      if (res.status !== 'ready' || !res.envelope || !res.envelope.formData) {
        // status 可能是 member_mismatch / revision_conflict / 空 —— 对用户是同一件事:这条恢复不了。
        return fail('这条草稿打不开了');
      }
      this.applyContent(res.envelope.formData.contents);
      this.setData({ draftsShow: false, postAudience: res.envelope.formData.postAudience === 'PRIVATE' ? 'PRIVATE' : 'PUBLIC' });
    },

    // 走到这里时删除确认闸已经过完(闸长在 cy-post-drafts 里),页面直接删,别再加一道 modal。
    onDeleteDraft(e) {
      const key = ((e || {}).detail || {}).key;
      const sheet = this.selectComponent && this.selectComponent('#drafts');
      if (!key || !this._memberId) {
        sheet && sheet.deleteFailed('草稿已不在了');
        return;
      }
      // 「回执 ≠ 观测」:removeDraft 返回 true 只说明调用被接受,removeStorageSync 还可能抛。
      // 判成没删,唯一算数的是**回头再读一遍 storage**,看这条 key 还在不在。
      let left;
      try {
        proEditorDraft.removeDraft(wx, { topicId: key }, { memberId: this._memberId });
        left = this.readDrafts();
      } catch (err) {
        sheet && sheet.deleteFailed('删除失败,请重试');
        return;
      }
      this.setData({ drafts: left });
      if (left.some((d) => d.key === key)) {
        sheet && sheet.deleteFailed('删除失败,请重试');   // 删了还在 = 没删掉,别报「已删除」
        return;
      }
      sheet && sheet.deleteDone();
    },

    onPickImage() {
      const that = this;
      const left = MAX_PICS - (that.data.picList || []).length;
      if (left <= 0) {
        cyToast('最多 6 张图片');
        return;
      }
      const current = this.communityCurrent(), client = this.communityClient();
      app.chooseImage(function (res) {
        if (!current()) return;
        const next = (that.data.picList || []).concat(res || []).slice(0, MAX_PICS);
        that.setData({ picList: next });
      }, left, { bizType: 'COMMUNITY_POST', mapResult: data => {
        if (!current()) throw { code: 499, msg: '已取消选择' };
        return client.captureUpload(data);
      } });
    },

    onClearPic(e) {
      const idx = Number(((e || {}).currentTarget || {}).dataset.inx);
      if (!Number.isFinite(idx)) return;
      const arr = (this.data.picList || []).slice();
      arr.splice(idx, 1);
      this.setData({ picList: arr });
    },

    onPickLocation() {
      const current = this.communityCurrent();
      pickLocation({ onPick: poi => {
        if (!current()) return;
        let grid;
        try { grid = geohash9(poi.latitude, poi.longitude); } catch (error) { cyToast(error.message || '地点无效，请重新选择'); return; }
        modal.show({ title: '精确地点仅自己可见', content: '保留精确地点将保存为私密帖文；移除地点也不会自动公开。', confirmText: '仅自己可见', cancelText: '取消',
          success: result => {
            if (!current() || !result.confirm) return;
            this._locationGrid = grid;
            this.setData({ address: placeLine(poi), postAudience: 'PRIVATE' });
          }
        });
      } });
    },

    onClearLocation() {
      this._lng = ''; this._lat = ''; this._locationGrid = null;
      this.setData({ address: '' });
    },

    onOpenActivityPicker() {
      this.setData({ activityPickerShow: true });
      this.loadActivityOptions();
    },

    onCloseActivityPicker() {
      this.setData({ activityPickerShow: false });
    },

    loadActivityOptions() {
      const epoch = (this._actEpoch || 0) + 1;
      this._actEpoch = epoch;
      this.setData({ activityLoading: true, activityError: false });
      const that = this;
      let pending = 2;
      let failed = 0;
      let joined = [];
      let hosted = [];
      const done = function () {
        pending -= 1;
        if (pending > 0 || epoch !== that._actEpoch) return;
        if (failed === 2) {
          that.setData({ activityLoading: false, activityError: true, activityList: [] });
          return;
        }
        that.setData({
          activityLoading: false,
          activityError: false,
          activityList: mergeActivityOptions(joined, hosted),
        });
      };
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/registration/list',
        method: 'POST',
        data: { owner_type: 2, pageNum: 1, pageSize: 50 },
        success(res) {
          if (res && (res.code == 200 || res.code == '200')) joined = activitiesFromJoined(res.data);
          else failed += 1;
          done();
        },
        fail() { failed += 1; done(); },
      });
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/activity/list',
        method: 'POST',
        data: { is_my: 1, pageNum: 1, pageSize: 50 },
        success(res) {
          if (res && (res.code == 200 || res.code == '200')) hosted = activitiesFromHosted(res.data);
          else failed += 1;
          done();
        },
        fail() { failed += 1; done(); },
      });
    },

    onPickActivity(e) {
      const ds = ((e || {}).currentTarget || {}).dataset || {};
      const list = this.data.activityList || [];
      let item = Number.isFinite(Number(ds.index)) ? list[Number(ds.index)] : null;
      if (!item && ds.activityId != null) {
        const id = String(ds.activityId);
        for (let i = 0; i < list.length; i++) {
          if (String(list[i].activityId) === id) { item = list[i]; break; }
        }
      }
      if (!item) return;
      this.setData({ selectedActivity: item, activityPickerShow: false });
    },

    onClearActivity() {
      this.setData({ selectedActivity: null });
    },

    clearAttachments() {
      this._locationGrid = null;
      this._lng = '';
      this._lat = '';
      this.setData({ picList: [], address: '', selectedActivity: null, activityPickerShow: false });
    },

    saveExistingPost(options, content) {
      return this.communityClient().saveEdit(Object.assign({}, options, {
        scope: 'square:compose:edit:' + this._editId, postId: this._editId,
        changes: { body: content }, retryUnknown: this._retryEditSnapshot === content
      }));
    },

    publishNewPost(options, content) {
      const activity = this.data.selectedActivity;
      const form = { body: content, mediaPaths: (this.data.picList || []).slice(), audience: this.data.postAudience || 'PUBLIC' };
      if (activity && activity.activityId) form.references = [{ referenceType: activity.dataType == 2 ? 'TOPIC' : 'ACTIVITY', referenceId: activity.activityId }];
      if (this.data.address) {
        if (!this._locationGrid || form.audience !== 'PRIVATE') throw { msg: '请重新确认地点仅自己可见' };
        Object.assign(form, { locationPrecision: 'EXACT', approximateGeohash: this._locationGrid, poiName: this.data.address, cityCode: null });
      }
      return this.communityClient().publishNew(Object.assign({}, options, { scope: 'square:compose', form }));
    },

    onPublish() {
      if (String(this._memberId || '') !== String(app.getUserID() || '')) { cyToast('登录身份已变化，请重新打开'); return; }
      const content = String(this.data.content || '').trim();
      if (!content) { cyToast('请输入内容'); return; }
      if (this._submitting) return;
      const editing = this.data.editMode && this._editId;
      const restarting = !editing && this._restartDiscarded === true;
      if (editing && !this._editReady) { this.loadEditSnapshot(); cyToast('请等待读取完成后保存'); return; }
      this.communityClient();
      const ownerId = String(app.getUserID() || ''), isOpen = this.communityCurrent();
      const sequence = (this._publishSequence || 0) + 1; this._publishSequence = sequence;
      const current = () => isOpen() && this._publishSequence === sequence;
      const snapshot = JSON.stringify([this.data.content, this.data.picList, this.data.address, this.data.postAudience, this.data.selectedActivity]);
      this._submitting = true; this.setData({ submitting: true });
      const fail = res => {
        if (!current() || Number(res && res.code) === 499) return;
        if (!editing && Number(res && res.code) === 409 && res.requiresReupload === true) { cyToast('请移除旧图，重新选图上传'); return; }
        if (!editing && !restarting && Number(res && res.code) === 409 && res.intentDiscarded === true && res.requiresRestart === true) {
          const restartSnapshot = JSON.stringify([this.data.content, this.data.picList, this.data.address, this.data.postAudience, this.data.selectedActivity]);
          modal.show({ title: '保留当前内容作为新帖', content: '上次提交已被删除。是否保留当前内容，作为一条新帖重新提交？', confirmText: '作为新帖', cancelText: '暂不提交',
            success: result => {
              if (!current() || !result.confirm) return;
              if (restartSnapshot !== JSON.stringify([this.data.content, this.data.picList, this.data.address, this.data.postAudience, this.data.selectedActivity])) { cyToast('内容已变化，请重新确认'); return; }
              this._restartDiscarded = true; this.onPublish();
            }
          });
          return;
        }
        if (res && res.requiresRecovery === true) {
          modal.show({ title: '先核对上次提交', content: '上次提交结果未确认。先核对上次提交，本次新修改会保留，不会被当作已经提交。', confirmText: '核对上次', cancelText: '暂不核对',
            success: result => {
              if (!current() || !result.confirm) return;
              this._recoverPrevious = true; this.onPublish();
            }
          });
          return;
        }
        if (res && res.retrySameWire === true) this._retryEditSnapshot = content;
        cyToast(app.getRequestErrorMessage(res, '结果未确认，内容已保留'));
      };
      const complete = () => { if (current()) { this._submitting = false; this.setData({ submitting: false }); } };
      const options = { current, autoErrorToast: false, recoverPrevious: this._recoverPrevious === true, restartDiscarded: restarting,
        selectGuideline: (document, task) => new Promise((resolve, reject) => modal.show({ title: '社区发布规范',
          content: document.rules.map(rule => rule.title + (rule.content ? '：' + rule.content : '')).join('\n'),
          confirmText: '同意并提交', cancelText: '取消',
          success: result => current() && !task.aborted ? resolve(result.confirm === true) : reject({ code: 499, msg: '已取消提交' }), fail: reject })),
        success: res => {
          if (!current()) return;
          if (res.code !== 200) { fail(res); return; }
          const post = res.data.post;
          const keepOpen = res.recoveredPrevious === true || snapshot !== JSON.stringify([this.data.content, this.data.picList, this.data.address, this.data.postAudience, this.data.selectedActivity]);
          const event = { id: post.id, version: post.version, lifecycle: post.lifecycle, audience: post.audience, keepOpen, ownerId };
          this._retryEditSnapshot = null;
          if (keepOpen) {
            cyToast(res.recoveredPrevious === true ? '旧提交已确认，新修改尚未保存' : '已保存，新修改尚未保存'); this.triggerEvent('published', event); return;
          }
          if (!editing && this._memberId) proEditorDraft.removeDraft(wx, draftIdentity(this._memberId), { memberId: this._memberId });
          this._published = true; this.applyContent(''); this.clearAttachments();
          this.triggerEvent('published', event); this.triggerEvent('close');
        }, fail, complete
      };
      this._recoverPrevious = false;
      this._restartDiscarded = false;
      try {
        if (editing) this.saveExistingPost(options, content);
        else this.publishNewPost(options, content);
      } catch (error) { fail(error); complete(); }
    },
  },
});
