// 模板页货架(2026-10-28 业主定稿原型 v21):主题 / 玩法两个 tab,
// 顶部横滑精选 → 标签(只换标签下方内容)→ 横滑货架 / 分类页。
// 放在 templatedetail 分包、由主包模板页异步引用：包内图跟着分包走，不占主包。
const art = require('./art.js');
const { isRecordList } = require('../../../utils/response-shape.js');

const app = getApp();
const FRESH_MS = 15000;        // 货架数据新鲜期：期内重复触发（attached+show、频繁切回）不重拉
const PAGE_SIZE = 50;          // 后端 startPage 单页上限 50,一次拿不全，要翻页
const THEME_SEGMENT_TITLE = /^G\d+｜/;
const RAIL_STEP_RPX = 642;     // 主题精选卡 622rpx + 间距 20rpx,算当前第几张

function fmtSec(sec) {
  const m = Math.round(Number(sec || 0) / 60);
  if (!m) return '';
  if (m < 60) return m + ' 分钟';
  const h = Math.floor(m / 60);
  const r = m % 60;
  return h + ' 小时' + (r ? r + ' 分' : '');
}

function rgba(hex, a) {
  const h = String(hex || '#2c2c2e').replace('#', '');
  return 'rgba(' + [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16)).join(',') + ',' + a + ')';
}

function columns(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}

function decorateTopic(item) {
  const a = art.topicArt(item.name);
  const games = Number(item.gameCount || 0);
  const chapters = Number(item.chapterCount || 0);
  const meta = [];
  // 未经试走的主题不能看起来像已验证:非 VERIFIED(含未下发)一律标「实验模板」
  meta.push(item.previewOnly ? '实验预览' : (item.templateStatus === 'VERIFIED' ? '' : '实验模板'));
  if (chapters > 1) meta.push(chapters + ' 章');
  if (games) meta.push(games + ' 个玩法');
  const dur = fmtSec(item.totalTime);
  if (dur) meta.push(dur);
  return {
    id: item.id,
    name: item.name || '',
    sub: item.subtitle || '',
    desc: item.description || '',
    cover: a ? a.cover : (item.imgUrl || ''),
    glow: a ? a.glow : '',
    key: a ? a.key : '',
    seconds: Number(item.totalTime || 0),
    typeName: Number(item.productType) === 1 ? '城市定向' : '自由探索',
    typeCls: Number(item.productType) === 1 ? 'city' : 'free',
    meta: meta.filter(Boolean).join(' · '),
    previewOnly: !!item.previewOnly
  };
}

function decorateGame(item) {
  const a = art.gameArt(item.title);
  const group = art.GROUPS.find((g) => g.key === a.grp);
  return {
    id: item.id,
    title: item.title,
    players: item.players || '',
    duration: item.duration,
    short: a.short,
    prop: a.prop,
    cover: a.cover,
    lead: a.lead,
    rest: a.rest,
    tintMid: rgba(a.tint, 0.55),
    tintEnd: rgba(a.tint, 0.88),
    _grp: a.grp,
    groupName: group.name,
    groupColor: group.color,
    previewOnly: !!item.previewOnly
  };
}

// 只上货架保留的 25 个单节点玩法;同名多条(种子重跑)留 id 最大的那条。
function pickGames(rows) {
  const byTitle = {};
  rows.forEach((row) => {
    if (!row || typeof row !== 'object') return;
    const packType = row.packType == null || row.packType === '' ? 0 : Number(row.packType);
    const title = String(row.title || '').trim();
    // D-04 取舍：货架只上业主保留的清单（不把填充数据上架）；表外的告警，让后台上新能被发现
    if (packType === 0 && title && !THEME_SEGMENT_TITLE.test(title) && !art.GAMES[title] && typeof console !== 'undefined') console.warn('[shelf] 玩法模板不在货架清单内，未上架');
    if (packType !== 0 || THEME_SEGMENT_TITLE.test(title) || !art.GAMES[title]) return;
    if (!byTitle[title] || Number(row.id) > Number(byTitle[title].id)) byTitle[title] = row;
  });
  return Object.keys(art.GAMES).filter((t) => byTitle[t]).map((t) => decorateGame(byTitle[t]));
}

