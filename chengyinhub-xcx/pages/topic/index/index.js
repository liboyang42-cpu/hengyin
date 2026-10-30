const modal = require('../../../utils/modal.js');
const loading = require('../../../utils/loading.js');
const toast = require('../../../utils/toast.js');
const app = getApp();
const { chinaParts } = require('../../../utils/datetime.js');
const themeRecords = require('../../../utils/theme-records.js');
const { isLoginRequired, retryLogin } = require('../../../utils/session/login-reload.js');
function recordsRequest(url, method, data, header) {
  return new Promise(resolve => app.sendRequest({ url, method, data, header, hideLoading: true, autoErrorToast: false,
    success: res => resolve(res || {}), successStatusAbnormal: () => resolve({ code: 'fail' }), fail: () => resolve({ code: 'fail', netFail: true }) }));
}
/* 结果面板终态停留时长;跳转挂在面板 close 上,时长只此一处 */
const RESULT_SHEET_MS = 2000;
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const { createCheckoutWorkflow, isOrderExpiredFailure, isTerminalOrderConflict,
  ORDER_REBUILD_REQUIRED, ORDER_EXPIRED_USER_MESSAGE } = require('../../../utils/checkout/checkout-workflow.js');
const subscribe = require('../../../utils/subscribe.js');
const { createRegistrationPaymentVerifier } = require('../../../utils/checkout/registration-payment-verifier.js');
const { buildTopicStatBar } = require('../utils/topic-detail-facts.js');
const { isRecordList } = require('../../../utils/response-shape.js');
const { haversine, distanceText } = require('../../../utils/geo.js');
const routePreview = require('../../../utils/topic-route-preview.js');
const marketingConsent = require('../../../utils/marketing-consent-entry.js');
const ticketSource = require('../../../utils/ticket-source.js');
const { buildTopicShare, resolveTopicCover } = require('../../../utils/topic-share.js');
const { decodeDisplayText } = require('../../../utils/display-text.js');
const contentReport = require('../../../utils/content-report.js');
const reportSheet = require('../../../utils/community-report-sheet.js');
const mediaPreview = require('../../../utils/media-preview.js');
const motion = require('../utils/topic-motion.js');
const KB_MS = 3600;          // 封面每张停留时长
const DOCK_MS = 620;         // 价格/收藏停靠动画时长(慢-快-慢)

// 只接受真实数值/十进制数字串；先验类型，不能把布尔、数组或空值强转为坐标。
function routeCoordinate(value, limit) { return routePreview.routeCoordinate(value, limit); }
function routePoint(row) { return routePreview.routePoint(row); }
// 只有站点坐标，没有道路距离和实际步行时长；nodeTime 是停留时长，不能代替交通时间。
function walkText(prev, node) {
  const a = routePoint(prev), b = routePoint(node);
  if (!a || !b) return '';
  const meters = haversine(a.latitude, a.longitude, b.latitude, b.longitude);
  if (!Number.isFinite(meters) || meters <= 0) return '';
  return '直线约 ' + distanceText(meters) + '';
}

/* CU-C-65:站点级字段裸插值会把 null 印成字面「null」(WXML 文本插值对 null 就是这么做的,
   与 description 家族同一个毛病)。统一在这里归一成 '',wxml 再各加一道 wx:if。 */
function nodeText(value) {
  if (value === null || value === undefined) return '';
  const out = String(value).trim();
  return out === 'null' ? '' : out;
}

// V-23c 业主 2026-10-29「简介最多20个字 在主题配置那里配置好的」:头图下只放主题配置里的短简介(subtitle),最多 20 字一行。
const HERO_INTRO_MAX = 20;
function heroIntro(subtitle) {
  return Array.from(nodeText(decodeDisplayText(subtitle))).slice(0, HERO_INTRO_MAX).join('');
}

// 2026-10-30:站点和玩法都没图时用这个主题自己的封面,不再落到通用城市占位图
function templateCover(template, node, topicCover) {
  const urls = [node && node.imgUrl, template.imgUrl, template.imgArr, topicCover].map((v) => String(v || '').split(/[,;]/).map((src) => src.trim()).filter(Boolean)[0] || '');
  return urls.map((src) => app.getImgUrl(src)).filter(Boolean)[0] || '/images/route_city_cover.png';
}

function normalizeChaptersList(chaptersList) {
  if (chaptersList == null) return [];
  if (!Array.isArray(chaptersList)) return null;
  const normalized = [];
  for (const chapter of chaptersList) {
    if (!chapter || typeof chapter !== 'object' || Array.isArray(chapter)) return null;
    const nodes = chapter.nodes == null ? [] : chapter.nodes;
    if (!isRecordList(nodes)) return null;
    for (const node of nodes) {
      if (node.registrationMerchantList != null && !isRecordList(node.registrationMerchantList)) return null;
    }
    normalized.push(Object.assign({}, chapter, { nodes: nodes.map((node) => Object.assign({}, node)) }));
  }
  return normalized;
}

function hasTopicDetail(data) {
  const nonEmptyObject = !!data && typeof data === 'object'
    && !Array.isArray(data) && Object.keys(data).length > 0;
  if (!nonEmptyObject) return false;
  if (!String(data.name || '').trim()) return false;
  if (normalizeChaptersList(data.chaptersList) === null) return false;
  return true;
}

// FOLLOWUP-A-1:只有公开可见(审核通过且上架)、且不是本人的主题才给举报;口径同后端 /api/topic/report。
function canReportTopic(info, me) {
  if (!info || Number(info.isOwner) === 1) return false;
  if (me && String(info.memberId || '') === String(me)) return false;
  return Number(info.status) === 1 && Number(info.userStatus) === 1;
}

