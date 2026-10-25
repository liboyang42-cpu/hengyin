// 官方地点挑战(地图点位卡「开始挑战」进来)。不另画玩法界面,整页复用游玩页已有的三件:
//   ① cy-playkit-qa mode=shot —— 到场拍照(节点要求拍照时才有这一步)
//   ② cy-playkit-qa mode=pick —— 观察答题,对错由服务端 /complete 判,正确答案不下发
//   ③ cy-medal-screen —— v13 通关勋章页;已经拿过的再完成一次只提示「收藏过」,不重复发
// 一次只出现一屏,每屏左上角都能退出;没有叠在别的弹层上的弹层。
// 拍照 + 答案 + 定位在最后一次 POST /api/city/nodes/{id}/complete 一起交,与据点详情同一条接口。
const app = getApp();
const cyToast = require('../../../utils/toast.js');
const geo = require('../../../utils/geo.js');
const loading = require('../../../utils/loading.js');

const KEYS = ['A', 'B', 'C', 'D'];
/* 服务端在写入前拒绝的那几句(与据点详情 PREWRITE_REJECTIONS 同源):这些回来就是「这次没过」,可以再答 */
const REJECTIONS = ['离据点太远,请到店后再完成', '答案不正确', '请上传拍照打卡照片', '照片地址不合法,请重新上传',
  '照片未上传成功或不属于当前账号，请重新拍摄上传', '照片校验暂不可用，请稍后重试', '据点未开放或已下线', '节点未上线', '缺少定位'];
const BADGE_WALL = '/subpackageP3/pages/badge-wall/index/index';

function positiveId(v) { const n = Number(v); return Number.isInteger(n) && n > 0 ? n : null; }

