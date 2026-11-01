// 主办工作台的叠层(2026-10-31 业主批准 P-6):邀约俱乐部 / 俱乐部主页 / 这一站的档位 / 附近商家 / 邀约承接。
// 都叠在合作俱乐部或招商抽屉上,不跳出主题;页面(merchantinfo)是唯一状态源,project-host 只渲染。
// 写操作全部走现成接口,规则在后端:
//   邀约俱乐部 → POST /api/coop/invite(幂等 requestId + 本机待办,与 pages/coop/invite 同一套 verification workflow)
//   改档位     → POST /api/topic/chapter/supply-terms(锁内校验归属;有商家接了 / 已开售拒)
//   邀约承接   → POST /api/merchant/chapter-application/invite(商家只能接受或拒绝,档位按这一站)
// 依赖与 getApp 都在用到时才取:本模块随工作台页一起加载,加载期不碰运行时(单测沙箱只给页面自己的白名单)。
const deps = () => ({
  createVerificationWorkflow: require('../../../utils/verification-readback.js').createVerificationWorkflow,
  inviteRequest: require('../../../utils/coop-invite-request.js'),
  memberId: require('../../../utils/session/login-reload.js').memberId,
});
const CLOSED = { show: false };
const TIER = { PERK: ['权益档', 'perk'], TRAFFIC: ['引流档', 'traffic'], REVSHARE: ['计酬档', 'revshare'] };
const JSON_HEADER = { 'Content-Type': 'application/json' };

function okRes(res) { return !!res && (res.code == 200 || res.code == '200'); }
function money(v) { const n = Number(v); return Number.isFinite(n) && n > 0 ? (n % 1 === 0 ? String(n) : n.toFixed(2)) : ''; }

/** 这一站的档与条件,一句话:权益档写门槛与家数,计酬档写俱乐部付的每人次上限,引流档写到店打卡。 */
function stationTermsText(row) {
  const cap = Number(row.maxMerchant) > 0 ? '最多 ' + Number(row.maxMerchant) + ' 家' : '不限家数';
  if (row.termsMode === 'TRAFFIC') return '到店打卡 · ' + cap;
  if (row.termsMode === 'REVSHARE') return '俱乐部付 ≤ ¥' + (money(row.maxPerHeadFee) || '—') + ' / 人次 · ' + cap;
  return (money(row.perkMinValue) ? '≥ ¥' + money(row.perkMinValue) + ' 权益' : '权益') + ' · ' + cap;
}

/** 招商第三版的站点行:已承接(有生效供给 ⇒ 锁档)/ 未开放(没开商家承接)/ 可招商 */
function decorateStations(rows) {
  if (!Array.isArray(rows)) return [];
  return rows.filter((r) => r && r.chapterId).map((r) => {
    const tier = TIER[r.termsMode] || TIER.PERK;
    return {
      chapterId: r.chapterId,
      name: r.name || '未命名站点',
      termsMode: TIER[r.termsMode] ? r.termsMode : 'PERK',
      perkMinValue: r.perkMinValue, maxPerHeadFee: r.maxPerHeadFee,
      maxMerchant: Number(r.maxMerchant) || 0,
      tierLabel: tier[0], tierKey: tier[1],
      termsText: stationTermsText(r),
      takerName: Number(r.liveOffers) > 0 ? (r.takerName || '已有商家承接') : '',
      state: Number(r.liveOffers) > 0 ? 'taken' : (r.recruitEnabled ? 'open' : 'off'),
    };
  });
}

/** 已邀约一行的三格状态:已发出 → 已读 → 已接受。只画在等回复的行;已合作 / 拒绝 / 撤回由标签说清(原型同)。 */
function inviteTrack(status, read) {
  if (status !== 0) return null;
  const at = read ? 2 : 1;
  return ['已发出', '已读', '已接受'].map((label, i) => ({ label, cls: i < at ? 'is-done' : (i === at ? 'is-cur' : '') }));
}

const data = {
  recruitStations: [],
  layers: { clubInvite: CLOSED, clubHome: CLOSED, tier: CLOSED, shops: CLOSED, shopInvite: CLOSED },
};