Page({
  ...themeRecords.methods(recordsRequest, false),
  openThemeRecordCard(e) { return this.openThemeResultCard({ currentTarget: { dataset: e.detail } }); },
  data: {
    ...themeRecords.data,
    // 选场次 / 选票种弹层(稿 11 240:401 · 11a 240:446)
    sessionPicker: { show: false },
    clubSheetShow: false,
    clubSheetItems: [],
    reportReasonShow: false,
    reportReasonItems: [],
    resultSheet: { show: false, kind: 'success', title: '', sub: '', meta: '', pill: '',
      why: '', duration: RESULT_SHEET_MS,
      consentName: '', consentChecked: false, consentState: 'idle', consentError: '' },
    reducedMotion: false,
    // cy-tabs 的数据源。2026-08-05 从手抄组件类名(<view class="cy-tabs cy-tabs--fill
    // cy-tabs--wide">)改成真的用组件 —— 手抄的那份还硬编码了 --cy-tabs-accent:#028707
    // (纯绿 hue 122°，和状态成功色的 161° 不是一档，属页面自选强调色)。
    // 2026-10-22 业主:三个标签 = 详情 / 故事 / 路线(自由探索没有固定路线,第三个叫「地点」)
    topicTabs: [{ key: '0', label: '详情' }, { key: '1', label: '故事' }, { key: '2', label: '路线' }],
    // ===== 2026-10-22 动效与新增块(docs/design/动效界面落地_20261022) =====
    coverImages: [], kbIdx: 0, coverLeaving: false, detailEnter: false, heroIdx: 0, navSolid: false, storyChars: [], routeNote: '', sketchPoints: [], routeReal: false, tabPre: false, buyOpts: [], buySel: 0, sessionPreferName: '',
    publisher: { name: '', avatar: '' }, heroCta: { kind: '', text: '' }, faved: false,
    docked: false, dockCtaStyle: '', dockFavStyle: '',
    merchantCards: [], storyCards: [], storyMeta: '', openChapter: -1, storyDocked: false,
    teams: { rows: [], need: 0 },
    prep: { show: false }, prepShow: false, prepBusy: false,
    progress: { total: 0, done: 0, labels: [] },
    selfPlayValidText: '可玩至购买后 90 天', selfPlayCheckoutShow: false, checkout: { cover: '', validText: '', placeText: '', priceText: '', free: false, profileName: '', profilePhone: '' },
    reveal: { show: false, cover: '', name: '', kindText: '', untilText: '', daysText: '', line: '' },
    id: 0,
    is_join: 0,
    // 自玩通行证购买确认(P1–P6 弹层,替代 wx.showModal);报名信息单独同意必须在建单前落库
    selfPlayConfirmShow: false,
    selfPlayConsentChecked: false,
    selfPlayConfirmLoading: false,
    selfPlayConsentError: '',
    selfPlayConsentHint: '',
    info: {}, // 主题详情数据
    statBar: [],
    loadError: false,
    topicLoaded: false,
    missingTopicId: false,
    // PR10 F-W1-12:未登录(无会话 / 401)读失败给登录引导,其余加载失败仍按 load-failure 留白
    loginRequired: false,
    // 拍板 2026-09-16 #10:发布商家已打烊时,购票/报名入口直接提示「商家暂停营业」
    // (文案与后端 IMerchantBusinessStatusService.MERCHANT_CLOSED_MESSAGE 同源)。
    merchantClosed: false,
    loadErrorTitle: '',
    loadErrorSub: '',

    // ===== 路线地图（橙色 polyline）相关 =====
    routeReady: false,        // 是否有可画的路线
    mapLatitude: 0,           // 地图中心纬度
    mapLongitude: 0,          // 地图中心经度
    routeMarkers: [],         // 节点标记（编号 1/2/3…）
    routePolyline: [],        // 按章分段的道路/节点虚线
    routeSourceText: '',
    routeIncludePoints: [],   // 让地图自动缩放到看全整条线

    statusBarHeight: getApp().globalData.statusBarHeight,
    navBarHeight: getApp().globalData.navBarHeight,

    //tab切换
    activeTab: '0',

    // 评价相关
    voteShow: false,
    starsBox: [1, 1, 1, 1, 1],
    ratingStars: [],
    answer: -1,
    plnr: "",
    canSubmitComment: false,
    uploadImages: [],

    // 原有的滚动相关数据
    screenHeight: 0,
    screen1Height: 0,
    screen2Top: 0,
    currentScreen: 0,
    isScrolling: false,
    startY: 0,
    startTime: 0,

    // 新增：吸顶相关数据
    isTabSticky: false, // 控制标签是否吸顶
    tabSectionTop: 0, // 标签部分距离顶部的位置
    topBarHeight: 0, // 顶部栏高度
    tabHeight: 100, // 标签栏高度
    totalStickyHeight: 0, // 吸顶总高度（状态栏+导航栏+标签栏）
  },

  onLoad(options) {
    const that = this;
    const topicId = Number(options.id);
    const validTopicId = Number.isFinite(topicId) && Number.isInteger(topicId) && topicId > 0;

    that.getSystemInfo();
    that.calculateScreenHeights();

    that.setData({
      is_join: options.is_join != undefined ? options.is_join : 0,
      id: validTopicId ? topicId : 0,
      topicLoaded: false,
    });

    if (!validTopicId) {
      that.setData({
        loadError: true,
        missingTopicId: true,
        loadErrorTitle: '无法打开主题',
        loadErrorSub: '请返回重新进入',
        topicLoaded: false,
      });
      return;
    }

    that.getData()

    that._initWorkflow();
    that._loadSignupProfile();

    // 初始化吸顶相关数据
    setTimeout(() => {
      that.getTopBarActualHeight();
      that.getTabSectionPosition();
      that.getTabHeight();
      
      // 计算吸顶总高度
      const totalHeight = that.data.statusBarHeight + that.data.navBarHeight + that.data.tabHeight;
      that.setData({
        totalStickyHeight: totalHeight
      });
    }, 500);
  },
 
  /**
   * 获取顶部栏实际高度
   */
  getTopBarActualHeight: function() {
    const that = this;
    const query = wx.createSelectorQuery().in(this);
    
    query.select('.topbar').boundingClientRect((rect) => {
      if (rect) {
        that.setData({
          topBarHeight: rect.height
        });
      }
    }).exec();
  },

  /**
   * 获取标签部分的位置信息
   */
  getTabSectionPosition: function() {
    const that = this;
    const query = wx.createSelectorQuery().in(this);
    
    setTimeout(() => {
      query.select('#tab-section').boundingClientRect((rect) => {
        if (rect) {
          wx.createSelectorQuery().in(that).selectViewport().scrollOffset((scrollRes) => {
            const absoluteTop = rect.top + scrollRes.scrollTop;
            that.setData({
              tabSectionTop: absoluteTop
            });
          }).exec();
        }
      }).exec();
    }, 200);
  },

  /**
   * 获取标签栏高度
   */
  getTabHeight: function() {
    const that = this;
    const query = wx.createSelectorQuery().in(this);
    
    query.select('#tab-section .tab2').boundingClientRect((rect) => {
      if (rect) {
        that.setData({
          tabHeight: rect.height
        });
      }
    }).exec();
  },

  /**
   * 监听页面滚动
   * _scrollTop/_scrollTimer 是实例字段不是 data:wxml 从不读它们,
   * 逐帧 setData 等于每帧白做一次逻辑层→渲染层序列化(2026-09-19 性能批3)
   */
  onPageScroll: function(e) {
    const that = this;
    const scrollTop = e.scrollTop;
    this._scrollTop = scrollTop;

    // 根据滚动位置判断当前所在屏幕
    const screen1Height = this.data.screen1Height;
    if (scrollTop < screen1Height * 0.7) { // 70%作为切换阈值
      if (this.data.currentScreen !== 0) {
        this.setData({
          currentScreen: 0
        });
      }
    } else {
      if (this.data.currentScreen !== 1) {
        this.setData({
          currentScreen: 1
        });
      }
    }

    this._checkDock(scrollTop); this._checkNav(scrollTop);

    // 吸顶逻辑
    const { tabSectionTop, topBarHeight, tabHeight } = this.data;

    // 清除之前的计时器
    if (this._scrollTimer) {
      clearTimeout(this._scrollTimer);
    }

    // 防抖处理
    this._scrollTimer = setTimeout(() => {
      const triggerPosition = tabSectionTop - topBarHeight - tabHeight;
      const shouldSticky = scrollTop >= triggerPosition;

      // 只在状态变化时更新
      if (shouldSticky !== that.data.isTabSticky) {
        that.setData({
          isTabSticky: shouldSticky
        });
      }
    }, 10);
  },
  /**
   * 页面显示时
   */
  onShow() {
    const reducedMotion = readReducedMotion();
    if (this.data.reducedMotion !== reducedMotion) this.setData({ reducedMotion }); this._navigating = false; // 返回时兜底清跳转锁
    this._startKenBurns();
    if (this.data.selfPlayCheckoutShow && !this._signupProfile) this._fillCheckoutProfile(); // 从个人资料补全回来
    const that = this;
    
    // 重新获取所有位置信息
    setTimeout(() => {
      that.setData({
        isTabSticky: false,
      });
      
      that.getTopBarActualHeight();
      that.getTabSectionPosition();
      that.getTabHeight();
      that.calculateScreenHeights();
    }, 100);
  },

  /**
   * 页面隐藏时
   */
  onHide() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._stopKenBurns();
    this.setData({
      isTabSticky: false,
    });
  },

  /**
   * 页面卸载时
   */
  onUnload() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._unloaded = true;
    reportSheet.cancel(this);
    this._themeRecordsDisposed = true;
    this._themeRecordsEpoch = (this._themeRecordsEpoch || 0) + 1;
    this._stopKenBurns();
    (this._motionTimers || []).forEach(clearTimeout);
    if (this._workflow) this._workflow.destroy();
    if (this._scrollTimer) {
      clearTimeout(this._scrollTimer);
    }
  },

  // V-22 业主 2026-10-29:主题音频只在游戏里播放,详情页不再放「主题音频讲解」行(播放代码随之删除)

  /**
   * 切换标签页
   */
  switchTab(e) {
    // cy-tabs 通过 detail.key 传当前项;保留 dataset 兼容旧调用点(若还有)
    const index = (e.detail && e.detail.key) !== undefined ? e.detail.key : e.currentTarget.dataset.index;
    this.setData({
      activeTab: index, tabPre: !this.data.reducedMotion // v13:切标签时各块从下方依次浮上来
    }, () => { if (this.data.tabPre) this._later(30, () => this.setData({ tabPre: false })); });
    if (String(index) === '2') this._loadProgress();
    if (String(index) === '1') { this.setData({ openChapter: -1 }); this._later(900, () => { if (String(this.data.activeTab) === '1') this.setData({ openChapter: 0 }); }); } // v13:进故事标签后第一章自己展开
  },

  /* 2026-10-22 业主:点「查看详情与报名」是点按转场,不是往下滑 ——
     封面文字先退场,再瞬时换到第二屏,第二屏头图回落、标题与按钮逐行上浮。 */
  scrollToSecond() {
    if (this.data.isScrolling) return;
    if (this.data.reducedMotion) return this._scrollToSecondPlain();
    this.setData({ isScrolling: true, coverLeaving: true });
    this._later(280, () => {
      const go = () => wx.pageScrollTo({ scrollTop: this.data.screen2Top, duration: 0,
        complete: () => {
          this.setData({ currentScreen: 1, isScrolling: false, detailEnter: true, coverLeaving: false });
          this._later(1100, () => this.setData({ detailEnter: false }));
          this._measureHero();
        } });
      if (this.data.screen2Top <= 0) { this.calculateScreenHeights(); this._later(200, go); } else go();
    });
  },

  _scrollToSecondPlain() {
    if (this.data.isScrolling) return;

    this.setData({
      isScrolling: true
    });

    const scrollToTarget = () => {
      wx.pageScrollTo({
        scrollTop: this.data.screen2Top,
        duration: 500,
        success: () => {
          this.setData({
            currentScreen: 1,
            isScrolling: false
          });
        },
        fail: () => {
          this.setData({
            isScrolling: false
          });
        }
      });
    };

    // 如果还没有获取到第二屏位置，先计算再滚动
    if (this.data.screen2Top <= 0) {
      this.calculateScreenHeights();
      setTimeout(scrollToTarget, 200);
    } else {
      scrollToTarget();
    }
  },
  
  /* ===== 2026-10-22 动效与新增块(docs/design/动效界面落地_20261022) ===== */
  _later(ms, fn) { const t = setTimeout(fn, ms); (this._motionTimers = this._motionTimers || []).push(t); return t; },

  _applyMotionData(data) {
    const merchantCards = motion.buildMerchantCards(data.chaptersList);
    const price = data.selfPlayPrice;
    const priceText = (price === undefined || price === null || price === '') ? '' : '¥' + price;
    this.setData({
      coverImages: motion.buildCoverImages(data), kbIdx: 0,
      publisher: motion.buildPublisher(data),
      heroCta: motion.buildHeroCta(data, { isJoin: this.data.is_join, merchantClosed: data.merchantClosed === true, priceText }),
      merchantCards,
      storyCards: motion.buildStoryCards(data, merchantCards),
      storyMeta: motion.storyMeta(data),
      'topicTabs[2].label': Number(data.productType) === 2 ? '地点' : '路线',
      routeNote: motion.routeNote(data, merchantCards.length), heroIdx: 0, routeReal: false,
      sketchPoints: [].concat(...(data.chaptersList || []).map((c) => (c && c.nodes) || [])).map(routePoint).filter(Boolean).map((p) => ({ lat: p.latitude, lng: p.longitude })),
    });
    this._progressLoaded = false;
    this._startKenBurns();
    this._loadTeams(data);
    this._loadPrep(data.id);
    this._loadFav(data.id);
    this._loadChars(data);
  },

  // 封面轮换:只在停留第一屏时换图,离屏/隐藏/减少动效时不跑
  _startKenBurns() {
    this._stopKenBurns();
    if (this.data.reducedMotion || (this.data.coverImages || []).length < 2) return;
    this._kbTimer = setInterval(() => {
      if (this.data.currentScreen !== 0) return;
      this.setData({ kbIdx: (this.data.kbIdx + 1) % this.data.coverImages.length });
    }, KB_MS);
  },
  _stopKenBurns() { if (this._kbTimer) { clearInterval(this._kbTimer); this._kbTimer = null; } },

  _windowSize() {
    try { const w = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync(); return { w: w.windowWidth || 375, h: w.windowHeight || 667, safeBottom: w.safeArea ? Math.max(0, (w.screenHeight || w.windowHeight) - w.safeArea.bottom) : 0 }; }
    catch (e) { return { w: 375, h: 667, safeBottom: 0 }; }
  },

  _measureHero() {
    if (typeof wx === 'undefined' || typeof wx.createSelectorQuery !== 'function') return;
    try { this._measureHeroNow(); } catch (e) { /* 量不到就不停靠,按钮留在原位 */ }
  },

  _measureHeroNow() {
    if (!this._capsule) { try { this._capsule = wx.getMenuButtonBoundingClientRect(); } catch (e) { this._capsule = null; } }
    // 这里只预取胶囊位置给停靠动画用;
    // 故事扇形卡收拢后的迷你卡叠已移进顶栏(v13),
    // 跟在返回键后面自然排布,
    // 不再按胶囊量位置。
    // (保留同样行数,行号基线不挪)
    const q = wx.createSelectorQuery().in(this);
    q.select('#heroActs').boundingClientRect();
    q.selectViewport().scrollOffset();
    q.exec((r) => {
      const rect = r && r[0], vp = r && r[1];
      if (!rect || !vp) return;
      this._heroTop = rect.top + vp.scrollTop;
      this._heroH = rect.height;
    });
  },

  /* 业主:价格按钮和收藏**被滚出顶栏之后**才离开原位,一起走;回到原位才回来。 */
  _checkDock(scrollTop) {
    if (this._heroTop == null || this._docking) return;
    const nav = (this.data.statusBarHeight || 0) + (this.data.navBarHeight || 44);
    const bottomOnScreen = this._heroTop + this._heroH - scrollTop;
    const want = this.data.docked ? bottomOnScreen < nav + 12 : bottomOnScreen < nav;
    if (want !== this.data.docked) this._animDock(want);
  },

  /* 停靠动画:各自从原位直线飞到停靠位(价格 → 右下角,收藏 → 顶栏胶囊左侧),
     自己的节奏(620ms,慢-快-慢),不跟滚动速度走。 */
  _animDock(on) {
    if (typeof wx.createSelectorQuery !== 'function') return;
    this._docking = true;
    const q = wx.createSelectorQuery().in(this);
    q.select('.hero-cta').boundingClientRect();
    q.select('.hero-fav').boundingClientRect(); q.select('.bm-main').boundingClientRect();
    q.exec((r) => {
      const cta = r && r[0], fav = r && r[1], bar = null; // v13 没有底栏:价格按钮一律停到右下角
      const win = this._windowSize(), cap = this._capsule || { top: 48, left: win.w - 95, height: 32 };
      const box = (x, y, w, h) => 'left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;height:' + h + 'px;';
      const ctaW = cta ? Math.max(cta.width, 150) : 150;
      const ctaAt = cta ? box(cta.left, cta.top, cta.width, cta.height) : '';
      const ctaDock = bar ? box(bar.left, bar.top, bar.width, bar.height) : box(win.w - 16 - ctaW, win.h - 16 - 50 - win.safeBottom, ctaW, 50);
      const favAt = fav ? box(fav.left, fav.top, fav.width, fav.height) : '';
      const favDock = box(cap.left - 8 - cap.height, cap.top, cap.height, cap.height);
      const ease = 'transition:left ' + DOCK_MS + 'ms cubic-bezier(.65,0,.35,1),top ' + DOCK_MS + 'ms cubic-bezier(.65,0,.35,1),width ' + DOCK_MS + 'ms cubic-bezier(.65,0,.35,1),height ' + DOCK_MS + 'ms cubic-bezier(.65,0,.35,1);';
      const still = 'transition:none;';
      if (this.data.reducedMotion) {
        this.setData({ docked: on, dockCtaStyle: ctaDock + still, dockFavStyle: favDock + still });
        this._docking = false;
        return;
      }
      if (on) {
        this.setData({ docked: true, dockCtaStyle: ctaAt + still, dockFavStyle: favAt + still });
        this._later(30, () => this.setData({ dockCtaStyle: ctaDock + ease, dockFavStyle: favDock + ease }));
        this._later(DOCK_MS + 40, () => { this._docking = false; });
      } else {
        this.setData({ dockCtaStyle: ctaAt + ease, dockFavStyle: favAt + ease });
        this._later(DOCK_MS, () => { this.setData({ docked: false }); this._docking = false; });
      }
    });
  },

  onHeroSwipe(e) { this.setData({ heroIdx: (e && e.detail && e.detail.current) || 0 }); },
  noop() {},
  onRouteReal() { this.setData({ routeReal: true }); },
  onBuyPick(e) { this.setData({ buySel: Number(e.currentTarget.dataset.i) || 0 }); },
  /* 去结算:自玩通行证进确认订单;发布者的票种绑在场次上,进选场次弹层并预选同名票种 */
  onBuyGo() {
    const opt = (this.data.buyOpts || [])[this.data.buySel];
    if (!opt || opt.kind === 'pass') return this.onSelfPlayGoCheckout();
    this.setData({ selfPlayConfirmShow: false, buyIn: false, sessionPreferName: opt.name });
    this.bmClick({ currentTarget: { dataset: {} } });
  },

  /* v13 顶栏:详情顶部照片滚过一半后,顶栏压黑并写主题名 */
  _checkNav(scrollTop) {
    const top = this.data.screen2Top || 0;
    const want = top > 0 && scrollTop > top + this._windowSize().h * 0.5;
    if (want !== this.data.navSolid) this.setData({ navSolid: want });
  },

  /* 主要角色:商家站点的出场 NPC,最多问 4 个站点;没有就整块不出 */
  _loadChars(data) {
    if (!this._loggedIn()) return;
    const asks = [];
    for (const c of (data && data.chaptersList) || []) {
      for (const n of (c && c.nodes) || []) {
        if (n && n.id && (n.registrationMerchantList || []).length && asks.length < 4) asks.push({ nodeId: n.id, place: nodeText(n.name) });
      }
    }
    if (!asks.length) return;
    const rows = [];
    let left = asks.length;
    const done = () => { left -= 1; if (!left && !this._unloaded) this.setData({ storyChars: motion.buildChars(rows) }); };
    asks.forEach((a) => app.sendRequest({ url: '/api/ai/npc/profile', method: 'GET', hideLoading: true, autoErrorToast: false,
      data: { scope: 'node', nodeId: a.nodeId },
      success: (res) => { const list = res && String(res.code) === '200' && res.data && res.data.npcs; if (Array.isArray(list)) list.forEach((npc) => rows.push({ npc, place: a.place })); done(); },
      fail: done,
    }));
  },

  onHeroCta() {
    const kind = this.data.heroCta && this.data.heroCta.kind;
    if (kind === 'locked') return this.lockedTip();
    if (kind === 'joined') return this.goJoinedPlay();
    if (kind === 'selfplay') return this.selfPlayBuy();
    if (kind === 'signup') return this.bmClick({ currentTarget: { dataset: {} } });
  },

  // 收藏 = 既有的主题「喜欢」(我的-喜欢 同一个接口);进页读一次当前状态
  _loggedIn() { return typeof app.getUserID === 'function' && !!app.getUserID(); },

  _loadFav(topicId) {
    if (!topicId || !this._loggedIn()) return;
    app.sendRequest({ url: '/api/topic/like_list', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: { pageNum: 1, pageSize: 200 },
      success: (res) => {
        if (this._unloaded || !res || String(res.code) !== '200' || !res.data) return;
        const rows = Array.isArray(res.data.rows) ? res.data.rows : [];
        this.setData({ faved: rows.some((t) => t && String(t.id) === String(topicId)) });
      },
      fail: () => {},
    });
  },

  toggleFav() {
    if (app.ensureLoginFor && !app.ensureLoginFor('like', () => this.toggleFav(), { owner: this, target: () => [this.data.id, this.data.info && this.data.info.id] })) return; // 2026-10-24 游客写前登录
    const id = (this.data.info && this.data.info.id) || this.data.id;
    if (!id || this._favBusy) return;
    const next = !this.data.faved;
    this._favBusy = true;
    this.setData({ faved: next });
    app.sendRequest({ url: '/api/topic/like', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: { id: id, type: next ? 1 : 0 },
      success: (res) => {
        this._favBusy = false;
        if (!res || String(res.code) !== '200') { this.setData({ faved: !next }); toast((res && res.msg) || '收藏没有保存，请重试'); return; }
        toast(next ? '已收藏' : '已取消收藏');
      },
      fail: () => { this._favBusy = false; this.setData({ faved: !next }); toast('网络异常，收藏没有保存'); },
    });
  },

  // 组队块:以第一个有坐标的站点为圆心读附近队伍,只留本主题的;读不到整块不出
  _loadTeams(data) {
    let lat = null, lng = null;
    for (const c of (data && data.chaptersList) || []) {
      for (const n of (c && c.nodes) || []) {
        const p = routePoint(n);
        if (p) { lat = p.latitude; lng = p.longitude; break; }
      }
      if (lat !== null) break;
    }
    if (lat === null || !this._loggedIn()) return;
    app.sendRequest({ url: '/api/team/nearby', method: 'GET', hideLoading: true, autoErrorToast: false,
      data: { lat: lat, lng: lng, radius: 20000 },
      success: (res) => {
        if (this._unloaded || !res || String(res.code) !== '200') return;
        this.setData({ teams: motion.buildTeams(res.data, data.id) });
      },
      fail: () => {},
    });
  },

  // 开局前准备(原型 B T-36,契约 §1.3):GET /api/play/setup 给可玩时段(非俱乐部且不是「不限」才有)、openNow 与作者准备项;
  // 两样都没配就整行不出(B1)。未成年夜间等平台规则只在服务端执行(/api/orient/safety/check),被拒时只写一句中性说明。
  _loadPrep(topicId) {
    if (!topicId || !this._loggedIn()) { this._prepSetup = null; this.setData({ prep: { show: false } }); return; }
    const seq = this._prepSeq = (this._prepSeq || 0) + 1;
    this.setData({ prep: { show: true, loading: true, rowText: '正在加载…', icon: 'clock' } });
    const failed = () => { if (!this._unloaded && seq === this._prepSeq) this.setData({ prep: { show: true, error: true, rowText: '开局前准备加载失败 · 轻点重试', icon: 'warning' } }); };
    app.sendRequest({ url: '/api/play/setup', method: 'GET', hideLoading: true, autoErrorToast: false, data: { topicId },
      success: (res) => {
        if (this._unloaded || seq !== this._prepSeq) return;
        if (!res || String(res.code) !== '200' || !res.data) { failed(); return; }
        this._prepSetup = res.data; this._buildPrep(res.data);
      },
      fail: failed,
    });
  },
  _buildPrep(setup, ticks) {
    const d = setup || {};
    const items = (Array.isArray(d.prepItems) ? d.prepItems : []).map((t) => String(t || '').trim()).filter(Boolean).slice(0, 6)
      .map((text, k) => ({ k, text, on: !!(ticks && ticks[k]) }));
    const w = d.playWindow && d.playWindow.mode && d.playWindow.mode !== 'any' ? d.playWindow : null;
    if (!items.length && !w) { this.setData({ prep: { show: false } }); return; }
    const windowText = !w ? '' : w.mode === 'night' ? '夜间 20:00–06:00' : w.mode === 'day' ? '白天 06:00–20:00' : (w.start || '') + '–' + (w.end || '');
    const closed = !!w && d.openNow === false;
    const left = items.filter((x) => !x.on).length;
    const refused = !!(this.data.prep && this.data.prep.refused);
    const rowText = closed ? '开局前准备 · ' + windowText + ' 开放' : left ? '开局前准备 · 还有 ' + left + ' 项未勾选' : '开局前准备 · 已全部勾选';
    this.setData({ prep: { show: true, items, windowText, closed, ready: !closed && !left, refused, rowText, icon: closed ? 'clock' : left ? 'info' : 'check' } });
  },
  _prepTicks() { const t = {}; ((this.data.prep && this.data.prep.items) || []).forEach((x) => { if (x.on) t[x.k] = true; }); return t; },
  openPrep() {
    const prep = this.data.prep || {};
    if (prep.loading) return;
    if (prep.error) { this._loadPrep((this.data.info && this.data.info.id) || this.data.id); return; }
    this._buildPrep(this._prepSetup, this._prepTicks()); this.setData({ prepShow: true });
  },
  closePrep() { this.setData({ prepShow: false }); },
  onPrepTick(e) {
    const k = Number(e.currentTarget.dataset.index); const t = this._prepTicks();
    if (e.detail && e.detail.checked) t[k] = true; else delete t[k];
    this._buildPrep(this._prepSetup, t);
  },
  startPrepPlay() {
    const info = this.data.info || {}; const prep = this.data.prep || {};
    if (!prep.ready || prep.refused || this.data.prepBusy || !info.id) return;
    this.setData({ prepBusy: true });
    const done = () => { if (!this._unloaded) this.setData({ prepBusy: false }); };
    const go = () => {
      this.setData({ prepShow: false });
      const name = info.name ? '&topicName=' + encodeURIComponent(info.name) : '';
      wx.navigateTo({ url: '/pages/play/index?topicId=' + info.id + name });
    };
    // 预检开关关闭 / 非定向主题时不拦,开局时服务端仍会再判一次
    const skip = (res) => !!(res && ['ORIENT_DISABLED', 'ORIENT_NOT_ORIENTEERING'].includes(res.errorCode));
    app.sendRequest({ url: '/api/orient/safety/check', method: 'POST', hideLoading: true, autoErrorToast: false,
      data: JSON.stringify({ topicId: info.id }), header: { 'Content-Type': 'application/json' },
      success: (res) => {
        done();
        if (this._unloaded) return;
        if (skip(res)) { go(); return; }
        if (!res || String(res.code) !== '200' || !res.data) { toast('网络异常，请重试'); return; }
        if (res.data.allowed !== true) { this.setData({ 'prep.refused': true }); return; }
        go();
      },
      fail: (res) => { done(); if (this._unloaded) return; if (skip(res)) go(); else toast('网络异常，请重试'); },
    });
  },

  // 组队卡 → 我的队伍「本场招募」(/api/orient/team/lobby):有场次的队伍按场次读,否则按主题读
  openTeamLobby() {
    const info = this.data.info || {};
    if (Number(this.data.is_join) !== 1 || Number(info.productType) !== 1 || !info.id) return;
    const row = (this.data.teams.rows || []).find((r) => r && r.activityId) || null;
    const scope = [info.name, row && row.when ? row.when + ' 场' : ''].filter(Boolean).join(' · ');
    wx.navigateTo({ url: '/subpackageRoam/my-teams/index?tab=open&ownerType=' + (row ? 2 : 1)
      + '&ownerId=' + encodeURIComponent(row ? row.activityId : info.id) + '&scope=' + encodeURIComponent(scope) });
  },

  onMerchantOpen(e) {
    const id = e && e.detail && e.detail.id;
    this.onLineupTap({ currentTarget: { dataset: { kind: 'merchant', id: id } } });
  },

  onStoryDock(e) { this.setData({ storyDocked: !!(e && e.detail && e.detail.docked) }); },

  onChapterCard(e) {
    const i = Number(e.currentTarget.dataset.i);
    this.setData({ openChapter: this.data.openChapter === i ? -1 : i });
  },

  // 路线进度:已购才读本人进度(/api/play/nodes 的 total/doneCount);未购就是 0,不演示假进度
  _loadProgress() {
    if (this._progressLoaded) return;
    this._progressLoaded = true;
    const info = this.data.info || {};
    const nodes = [];
    (info.chaptersList || []).forEach((c) => (c.nodes || []).forEach((n) => nodes.push(n)));
    const labels = (n) => Array.from({ length: n }, (_, i) => (Number(info.productType) === 2 ? '第 ' + (i + 1) + ' 家' : '第' + (i + 1) + '站')); // v13:自由探索按「家」数
    const fallback = () => { if (!this._unloaded) this.setData({ progress: { total: nodes.length, done: 0, labels: labels(nodes.length) } }); };
    if (Number(this.data.is_join) !== 1 || !this._loggedIn()) return fallback();
    app.sendRequest({ url: '/api/play/nodes', method: 'GET', hideLoading: true, autoErrorToast: false,
      data: { topicId: info.id || this.data.id },
      success: (res) => {
        const d = res && String(res.code) === '200' && res.data;
        const total = d ? Number(d.total) : 0;
        if (this._unloaded) return;
        if (!(total > 0)) return fallback();
        this.setData({ progress: { total: total, done: Math.min(total, Math.max(0, Number(d.doneCount) || 0)), labels: labels(total) } });
      },
      fail: fallback,
    });
  },

  /* 自玩:弹层只说清买的是什么,按钮「去结算」进确认订单页(业主:随时开玩不直接付款) */
  onSelfPlayGoCheckout() {
    if (app.ensureLoginFor && !app.ensureLoginFor('checkout', () => this.onSelfPlayGoCheckout(), { owner: this, target: () => [this.data.id, this.data.info && this.data.info.id] })) return; // 2026-10-24 游客写前登录
    if (this.lockedTip()) return;
    const info = this.data.info || {};
    const win = motion.passWindow({ topicEnd: info.endDate });
    const p = this._signupProfile || {};
    const phone = String(p.phone || '');
    const price = Number(info.selfPlayPrice);
    this.setData({
      selfPlayConfirmShow: false,
      selfPlayCheckoutShow: true,
      selfPlayConsentHint: '',
      selfPlayConsentError: '',
      checkout: {
        cover: (this.data.coverImages && this.data.coverImages[0]) || info.imgUrl || '',
        validText: win.line.split(' · ')[0],
        placeText: (info.venue && info.venue.name) || '',
        priceText: (info.selfPlayPrice === undefined || info.selfPlayPrice === null || info.selfPlayPrice === '') ? '—' : '¥' + info.selfPlayPrice,
        free: !(price > 0),
        profileName: p.realName || '',
        profilePhone: phone.length >= 7 ? phone.slice(0, 3) + '****' + phone.slice(-4) : phone,
      },
    });
  },

  onCheckoutBack() {
    if (this.data.selfPlayConfirmLoading || this._selfPlaySubmitting) return;
    this.setData({ selfPlayCheckoutShow: false });
  },

  onCheckoutProfile() {
    if (this._navigating) return; this._navigating = true;
    wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao', complete: () => { this._navigating = false; } });
  },

  /* 付款成功 → 通行证到账动效。可选的商家营销同意行(拍板第 40 条 A)照旧要给:
     动效收尾时有同意可选才弹原结果面板,否则直接去票夹/开局。 */
  _showPassReveal(topicId, registrationId) {
    const info = this.data.info || {};
    const win = motion.passWindow({ topicEnd: info.endDate });
    this._revealTopicId = topicId;
    this._revealRegId = registrationId || 0;
    const price = info.selfPlayPrice;
    const priceText = (price === undefined || price === null || price === '') ? '' : '¥' + price;
    this.setData({
      is_join: 1,
      heroCta: motion.buildHeroCta(info, { isJoin: 1, merchantClosed: false, priceText }),
      reveal: {
        show: true,
        cover: (this.data.coverImages && this.data.coverImages[0]) || info.imgUrl || '',
        name: info.name || '',
        kindText: '城瘾 · ' + (Number(info.productType) === 2 ? '自由探索' : '城市定向') + '通行证',
        untilText: win.untilText, daysText: win.daysText, line: win.line,
      },
    });
    // 期限口径同后端建单(CmsRegistrationServiceImpl:购买后 90 天,主题先结束则以主题为准),本地即可算准
    this._pendingOffer = null;
    marketingConsent.loadOffer(app, info.memberId || 0, (offer) => { if (offer) this._pendingOffer = offer; });
  },

  onRevealWallet() { this._finishReveal('wallet'); },
  onRevealPlay() { this._finishReveal('play'); },

  _finishReveal(kind) {
    this.setData({ 'reveal.show': false, selfPlayCheckoutShow: false });
    const hasOffer = !!this._pendingOffer;
    this._pendingOffer = null;
    if (hasOffer) {
      // 商家营销同意(拍板第 40 条 A)仍走原结果面板那一行;面板收起后再去票夹/开局
      this._revealNext = kind;
      this._goSelfPlay(this._revealTopicId);
      return;
    }
    this._goAfterReveal(kind);
  },

  _goAfterReveal(kind) {
    const topicId = this._revealTopicId || (this.data.info && this.data.info.id) || this.data.id;
    const regId = this._revealRegId;
    if (kind === 'play' && regId && topicId) {
      const name = this.data.info && this.data.info.name ? '&topicName=' + encodeURIComponent(this.data.info.name) : '';
      wx.navigateTo({ url: '/pages/play/index?topicId=' + topicId + '&registrationId=' + regId + '&entry=ticket' + name });
      return;
    }
    wx.navigateTo({ url: '/subpackageMember/signup/index?stype=0&arrive=1' });
  },

  goTemplateDetail(e) {
    let id = e.currentTarget.dataset.id;
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: '/pages/templatedetail/templatedetail?id=' + id + '&scope=my',
    })
  },
  
  goLocation(e) {
    let latitude = e.currentTarget.dataset.latitude;
    let longitude = e.currentTarget.dataset.longitude;
    let name = e.currentTarget.dataset.name;
    let address = e.currentTarget.dataset.address;

    // 场地卡在节点无坐标时也会渲染,此时不带经纬度;openLocation 传 NaN 会静默失败
    if (!latitude || !longitude) return;

    // 打开微信内置地图查看位置
    wx.openLocation({
      latitude: parseFloat(latitude), // 纬度，范围为-90~90，负数表示南纬
      longitude: parseFloat(longitude), // 经度，范围为-180~180，负数表示西经
      name: name, // 位置名
      address: address, // 地址的详细说明
      scale: 18, // 地图缩放级别，整形值，范围从1~28
      success: function (res) {
      },
      fail: function (err) {
      }
    })
  },
  
  goPoint() {
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: '/subpackageP3/pages/growthcenter/index/index',
    })
  },

  /**
   * 触摸开始事件
   */
  touchStart(e) {
    if (this.data.isScrolling) return;
    this.setData({
      startY: e.touches[0].clientY,
      startTime: Date.now()
    });
  },
  
  /**
   * 稿 214:384「阵容/商家」。阵容说的是**参与品牌**(商家与俱乐部),不是买了票的玩家 ——
   * 页上原来两块:「阵容」列 omsTicketList[].cmsRegistrationList(报名玩家),
   * 「参与品牌」才是商家。2026-09-04 判定表定:两块是一回事,合并;俱乐部那一半原先没有。
   *
   * 同一家商家会挂在多个章节/节点上,按商家 id 去重 —— 不去重横滑条里会连着出现同一家。
   * 俱乐部没有头像字段(TopicInfoVO 只有 clubId / clubName),头像交给 cy-avatar 的兜底,
   * 不去别处凑一张。名字都没有的行直接跳过,不摆占位卡。
   */
  /* 稿 181:644 / 214:499 的「活动内容」——主题介绍下面那串「这趟里都有什么」。
     ⚠️ 稿上那六条(5 articles / 12 downloadable resources / 10 coding exercises /
        Full lifetime access / Access on mobile and TV / Certificate of completion)
        是 Udemy 的占位内容,按「那些不是我们的不做」一条都不抄。
        但**这个位置本身是真的**:买家在这一屏看不到「有几章、有没有语音导览、
        通关给不给勋章和券」—— 这四件事全在 /api/topic/info-to-user 的返回里,
        只是从来没渲染过(全仓 grep 过:finishMedalName / completeRewardCouponId /
        totalChapterCount 在两个详情页里一次都没出现)。
     只写后端真给了的事实,拿不到就少一条;一条都没有整块不出 —— 不摆空标题。
     ⚠️ 不重复顶部数据条已经说过的(评价 / 预计游玩 / 开放时间 / 总里程)。 */
  buildContentIncludes(data) {
    const d = data || {};
    const out = [];
    const chapters = Number(d.totalChapterCount);
    if (Number.isFinite(chapters) && chapters > 0) out.push(chapters + ' 个章节');
    // 服务端实时整条路线总站点数（含未解锁节点的数量）；chaptersList 只是当前可见子集。
    // 0 是权威值，不能用可见节点数覆盖，也不能从子集推测缺失的总数。
    // 首章试看、分支 teaser 与商家单站承接都可能返回少于总数的节点。
    // 总数只是数量，不授予读取未揭示节点、模板或路线的权限。
    // 缺失值不生成站点条目；章节局部节点标签继续反映各自可见内容。
    const stations = Number(d.locationCount);
    if (Number.isFinite(stations) && stations > 0) out.push(stations + ' 个节点');
    const plays = Number(d.templateCount);
    if (Number.isFinite(plays) && plays > 0) out.push(plays + ' 个玩法');
    if (d.audioUrl) {
      const mins = Math.round(Number(d.audioDuration) / 60);
      out.push(Number.isFinite(mins) && mins > 0 ? ('语音导览 ' + mins + ' 分钟') : '语音导览');
    }
    // 勋章名可以为空(后端默认「主题名 · 通关」),所以判据是**有没有配**,不是名字非空
    if (d.finishMedalName || d.finishMedalImg) {
      out.push('通关勋章：' + (d.finishMedalName || (d.name ? d.name + ' · 通关' : '通关纪念')));
    }
    // 只知道配了券、拿不到券名(TopicInfoVO 没有这个字段)—— 就只说配了,不编一个名字
    if (d.completeRewardCouponId) out.push('通关后可领一张优惠券');
    return out;
  },

  buildLineup(data) {
    const rows = [];
    if (data && String(data.clubName || '').trim()) {
      rows.push({
        key: 'club-' + (data.clubId || 0),
        kind: 'club',
        targetId: data.clubId || 0,
        name: String(data.clubName).trim(),
        role: '俱乐部',
        avatar: '',
      });
    }
    const seen = {};
    const chapters = (data && data.chaptersList) || [];
    for (let i = 0; i < chapters.length; i++) {
      const nodes = (chapters[i] && chapters[i].nodes) || [];
      for (let j = 0; j < nodes.length; j++) {
        const list = nodes[j].registrationMerchantList || [];
        for (let k = 0; k < list.length; k++) {
          const row = list[k];
          const shop = row && row.mmsMerchant;
          const name = String((shop && shop.name) || '').trim();
          if (!name) continue;
          const dedupeKey = String((shop && shop.id) || name);
          if (seen[dedupeKey]) continue;
          seen[dedupeKey] = 1;
          rows.push({
            key: 'merchant-' + dedupeKey,
            kind: 'merchant',
            targetId: row.memberId || 0,
            name,
            avatar: String(row.picUrl || '').split(',')[0].trim(),
            role: '商家',
          });
        }
      }
    }
    return rows;
  },

  /* 俱乐部去俱乐部页,商家去他的个人主页。没有落点(俱乐部没 id / 商家没 memberId)就不跳,
     不静默跳到一个错的地方。 */
  onLineupTap(e) {
    const { kind, id } = e.currentTarget.dataset;
    const targetId = Number(id) || 0;
    if (!targetId) return;
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ complete: () => { this._navigating = false; },
      url: kind === 'club'
        ? '/pages/club/detail/index?id=' + targetId
        : '/pages/userinfo/userinfo?userId=' + targetId,
    });
  },


  /**
   * 触摸移动事件
   */
  touchMove(e) {
    // 这里可以添加一些交互效果，但不是必须的
  },

  /**
   * 触摸结束事件
   */
  touchEnd(e) {
    if (this.data.isScrolling) return;

    const endY = e.changedTouches[0].clientY;
    const distance = endY - this.data.startY;
    const duration = Date.now() - this.data.startTime;
    const minSwipeDistance = 50;

    // 只有快速滑动或距离足够时才触发
    if (Math.abs(distance) > minSwipeDistance || duration < 300) {
      // 下滑操作（手指向上滑动）
      if (distance < -minSwipeDistance && this.data.currentScreen === 0) {
        this.scrollToSecond();
      }
      // 上滑操作（手指向下滑动）- 只有在接近顶部时才返回第一屏
      else if (distance > minSwipeDistance && (this._scrollTop || 0) <= this.data.screen2Top + 100) {
        this.scrollToFirst();
      }
    }
  },

  /**
   * 滚动到第一屏
   */
  scrollToFirst() {
    if (this.data.isScrolling) return;

    this.setData({
      isScrolling: true
    });

    wx.pageScrollTo({
      scrollTop: 0,
      duration: 500,
      success: () => {
        this.setData({
          currentScreen: 0,
          isScrolling: false
        });
      },
      fail: () => {
        this.setData({
          isScrolling: false
        });
      }
    });
  },

  /**
   * 增强版滚动到第二屏 - 处理可能的高度计算延迟
   */
  scrollToSecondEnhanced() {
    if (this.data.isScrolling) return;

    this.setData({
      isScrolling: true
    });

    const tryScroll = (attempts = 0) => {
      if (attempts > 3) {
        // 最终备用方案：滚动一屏高度
        wx.pageScrollTo({
          scrollTop: this.data.screenHeight,
          duration: 600,
          success: () => {
            this.setData({
              currentScreen: 1,
              isScrolling: false
            });
          },
          fail: () => {
            this.setData({
              isScrolling: false
            });
          }
        });
        return;
      }

      if (this.data.screen2Top > 0) {
        wx.pageScrollTo({
          scrollTop: this.data.screen2Top,
          duration: 600,
          success: () => {
            this.setData({
              currentScreen: 1,
              isScrolling: false
            });
          },
          fail: () => {
            this.setData({
              isScrolling: false
            });
          }
        });
      } else {
        // 重新计算高度后重试
        this.calculateScreenHeights();
        setTimeout(() => tryScroll(attempts + 1), 200);
      }
    };

    tryScroll();
  },

  /**
   * 返回上一页
   */
  goBack() {
    wx.navigateBack({
      delta: 1,
      fail: () => this.goTopicList()
    });
  },

  goTopicList() {
    wx.reLaunch({ url: '/pages/template/index' });
  },
  
  // 提交评价
  submitComment() {
    if (app.ensureLoginFor && !app.ensureLoginFor('post', () => this.submitComment(), { owner: this, target: () => [this.data.id] })) return; // 2026-10-24 游客写前登录
    const that = this;
    if (!that.data.canSubmitComment) return;
    const {
      answer,
      plnr,
      id
    } = that.data;

    if (answer === -1) {
      toast('请选择评分');
      return;
    }

    if (!plnr.trim()) {
      toast('请输入评价内容');
      return;
    }
    // BL-198:单飞,请求在途时忽略重复点击,避免同一条评价发两次。
    if (that._commentSubmitting) return;
    that._commentSubmitting = true;
    app.sendRequest({
      url: '/api/comment/add',
      data: {
        owner_type: 1,
        owner_id: id,
        rating: answer + 1,
        contents: plnr,
        reply_id: 0,
        img_arr: that.data.uploadImages.join(';')
      },
      method: "POST",
      success: function (res) {
        if (res.code == "200") {
          toast.success('评价成功');
          that.voteClose();
          // 重新加载数据更新评价列表
          that.getData();
        } else {
          app.tips(res.msg);
        }
      },
      complete: function () {
        that._commentSubmitting = false;
      }
    });
  },
  
  //报名
  // F2-3 售票门禁:招募/定价阶段点购票的提示
  lockedTip() {
    const lc = this.data.info && this.data.info.lifecycle;
    if (![1, 2, 7].includes(Number(lc))) return false;
    if (lc == 2 && this.data.info && this.data.info.isOwner == 1) {
      if (!this._navigating) { this._navigating = true; wx.navigateTo({ url: '/pages/topic/pricing/index?topicId=' + this.data.id, complete: () => { this._navigating = false; } }); }
      return true;
    }
    toast(lc == 7 ? '该主题立项待审，暂未开售' : (lc == 1 ? '该主题招募中，暂未开售' : '该主题定价中，暂未开售'));
    return true;
  },

  bmClick(e) {
    if (app.ensureLoginFor && !app.ensureLoginFor('signup', () => this.bmClick(e), { owner: this, target: () => [this.data.id] })) return; // 2026-10-24 游客写前登录

    if(app.getUserType()==2){
      toast('商家用户不可报名主题');
      return;
    }
    if (this.data.merchantClosed) { toast('商家暂停营业，暂不可报名', { icon: 'none' }); return; }
    if (this.lockedTip()) return;

    /* 2026-09-06 用户拍板:**主题报名没有废弃,是这里写错了**。
       原来这段把人踢去 play-activity-detail 再选一遍票 —— 主题页上明明已经把场次
       和价格都列出来了(det5 那一段),点一下却跳走,回来还得重新找。
       现在按稿 11/11a 在主题页内开弹层选场次 + 票种,选完直接进结算。

       ⚠️ 票仍然绑到具体场次(结算走 baoming?activityId=X&ticketId=Y,后端 ownerType=2),
       「主题页内能买」说的是**交互层**,不是绕过场次买一张没人核销的票。
       被删掉的那句老注释把这两件事混成了一件,顺带还写了一套靠
       name/startTime 字符串去猜「这张票属于哪个场次」的映射 —— 弹层里直接按场次
       拉它自己的票,那套猜测不需要了。 */
    const activities = (this.data.info && this.data.info.activityList) || [];
    if (!activities.length) {
      toast('暂无可参加的场次', { icon: 'none' });
      return;
    }
    this.setData({ sessionPicker: { show: true } });
  },

  closeSessionPicker() { this.setData({ 'sessionPicker.show': false, sessionPreferName: '' }); },

  // 选完场次与票种 → 结算。参数形状与活动详情页那条入口一致,后端一处都不用改。
  onSessionPicked(e) {
    if (this.lockedTip()) return;
    const picked = (e && e.detail) || {};
    if (!picked.activityId || !picked.ticketId) return;
    this.setData({ 'sessionPicker.show': false });
    wx.navigateTo({
      url: '/pages/activity/baoming/baoming?activityId=' + picked.activityId
        + '&ticketId=' + picked.ticketId,
    });
  },

  // Beta 转正式:清 beta_flag,主题从此不再挂 Beta 标识。仅主题作者(info.isOwner==1)可见入口。
  // 服务端 WHERE beta_flag=1 才清,所以重复点击/并发只会成功一次,失败按「不在 Beta 期」回话。
  onGraduateBeta() {
    const that = this;
    const topicId = that.data.id;
    if (!topicId) return;
    modal.show({
      title: '转为正式主题',
      content: '转正后不再显示 Beta 标记。主题内容、已售出的票和进行中的行程都不受影响。',
      confirmText: '确认转正',
      success(m) {
        if (!m.confirm) return;
        app.sendRequest({
          url: '/api/topic/beta/graduate',
          method: 'POST',
          data: { topicId: topicId },
          success(r) {
            if (r.code == '200') {
              app.tips('已转为正式主题');
              that.setData({ 'info.betaFlag': 0 });
            } else {
              app.tips(r.msg || '转正失败');
            }
          }
        });
      }
    });
  },

  // 移交给俱乐部承接:复制一份新的城市定向草稿挂到所选承接俱乐部,并下架原主题;发起人保留所有权。仅主题创建者(info.isOwner==1)可见入口。
  onTransferToClub() {
    const that = this;
    const topicId = that.data.id;
    if (!topicId) return;
    // BL-201:移交每调一次服务端就复制一份新草稿;成功后入口仍在、页面不回读,本实例内不再放行。
    if (that._transferredToClub) { app.tips('已移交，请到新主题继续配置'); return; }
    if (that._transferringToClub) return;
    app.sendRequest({
      hideLoading: true, url: '/api/club/my', method: 'POST', data: {},
      success(res) {
        const clubs = (res.code == '200' && res.data) || [];
        if (!clubs.length) { app.tips('你还没有可承接的俱乐部'); return; }
        const shown = clubs.slice(0, 6); // 上限 6 项:沿用原 ActionSheet 的口径,本轮不改行为
        /* 2026-09-02:原来这里弹 wx.showActionSheet(系统弹层,设计体系外)。
           选俱乐部属于「一次选择」,走 cy-option-sheet 默认形态;选中之后的二次确认
           modal 保留 —— 移交是不可逆写,那一步不能省。 */
        that._clubSheetClubs = shown.map(c => ({ id: c.id, name: c.name }));   // 同上,不渲染 ⇒ 不进 data
        that.setData({
          clubSheetShow: true,
          clubSheetItems: shown.map(c => c.name),
        });
      },
      fail() { app.tips('网络异常，请重试'); }
    });
  },

  onClubSheetCancel() { this.setData({ clubSheetShow: false }); },

  onClubSheetSelect(e) {
    const that = this;
    const topicId = that.data.id;
    const club = (that._clubSheetClubs || [])[e.detail.index];
    that.setData({ clubSheetShow: false });
    if (!club || !topicId) return;
    modal.show({
      title: '移交给俱乐部承接',
      content: '将复制一份新的城市定向主题(有人带),挂到「' + club.name + '」承接；原主题会下架，历史票不受影响。',
      confirmText: '确认移交',
      success(m) {
        if (!m.confirm) return;
        if (that._transferredToClub || that._transferringToClub) return;
        that._transferringToClub = true;
        app.sendRequest({
          url: '/api/topic/transfer-to-club',
          // 后端 transferToClub(String topicId, String clubId, …) 是 form 绑定(ApiTopicController:783):
          // 发 JSON body 时 getParameter 拿不到 → 恒「参数不完整」。
          data: { topicId: topicId, clubId: club.id },
          method: 'POST',
          success(r) {
            if (r.code == '200') {
              that._transferredToClub = true; // 成功后保持在途标记,直到页面重新加载
              const newId = r.data && r.data.newTopicId;
              modal.show({
                title: '已移交',
                content: '已生成新的城市定向草稿，去配置票种并邀请该俱乐部承接?',
                confirmText: '去配置', cancelText: '稍后',
                success(g) {
                  if (g.confirm && newId) {
                    wx.navigateTo({ url: '/pages/topic/index/index?id=' + newId });
                  }
                }
              });
            } else {
              that._transferringToClub = false;
              app.tips(r.msg || '移交失败');
            }
          },
          fail() { that._transferringToClub = false; app.tips('网络异常，请重试'); }
        });
      }
    });
  },

  _initWorkflow() {
    const that = this;
    const verifyRegistrationPayment = createRegistrationPaymentVerifier(app);
    that._workflow = createCheckoutWorkflow({
      createOrder: function (payload, cb) {
        const { topicId, realName, phone, requestId } = payload;
        loading.show('提交中...');
        const body = {
          ownerType: 1,
          ownerId: topicId,
          realName: realName,
          phone: phone,
          isUsePoint: 0,
          requestId: requestId
        };
        // 3-5 票源归因:主题页这一单 ownerType=1,ownerId 就是「期」本身,
        // 所以直接拿建单参数喂 attributionPayload(它内部已判「捕获期 ≠ 下单期 ⇒ 不带」)。
        Object.assign(body, ticketSource.attributionPayload(topicId));
        return app.sendRequest({
          url: '/api/registration/create',
          method: 'POST',
          header: { 'Content-Type': 'application/json' },
          data: JSON.stringify(body),
          success: function (res) {
            if (isTerminalOrderConflict(res)) {
              // fix-be-0917(B-R2):旧幂等键对应的自玩单已取消/已过期,后端不再重放终态单。
              // 作废旧键,交给 onOrderFail 换新键重建。
              that._selfPlayReqId = null;
              that._rebuildFallbackMessage = res.msg || '';
              cb({ ok: false, msg: ORDER_REBUILD_REQUIRED });
              return;
            }
            loading.hide();
            if (res.code == '200' && res.data) {
              var d = res.data;
              if (d.payParams && d.payParams.package) {
                that._selfPlayReqId = null;
                cb({ ok: true, data: d });
              } else if (d.payableAmount === 0) {
                that._selfPlayReqId = null;
                cb({ ok: true, data: d });
              } else if (d.registrationId) {
                // 重放命中已有订单,回到那张订单取支付参数
                loading.show('正在发起支付…');
                app.sendRequest({
                  url: '/api/registration/pay',
                  method: 'POST',
                  data: { id: d.registrationId },
                  success: function (payRes) {
                    // 拍板 #21:自玩通行证的过期待支付单 —— 后端这次 /pay 已即时关单,
                    // 旧幂等键重放只会再撞关单。不提示,交给 onOrderFail 换新键重建。
                    if (isOrderExpiredFailure(payRes)) {
                      that._selfPlayReqId = null;
                      that._rebuildFallbackMessage = ORDER_EXPIRED_USER_MESSAGE;
                      cb({ ok: false, msg: ORDER_REBUILD_REQUIRED });
                      return;
                    }
                    loading.hide();
                    if (payRes.code == '200' && payRes.data && payRes.data.payParams && payRes.data.payParams.package) {
                      that._selfPlayReqId = null;
                      cb({ ok: true, data: payRes.data });
                    } else {
                      cb({ ok: false, msg: payRes.msg || '支付信息不完整，请重试' });
                    }
                  },
                  fail: function () {
                    loading.hide();
                    cb({ ok: false, msg: '网络错误，请重试' });
                  }
                });
              } else {
                cb({ ok: false, msg: '支付信息不完整，请重试' });
              }
            } else {
              cb({ ok: false, msg: res.msg || '下单失败' });
            }
          },
          fail: function () {
            loading.hide();
            cb({ ok: false, msg: '网络错误，请重试' });
          }
        });
      },
      requestPayment: function (orderData, cb) {
        var p = orderData.payParams;
        wx.requestPayment({
          timeStamp: p.timeStamp,
          nonceStr: p.nonceStr,
          package: p.package,
          signType: p.signType,
          paySign: p.paySign,
          success: function () { cb({ ok: true }); },
          fail: function (res) {
            cb({ ok: false, cancelled: res.errMsg === 'requestPayment:fail cancel', errMsg: res.errMsg });
          }
        });
      },
      verifyPayment: function (data, cb) {
        var regId = data.registrationId;
        if (!regId) { cb({ ok: false, errMsg: '缺少订单编号' }); return; }
        return verifyRegistrationPayment({ registrationId: regId }, cb);
      }
    });
  },

  // M2 购买自玩通行证:确认(90天有效期+安全须知)→ /create(ownerType=1) → 支付 → play?topicId=
  selfPlayBuy() {
    if (app.ensureLoginFor && !app.ensureLoginFor('checkout', () => this.selfPlayBuy(), { owner: this, target: () => [this.data.id, this.data.info && this.data.info.id] })) return; // 2026-10-24 游客写前登录
    const that = this;
    if (that.data.merchantClosed) { toast('商家暂停营业，暂不可报名', { icon: 'none' }); return; }
    const info = that.data.info || {};
    const topicId = info.id || that.data.id;
    if (!topicId) return;
    if (app.getUserType() == 2) {
      toast('商家用户不可购买');
      return;
    }
    if (that.lockedTip()) return;
    that._selfPlayTopicId = topicId;
    that.setData({
      selfPlayConfirmShow: true, buyIn: false, buyOpts: motion.buyOptions(info), buySel: 0,
      buyChips: motion.buyChips(info, that.data.storyMeta),
      selfPlayValidText: motion.passWindow({ topicEnd: info.endDate }).line.split(' · ')[0],
      selfPlayConsentChecked: !!that._hostShareConsentReady,
      selfPlayConsentError: '',
      selfPlayConsentHint: '',
      selfPlayConfirmLoading: false
    });
    that._later(30, () => { if (that.data.selfPlayConfirmShow) that.setData({ buyIn: true }); });
  },

  onSelfPlayConsentChange(e) {
    const checked = !!(e && e.detail && e.detail.checked);
    if (!checked) this._hostShareConsentReady = false;
    this.setData({ selfPlayConsentChecked: checked, selfPlayConsentError: '', selfPlayConsentHint: '' });
  },

  onSelfPlayCancel() {
    if (this.data.selfPlayConfirmLoading || !this.data.selfPlayConfirmShow) return;
    if (this.data.reducedMotion) { this.setData({ selfPlayConfirmShow: false, buyIn: false }); return; }
    this.setData({ buyIn: false });
    this._later(250, () => { if (!this.data.buyIn) this.setData({ selfPlayConfirmShow: false }); }); // 250 = --cy-motion-sheet-out
  },

  // 后端 /api/registration/create 与 /pay 硬校验「报名信息单独同意」已落库(activity_host_data_sharing /
  // activity_signup)。这里必须先等 recordConsent 成功再建单,否则自玩单永远被拒。
  onSelfPlayConfirm() {
    const that = this;
    if (that.lockedTip()) return;
    const topicId = that._selfPlayTopicId || (that.data.info && that.data.info.id) || that.data.id;
    if (!topicId || that.data.selfPlayConfirmLoading || that._selfPlaySubmitting) return;
    if (!that.data.selfPlayConsentChecked) {
      // 单独同意只能用户自己勾,按钮不代勾;这里只提示,不算错误态
      that.setData({ selfPlayConsentHint: '请先勾选同意' });
      return;
    }
    // 保持用户点击手势：订阅拒绝/失败不阻塞既有同意记录和支付链。
    if (that._hostShareConsentReady || app.recordConsent) {
      subscribe.request(['signupSuccess', 'activityStart', 'refund']);
    }
    if (that._hostShareConsentReady) {
      that.setData({ selfPlayConfirmShow: false });
      that._createSelfPlayOrder(topicId);
      return;
    }
    if (!app.recordConsent) {
      that.setData({ selfPlayConsentError: '同意记录服务暂不可用，请稍后重试' });
      return;
    }
    that.setData({ selfPlayConfirmLoading: true, selfPlayConsentError: '' });
    app.recordConsent({
      docType: 'activity_host_data_sharing',
      scene: 'activity_signup',
      eventType: 'AGREE'
    }).then(function () {
      that._hostShareConsentReady = true;
      that.setData({ selfPlayConfirmLoading: false, selfPlayConfirmShow: false });
      that._createSelfPlayOrder(topicId);
    }).catch(function () {
      that.setData({ selfPlayConfirmLoading: false, selfPlayConsentError: '同意记录未保存，请检查网络后重试' });
    });
  },

  goMyOrders() {
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/order/order', complete: () => { this._navigating = false; } });
  },

  // /api/registration/create 的 realName/phone 是 @NotBlank(RegistrationRequest.java:37,42)。
  // 本页原先 data.userInfo 从声明到使用之间没有任何一处赋值 ⇒ 自玩通行证建单恒以空姓名空号提交,
  // 任何玩家、任何在架自玩主题都买不成。这里按报名页同一口径把资料取回来缓存。
  // 只喂建单、页面不渲染,所以存实例字段而不是 setData(U4 A2:内部状态不该进 data)。
  _loadSignupProfile(done) {
    const that = this;
    const settle = function () { done && done(that._signupProfile || null); };
    app.sendRequest({
      hideLoading: true,
      url: '/api/user/info',
      method: 'POST',
      data: { member_id: app.getUserID() },
      success(res) {
        const d = res && res.code == '200' && res.data
          && typeof res.data === 'object' && !Array.isArray(res.data) ? res.data : null;
        const realName = d ? (d.name || d.realName || d.fullName || '') : '';
        const phone = d ? (d.phone || d.mobilePhone || '') : '';
        // 只在两项都齐时才落缓存:半套缓存会让下面那次「现取一次」白取并原地打转。
        if (realName && phone) that._signupProfile = { realName: realName, phone: phone };
        settle();
      },
      fail: settle
    });
  },

  // 资料没补全时不要去打一发必被拒的建单:后端只会回「姓名不能为空」,
  // 玩家看到的是一句跟自己无关的报错,而且没有任何出路。
  _signupProfileMissing() {
    const u = this._signupProfile || {};
    return !u.realName || !u.phone;
  },

  _createSelfPlayOrder(topicId) {
    const that = this;
    if (that._selfPlaySubmitting) return;
    that._selfPlaySubmitting = true;

    if (!that._selfPlayReqId) {
      that._selfPlayReqId = 'req_' + Date.now() + '_' + Math.random().toString(36).slice(2, 10);
    }
    if (that._signupProfileMissing()) {
      // 进页预取可能还没回来(弱网、或进页就点购买)。先现取一次再判,
      // 否则会把「资料其实全」的人误判成没填、推去补一个本来就填好的字段。
      that._loadSignupProfile(function (profile) {
        that._selfPlaySubmitting = false;
        if (profile) { that._createSelfPlayOrder(topicId); return; }
        modal.show({
          title: '还差姓名和手机号',
          content: '建单要用你的真实姓名和手机号，资料页补一次就好，之后不用再填。',
          confirmText: '去补全',
          cancelText: '稍后再说',
          success(res) {
            if (res && res.confirm) wx.navigateTo({ url: '/pages/gerenziliao/gerenziliao' });
          }
        });
      });
      return;
    }

    const u = that._signupProfile;
    const payload = {
      topicId: topicId,
      realName: u.realName,
      phone: u.phone,
      requestId: that._selfPlayReqId
    };

    var submitted = that._workflow.submit(payload, {
      onOrderFail: function (res) {
        that._selfPlaySubmitting = false;
        // 拍板 #21 / fix-be-0917:旧幂等键不可用(单已过期/已取消)先自动换新键重建一次;
        // 重建后仍不可用才给可见提示。
        if (res && res.msg === ORDER_REBUILD_REQUIRED) {
          if (that._rebuildSelfPlayOrderWithNewKey(topicId)) return;
          loading.hide();
          res = { msg: that._rebuildFallbackMessage || ORDER_EXPIRED_USER_MESSAGE };
        }
        toast(res && res.msg || '下单失败');
      },
      onFreeSuccess: function (data) {
        that._selfPlaySubmitting = false;
        that._selfPlayReqId = null;
        that._onSelfPlayPaid(topicId, data);
      },
      onPayVerifying: function (data) {
        loading.show('确认支付结果...');
      },
      onPaySuccess: function (data) {
        that._selfPlaySubmitting = false;
        that._selfPlayReqId = null;
        loading.hide();
        that._onSelfPlayPaid(topicId, data);
      },
      onPayCancel: function () {
        that._selfPlaySubmitting = false;
        toast('已取消支付');
      },
      onPayFail: function (res) {
        loading.hide();
        that._selfPlaySubmitting = false;
        toast(res && res.errMsg || '支付失败');
        // 付款失败不清幂等键,同一意图重试复用
      },
      onPayUnknown: function () {
        loading.hide();
        that._selfPlaySubmitting = false;
        modal.show({
          title: '支付结果待确认',
          content: '暂不要重复支付，请稍后从订单或主题页查看通行证状态。',
          showCancel: false
        });
      }
    });

    if (!submitted) {
      that._selfPlaySubmitting = false;
      loading.hide();
      // unknown 是 workflow 故意留的闸(防重复建单扣款),但闸不能是哑巴:
      // 关掉「支付结果待确认」弹层后再点同一个按钮,原来什么都不说。
      if (that._workflow.getState() === 'unknown') {
        modal.show({
          title: '支付结果待确认',
          content: '这笔付款还在确认中，请勿重复支付。稍后可到「我的-订单」查看通行证状态。',
          showCancel: false
        });
      }
    }
  },

  /**
   * 2026-09-17 拍板 #21(+fix-be-0917 B-R2):旧幂等键不可用时自动换新键重建(与报名页同口径)。
   * 触发面:① /pay 见过期拒绝(后端已即时关单);② /create 回终态单冲突 409(已取消/已过期)。
   * 检测点已作废旧键,这里换新键回到正常建单接口重建(商家闭店/名额/价格等校验照走)。
   * 每次购买意图只自动重建一次;重建后仍不可用由 onOrderFail 给可见提示(旧键此时已作废,
   * 用户再点一次就是全新一单)。
   */
  _rebuildSelfPlayOrderWithNewKey(topicId) {
    const that = this;
    if (that._rebuildUsed) return false;
    that._rebuildUsed = true;
    // 与报名页同因:先收掉上一跳的 loading,避免重建路上任何早退把遮罩留在屏上。
    loading.hide();
    that._createSelfPlayOrder(topicId);
    return true;
  },

  _retrySelfPlayPayment(registrationId, topicId) {
    const that = this;
    app.sendRequest({
      url: '/api/registration/pay',
      method: 'POST',
      data: { id: registrationId },
      success: (res) => {
        if (res.code == '200' && res.data && res.data.payParams && res.data.payParams.package) {
          that._selfPlayReqId = null;
          // 走 order 路径的重新支付
          that._workflow.submit({ id: registrationId }, {
            onOrderFail: function () { toast('支付信息不完整，请重试'); },
            onPaySuccess: function () { that._onSelfPlayPaid(topicId, { id: registrationId }); },
            onPayCancel: function () { toast('已取消支付'); },
            onPayFail: function (res) { toast('支付失败'); }
          });
        } else {
          toast(res.msg || '支付信息不完整，请重试');
        }
      },
      fail: () => {
        toast('网络错误，请重试');
      }
    });
  },

  /* 自玩票买完的唯一落点(三个调用点都走这儿)。原来是 toast + 800ms 硬等,
     现在换成结果面板:跳转挂在面板收掉之后,时长只此一处,不再两个数字各说各话。 */
  /* 2026-10-22 业主:付完先播「通行证已到账」,再由动效里的「放进票夹 / 开始游戏」决定去向 */
  _onSelfPlayPaid(topicId, data) {
    const regId = data && (data.registrationId || data.id);
    this._showPassReveal(topicId, Number(regId) > 0 ? regId : 0);
  },

  _goSelfPlay(topicId) {
    this.setData({
      resultSheet: {
        show: true, kind: 'success', title: '购买成功',
        sub: '已放入票夹',
        meta: '', pill: '', why: '', duration: RESULT_SHEET_MS,
        consentName: '', consentChecked: false, consentState: 'idle', consentError: '',
      },
    });
    // 2026-09-17 拍板第 40 条 A:这单背后有生效商家且尚未同意时,面板里多一行可选勾选。
    // 拿不到 / 已同意 / 有歧义一律不出(见 utils/marketing-consent-entry.js)。
    this._loadMarketingConsentOffer((this.data.info && this.data.info.memberId) || 0);
  },

  /* 同意入口:owner memberId 来自主题详情已返回的 memberId;商家一方 id 只认 GET 回包。
     面板已收掉就不再补塞 —— 错过的用户在订单详情「权益已核销」处还有兜底入口。 */
  _loadMarketingConsentOffer(ownerMemberId) {
    const that = this;
    that._consentOffer = null;
    that._consentRequestId = null;
    marketingConsent.loadOffer(app, ownerMemberId, function (offer) {
      if (!offer) return;
      if (!that.data.resultSheet || !that.data.resultSheet.show) return;
      that._consentOffer = offer;
      that._consentRequestId = marketingConsent.newRequestId('self-play');
      that.setData({
        'resultSheet.consentName': offer.merchantName,
        'resultSheet.consentChecked': false,
        'resultSheet.consentState': 'idle',
        'resultSheet.consentError': '',
      });
    });
  },

  onResultConsentChange(e) {
    const checked = !!(e.detail && e.detail.checked);
    const state = this.data.resultSheet.consentState;
    if (state === 'saving') return;                       // 在途结果说了算,勾选态不来回抖
    if (!checked) {
      this.setData({ 'resultSheet.consentChecked': false, 'resultSheet.consentState': 'idle', 'resultSheet.consentError': '' });
      return;
    }
    const offer = this._consentOffer;
    if (!offer) return;
    this._submitMarketingConsent(offer);
  },

  onResultConsentRetry() {
    if (!this._consentOffer || this.data.resultSheet.consentState === 'saving') return;
    this._submitMarketingConsent(this._consentOffer);
  },

  /* 同一次勾选意图(含失败重试)复用同一个 requestId:重放由服务端识别,不写第二条。 */
  _submitMarketingConsent(offer) {
    const that = this;
    if (!that._consentRequestId) that._consentRequestId = marketingConsent.newRequestId('self-play');
    that.setData({ 'resultSheet.consentChecked': true, 'resultSheet.consentState': 'saving', 'resultSheet.consentError': '' });
    marketingConsent.optIn(app, offer, that._consentRequestId, function (result) {
      if (result.ok) {
        // 成功态就是面板上那行「已同意,可在设置里随时退订」—— 不再补一发两秒就没的 toast
        // (成功 toast 棘轮只减不增:提示不替代状态)。
        that.setData({ 'resultSheet.consentState': 'saved', 'resultSheet.consentError': '' });
        return;
      }
      that.setData({ 'resultSheet.consentState': 'failed',
        'resultSheet.consentError': result.message || '同意没有保存成功，请重试' });
    });
  },

  onResultSheetClose() {
    this.setData({ 'resultSheet.show': false });
    if (this._revealNext) { const kind = this._revealNext; this._revealNext = ''; this._goAfterReveal(kind); return; }
    wx.navigateTo({ url: '/subpackageMember/signup/index' });
  },

  // 票夹的 focusId 是 registrationId(signup/index.js:118),这里手上只有 topicId,传过去永远定位不到;
  // 老实打开路线票 tab,让用户在票夹里点那张票进 play。
  goJoinedPlay() {
    if (this._navigating) return; this._navigating = true; wx.navigateTo({ url: '/subpackageMember/signup/index?stype=0', complete: () => { this._navigating = false; } });
  },

  //投票弹框打开
  voteClick() {
    this.setData({
      voteShow: true
    })
  },
  
  //投票弹框关闭
  voteClose() {
    this.setData({
      voteShow: false,
      answer: -1,
      plnr: "",
      uploadImages: [],
      canSubmitComment: false
    });
  },
  
  // 上传图片
  upLoadImg() {
    const that = this;
    app.chooseImage(function (res) {
      let ll = that.data.uploadImages != "" ? that.data.uploadImages : [];
      res.forEach(function (v) {
        ll.push(v);
      })
      that.setData({
        'uploadImages': ll
      })
    }, 6);
  },
  
  // 删除图片
  deleteImage(e) {
    const index = e.currentTarget.dataset.index;
    const images = this.data.uploadImages;
    images.splice(index, 1);
    this.setData({
      uploadImages: images
    });
  },
  
  // 预览图片
  previewImage(e) {
    const src = e.currentTarget.dataset.src;
    mediaPreview.open(this.data.uploadImages, src);
  },
  
  
  /**
   * 评分组件选中处理 
   */
  changePic: function (e) {
    let f = this
    var index = e.currentTarget.dataset.index

    f.setData({
      answer: index
    }, () => f.refreshCommentState())
  },

  /**
   * 计算各屏高度和位置
   */
  calculateScreenHeights() {
    const that = this;

    // 延迟执行，确保页面渲染完成
    setTimeout(() => {
      const query = wx.createSelectorQuery();

      // 获取第一屏高度和第二屏位置
      query.select('#screen1').boundingClientRect();
      query.select('#screen2').boundingClientRect();

      query.exec((res) => {
        if (res[0] && res[1]) {
          const screen1Height = res[0].height;
          const screen2Top = res[1].top + (that._scrollTop || 0); // 加上当前滚动位置

          that.setData({
            screen1Height: screen1Height,
            screen2Top: screen2Top
          });

        } else {
          // 如果获取失败，使用备用方案
          that.setData({
            screen1Height: that.data.screenHeight,
            screen2Top: that.data.screenHeight
          });
        }
      });
    }, 800);
  },

  /**
   * 获取系统信息
   */
  getSystemInfo() {
    const that = this;
    wx.getSystemInfo({
      success: function (res) {
        that.setData({
          screenHeight: res.windowHeight
        });
      }
    });
  },

  /**
   * 格式化日期
   */
  formatDate(dateString) {
    if (!dateString) return '';
    if (typeof dateString === 'string' && /^\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:[ T]\d{1,2}:\d{1,2}(?::\d{1,2}(?:\.\d{1,9})?)?)?$/.test(dateString.trim())) {
      const parts = chinaParts(dateString);
      return parts ? `${parts.month}.${parts.day}` : '';
    }
    const date = new Date(dateString);
    if (Number.isNaN(date.getTime())) return '';
    const month = date.getMonth() + 1;
    const day = date.getDate();
    return `${month}.${day}`;
  },

  // 场地卡数据:addressName 形如「龙美术馆（上海市徐汇区龙腾大道3398号）」,拆成名称+地址;
  // 主题本身没有经纬度字段,坐标借第一个有坐标的节点(即路线起点),没有就只展示不跳地图
  buildVenue(data) {
    const raw = String(data.addressName || '').trim();
    if (!raw) return null;
    const m = raw.match(/^(.*?)[（(](.+?)[）)]\s*$/);
    const venue = { name: m ? m[1].trim() : raw, addr: m ? m[2].trim() : '' };
    const chapters = data.chaptersList || [];
    for (const chapter of chapters) {
      for (const node of (chapter.nodes || [])) {
        if (node.latitude && node.longitude) {
          venue.latitude = node.latitude;
          venue.longitude = node.longitude;
          return venue;
        }
      }
    }
    return venue;
  },

  /**
   * 将秒数转换为小时数字格式
   * @param {number} seconds - 秒数
   * @returns {number} 小时数（整数或小数）
   */
  formatHour(seconds) {
    if (seconds === null || seconds === undefined || seconds === '') return 0;

    // 确保输入是数字
    const sec = Number(seconds);
    if (isNaN(sec) || sec < 0) return 0;

    // 计算小时数（保留1位小数）
    const hours = sec / 3600;

    // 根据需求返回整数或小数
    if (hours % 1 === 0) {
      // 整数小时
      return hours;
    } else {
      // 小数小时，保留1位小数
      return Math.round(hours * 10) / 10;
    }
  },
  
  formatTimeToChinese(seconds) {
    if (seconds === null || seconds === undefined || seconds === '') return '0时';
    
    // 确保输入是数字
    const sec = Number(seconds);
    if (isNaN(sec) || sec < 0) return '0时';
    
    // 计算小时和分钟
    const hours = Math.floor(sec / 3600);
    const minutes = Math.floor((sec % 3600) / 60);
    
    // 格式化输出
    if (hours === 0) {
      // 少于1小时
      if (minutes === 0) {
        return '小于1分钟';
      } else {
        return `${minutes}分钟`;
      }
    } else {
      // 1小时或以上
      if (minutes === 0) {
        return `${hours}时`;
      } else {
        return `${hours}时${minutes}分`;
      }
    }
  },
  
  // 文本域失去焦点
  onCommentInput(e) {
    this.setData({
      plnr: e.detail.value
    }, () => this.refreshCommentState());
  },

  refreshCommentState() {
    const canSubmitComment = this.data.answer !== -1 && !!(this.data.plnr || '').trim();
    if (canSubmitComment !== this.data.canSubmitComment) this.setData({ canSubmitComment });
  },
   
  //获取数据
  /** 每章单独使用完整道路；缺失或损坏时只画本章节点虚线，不能跨章补线。 */
  buildRouteMap: function (chaptersList) {
    this.setData(routePreview.build(chaptersList));
  },

  /**
   * 点击节点 marker：提示节点名（后续可改成滚动到对应节点卡片）
   */
  onNodeTap: function (e) {
    const markerId = (e.detail && e.detail.markerId !== undefined) ? e.detail.markerId : e.markerId;
    const m = (this.data.routeMarkers || [])[markerId];
    if (m && m.callout && m.callout.content) {
      toast(m.callout.content);
    }
  },

  getData: function () {
    const that = this;
    app.sendRequest({
      hideLoading: true,
      url: '/api/topic/info-to-user',
      autoErrorToast: false, // 失败由整页 auto-back fail 半屏讲原因,不再叠 toast
      data: {
        id: that.data.id
      },
      method: "POST",
      success: function (res) {
        if (res.code == "200") {
          if (!hasTopicDetail(res.data)) {
            that.setData({
              loadError: true,
              missingTopicId: false,
              loadErrorTitle: '主题暂时无法打开',
              loadErrorSub: res.msg || '主题详情暂时无法加载，请稍后重试。',
              topicLoaded: false,
            });
            return;
          }
          res.data['chaptersList'] = normalizeChaptersList(res.data['chaptersList'])
          // 主题文字按纯文本展示:服务端过滤会把 x<y 存成 x&lt;y,在映射这一处解码。
          for (const key of ['name', 'subtitle', 'description']) res.data[key] = decodeDisplayText(res.data[key])
          const totalChapterCount = Number(res.data.totalChapterCount || 0)
          const unlockedChapterCount = Number(
            res.data.unlockedChapterCount != null
              ? res.data.unlockedChapterCount
              : res.data.chaptersList.length
          )
          res.data['lockedChapterCount'] = Math.max(0, totalChapterCount - unlockedChapterCount)
          res.data['showStartDate'] = that.formatDate(res.data['startDate'])
          res.data['showEndDate'] = that.formatDate(res.data['endDate'])
          res.data['omsTicketList'] = that.processTopicDate(res.data['omsTicketList'])
          res.data['perkCapacityText'] = that.formatPerkCapacity(res.data['perkSellableCapacity'])
          res.data['totalHour'] = that.formatHour(res.data['totalTime'])
          res.data['totalTimeFormatted'] = that.formatTimeToChinese(res.data['totalTime'])


          if (res.data['chaptersList']) {
            res.data['chaptersList'] = that.processChaptersList(res.data['chaptersList'], res.data['imgUrl']);
            that.buildRouteMap(res.data['chaptersList']);
          }
          // 放在 if 外面:没有章节的主题也可能有俱乐部,阵容不该跟着章节一起消失
          res.data['lineup'] = that.buildLineup(res.data);
          res.data['contentIncludes'] = that.buildContentIncludes(res.data);

          res.data['venue'] = that.buildVenue(res.data);
          res.data['heroIntro'] = heroIntro(res.data.subtitle);
          const rounded = Math.max(0, Math.min(5, Math.round(Number(res.data.averageRating) || 0)));
          const ratingStars = [];
          for (let i = 1; i <= rounded; i++) ratingStars.push({ star: i });

          // V-23:详情正文不再放举报行,可否举报只作处理器内部闸,不进页面 data
          that._canReport = canReportTopic(res.data, typeof app.getUserID === 'function' ? app.getUserID() : '');
          that.setData({
            info: res.data,
            // P0(2026-09-05 审核):已购态不再只信 URL 参数 is_join(全仓只有订单详情会传 1,
            // 从首页/搜索进来的已购用户永远看不到「开始玩」)。/info-to-user 已按 ownerType=1 &
            // registrationStatus=2 回填 isSignUp,与自玩通行证的建单口径同源,直接用它。
            is_join: (String(that.data.is_join) === '1' || Number(res.data.isSignUp) === 1) ? 1 : 0,
            // 稿 214:384 的数据条:与商家版 181:575 同一份格子(utils 里只有一份实现)
            statBar: buildTopicStatBar(res.data, { keepZeroCounts: true }),
            ratingStars,
            // 探店日/自由探索(productType=2):场次块换真场次卡,不再当「阵容」花名册渲染
            isExplore: Number(res.data.productType) === 2,
            loadError: false,
            topicLoaded: true,
            everLoaded: true,   // 成功读到过一次:之后的刷新失败留在原页给重试,不 auto-back 把人踢走
            missingTopicId: false,
            loginRequired: false,
            merchantClosed: res.data.merchantClosed === true,
            loadErrorTitle: '',
            loadErrorSub: '',
          });

          that._applyMotionData(res.data);
          // 数据加载完成后重新计算位置
          setTimeout(() => {
            that.getTabSectionPosition();
            that.getTabHeight();
            that._measureHero();
          }, 300);
        } else {
          that.setData({
            loadError: true,
            missingTopicId: false,
            loadErrorTitle: '主题暂时无法打开',
            loadErrorSub: res.msg || '主题详情暂时无法加载，请稍后重试。',
            topicLoaded: false,
          });
        }
      },
      fail: function (res, statusCode) {
        that.setData({
          loadError: true,
          missingTopicId: false,
          loginRequired: isLoginRequired(app, res, statusCode),
          loadErrorTitle: '主题详情加载失败',
          loadErrorSub: '',
          topicLoaded: false,
        });
      }
    });
  },

  // 详情加载失败重试(cy-error retry 事件)
  onRetryLoad: function () {
    if (this.data.missingTopicId) {
      this.goTopicList();
      return;
    }
    this.setData({ loadError: false, topicLoaded: false, loadErrorTitle: '', loadErrorSub: '' });
    this.getData();
  },

  // 登录引导「登录」:重登成功后重读详情
  onLoginRetry: function () {
    retryLogin(this, app, function () {
      this.setData({ loadError: false, loginRequired: false, topicLoaded: false, loadErrorTitle: '', loadErrorSub: '' });
      this.getData();
    });
  },
  
  /**
   * 特殊数据处理函数 - 处理主题日期
   */
  processTopicDate: function (list, sellableCapacity) {
    if (!isRecordList(list) || list.length === 0) return [];
    
    for (let i = 0; i < list.length; i++) {
      if (list[i]['startTime']) {
        let [datePart, timePart] = list[i]['startTime'].split(' ');
        let [, month, day] = datePart.split('-');
        let [hour, minute] = timePart.split(':');
        list[i]['monthDayTime'] = `${month}-${day} ${hour}:${minute}`;
      }
      if (list[i]['endTime']) {
        let [datePart, timePart] = list[i]['endTime'].split(' ');
        let [, month, day] = datePart.split('-');
        let [hour, minute] = timePart.split(':');
        list[i]['endMonthDayTime'] = `${month}-${day} ${hour}:${minute}`;
      }
      

      // 探店日场次卡:同日场次压成「MM-DD HH:mm–HH:mm」,跨日保留两端全量
      if (list[i]['monthDayTime'] && list[i]['endMonthDayTime']) {
        const sd = list[i]['monthDayTime'].slice(0, 5), ed = list[i]['endMonthDayTime'].slice(0, 5);
        list[i]['sessionTimeText'] = sd === ed
          ? (list[i]['monthDayTime'] + '–' + list[i]['endMonthDayTime'].slice(6))
          : (list[i]['monthDayTime'] + ' ~ ' + list[i]['endMonthDayTime']);
      } else {
        list[i]['sessionTimeText'] = list[i]['monthDayTime'] || '';
      }
      // 场次余席属于 activity ticket；期级权益容量另行显示，不能把同一个数印到每张卡。
      const rawRemaining = list[i]['remainingInventory'];
      const remaining = rawRemaining === null || rawRemaining === undefined || rawRemaining === ''
        ? NaN : Number(rawRemaining);
      list[i]['remaining'] = Number.isFinite(remaining)
        ? Math.max(0, remaining)
        : null;
    }
    return list;
  },

  formatPerkCapacity(value) {
    if (value === null || value === undefined || value === '') return '';
    const capacity = Number(value);
    if (!Number.isFinite(capacity) || capacity >= 2147483647) return '';
    return '本期可售 ' + Math.max(0, capacity) + ' 张';
  },
  
  /**
   * 判断整个活动是否有商家
   */
  
  /**
   * 判断章节下是否有商家
   */
  processChaptersList(chaptersList, topicCover) {
    if (!chaptersList || !Array.isArray(chaptersList)) return chaptersList;
    let seq = 0, prevNode = null; // v13 路线:站号全主题连续编号;站间步行线跨章接上(原型每章标题下也有一段)

    for (let j = 0; j < chaptersList.length; j++) {
      const chapter = chaptersList[j];
      chapter.name = decodeDisplayText(chapter.name);
      chapter.description = decodeDisplayText(chapter.description);
      // 稿 181:715:章节标题下面这行说的是**这一章**的时长 / 地点数 / 玩法数。
      // 原来读的是 info.*(整个主题的合计),挨着章节标题印,看上去就像这一章的 ——
      // 三章会印出三组一模一样的数,零报错。字段在 CmsTopicChapter 上本来就有。
      // 拿不到的那一项不写,不补 0(补 0 会说成「这章 0 个地点」)。
      chaptersList[j].metaTime = chapter.totalTime ? this.formatTimeToChinese(chapter.totalTime) : '';
      // 单位跟着数一起在这里拼:wxml 里「{{x}} 个节点」这种插值紧跟单位的写法,
      // 值缺了会变成孤零零一个「个节点」(有门禁专门拦)。
      // CU-C-66:章节级同一个快照毛病 —— 按本章真实节点数列,不读 location_count
      const nodeCount = (chapter.nodes || []).length || Number(chapter.locationCount) || 0;
      chaptersList[j].metaPlace = nodeCount > 0 ? nodeCount + ' 个节点' : '';
      chaptersList[j].metaPlay = Number(chapter.templateCount) > 0 ? chapter.templateCount + ' 个玩法' : '';
      chaptersList[j].hasMeta = !!(chaptersList[j].metaTime || chaptersList[j].metaPlace || chaptersList[j].metaPlay);
      chaptersList[j].chapLabel = motion.chapterLabel(j, chaptersList.length) + (chapter.name ? ' · ' + chapter.name : '');
      // 站间只写坐标直线距离；第一站没有上一站，不跨章计算。
      const ns = chapter.nodes || [];
      for (let n = 0; n < ns.length; n++) {
        // CU-C-65:站点这一层的裸字段全部归一,别把 null 渲染给玩家
        ns[n].businessTime = nodeText(ns[n].businessTime);
        ns[n].address = nodeText(ns[n].address);
        ns[n].name = nodeText(ns[n].name);
        ns[n].description = decodeDisplayText(ns[n].description);
        const firstMerchant = (ns[n].registrationMerchantList || [])[0];
        if (firstMerchant && firstMerchant.mmsMerchant) {
          firstMerchant.mmsMerchant.name = nodeText(firstMerchant.mmsMerchant.name);
        }
        // V-22b:节点介绍卡先用站点自己的图,再用玩法图;相对路径/http 签名链接/逗号多图在真机上都出不来,卡上一块空白。
        if (ns[n].cmsMemberTemplate) ns[n].cmsMemberTemplate.imgUrl = templateCover(ns[n].cmsMemberTemplate, ns[n], topicCover);
        ns[n].walkText = prevNode ? walkText(prevNode, ns[n]) : '';
        seq += 1; ns[n].seq = seq; prevNode = ns[n];
        ns[n].giftText = firstMerchant && firstMerchant.mmsMerchant ? nodeText(firstMerchant.mmsMerchant.derivativeBenefits) : '';
      }
    }
    for (let j = 0; j < chaptersList.length; j++) {
      for (let i = 0; i < chaptersList[j]['nodes'].length; i++) {
        if (chaptersList[j]['nodes'][i]['registrationMerchantList']) {
          for (let z = 0; z < chaptersList[j]['nodes'][i]['registrationMerchantList'].length; z++) {
            if (chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['startDate']) {
              let [year, month, day] = chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['startDate'].split('-');
              chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['month'] = month;
              chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['day'] = day;
            }
            if (chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['endDate']) {
              let [year, month, day] = chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['endDate'].split('-');
              chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['endmonth'] = month;
              chaptersList[j]['nodes'][i]['registrationMerchantList'][z]['endday'] = day;
            }

          }
        }

      }
    }

    return chaptersList;
  },

  // 修改展开/收起函数，支持独立控制每个票务

  // FOLLOWUP-A-1:举报主题,流程与动态详情 reportSquare 同一套(utils/content-report.js)
  reportTopic() {
    if (app.ensureLoginFor && !app.ensureLoginFor('report', () => this.reportTopic(), { owner: this, target: () => [this.data.id] })) return; // 2026-10-24 游客写前登录
    if (!this._canReport) return;
    contentReport.reportTopic(this, app, this.data.id);
  },
  onReportReasonSelect(e) { reportSheet.select(this, e); },
  onReportReasonCancel() { reportSheet.cancel(this); },

  /**
   * 用户点击右上角分享
   */
  onShareAppMessage() {
    /* CU-C-38:标题原来写死成泛化的「主题详情」——接收人从卡片上看不出分享的是哪场活动。
       主题名这一屏手上就有(与 :138 那处 {{info.name}} 同一份),没有才退回泛化文案。
       卡片图同样从这一屏的封面取:走 utils/topic-share 与俱乐部「带票分享」共用一份口径,
       取不到封面就不传 imageUrl(微信自己的默认卡片图),不拿占位图冒充。 */
    const info = this.data.info || {};
    return buildTopicShare({
      topicId: this.data.id,
      name: info.name,
      cover: resolveTopicCover(info, (path) => app.getImgUrl(path)),
    });
  }
})