Page({
  data: {
    step: 'loading',          // loading | photo | quiz | done | error
    errorText: '',
    node: null,
    options: [],
    medal: { items: [], title: '', sub: '' },
    backTop: 48,
    privacyGateShow: false,
  },

  onLoad(q) {
    this._poiId = positiveId(q && q.poiId);
    this._photoUrl = (q && q.photo) ? decodeURIComponent(q.photo) : ''; // 地图上取景卡拍的那张,已上传
    let backTop = 48;
    try {
      const menu = wx.getMenuButtonBoundingClientRect && wx.getMenuButtonBoundingClientRect();
      if (menu && menu.top) backTop = Math.round(menu.top);
    } catch (e) { /* 用默认 */ }
    this.setData({ backTop });
    this._load();
  },
  onUnload() { this._gone = true; clearTimeout(this._nextTimer); },

  _load() {
    if (!this._poiId) { this.setData({ step: 'error', errorText: '没有找到这个地点' }); return; }
    this.setData({ step: 'loading' });
    app.sendRequest({
      url: '/api/city/nodes/' + this._poiId, method: 'GET', hideLoading: true, silentError: true,
      success: (res) => {
        if (this._gone) return;
        const n = res && res.code == 200 && res.data;
        if (!n || positiveId(n.poiId == null ? n.id : n.poiId) !== this._poiId) { this.setData({ step: 'error', errorText: (res && res.msg) || '地点暂时打不开' }); return; }
        const options = KEYS.map((k) => ({ id: k, t: n['question' + k] })).filter((o) => o.t);
        this.setData({ node: n, options });
        if (n.completed) { this._finish({ alreadyClaimed: true, badgeName: '' }); return; }
        if (n.status !== 1) { this.setData({ step: 'error', errorText: '这个地点的挑战暂未开放' }); return; }
        const quiz = n.validationMethod === 3 && options.length > 0;
        if (n.photoRequired && !this._photoUrl) { this.setData({ step: 'photo' }); return; } // 没从地图拍:这里补拍
        if (quiz) { this.setData({ step: 'quiz' }); return; }
        if (this._photoUrl) { // 只拍照的地点:照片已有,直接交
          this._submit('', (ok, msg) => { if (ok) this._finish(this._result || {}); else this.setData({ step: 'error', errorText: msg }); });
          return;
        }
        this.setData({ step: 'error', errorText: '这个地点还没有配置挑战' });
      },
      fail: () => { if (!this._gone) this.setData({ step: 'error', errorText: '网络异常，请重试' }); },
      successStatusAbnormal: (res) => { if (!this._gone) this.setData({ step: 'error', errorText: (res && res.msg) || '地点暂时打不开' }); },
    });
  },
  retry() { this._load(); },
  showPrivacyGate() { this.setData({ privacyGateShow: true }); },
  onPrivacyGateSettled() { this.setData({ privacyGateShow: false }); },

  /* ① 拍照:照片先上传拿地址,拿到才算这一步过;只拍照的地点(核验方式 2)拍完直接交 */
  onShoot(e) {
    const d = (e && e.detail) || {};
    if (!d.tempFilePath || this._uploading) return;
    this._uploading = true;
    loading.show('正在上传');
    app.getUploadClient().uploadAll([d.tempFilePath], {
      fileSizes: [d.size],
      onDone: (r) => {
        this._uploading = false;
        loading.hide();
        if (this._gone) return;
        const url = r && r.ok && r.results && r.results[0];
        if (!url) { cyToast((r && r.failures && r.failures[0] && r.failures[0].msg) || '照片没有上传成功，请重拍'); return; }
        this._photoUrl = url;
        const kit = this.selectComponent('#photoKit');
        if (this.data.node.validationMethod === 3) { if (kit) kit.settle(true, { feedback: '照片已保存，接下来答一道题' }); return; }
        this._submit('', (ok, msg) => { if (kit) kit.settle(ok, { feedback: ok ? '' : msg }); });
      },
    });
  },
  onPhotoContinue() {
    if (this.data.node.validationMethod === 3) this.setData({ step: 'quiz' });
    else this._finish(this._result || {});
  },

  /* ② 答题:点一个选项就交,服务端判对错 */
  onPick(e) {
    const d = (e && e.detail) || {};
    const kit = this.selectComponent('#quizKit');
    this._submit(d.optionId, (ok, msg) => { if (kit) kit.settle(ok, { index: d.index, feedback: ok ? '' : (msg === '答案不正确' ? '' : msg) }); });
  },
  onQuizContinue() { this._finish(this._result || {}); },
  /* 判定屏(对勾)停一下再进下一屏,和游玩页节奏一致;失败屏由玩家点退出 */
  onPhotoVerdict(e) { if (e && e.detail && e.detail.kind === 'win') this._later(() => this.onPhotoContinue()); },
  onQuizVerdict(e) { if (e && e.detail && e.detail.kind === 'win') this._later(() => this.onQuizContinue()); },
  _later(fn) { clearTimeout(this._nextTimer); this._nextTimer = setTimeout(() => { if (!this._gone) fn(); }, 1400); },

  _submit(answer, done) {
    if (this._submitting) return;
    this._submitting = true;
    const end = (ok, msg) => { this._submitting = false; if (!this._gone) done(ok, msg); };
    wx.getLocation({
      type: 'gcj02',
      success: (loc) => {
        const n = this.data.node;
        const far = geo.haversine(loc.latitude, loc.longitude, Number(n.lat), Number(n.lng)) > Number(n.radiusM || 80) + 30;
        if (far) { end(false, '离这里还有点远，走到地点附近再交'); return; }
        app.sendRequest({
          url: '/api/city/nodes/' + this._poiId + '/complete', method: 'POST', hideLoading: true, silentError: true,
          data: { lat: loc.latitude, lng: loc.longitude, answer: answer || '', photoUrl: this._photoUrl || '', code: '' },
          success: (res) => {
            if (res && res.code == 200 && res.data && typeof res.data.needRedeem === 'boolean') { this._result = res.data; end(true); return; }
            const msg = (res && res.msg) || '';
            if (REJECTIONS.indexOf(msg) >= 0) { end(false, msg); return; }
            this._submitting = false; cyToast('结果还没确认，请稍后重试');
          },
          fail: () => { this._submitting = false; cyToast('网络异常，请重试'); },
          successStatusAbnormal: (res) => {
            const msg = (res && res.msg) || '';
            if (REJECTIONS.indexOf(msg) >= 0) { end(false, msg); return; }
            this._submitting = false; cyToast(msg || '结果还没确认，请稍后重试');
          },
        });
      },
      fail: () => { end(false, '需要定位才能确认你到了这里'); },
    });
  },

  /* ③ 勋章页:拿到的是哪枚就写哪枚;再完成一次不重复发 */
  _finish(r) {
    const n = this.data.node || {};
    const again = !!r.alreadyClaimed;
    const name = r.badgeName || n.templateTitle || n.name || '地点勋章';
    this.setData({
      step: 'done',
      medal: {
        items: [{ key: 'poi', name, sub: n.name || '', img: '', mic: 'key' }],
        title: again ? '这枚勋章已经收藏过' : '地点挑战完成',
        sub: again ? '再完成一次不会重复发放' : '勋章已放进徽章墙',
      },
    });
  },
  onMedalPrimary() { // 投一张换一张:原来的城市签玩法
    const place = (this.data.node && this.data.node.name) || '这一站';
    wx.redirectTo({ url: '/subpackageRoam/citystamp/index?kind=sign&place=' + encodeURIComponent(place) });
  },
  onMedalSecondary() { wx.redirectTo({ url: BADGE_WALL }); },
  exit() { wx.navigateBack({ fail: () => wx.redirectTo({ url: '/subpackageRoam/nearby/index' }) }); },
});