Component({
  properties: {
    light: { type: Boolean, value: false },
    scope: { type: String, value: '' },
    readonly: { type: Boolean, value: false },
    reducedMotion: { type: Boolean, value: false }
  },

  data: {
    tab: 'topic',
    tabItems: [{ key: 'topic', label: '主题', icon: '/pages/templatedetail/images/tpl-tab-topic.svg' }, { key: 'game', label: '玩法', icon: '/pages/templatedetail/images/tpl-tab-game.svg' }],
    glow: '',
    featIndex: 0,
    // 远端封面不可用时显示已有的明确失败态，不能拿别的业务内容图冒充模板封面
    coverErrorSrc: '/images/cover-route.svg',
    topicLoaded: false,
    gameLoaded: false,
    topicError: '',
    gameError: '',
    // 主题
    featTopics: [],
    topicTags: [],
    topicTagGroups: [],
    allTagsShow: false,
    tf: 'all',
    topicShelves: [],
    topicCat: null,
    // 玩法
    featGames: [],
    gameTags: [],
    gameTagGroups: [],
    gf: 'all',
    gameShelves: [],
    gameCat: null
  },

  lifetimes: {
    attached() {
      this._hidden = false; this._detached = false;
      this._shelfEpoch = (this._shelfEpoch || 0) + 1;
      this._topics = [];
      this._games = [];
      this.loadTopics();
      this.loadGames();
    },
    detached() {
      this._detached = true; this._shelfEpoch = (this._shelfEpoch || 0) + 1;
      this._topicSeq = (this._topicSeq || 0) + 1; this._gameSeq = (this._gameSeq || 0) + 1;
      this._seq = (this._seq || 0) + 1;
      if (this._coverTimer) { clearTimeout(this._coverTimer); this._coverTimer = null; }
    }
  },

  pageLifetimes: {
    // 回到模板页时静默刷新(后台改了上架不用重进小程序)
    show() {
      this._hidden = false;
      // attached 刚拉过就不重复拉；回前台超过 FRESH_MS 才静默刷新
      this.loadTopics(true);
      this.loadGames(true);
    },
    hide() {
      this._hidden = true;
      this._shelfEpoch = (this._shelfEpoch || 0) + 1;
      // 在途请求随代次作废：回前台必须重拉，不能被新鲜期挡住
      if (this._topicBusy) this._topicAt = 0;
      if (this._gameBusy) this._gameAt = 0;
    }
  },

  methods: {
    loadTopics(soft) {
      if (soft && this._topicAt && Date.now() - this._topicAt < FRESH_MS && (this.data.topicLoaded || this._topicBusy)) return;
      this._topicAt = Date.now();
      const seq = this._topicSeq = (this._topicSeq || 0) + 1;
      const owner = app.getUserID && app.getUserID(), scope = this.data.scope, epoch = this._shelfEpoch;
      const current = () => !this._hidden && !this._detached && seq === this._topicSeq && epoch === this._shelfEpoch
        && owner === (app.getUserID && app.getUserID()) && scope === this.data.scope;
      this._topicBusy = true;
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/template/topic-template/list',
        method: 'POST',
        data: {},
        success: (res) => {
          if (!current()) return;
          this._topicBusy = false;
          if (res && res.code == '200' && isRecordList(res.data)) {
            this._topics = res.data.filter((t) => art.HIDDEN_TOPICS.indexOf(art.themeKey(t.name)) < 0).map(decorateTopic);
            this.setData({ topicLoaded: true, topicError: '' });
            this.buildTopics();
          } else if (!this.data.topicLoaded) {
            this.setData({ topicError: app.getRequestErrorMessage(res, '主题模板加载失败') });
          }
        },
        fail: (res) => {
          if (current()) { this._topicBusy = false; this._topicAt = 0; }
          if (!current() || this.data.topicLoaded) return;
          this.setData({ topicError: app.getRequestErrorMessage(res, '主题模板加载失败') });
        }
      });
    },

    // 后端单页最多 50 条：满页且没到 total 就接着取下一页，取全再上货架
    loadGames(soft) {
      if (soft && this._gameAt && Date.now() - this._gameAt < FRESH_MS && (this.data.gameLoaded || this._gameBusy)) return;
      this._gameAt = Date.now();
      const seq = this._gameSeq = (this._gameSeq || 0) + 1;
      const owner = app.getUserID && app.getUserID(), scope = this.data.scope, epoch = this._shelfEpoch;
      const current = () => !this._hidden && !this._detached && seq === this._gameSeq && epoch === this._shelfEpoch
        && owner === (app.getUserID && app.getUserID()) && scope === this.data.scope;
      const failed = (res) => {
        if (!current()) return;
        this._gameBusy = false; this._gameAt = 0;
        if (this.data.gameLoaded) return;
        this.setData({ gameError: app.getRequestErrorMessage(res, '玩法模板加载失败') });
      };
      const fetchPage = (n, done) => app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/template/list',
        method: 'POST',
        data: { pageNum: n, pageSize: PAGE_SIZE, pack_type: 0 },
        success: (res) => {
          if (!current()) return;
          const data = res && res.data;
          const list = data ? (data.rows || data) : null;
          if (!res || res.code != '200' || !isRecordList(list)) { failed(res); return; }
          done(data, list);
        },
        fail: failed
      });
      // 第 1 页拿到 total 后，其余页并行取，取全再校验并上货架
      this._gameBusy = true;
      fetchPage(1, (data, first) => {
        const hasTotal = Object.prototype.hasOwnProperty.call(data, 'total');
        const total = hasTotal ? Number(data.total) : NaN;
        if (hasTotal && (!Number.isSafeInteger(total) || total < 0)) { failed(); return; }
        const pages = [first];
        const finish = () => {
          if (!current()) return;
          const rows = [], seen = new Set();
          for (const list of pages) {
            if (list.some(r => !/^[1-9]\d*$/.test(String(r.id))
                || typeof r.id === 'number' && !Number.isSafeInteger(r.id) || seen.has(String(r.id)))
                || new Set(list.map(r => String(r.id))).size !== list.length) { failed(); return; }
            list.forEach((r) => { seen.add(String(r.id)); rows.push(r); });
          }
          if (hasTotal && (rows.length > total || rows.length < total)) { failed(); return; }
          this._gameBusy = false;
          this._games = pickGames(rows);
          this.setData({ gameLoaded: true, gameError: '' });
          this.buildGames();
        };
        if (hasTotal ? first.length >= total : first.length < PAGE_SIZE) { finish(); return; }
        if (hasTotal) {
          const count = Math.ceil(total / PAGE_SIZE) - 1;
          if (count < 1) { finish(); return; } // total 与首页条数对不上：交给 finish 的校验判失败
          let left = count;
          for (let i = 0; i < count; i++) {
            fetchPage(i + 2, (d, list) => { pages[i + 1] = list; if (--left === 0) finish(); });
          }
          return;
        }
        // 无 total：只能顺序翻页
        const next = (n) => fetchPage(n, (d, list) => {
          pages.push(list);
          if (list.length >= PAGE_SIZE) next(n + 1); else finish();
        });
        next(2);
      });
    },

    retry() {
      if (this.data.tab === 'topic') this.loadTopics();
      else this.loadGames();
    },

    // ===== 标签：横滑行只放常用几个(选中的不在其中就临时插到最前),其余进「全部分类」按组列出 =====
    tagSet(groups, rowKeys, items, selected) {
      const view = (tag) => ({ key: tag.key, name: tag.name, prop: art.propSrc(tag.icon) });
      const live = groups
        .map((g) => ({ name: g.name, tags: g.tags.filter((tag) => items.some(tag.test)).map(view) }))
        .filter((g) => g.tags.length);
      const all = live.reduce((list, g) => list.concat(g.tags), []);
      const row = rowKeys.map((k) => all.find((t) => t.key === k)).filter(Boolean);
      const picked = all.find((t) => t.key === selected);
      if (picked && row.indexOf(picked) < 0) row.unshift(picked);
      return { row: row, groups: live };
    },

    // ===== 主题 =====
    topicsByNames(names) {
      return names.map((n) => this._topics.find((t) => t.key === n)).filter(Boolean);
    },

    buildTopics() {
      const all = this._topics;
      const feat = this.topicsByNames(art.FEATURED_TOPICS);
      all.forEach((t) => { if (feat.indexOf(t) < 0) feat.push(t); });
      const shelves = [];
      if (all.length) shelves.push({ title: '全部主题', rows: true, cols: columns(all, 3) });
      art.TOPIC_SHELVES.forEach((s) => {
        const items = all.filter(s[1]);
        if (items.length) shelves.push({ title: s[0], rows: false, items: items });
      });
      const featTopics = feat.slice(0, art.FEATURED_TOPICS.length);
      const featIndex = Math.min(this.data.featIndex, Math.max(featTopics.length - 1, 0));
      this.setData({ featTopics, topicShelves: shelves, featIndex });
      this.applyTopicCat(this.data.tf);
      this.applyGlow();
    },

    applyTopicCat(key) {
      const tag = art.TOPIC_TAGS.find((t) => t.key === key);
      const items = tag ? this._topics.filter(tag.test) : [];
      const tf = tag && items.length ? key : 'all';
      const set = this.tagSet(art.TOPIC_TAG_GROUPS, art.TOPIC_ROW, this._topics, tf);
      this.setData({
        tf: tf,
        topicTags: set.row,
        topicTagGroups: set.groups,
        topicCat: tf === 'all' ? null : { name: tag.name, prop: art.propSrc(tag.icon), items: items }
      });
    },

    // ===== 玩法 =====
    buildGames() {
      const all = this._games;
      const feat = art.FEATURED_GAMES.map((f) => all.find((g) => g.title === f[0])).filter(Boolean);
      const shelves = art.GROUPS
        .map((grp) => ({ title: grp.name, cols: columns(all.filter((g) => g._grp === grp.key), 3) }))
        .filter((s) => s.cols.length);
      this.setData({ featGames: feat, gameShelves: shelves });
      this.applyGameCat(this.data.gf);
    },

    applyGameCat(key) {
      const tag = art.GAME_TAGS.find((t) => t.key === key);
      const items = tag ? this._games.filter(tag.test) : [];
      const gf = tag && items.length ? key : 'all';
      const set = this.tagSet(art.GAME_TAG_GROUPS, art.GAME_ROW, this._games, gf);
      this.setData({
        gf: gf,
        gameTags: set.row,
        gameTagGroups: set.groups,
        gameCat: gf === 'all' ? null : { name: tag.name, prop: art.propSrc(tag.icon), items: items }
      });
    },

    // ===== 交互 =====
    switchTab(e) {
      const tab = ((e && e.detail) || {}).key === 'game' ? 'game' : 'topic';
      if (tab === this.data.tab) return;
      this.setData({ tab: tab, featIndex: 0 });
      this.applyGlow();
      // 成功但为空的货架：切回来时重拉一次，不让空态成为死胡同
      if (tab === 'topic' && (!this.data.topicLoaded || !this._topics.length)) this.loadTopics();
      if (tab === 'game' && (!this.data.gameLoaded || !this._games.length)) this.loadGames();
    },

    pickTag(e) {
      const key = e.currentTarget.dataset.key || 'all';
      if (this.data.tab === 'topic') this.applyTopicCat(key);
      else this.applyGameCat(key);
      if (this.data.allTagsShow) this.setData({ allTagsShow: false });
    },

    openAllTags() { this.setData({ allTagsShow: true }); },
    closeAllTags() { this.setData({ allTagsShow: false }); },

    onRailScroll(e) {
      const rpx = (e.detail.scrollLeft || 0) * 750 / (this._winWidth || (this._winWidth = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).windowWidth || 375));
      const i = Math.max(0, Math.min(Math.round(rpx / RAIL_STEP_RPX), this.data.featTopics.length - 1));
      if (i === this.data.featIndex) return;
      this.setData({ featIndex: i });
      this.applyGlow();
    },

    // 只有主题 tab 跟着当前精选封面取色;玩法 tab 不铺色(业主 v15)。标签不影响顶部。
    applyGlow() {
      const t = this.data.tab === 'topic' ? this.data.featTopics[this.data.featIndex] : null;
      const glow = t && t.glow ? t.glow : '';
      if (glow !== this.data.glow) this.setData({ glow: glow });
    },

    // 远端封面加载失败：按稳定 id 标记那一条(列表随时可能被下一轮请求换掉，不按下标猜位置)
    onCoverError(e) {
      const id = String(((e && e.currentTarget && e.currentTarget.dataset) || {}).id || '');
      const hit = id && this._topics.find((t) => String(t.id) === id);
      if (!hit || !hit.cover) return;
      hit.cover = '';
      // 弱网下多张封面连续失败：合并成一次重建
      if (this._coverTimer) return;
      this._coverTimer = setTimeout(() => { this._coverTimer = null; if (!this._detached) this.buildTopics(); }, 120);
    },

    goSearch() { this.triggerEvent('search'); },

    // 防连点：同一目标 600ms 内的重复点击不再压栈
    _dupNav(key) {
      const now = Date.now();
      if (this._navKey === key && now - (this._navAt || 0) < 600) return true;
      this._navKey = key; this._navAt = now;
      return false;
    },

    openTopic(e) {
      const item = e.currentTarget.dataset.item || {};
      if (item.previewOnly) { this.triggerEvent('toast', { text: '暂未开放' }); return; }
      if (!item.id) return;
      const qs = ['id=' + item.id];
      if (this.data.scope) qs.push('scope=' + this.data.scope);
      if (this.data.readonly) qs.push('readonly=1');
      if (this._dupNav('t' + item.id)) return;
      wx.navigateTo({ url: '/pages/templatedetail/topic-preview/index?' + qs.join('&') });
    },

    openGame(e) {
      const item = e.currentTarget.dataset.item || {};
      if (item.previewOnly) { this.triggerEvent('toast', { text: '暂未开放' }); return; }
      if (!item.id) return;
      if (this._dupNav('g' + item.id)) return;
      wx.navigateTo({ url: '/pages/templatedetail/templatedetail?id=' + item.id +
        (this.data.scope === 'MERCHANT' ? '&operationScope=MERCHANT' : '') + (this.data.readonly ? '&readonly=1' : '') });
    }
  }
});