const methods = {
  closeLayer(e) {
    const key = ((e && e.currentTarget && e.currentTarget.dataset) || {}).layer;
    if (!key || !this.data.layers[key]) return;
    if ((key === 'clubInvite' || key === 'shopInvite') && this.data.layers[key].sending) return;
    this.setData({ ['layers.' + key]: CLOSED });
  },

  // 合作俱乐部抽屉 / 主页叠层里的「邀约」:叠一层邀约表单
  inviteClubRow(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const row = ((this.data.clubCandidates || {}).rows || []).find((c) => String(c.id) === String(ds.id)) || {};
    this._openClubInvite({ id: ds.id, name: ds.name || row.name, logo: row.logo }, 2);
  },

  inviteFromClubHome() {
    const home = this.data.layers.clubHome || {};
    this._openClubInvite({ id: home.clubId, name: home.name, logo: home.logo }, 3);
  },

  _openClubInvite(club, depth) {
    if (!club.id) return;
    this.setData({ 'layers.clubInvite': {
      show: true, depth, clubId: club.id, name: club.name || '俱乐部', logo: club.logo || '',
      initial: String(club.name || '?').slice(0, 1), shareMode: 2, fee: '', message: '', sending: false, error: '',
    } });
  },

  setClubInviteMode(e) {
    const mode = Number(e.currentTarget.dataset.mode) === 2 ? 2 : 0;
    this.setData({ 'layers.clubInvite.shareMode': mode, 'layers.clubInvite.error': '' });
  },
  onClubInviteFee(e) { this.setData({ 'layers.clubInvite.fee': e.currentTarget.dataset.value, 'layers.clubInvite.error': '' }); },
  onClubInviteMessage(e) { this.setData({ 'layers.clubInvite.message': e.currentTarget.dataset.value }); },

  _clubInviteWorkflow() {
    const { createVerificationWorkflow, memberId } = deps();
    if (!this._clubInviteWriteWorkflow) this._clubInviteWriteWorkflow = createVerificationWorkflow({
      storage: wx, getOwner: () => memberId(getApp()), requestIdentity: true,
    });
    return this._clubInviteWriteWorkflow;
  },

  sendClubInvite() {
    const form = this.data.layers.clubInvite || {};
    if (!form.show || form.sending) return;
    const topicId = this.data.topicId || (this.data.info && this.data.info.topicId);
    if (form.shareMode === 2 && !(Number(form.fee) > 0)) {
      this.setData({ 'layers.clubInvite.error': '每核销 1 位玩家的金额要大于 0' });
      return;
    }
    const payload = { inviteType: 1, toType: 'club', toId: String(form.clubId), topicId: String(topicId),
      shareMode: form.shareMode, message: form.message || '邀请贵俱乐部来参加活动' };
    if (this.data.operationScope) payload.scope = this.data.operationScope;
    if (form.shareMode === 2) payload.fixedFee = form.fee;
    const { inviteRequest, memberId } = deps();
    const key = inviteRequest.writeKey(memberId(getApp()), payload);
    const that = this;
    this.setData({ 'layers.clubInvite.sending': true, 'layers.clubInvite.error': '' });
    const accepted = this._clubInviteWorkflow().run(key, function (done, identity) {
      const classify = (res, status) => inviteRequest.classify(res, status, identity.requestId);
      return getApp().sendRequest({
        url: '/api/coop/invite', method: 'POST', autoErrorToast: false, header: JSON_HEADER,
        data: JSON.stringify(Object.assign({}, payload, { requestId: identity.requestId })),
        success(res) { done(classify(res, 200)); },
        fail(res, status) { done(status ? classify(res, status) : { status: 'unknown' }); },
        successStatusAbnormal(res, status) { done(classify(res, status)); },
      });
    }, function (result) {
      if (result.status === 'success') {
        // 不弹提示:叠层收起,这一行进「已邀约」最上面,三格状态条从「已发出」开始走
        that.setData({ 'layers.clubInvite': CLOSED });
        that.loadProjectHome();
        that.loadClubCandidates();
        return;
      }
      const msg = result.status === 'failed'
        ? ((result.response && result.response.msg) || '邀约没发出去')
        : (result.status === 'blocked' ? '本机还有一条邀约结果待确认，请稍后再试' : '邀约结果待确认，请稍后在「已邀约」里查看，勿重复发送');
      that.setData({ 'layers.clubInvite.sending': false, 'layers.clubInvite.error': msg });
    });
    if (!accepted) this.setData({ 'layers.clubInvite.sending': false, 'layers.clubInvite.error': '本机还有一条邀约结果待确认，请稍后再试' });
  },

  // 俱乐部主页叠层:看完返回仍在合作俱乐部抽屉
  openClubHome(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    if (!ds.id) return;
    const invited = (this.data.clubSheet.items || []).some((c) => String(c.clubId) === String(ds.id));
    this.setData({ 'layers.clubHome': { show: true, depth: 2, clubId: ds.id, state: 'loading', canInvite: !invited,
      name: '', stats: [], tags: [], areas: [], acts: [], members: [] } });
    this._loadClubHome(ds.id);
  },

  reloadClubHome() {
    const id = (this.data.layers.clubHome || {}).clubId;
    if (!id) return;
    this.setData({ 'layers.clubHome.state': 'loading' });
    this._loadClubHome(id);
  },

  _loadClubHome(clubId) {
    const that = this;
    const epoch = this._clubHomeEpoch = (this._clubHomeEpoch || 0) + 1;
    const current = () => epoch === that._clubHomeEpoch && String((that.data.layers.clubHome || {}).clubId) === String(clubId);
    // 四个读都写成字面量路径(U1 可核对);失败走默认错误提示,主页那一块原地给重试
    const req = (res, done) => { if (current()) done(okRes(res) ? res.data : null); };
    const miss = (done) => () => { if (current()) done(null); };
    const onDetail = (club) => {
      if (!club || !club.id) { that.setData({ 'layers.clubHome.state': 'error' }); return; }
      const split = (v) => String(v || '').split(/[,，、\s]+/).map((t) => t.trim()).filter(Boolean);
      that.setData({ 'layers.clubHome': Object.assign({}, that.data.layers.clubHome, {
        state: 'ready', name: club.name || '未命名俱乐部', logo: getApp().getImgUrl ? getApp().getImgUrl(club.logo || '') : (club.logo || ''),
        cover: club.cover ? (getApp().getImgUrl ? getApp().getImgUrl(club.cover) : club.cover) : '',
        initial: String(club.name || '?').slice(0, 1),
        meta: [club.leaderName ? '主理人 ' + club.leaderName : '', club.city || ''].filter(Boolean).join(' · '),
        // 团员评分 / 准时开团后端还没有这两项数据:写「—」,不造 0
        stats: [
          { label: '成员', value: typeof club.memberCount === 'number' ? String(club.memberCount) : '—' },
          { label: '带过的团', value: typeof club.topicCount === 'number' ? String(club.topicCount) : '—' },
          { label: '团员评分', value: '—' },
          { label: '准时开团', value: '—' },
        ],
        tags: split(club.tags || club.activityPrefs).slice(0, 6),
        areas: split(club.city).slice(0, 2),
        bio: club.description || '',
      }) });
      const onTopics = (rows) => {
        if (!Array.isArray(rows)) return;
        that.setData({ 'layers.clubHome.acts': rows.slice(0, 6).map((t) => ({ id: t.id, name: t.name || '未命名主题',
          img: t.imgUrl ? (getApp().getImgUrl ? getApp().getImgUrl(String(t.imgUrl).split(',')[0]) : String(t.imgUrl).split(',')[0]) : '' })) });
      };
      const onPost = (rows) => {
        const p = Array.isArray(rows) && rows[0];
        if (p && (p.content || p.title)) that.setData({ 'layers.clubHome.post': { text: p.content || p.title, time: String(p.createTime || '').slice(5, 16) } });
      };
      const onMembers = (rows) => {
        if (!Array.isArray(rows)) return;
        const total = typeof club.memberCount === 'number' ? club.memberCount : rows.length;
        that.setData({ 'layers.clubHome.members': rows.slice(0, 8).map((m, i) => ({ id: m.memberId || i,
          avatar: m.avatar ? (getApp().getImgUrl ? getApp().getImgUrl(m.avatar) : m.avatar) : '', initial: String(m.nickname || '?').slice(0, 1) })),
        'layers.clubHome.moreMembers': Math.max(0, total - Math.min(8, rows.length)) });
      };
      getApp().sendRequest({ url: '/api/club/topics', hideLoading: true, method: 'POST', header: JSON_HEADER,
        data: JSON.stringify({ id: clubId }), success: (res) => req(res, onTopics), fail: miss(onTopics) });
      getApp().sendRequest({ url: '/api/club/post/list', hideLoading: true, method: 'POST', header: JSON_HEADER,
        data: JSON.stringify({ id: clubId, clubId, limit: 1 }), success: (res) => req(res, onPost), fail: miss(onPost) });
      getApp().sendRequest({ url: '/api/club/members', hideLoading: true, method: 'POST', header: JSON_HEADER,
        data: JSON.stringify({ clubId }), success: (res) => req(res, onMembers), fail: miss(onMembers) });
    };
    getApp().sendRequest({ url: '/api/club/detail', hideLoading: true, method: 'POST', header: JSON_HEADER,
      data: JSON.stringify({ id: clubId }), success: (res) => req(res, onDetail), fail: miss(onDetail) });
  },

  // 这一站的档位:商家主办两档;俱乐部自己发的自由探索多一档计酬(后端 ClubDirectMerchantPolicy 同口径)
  openTierLayer(e) {
    const id = String(((e && e.currentTarget && e.currentTarget.dataset) || {}).id || '');
    const st = (this.data.recruitStations || []).find((s) => String(s.chapterId) === id);
    if (!st) return;
    const club = this.data.hostOwnerType === 'club' && Number((this.data.hostTopic || {}).productType) === 2;
    const options = [{ key: 'PERK', label: '权益档', desc: '商家给玩家一份真实权益（零售价不低于门槛），最强获客；不分票钱。', field: 'perkMinValue', fieldLabel: '门槛' }];
    if (club) options.push({ key: 'REVSHARE', label: '计酬档', desc: '商家认真接待，俱乐部按核销给商家固定人头费（直付，不经平台）。', field: 'maxPerHeadFee', fieldLabel: '每人次上限' });
    options.push({ key: 'TRAFFIC', label: '引流档', desc: '商家接待玩家到店打卡，可以自愿给折扣；零结算。' });
    this.setData({ 'layers.tier': { show: true, chapterId: st.chapterId, name: st.name, club, options,
      mode: options.some((o) => o.key === st.termsMode) ? st.termsMode : 'PERK',
      perkMinValue: money(st.perkMinValue), maxPerHeadFee: money(st.maxPerHeadFee),
      maxMerchant: st.maxMerchant, saving: false, error: '' } });
  },
  setTierMode(e) { this.setData({ 'layers.tier.mode': e.currentTarget.dataset.mode, 'layers.tier.error': '' }); },
  onTierField(e) {
    const field = e.currentTarget.dataset.field;
    if (field !== 'perkMinValue' && field !== 'maxPerHeadFee') return;
    this.setData({ ['layers.tier.' + field]: e.currentTarget.dataset.value, 'layers.tier.error': '' });
  },
  stepTierMax(e) {
    const next = Math.max(0, Math.min(99, (Number(this.data.layers.tier.maxMerchant) || 0) + Number(e.currentTarget.dataset.delta)));
    this.setData({ 'layers.tier.maxMerchant': next });
  },

  saveTier() {
    const t = this.data.layers.tier || {};
    if (!t.show || t.saving) return;
    const body = { chapterId: t.chapterId, termsMode: t.mode, maxMerchant: Number(t.maxMerchant) || 0 };
    if (t.mode === 'PERK' && t.perkMinValue !== '') body.perkMinValue = t.perkMinValue;
    if (t.mode === 'REVSHARE') body.maxPerHeadFee = t.maxPerHeadFee;
    if (this.data.operationScope) body.scope = this.data.operationScope;
    const that = this;
    this.setData({ 'layers.tier.saving': true, 'layers.tier.error': '' });
    getApp().sendRequest({
      url: '/api/topic/chapter/supply-terms', method: 'POST', hideLoading: true, autoErrorToast: false, data: body,
      success(res) {
        if (!okRes(res)) { that.setData({ 'layers.tier.saving': false, 'layers.tier.error': getApp().getRequestErrorMessage(res, '档位没保存') }); return; }
        that.setData({ 'layers.tier': CLOSED });
        that.loadProjectHome();
      },
      fail(res) { that.setData({ 'layers.tier.saving': false, 'layers.tier.error': getApp().getRequestErrorMessage(res, '档位没保存，检查网络后重试') }); },
    });
  },

  // 附近商家:按这一站的品类可邀约的商家(后端 invitable 已按品类 / 去重 / 已申请过滤)
  openShopsLayer(e) {
    const id = String(((e && e.currentTarget && e.currentTarget.dataset) || {}).id || '');
    const st = (this.data.recruitStations || []).find((s) => String(s.chapterId) === id);
    if (!st) return;
    this.setData({ 'layers.shops': { show: true, chapterId: st.chapterId, name: st.name, state: 'loading', rows: [] } });
    this._loadShops(st.chapterId);
  },
  reloadShops() {
    const id = (this.data.layers.shops || {}).chapterId;
    if (!id) return;
    this.setData({ 'layers.shops.state': 'loading' });
    this._loadShops(id);
  },
  _loadShops(chapterId) {
    const topicId = Number(this.data.topicId || (this.data.info && this.data.info.topicId));
    const body = { topicId };
    if (this.data.operationScope) body.scope = this.data.operationScope;
    const that = this;
    const epoch = this._shopsEpoch = (this._shopsEpoch || 0) + 1;
    getApp().sendRequest({
      url: '/api/merchant/chapter-application/invitable', method: 'POST', hideLoading: true,
      header: JSON_HEADER, data: JSON.stringify(body),
      success(res) {
        if (epoch !== that._shopsEpoch) return;
        if (!okRes(res) || !Array.isArray(res.data)) { that.setData({ 'layers.shops.state': 'error' }); return; }
        const rows = res.data.filter((r) => r && String(r.chapterId) === String(chapterId) && r.memberId && r.replacementOfOfferId == null)
          .map((r) => ({ memberId: r.memberId, name: r.name || '未命名商家', initial: String(r.name || '?').slice(0, 1),
            logo: r.logo ? (getApp().getImgUrl ? getApp().getImgUrl(r.logo) : r.logo) : '',
            sub: [r.address, r.distance != null ? (r.distance >= 1000 ? (r.distance / 1000).toFixed(1) + ' km' : Math.round(r.distance) + ' m') : ''].filter(Boolean).join(' · ') }));
        that.setData({ 'layers.shops.state': 'ready', 'layers.shops.rows': rows });
      },
      fail() { if (epoch === that._shopsEpoch) that.setData({ 'layers.shops.state': 'error' }); },
    });
  },

  openShopInvite(e) {
    const id = String(((e && e.currentTarget && e.currentTarget.dataset) || {}).id || '');
    const shop = ((this.data.layers.shops || {}).rows || []).find((r) => String(r.memberId) === id);
    const st = (this.data.recruitStations || []).find((s) => String(s.chapterId) === String((this.data.layers.shops || {}).chapterId));
    if (!shop || !st) return;
    this.setData({ 'layers.shopInvite': { show: true, memberId: shop.memberId, name: shop.name, logo: shop.logo, initial: shop.initial,
      chapterId: st.chapterId, station: st.name, tierKey: st.tierKey, tierLabel: st.tierLabel, termsText: st.termsText, sending: false, error: '' } });
  },

  sendShopInvite() {
    const inv = this.data.layers.shopInvite || {};
    if (!inv.show || inv.sending) return;
    const payload = { merchantMemberId: inv.memberId, chapterId: inv.chapterId };
    if (this.data.operationScope) payload.scope = this.data.operationScope;
    const that = this;
    this.setData({ 'layers.shopInvite.sending': true, 'layers.shopInvite.error': '' });
    getApp().sendRequest({
      url: '/api/merchant/chapter-application/invite', method: 'POST', hideLoading: true,
      header: JSON_HEADER, data: JSON.stringify(payload),
      success(res) {
        if (!okRes(res)) { that.setData({ 'layers.shopInvite.sending': false, 'layers.shopInvite.error': getApp().getRequestErrorMessage(res, '邀约没发出去') }); return; }
        // 两层一起收起回到招商:邀约进「章节承接申请」里,状态在那里看
        that.setData({ 'layers.shopInvite': CLOSED, 'layers.shops': CLOSED });
        that.loadOwnerChapterApplications(true);
      },
      fail(res) { that.setData({ 'layers.shopInvite.sending': false, 'layers.shopInvite.error': getApp().getRequestErrorMessage(res, '邀约没发出去，检查网络后重试') }); },
    });
  },
};

module.exports = { data, methods, decorateStations, inviteTrack, stationTermsText };
