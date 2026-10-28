// 模板页货架(2026-10-28 业主定稿原型 v21):主题 / 玩法两个 tab,
// 顶部横滑精选 → 标签(只换标签下方内容)→ 横滑货架 / 分类页。
// 放在 templatedetail 分包、由主包模板页异步引用：包内图跟着分包走，不占主包。
const art = require('./art.js');
const { isRecordList } = require('../../../utils/response-shape.js');

const app = getApp();
const PAGE_SIZE = 50;          // 后端 startPage 单页上限 50,一次拿不全，要翻页
const MAX_PAGES = 10;
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
  const games = Number(item.templateCount || 0);
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
    tabItems: [{ key: 'topic', label: '主题', icon: '/images/tpl-tab-topic.svg' }, { key: 'game', label: '玩法', icon: '/images/tpl-tab-game.svg' }],
    glow: '',
    featIndex: 0,
    // 远端封面不可用时显示已有的明确失败态，不能拿别的业务内容图冒充模板封面
    coverErrorSrc: '/images/no_data.svg',
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
      this._topics = [];
      this._games = [];
      this.loadTopics();
      this.loadGames();
    },
    detached() {
      this._seq = (this._seq || 0) + 1;
    }
  },

  pageLifetimes: {
    // 回到模板页时静默刷新(后台改了上架不用重进小程序)
    show() {
      if (this.data.topicLoaded) this.loadTopics();
      if (this.data.gameLoaded) this.loadGames();
    }
  },

  methods: {
    loadTopics() {
      const seq = this._topicSeq = (this._topicSeq || 0) + 1;
      app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/template/topic-template/list',
        method: 'POST',
        data: {},
        success: (res) => {
          if (seq !== this._topicSeq) return;
          if (res && res.code == '200' && isRecordList(res.data)) {
            this._topics = res.data.filter((t) => art.HIDDEN_TOPICS.indexOf(art.themeKey(t.name)) < 0).map(decorateTopic);
            this.setData({ topicLoaded: true, topicError: '' });
            this.buildTopics();
          } else if (!this.data.topicLoaded) {
            this.setData({ topicError: app.getRequestErrorMessage(res, '主题模板加载失败') });
          }
        },
        fail: (res) => {
          if (seq !== this._topicSeq || this.data.topicLoaded) return;
          this.setData({ topicError: app.getRequestErrorMessage(res, '主题模板加载失败') });
        }
      });
    },

    // 后端单页最多 50 条：满页且没到 total 就接着取下一页，取全再上货架
    loadGames() {
      const seq = this._gameSeq = (this._gameSeq || 0) + 1;
      const rows = [];
      const failed = (res) => {
        if (seq !== this._gameSeq || this.data.gameLoaded) return;
        this.setData({ gameError: app.getRequestErrorMessage(res, '玩法模板加载失败') });
      };
      const page = (n) => app.sendRequest({
        hideLoading: true,
        silentError: true,
        url: '/api/template/list',
        method: 'POST',
        data: { pageNum: n, pageSize: PAGE_SIZE, pack_type: 0 },
        success: (res) => {
          if (seq !== this._gameSeq) return;
          const data = res && res.data;
          const list = data ? (data.rows || data) : null;
          if (!res || res.code != '200' || !isRecordList(list)) { failed(res); return; }
          list.forEach((r) => rows.push(r));
          const total = Number(data.total || 0);
          if (list.length >= PAGE_SIZE && !(total && rows.length >= total) && n < MAX_PAGES) { page(n + 1); return; }
          this._games = pickGames(rows);
          this.setData({ gameLoaded: true, gameError: '' });
          this.buildGames();
        },
        fail: (res) => {
          if (seq !== this._gameSeq || this.data.gameLoaded) return;
          this.setData({ gameError: app.getRequestErrorMessage(res, '玩法模板加载失败') });
        }
      });
      page(1);
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
      const featTopics = feat.slice(0, 6);
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
      if (tab === 'topic' && !this.data.topicLoaded) this.loadTopics();
      if (tab === 'game' && !this.data.gameLoaded) this.loadGames();
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
      this.buildTopics();
    },

    goSearch() { this.triggerEvent('search'); },

    openTopic(e) {
      const item = e.currentTarget.dataset.item || {};
      if (item.previewOnly) { this.triggerEvent('toast', { text: '这是预览模板，保存后才能查看' }); return; }
      if (!item.id) return;
      const qs = ['id=' + item.id];
      if (this.data.scope) qs.push('scope=' + this.data.scope);
      if (this.data.readonly) qs.push('readonly=1');
      wx.navigateTo({ url: '/pages/templatedetail/topic-preview/index?' + qs.join('&') });
    },

    openGame(e) {
      const item = e.currentTarget.dataset.item || {};
      if (item.previewOnly) { this.triggerEvent('toast', { text: '这是预览模板，保存后才能查看' }); return; }
      if (!item.id) return;
      wx.navigateTo({ url: '/pages/templatedetail/templatedetail?id=' + item.id +
        (this.data.scope === 'MERCHANT' ? '&operationScope=MERCHANT' : '') });
    }
  }
});
