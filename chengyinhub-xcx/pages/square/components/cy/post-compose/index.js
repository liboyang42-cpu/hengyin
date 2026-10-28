// cy-post-compose —— 新建帖文弹窗(内容照 Figma 379:2484,呈现照 334:3563)
//
// ⚠️ 这是【组件】不是页面。2026-09-03 用户定:「新建帖文是弹窗,草稿就是弹窗叠弹窗」。
// 做成独立路由的话新路由下前一页不渲染,压暗底层是假的;挂在广场页上,压暗露出的
// 才是真的列表。顺带三个好处:行内 composer 展开不用跨页传值(同页同数据,不会丢字)、
// 不必再为「自绘顶栏」向标题体系申请例外、面板顶已在胶囊之下所以不用自己算 navRight。
//
// 发布与列表共用canonical adapter：真实规范确认、草稿/编辑与审核状态。
// 不新建编辑页面；现组件继续负责正文、单飞、草稿与失败保留。
const cyToast = require('../../../../../utils/toast.js');
const app = getApp();
const community = require('../../../../../utils/community-post-api.js').createClient(app, wx);
const proEditorDraft = require('../../../utils/publish/pro-editor-draft.js');
const publishIntent = require('../../../../../utils/publish/publish-intent.js');
const { chinaParts } = require('../../../../../utils/datetime.js');
const { resolveMenuChrome } = require('../../../../../utils/nav-safe-area.js');
const { pickLocation } = require('../../../../../utils/location/location-manager.js');

const MAX_LEN = 8000;
const MAX_PICS = 6;
// 2026-10-27 P-20 owner:俱乐部发帖也走这一个全屏发帖弹窗(club-id 非空即俱乐部模式)。
// 俱乐部帖沿用原俱乐部页的上限与接口:最多 9 张图、可以只发图、可带一条游玩记录、管理员可发公告。
const CLUB_MAX_PICS = 9;
// 发帖引用被服务端拒绝时的机器错误码 → 用户文案;未知码回落到服务端 msg(原在 club/detail)。
const CLUB_POST_REF_ERROR_TEXT = {
  CLUB_POST_REF_UNAVAILABLE: '引用的记录暂不可用，已取消引用',
  CLUB_POST_REF_NOT_JOINED: '只能带上你自己参加过的记录，已取消引用',
  CLUB_POST_REF_INVALID: '引用信息有误，已取消引用',
};

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
// 俱乐部模式按团分槽(club_post_<clubId>_<memberId>),不和广场那一份互相覆盖。
function draftIdentity(memberId, clubId) {
  if (clubId != null && clubId !== '') return { topicId: 'club_post_' + String(clubId) + '_' + String(memberId) };
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
  // 2026-10-27 P-20 owner:发帖是全屏弹窗 —— 还原 10-23 统一 80% 档之前的「面板顶 = 胶囊底」,
  // 只对发帖与草稿这两层把 full 档抬到整屏,上限仍由胶囊实测(--cy-comp-sheet-max-h)封顶。
  return '--cy-comp-sheet-max-h: calc(100vh - ' + Math.round(top) + 'px); --cy-comp-sheet-full: 100vh;';
}

Component({
  options: { styleIsolation: 'isolated' },

  properties: {
    show: { type: Boolean, value: false },
    // 行内 composer 里已经写了一半的正文。同页传值,不经 storage ——
    // 经 storage 就会把上一条草稿覆盖掉(草稿只有一个槽位)。
    prefill: { type: String, value: '' },
    // H050 编辑态:传 { id, contents, dataId, dataType } 即改这条帖。
    // 只改正文与关联 —— 图片/地点不提交,后端 R10-06「未提交字段不清空」会原样保留,
    // 所以编辑态不给附件工具(给了也提交不上去,等于骗用户)。
    editPost: { type: Object, value: null },
    // 俱乐部模式(P-20):发到 /api/club/post/create。传 club.id(页面的 clubId 不走 setData)。
    clubId: { type: null, value: '' },
    // 俱乐部模式:可发公告(主理人 / 有内容管理权的管理员)
    canAnnounce: { type: Boolean, value: false },
    // 俱乐部模式:「带上一次游玩记录」由宿主页的选择器选,这里只展示与提交
    postRef: { type: Object, value: null },
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
    asAnnouncement: false,
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
      this._disposed = false;
      this._composeVisible = !!this.data.show;
      this._submitting = false;
      this._published = false;
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
      this._disposed = true;
      this._composeVisible = false;
      this.cancelPublish();
    },
  },

  pageLifetimes: {
    hide() { this._composeVisible = false; this.cancelPublish(); },
    show() { this._composeVisible = !!this.data.show; },
  },

  observers: {
    // 每次拉起都重新取一次正文:优先用行内 composer 带过来的半截,
    // 没有才回 storage 捡草稿。关闭时存一次 —— 组件不卸载,所以不能只靠 detached。
    show(open) {
      if (this._disposed) return;
      this._composeVisible = !!open;
      this.cancelPublish();
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
      const memberId = app.getUserID();
      if (String(this._memberId || '') !== String(memberId || '')) {
        this.persist();
        this._memberId = memberId;
        this._published = false;
        this._composeVisible = false;
        this.setData({ editMode: false });
        this.applyContent('');
        this.clearAttachments();
        this.triggerEvent('close');
        cyToast('登录身份已变化，请重新打开编辑器');
        return;
      }
      this._published = false;
      const edit = this.data.editPost;
      if (edit && edit.id != null && edit.id !== '') { this.applyEdit(edit); return; }
      this.setData({ editMode: false });
      const carried = String(this.data.prefill || '');
      if (carried.trim()) { this.applyContent(carried); return; }
      if (!this._memberId) return;
      // member_mismatch / revision_conflict 一律当没有草稿:
      // 宁可让用户少恢复一次,也不能把别人的文字塞进他的输入框。
      const res = proEditorDraft.loadDraft(wx, draftIdentity(this._memberId, this.data.clubId), { memberId: this._memberId });
      const saved = res.status === 'ready' && res.envelope && res.envelope.formData
        ? String(res.envelope.formData.contents || '')
        : '';
      this.applyContent(saved);
    },
  },

  methods: {
    cancelPublish() {
      const pending = this._publishContext;
      this._publishContext = null;
      this._submitting = false;
      if (pending) {
        if (pending.intent && !pending.settled) {
          publishIntent.settle(wx, 'square:compose', pending.memberId, pending.intent.key, 'unknown');
        }
        if (pending.task && pending.task.abort) pending.task.abort();
      }
      if (!this._disposed && this.data.submitting) this.setData({ submitting: false });
    },

    isCurrentPublish(pending) {
      return this._publishContext === pending && !this._disposed && this._composeVisible
        && String(app.getUserID() || '') === String(pending.memberId || '')
        && !!this.data.editMode === !!pending.editing
        && (!pending.editing || this._editId === pending.editId);
    },

    composeSnapshot() {
      return JSON.stringify([this.data.content, this.data.picList, this.data.address,
        this._lng, this._lat, this.data.selectedActivity]);
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
          topicId: draftIdentity(this._memberId, this.data.clubId).topicId,
          formData: { contents: this.data.content },
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
        canSubmit: this.submittable(text, this.data.picList),
      });
    },

    // 广场帖必须有正文;俱乐部帖沿用原俱乐部页口径「说点什么，或加张图」。
    submittable(text, pics) {
      if (String(text || '').trim().length > 0) return true;
      return !!(this.data.clubId && !this.data.editMode && pics && pics.length);
    },

    // 以编辑态打开:正文与关联来自被编辑的帖子;图片/地点/预览图不参与(后端保留原值)。
    // 关联要留着并重发 —— 后端编辑分支对 data_id 无值即清 0,不重发会把关联丢掉。
    applyEdit(edit) {
      this.cancelPublish();
      this._editId = String(edit.id);
      this._editDataId = edit.dataId == null || edit.dataId === '' ? '' : String(edit.dataId);
      this._editDataType = edit.dataType == null || edit.dataType === '' ? '' : String(edit.dataType);
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
    },

    onInput(e) {
      if (this._disposed || !this._composeVisible || String(app.getUserID() || '') !== String(this._memberId || '')) return;
      this.applyContent(((e || {}).detail || {}).value || '');
    },

    // 关闭的所有权在广场页(show 是它给的),这里只上报。
    onCancel() {
      this._composeVisible = false;
      this.cancelPublish();
      this.triggerEvent('close');
    },

    // 只有一份草稿(key 固定,见 draftIdentity),所以列表长度恒为 0 或 1。
    // 不做多份草稿:那要在 pro-editor-draft 之外再维护一张 key 索引表,
    // 而稿子只要求「离页存一份、回来能捡回来」。要多份就是另一张卡。
    readDrafts() {
      const memberId = this._memberId;
      if (!memberId) return [];
      const identity = draftIdentity(memberId, this.data.clubId);
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
        return fail('这条草稿无法打开了');
      }
      this.applyContent(res.envelope.formData.contents);
      this.setData({ draftsShow: false });
    },

    // 2026-10-23 业主定:删草稿 = 先从列表拿掉,提示条留「撤销」4 秒,到期 / 被顶掉 / 页面卸载才真正删 storage。
    onDeleteDraft(e) {
      const key = ((e || {}).detail || {}).key;
      if (!key || !this._memberId) { cyToast('草稿已不在了'); return; }
      const memberId = this._memberId;
      this.setData({ drafts: (this.data.drafts || []).filter((d) => d.key !== key) });
      cyToast.undo('草稿已删除', {
        onUndo: () => { this.setData({ drafts: this.readDrafts() }); },
        onCommit: () => {
          // 「回执 ≠ 观测」:removeStorageSync 还可能抛,唯一算数的是回头再读一遍 storage。
          try {
            proEditorDraft.removeDraft(wx, { topicId: key }, { memberId });
          } catch (err) {
            this.setData({ drafts: this.readDrafts() });
            cyToast('删除失败，请重试');
            return;
          }
          const left = this.readDrafts();
          if (left.some((d) => d.key === key)) {
            this.setData({ drafts: left });
            cyToast('删除失败，请重试');   // 删了还在 = 没删掉,别报「已删除」
          }
        },
      });
    },

    onPickImage() {
      const that = this;
      const club = !!that.data.clubId;
      const max = club ? CLUB_MAX_PICS : MAX_PICS;
      const left = max - (that.data.picList || []).length;
      if (left <= 0) {
        cyToast('最多 ' + max + ' 张图片');
        return;
      }
      const pick = function (res) {
        const next = (that.data.picList || []).concat(res || []).slice(0, max);
        that.setData({ picList: next, canSubmit: that.submittable(that.data.content, next) });
      };
      // 俱乐部帖沿用原俱乐部页的上传口径(不走社区帖的媒体登记)
      if (club) app.chooseImage(pick, left);
      else app.chooseImage(pick, left, { bizType: 'COMMUNITY_POST', mapResult: community.captureUpload });
    },

    onClearPic(e) {
      const idx = Number(((e || {}).currentTarget || {}).dataset.inx);
      if (!Number.isFinite(idx)) return;
      const arr = (this.data.picList || []).slice();
      arr.splice(idx, 1);
      this.setData({ picList: arr, canSubmit: this.submittable(this.data.content, arr) });
    },

    // 俱乐部模式的「带上一次游玩记录」与公告开关
    onPickClubRef() { this.triggerEvent('refpick'); },
    onClearClubRef() { this.triggerEvent('refclear'); },
    onToggleAnnouncement() {
      if (!this.data.canAnnounce) return;
      this.setData({ asAnnouncement: !this.data.asAnnouncement });
    },

    onPickLocation() {
      const that = this;
      pickLocation({
        onPick(poi) {
          that._lng = poi.longitude;
          that._lat = poi.latitude;
          that.setData({ address: placeLine(poi) });
        }
      });
    },

    onClearLocation() {
      this._lng = '';
      this._lat = '';
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
      this._lng = '';
      this._lat = '';
      this.setData({ picList: [], address: '', selectedActivity: null, activityPickerShow: false, asAnnouncement: false,
        canSubmit: this.submittable(this.data.content, []) });
    },

    onPublish() {
      if (app.ensureLoginFor && !app.ensureLoginFor('post', () => this.onPublish())) return; // 2026-10-24 游客写前登录
      const that = this;
      if (that._disposed || !that._composeVisible) return;
      if (String(app.getUserID() || '') !== String(that._memberId || '')) {
        that.onCancel();
        cyToast('登录身份已变化，请重新打开编辑器');
        return;
      }
      const content = String(that.data.content || '').trim();
      // 与 canSubmit 同一口径。视觉禁用只是 opacity,真正的闸在这里。
      if (!that.submittable(content, that.data.picList)) {
        cyToast(that.data.clubId ? '说点什么，或加张图' : '请输入内容');
        return;
      }
      if (that._submitting) return;
      that._submitting = true;
      that.setData({ submitting: true });
      if (that.data.clubId && !that.data.editMode) { that.publishClubPost(content); return; }

      const activity = that.data.selectedActivity;
      const editing = that.data.editMode && that._editId;
      // 编辑仅表达正文与关联的用户意图；adapter先读本人原帖与version，保留其余未编辑字段。
      const payload = editing
        ? {
            id: that._editId,
            contents: content,
            data_id: that._editDataId || '',
            data_type: that._editDataId ? (that._editDataType || '1') : '',
          }
        : {
            contents: content,
            pics: (that.data.picList || []).join(';'),
            address: that.data.address || '',
            longitude: that._lng || '',
            latitude: that._lat || '',
            data_id: activity && activity.activityId ? activity.activityId : '',
            data_type: activity && activity.activityId ? (activity.dataType || 1) : '',
          };
      // R10-03:稳定发布意图键只服务「新建」;编辑是幂等的 update,不占意图键。
      let intent = null;
      if (!editing) {
        intent = publishIntent.begin(wx, {
          scope: 'square:compose',
          memberId: that._memberId,
          payload: [payload.contents, payload.pics, payload.address, payload.longitude, payload.latitude, payload.data_id, payload.data_type],
        });
        payload.request_id = intent.key;
        if (intent.previousUnknown) cyToast('上次结果未确认，重试不重复发');
      }

      const pending = { memberId: that._memberId, editing: editing, editId: that._editId,
        intent: intent, snapshot: that.composeSnapshot(), settled: false, task: null };
      that._publishContext = pending;
      const task = community.request({
        hideLoading: true,
        silentError: true,
        url: '/api/creativesquare/action',
        intentScope: 'square:compose',
        current: () => that.isCurrentPublish(pending),
        method: 'POST',
        data: payload,
        success: function (res) {
          if (!that.isCurrentPublish(pending)) return;
          if (!res || res.code != '200') {
            if (!editing) {
              if (res && res.intentDiscarded === true) {
                publishIntent.discard(wx, 'square:compose', pending.memberId, intent.key);
              } else {
                publishIntent.settle(wx, 'square:compose', pending.memberId, intent.key, 'rejected');
              }
              pending.settled = true;
            }
            if (!res || Number(res.code) !== 499) {
              cyToast(app.getRequestErrorMessage ? app.getRequestErrorMessage(res, '社区操作失败，请重试') : '社区操作失败，请重试');
            }
            return;
          }
          const keepOpen = res.recoveredPrevious === true || that.composeSnapshot() !== pending.snapshot;
          if (!editing) {
            publishIntent.settle(wx, 'square:compose', pending.memberId, intent.key, 'success');
            pending.settled = true;
            if (!keepOpen && pending.memberId) {
              proEditorDraft.removeDraft(wx, draftIdentity(pending.memberId, that.data.clubId), { memberId: pending.memberId });
            }
          }
          if (res.data && res.data.lifecycle !== 'PUBLISHED') cyToast('已提交审核，审核通过后公开显示');
          that._publishContext = null;
          that._submitting = false;
          that.setData({ submitting: false });
          // 保存回包只清提交时的稿子，继续输入的正文和附件留在当前编辑器。
          that._published = !keepOpen;
          if (keepOpen) {
            that.persist();
            that.triggerEvent('published', { keepOpen: true });
            return;
          }
          that.applyContent('');
          that.clearAttachments();
          that.triggerEvent('published');
          that.triggerEvent('close');
        },
        fail: (err) => {
          if (!that.isCurrentPublish(pending)) return;
          if (!editing) {
            publishIntent.settle(wx, 'square:compose', pending.memberId, intent.key, 'unknown');
            pending.settled = true;
          }
          cyToast(app.getRequestErrorMessage ? app.getRequestErrorMessage(err, '网络异常，请检查后重试') : '网络异常，请检查后重试');
        },
        complete: function () {
          if (!that.isCurrentPublish(pending)) return;
          that._publishContext = null;
          that._submitting = false;
          that.setData({ submitting: false });
        },
      });
      if (that._publishContext === pending) pending.task = task;
    },

    // 俱乐部模式发布(P-20):请求体与原俱乐部页内嵌输入框一致;单飞 / 关闭即存 / 发后删草稿与广场同一套。
    publishClubPost(content) {
      const that = this;
      const ref = that.data.postRef;
      const clubId = that.data.clubId;
      const pending = { memberId: that._memberId, editing: false, editId: that._editId,
        intent: null, snapshot: that.composeSnapshot(), settled: true, task: null };
      that._publishContext = pending;
      const finish = function () {
        that._publishContext = null;
        that._submitting = false;
        that.setData({ submitting: false });
      };
      const task = app.sendRequest({
        url: '/api/club/post/create', method: 'POST',
        data: JSON.stringify({
          clubId: clubId,
          content: content,
          images: (that.data.picList || []).join(','),
          type: that.data.canAnnounce && that.data.asAnnouncement ? 2 : 0,
          // 没选就一个字段都不带 —— 半个引用(只有类型没有 ID)会被服务端挡下
          refType: ref ? ref.refType : null,
          refId: ref ? ref.refId : null,
        }),
        header: { 'Content-Type': 'application/json', 'Authorization': app.getAuthorization() },
        success(res) {
          if (!that.isCurrentPublish(pending)) return;
          finish();
          if (res && res.code == '200') {
            if (pending.memberId) {
              proEditorDraft.removeDraft(wx, draftIdentity(pending.memberId, clubId), { memberId: pending.memberId });
            }
            that._published = true;
            that.applyContent('');
            that.clearAttachments();
            that.triggerEvent('published');
            that.triggerEvent('close');
            return;
          }
          if (res && CLUB_POST_REF_ERROR_TEXT[res.errorCode]) {
            // 正文与图片保留,只撤掉这条引用,用户可以直接再发
            that.triggerEvent('refclear');
            cyToast(CLUB_POST_REF_ERROR_TEXT[res.errorCode]);
            return;
          }
          cyToast((res && res.msg) || '发布失败');
        },
        fail() {
          if (!that.isCurrentPublish(pending)) return;
          finish();
          cyToast('网络异常，请重试');
        },
      });
      if (that._publishContext === pending) pending.task = task;
    },
  },
});
