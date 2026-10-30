/*
 * 修 47 项「状态没兑现」：矩阵 fixture 的字段名是猜的，页面根本没有那些字段。
 * 实测：merchant/ledger 的 fixture 写 activeTab/loading/list，页面真实字段是
 * tab/orderLoading/orders/orderError/ordersLoaded —— setData 全打空。
 *
 * 做法不是逐页手写 47 份 fixture，而是分两半：
 *   状态开关（loading/error/loaded/列表空满）→ 从页面 js 的 data 声明按命名机器生成，可靠
 *   内容字段（标题、金额、图片…）→ 沿用矩阵原 fixture，但**丢掉页面 data 里不存在的键**
 * 丢掉的键写进 dropped，避免"改完还是打空"却看不出来。
 *
 * 输出 fixtures.json: { shotId: {data, dropped:[], switches:{}} }
 */
const fs = require('fs');
const path = require('path');
const { SHOTS } = require('./shot-matrix');
const FIXTURE_ACTIONS = require('./fixture-actions.json');

const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(__dirname, 'fixtures.json');
// 生成产物绝不能反过来当输入；否则已经判定 dropped 的旧键会被下一轮偷偷带回。
// 需要行为驱动的实跑动作单独放 fixture-actions.json，矩阵仍是 data/target 单一真源。

// 这两页的 data 声明含会干扰当前轻量扫描器的注释文本，显式锁住正常态门。
// 覆盖值仍须同时写在 shot-matrix，避免矩阵 fallback 与生成 fixture 漂移。
const FIXTURE_OVERRIDES = {
  // 首页首载只点亮三段同构骨架；底部分页 loading 是另一条用户动作，不能混进首载截图。
  A02: { isBottomLoading: false },
  A08: { loadError: true, list: [], loadMoreError: '', submitError: '', submitErrorKind: '' },
  A11: {
    info: { contents: '城市的细节不会在匆忙经过时自己出现。沿着河岸慢慢走，留意门牌、砖缝、旧招牌和夜色里仍亮着的窗，把这段足够长的文字完整排进卡片，任何一个连续片段都不应该越过屏幕边缘。' },
    picList: ['/images/route_city_cover.png'],
  },
  A22: {
    searchHistory: ['外滩', '夜行'],
    hotkeys: ['交友', '美食', '运动', '读书'],
    categoryList: [], categoryLoading: false, categoryError: '',
  },
  A28: {
    searchLoading: true, showSearchSkeleton: true, activeResultType: 'all',
    resultTabs: [{ key: 'all', label: '全部' }], resultGroups: [], visibleResults: [],
  },
  A58: { pendingAuthorization: false, submitting: false, submitError: '' },
  C01: { dashboardLoading: false, joinLoading: false, hostLoading: false, todoLoading: false },
  C03: { consoleError: '工作台暂时无法加载', dashboardError: '' },
  B66: {
    loaded: true, loadFail: false, partialFail: false, glFail: false, viewMode: 'list',
    badges: [{ code: 'FIRST_STEP', family: 'identity', name: '开始在场', unlocked: true }],
    badgeGroups: [{ key: 'identity', title: '', items: [{ code: 'FIRST_STEP', family: 'identity', name: '开始在场', unlocked: true }] }],
  },
  C13: { marketingLoaded: true, marketingDataState: 'empty', couponCard: null, entries: [], deckGhosts: 0, couponRate: '' },
  C21: {
    loadState: 'error', loadErrTitle: '店铺装修加载失败',
    loadErrSub: '暂时没有加载出来，请稍后重试', saveError: '', featuredError: '',
  },
  C27: {
    fromMerchantJoin: true,
    info: {
      name: '苏河湾夜行', imgUrl: '/images/route_city_cover.png',
      description: '沿苏州河完成两段城市观察。',
      chaptersList: [{ name: '第一章', nodes: [{ name: '河畔咖啡', cmsMemberTemplate: { title: '找到桥下的灯' } }] }],
    },
  },
  E02: {
    isMerchantViewer: true, loading: false, loadErr: true, loadErrKind: 'network',
    loadErrTitle: '网络没连上', loadErrSub: '活动详情还没加载出来，连接恢复后可以重试。',
    loadErrAction: '重试', loadErrActionType: 'retry',
  },
  // relation 的正常正文走 wx:else；源码只显式比较 loading/error，通用枚举收窄无法
  // 推断 ready，但页面请求成功会明确写入 ready。锁住正文态，避免生成空字符串靠 falsy 偶然放行。
  C14: { pageState: 'ready' },
  C15: { pageState: 'ready' },
  C22: { loading: false, loaded: true, loadState: 'ready' },
  C12c: { factState: 'ready', aiError: '智能解读暂不可用' },
  C28: {
    pendingCount: 5,
    verifiedCount: 3,
    totalCount: 8,
    allNodesList: [],
    allChaptersList: [],
    myChapterApplications: [],
    myChapterNodes: [],
    chapterRecruitmentState: 'idle',
    coopState: 'idle',
  },
  B11: { state: 'error', errMsg: '暂时无法生成核销码，请稍后重试' },
  B23: {
    pageState: 'ready',
    participantState: 'ready',
    hostShareChecked: true,
    hostShareConsentReady: true,
    quoteReady: true,
    paymentReady: true,
    paymentReadinessChecking: false,
    canPay: true,
    isPaying: false,
    submitError: '报名服务暂时不可用，请稍后重试',
    waitlistError: '',
    loadErrorMsg: '',
    participantErrorMsg: '',
  },
  D18: { entryState: 'ready', qrState: 'error', errMsg: '二维码暂时没能生成，请稍后重试', pollError: '' },
  D10: { firstLoading: false, errorMsg: '参与记录加载失败，请重试', list: [] },
  B64: { error: true, items: [] },
  F16: { startText: '18:30', endText: '21:00', drillResult: [] },
  // growthcenter 在 Page() 之前有 HTTP 失败对象 `data: {}`，当前轻量扫描器会先命中它。
  // 显式覆盖仍与 shot-matrix 的 B68 同值，避免把旧 stats/badges 键静默丢成空页。
  B68: {
    overviewState: 'ready',
    overviewLoaded: true,
    overviewError: '',
    overview: {
      levelText: 'Lv.6 城市探索者',
      stats: [
        { key: 'explore', label: '探索值', value: '1,280', unit: '' },
        { key: 'badge', label: '徽章', value: '6', unit: '枚' },
        { key: 'topics', label: '完成路线', value: '3', unit: '条' },
        { key: 'distance', label: '探索距离', value: '42.6', unit: 'km' },
      ],
    },
    badges: [{ code: 'FIRST_STEP', name: '初探', initial: '初', unlockText: '2026-08-18' }],
    badgeCountText: '1',
    badgeLoadError: false,
    showBadges: true,
    showBadgeEmpty: false,
  },
  B75: { errorMsg: '排行榜没有加载出来' },
  // 主题 normal/route 由矩阵显式提供 info；通用列表推断不能再凭 WXML 里其它
  // wx:for 造出「示例label/value」评价卡，否则截图虽然不黑，仍是假产品内容。
  C23: { routeReady: false, detailFacts: [], ratingRows: [], ratingTotal: 0, starsBox: [], uploadImages: [] },
  C24: { routeReady: false, detailFacts: [], ratingRows: [], ratingTotal: 0, starsBox: [], uploadImages: [] },
  // Tag Picker 的矩阵正常态明确允许 3 选 2；通用数字补全会把 num 猜成 1，
  // 造成截图里“上限 1 个却已选 2 个”的不可能状态。
  B54: { num: 3 },
};

// 游戏填充态的每个字段都参与可见玩法分支；通用列表/计数补全会制造假提示、假计数。
// 这些页直接以 shot-matrix 为单一真源，组件目标的玩法截图本来就走同一条精确路径。
const EXACT_MATRIX_FIXTURES = new Set(['F42', 'F43', 'F44', 'F45', 'F46', 'F51', 'F52']);

// 取 `data: { ... }` 的顶层键。手写括号配对，因为值里有对象/数组/模板串。
function pageDataKeys(route) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.js');
  if (!fs.existsSync(f)) return null;
  const src = fs.readFileSync(f, 'utf8');
  const m = /\n\s*data\s*:\s*\{/.exec(src);
  if (!m) return null;
  let i = m.index + m[0].length;
  let depth = 1;
  const start = i;
  for (; i < src.length && depth > 0; i += 1) {
    const c = src[i];
    if (c === '{' || c === '[') depth += 1;
    else if (c === '}' || c === ']') depth -= 1;
    else if (c === "'" || c === '"' || c === '`') { // 跳过字符串，里面的括号不算
      const q = c;
      i += 1;
      while (i < src.length && src[i] !== q) i += src[i] === '\\' ? 2 : 1;
    }
  }
  const body = src.slice(start, i - 1);
  const keys = new Map();
  let d = 0;
  for (let j = 0; j < body.length; j += 1) {
    const c = body[j];
    if (c === '{' || c === '[') d += 1;
    else if (c === '}' || c === ']') d -= 1;
    else if (c === "'" || c === '"' || c === '`') { const q = c; j += 1; while (j < body.length && body[j] !== q) j += body[j] === '\\' ? 2 : 1; }
    else if (d === 0 && /[A-Za-z_$]/.test(c)) {
      const rest = body.slice(j);
      const km = /^([A-Za-z_$][\w$]*)\s*:/.exec(rest);
      if (km) {
        // 值的头几个字符决定类型：[ 数组、{ 对象、true/false 布尔
        const vm = /^[A-Za-z_$][\w$]*\s*:\s*(\[|\{|true|false|'|"|`|[-\d])/.exec(rest);
        // 数组再看一眼元素形态：['a','b'] 是字符串数组。占位给 {id:1} 会渲染成
        // 「[object Object]」—— publish/simple 的 aiPrompts 就是这么拍出来的，
        // 看图时像页面 bug，其实是 fixture 塞错了类型。
        let t = vm ? vm[1] : '?';
        if (t === '[' && /^[A-Za-z_$][\w$]*\s*:\s*\[\s*['"`]/.test(rest)) t = "['";
        keys.set(km[1], t);
        j += km[0].length - 1;
      }
    }
  }
  // ⚠️ 只扫 data: {} 声明会漏掉「运行时才出现」的字段：square/list 的 loadError
  // 从没在 data 里声明过，只在 setData({ loadError: ... }) 里出现，于是 error 态
  // 的注入被当成「页面没有这个字段」丢弃，拍出来和 skeleton 态一样。
  // 补扫 setData 的字面量键名，类型按赋的值猜。
  for (const m of src.matchAll(/setData\(\s*\{([^}]*)\}/g)) {
    for (const km of m[1].matchAll(/(?:^|[,{\s])([A-Za-z_$][\w$]*)\s*:\s*(\[|\{|true|false|'|"|`|[-\d])/g)) {
      if (!keys.has(km[1])) keys.set(km[1], km[2]);
    }
  }
  return keys;
}

const IMG_PLACEHOLDER = '/images/route_city_cover.png';
const isArr = (v) => v === '[' || v === "['";
const isStrArr = (v) => v === "['";
// 三态权限门：null=校验中 / true=可看 / false=无权限。club/enroll 的 hasPermission
// 就是这种，初值 null。生成器早先只认布尔与字符串，于是 normal/empty 两态都卡在
// 「校验中」这个门后面，和 permission 态拍出同一张图 —— 看着像页面 bug，实际是
// fixture 没过门。除 permission 态外一律放行。
// 状态机字段：loadState/pageState/listState/aiState 这类，值是字符串枚举
// ('loading' | 'ready' | 'error' | 'empty')，wxml 用 wx:if="{{loadState === 'error'}}"
// 分支。生成器原本只认名字带 err/loading/loaded 的字段，这类**名字里没有那些词**，
// 于是 error/empty 态的门根本没被打开，拍出来和 normal 态一模一样。
// 2026-08-05 实测 decor/mycanyu/infomation 等都是这么撞 md5 的。
// ⚠️ 白名单前缀必然漏：square/detail 的 detailState 就不在 (load|page|list|ai|view|data) 里，
// 于是 loading/error 两态的门根本没打开、拍出来和 normal 一样。改成「任何 xxxState / state」，
// 靠命名约定而不是穷举。real estate 那种 estate 结尾用 [a-z]+State 的大写 S 挡掉。
const isStateMachine = (k) => /^state$/.test(k) || /^[a-zA-Z]+State$/.test(k);
const STATE_ENUM = { loading: 'loading', error: 'error', empty: 'empty', normal: 'ready', scrolled: 'ready', filter: 'ready', success: 'ready' };
// ⚠️ 不是所有 xxxState 都用 'ready' 表示正常。merchant/index 的 consoleState 是
// **异常标记**：wx:if="{{consoleState}}" 一旦非空就进「未登录 / 加载失败」闸，
// 正常态是空字符串。一律给 'ready' 会把好好的工作台拍成「工作台没能加载出来」。
// ⇒ 先从 wxml 收集这个字段真正被比较的枚举值，想给的值不在枚举里就给 ''
//   —— 空字符串既让所有 === 比较不成立，又是 falsy，两种约定都安全。
function stateEnumFor(route, key) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  if (!fs.existsSync(f)) return null;
  const wxml = fs.readFileSync(f, 'utf8');
  // 字段被当**属性传给子组件**时（state="{{state}}"），值域在组件内部，wxml 里
  // 当然搜不到 === 'error' 这样的比较。收窄会把合法值改没：citynode-code 把
  // state 整个交给 cy-qr-voucher，组件按它显示 ready/error 文案。⇒ 不收窄。
  // ⚠️ 判「是不是属性绑定」要先把 wx: 指令剥掉 —— wx:if="{{consoleState}}" 同样是
  // ="{{...}}" 形式，不剥的话每个裸真值门都被当成属性传递放行，收窄逻辑等于没写。
  // 而裸真值门恰恰是最需要收窄的那种：wx:if="{{consoleState}}" 意味着「非空即异常」，
  // 正常态必须是空字符串。
  const noDirectives = wxml.replace(/wx:(if|elif|for|for-items)\s*=\s*"[^"]*"/g, '');
  if (new RegExp('=\\s*"\\{\\{\\s*' + key + '\\s*\\}\\}"').test(noDirectives)) return null;
  const re = new RegExp('\\b' + key + "\\s*===?\\s*['\"]([^'\"]+)['\"]", 'g');
  const out = new Set([...wxml.matchAll(re)].map((m) => m[1]));
  return out.size ? out : null;
}
const stateValue = (route, key, want) => {
  const en = stateEnumFor(route, key);
  return (!en || en.has(want)) ? want : '';
};
// 「上下文就绪」闸：merchantapply2 的 applicationContextReady 为假就整页显示
// 「无法继续报名，缺少完整报名上下文」—— 表单一个字段都不渲染。
// 这类闸名字里没有 perm/auth，isPermGate 认不出，但语义一样：不放行就什么都看不到。
const isReadyGate = (k) => /(ContextReady|Ready|Initialized|Prepared)$/.test(k) || /^(is|has)?(context|init)Ready$/i.test(k);
const isPermGate = (k) => /^(has|can|is)[A-Z].*(permission|perm|auth|access)$/i.test(k) || /^hasPermission$/.test(k);
// 页面骨架里的数组（tab 条、筛选项、选项组、菜单），不是内容列表
// 「计数」字段：total / count / num / xxxCount。只填列表不填计数会造出**矛盾状态**：
// stamp-album 的 fixture 给了 items:[1 条] 却没给 total，total 停在初值 0 ⇒
// wx:elif="{{loaded && total === 0}}" 成立，那张邮票和空态**同时渲染**，
// 看图时像「空态插画左对齐、排版错位」，实际是 fixture 自己造的矛盾。
const isCount = (k) => /^(total|count|num)$/i.test(k) || /(Count|Total|Num)$/.test(k);
const isChrome = (k) => /tabs?$|tabs?[A-Z]|^tabs|filter|options?$|menu|nav|chips?$|steps?$|segments?/i.test(k);
const nameIs = (k, re) => re.test(k);
// 「零行」标志：列表页的 nodata 与 loadError 常互斥（「拿不到」和「没有」不是一回事），
// 名字里既没有 err 也没有 loading，空态的门于是从没被打开、cy-empty 拍不出来。
const isNoData = (k) => /^(no|has)?(data|result|record|item)s?$/i.test(k) && /^(no|nodata|noData|isEmpty|empty)/i.test(k);
// 嵌套门：index 页的 listErr 是**对象**（listErr.nearbyActivityList），pointsSheet.loading
// 同理。名字匹配 err/loading 但声明是 {}，给布尔等于没给 —— 门后的 cy-error 永远不亮。
// 子键从 wxml 里现取，别猜。
function nestedKeys(route, root) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  if (!fs.existsSync(f)) return [];
  const re = new RegExp('\\b' + root.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\.([A-Za-z_$][\\w$]*)', 'g');
  return [...new Set([...fs.readFileSync(f, 'utf8').matchAll(re)].map((m) => m[1]))];
}

// 列表项要填哪些字段，同样直接问 wxml：扫 item.xxx 的实际用法。
// ⚠️ 靠一张固定的「常见字段表」补占位是补不完的：广场帖文用 content、
// 玩家卡用 nickName、票券用 validDate…… 缺一个字段那块就渲染成空白，
// 看图时像「卡片空了」的 UI 缺陷。列表项字段只有 wxml 知道。
function itemFieldsFor(route) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  if (!fs.existsSync(f)) return {};
  const wxml = fs.readFileSync(f, 'utf8');
  const out = {};
  for (const m of wxml.matchAll(/\bitem\.([A-Za-z_$][\w$]*)/g)) {
    const k = m[1];
    if (out[k] !== undefined) continue;
    if (/^(id|index)$/i.test(k)) { out[k] = 1; continue }
    if (/(img|image|cover|avatar|logo|pic|photo|thumb|banner|url)$/i.test(k)) { out[k] = IMG_PLACEHOLDER; continue }
    if (/(count|num|qty|total|score|points?|progress|level|days?)$/i.test(k)) { out[k] = 3; continue }
    if (/^(is|has|can|show)[A-Z]/.test(k)) { out[k] = false; continue }
    if (/(list|items|tags|imgList|arr)$/i.test(k)) { out[k] = []; continue }
    if (/(amount|price|money|fee)/i.test(k)) { out[k] = '69.00'; continue }
    if (/(time|date|at$)/i.test(k)) { out[k] = '08-05 10:00'; continue }
    out[k] = '示例' + k;
  }
  return out;
}

// 列表该填哪个字段，不能靠名字猜：ledger 有 orders 和 visibleOrders 两个数组，
// wxml 里 wx:for 绑的是 visibleOrders，填 orders 等于没填。直接问 wxml。
function forSources(route) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  if (!fs.existsSync(f)) return [];
  const wxml = fs.readFileSync(f, 'utf8');
  const out = new Set();
  for (const m of wxml.matchAll(/wx:for\s*=\s*"\{\{\s*([A-Za-z_$][\w$.]*)\s*\}\}"/g)) {
    out.add(m[1].split('.')[0]);
  }
  return [...out];
}

// form 态光设 loading=false 没用：merchant/apply 的 <input> 挂在
// wx:if="{{mode==='form' && step===1}}" 下，不把 mode/step 调对，页面停在
// status 分支，input 一个都不渲染 —— 断言红，但红的是 fixture 不是页面。
// 从包裹 input 的那个 wx:if 反解出该设哪些字段。
function formGate(route, keys) {
  const f = path.join(ROOT, route.split('?')[0].replace(/^\//, '') + '.wxml');
  if (!fs.existsSync(f)) return {};
  const lines = fs.readFileSync(f, 'utf8').split('\n');
  const firstInput = lines.findIndex((l) => /<input|<textarea/.test(l));
  if (firstInput < 0) return {};
  const out = {};
  for (let i = firstInput; i >= 0; i -= 1) {
    const m = /wx:(?:if|elif)\s*=\s*"\{\{([^}]*)\}\}"/.exec(lines[i]);
    if (!m) continue;
    for (const eq of m[1].matchAll(/([A-Za-z_$][\w$]*)\s*===?\s*(?:'([^']*)'|"([^"]*)"|(\d+))/g)) {
      const [, k, s1, s2, num] = eq;
      if (keys.has(k) && !(k in out)) out[k] = num !== undefined ? Number(num) : (s1 !== undefined ? s1 : s2);
    }
    if (Object.keys(out).length) break; // 只取最近那层门，再往上是无关的外层条件
  }
  return out;
}

function switchesFor(keys, state, listSrc, matrixArrays, activeTabValue, route, itemFields) {
  const out = {};
  for (const [k, v] of keys) {
    const loading = nameIs(k, /loading/i);
    // 只匹配结尾会漏掉 errorMsg：mylike 的 <cy-error wx:if="{{errorMsg}}"> 因此
    // 一直亮着，normal 态的 forbid 断言恒红。名字里带 err 就算，值按声明类型给
    // —— 字符串型必须给 ''，给 false 一样是 truthy 之外的坑（'' 才清得掉）。
    // ⚠️ 只认 err 会漏 fail：badge-wall 的 loadFail / partialFail / glFail 三个
    // 都没被匹配，normal 态一个都没关掉，wx:if="{{loadFail || partialFail || glFail}}"
    // 恒真 ⇒ 整页拍成错误态、forbid 断言红。
    const err = nameIs(k, /err|fail/i);
    const loaded = nameIs(k, /loaded/i);
    const arr = isArr(v);
    const isStr = v === "'" || v === '"' || v === '`';
    const isObj = v === '{';
    // 声明是对象时，布尔值塞进去等于没塞（listErr.nearbyActivityList 恒 undefined）。
    // 把子键从 wxml 取出来，整份对象一起给。
    const objOf = (val) => Object.fromEntries(nestedKeys(route || '', k).map((n) => [n, val]));
    const off = isObj ? objOf(false) : isStr ? '' : false;
    const on = isObj ? objOf(true) : isStr ? '出错了' : true;
    if (state === 'empty') {
      if (isPermGate(k) || isReadyGate(k)) { out[k] = true; continue }
      if (isStateMachine(k)) { out[k] = stateValue(route, k, STATE_ENUM['empty']); continue }
      // 空态只清「内容」，不能连导航一起清：order 页的 orderTabs 也是数组，
      // 一并清空后整排 tab 从截图里消失，看上去像页面 bug —— 实际是 fixture
      // 造的假象。差点据此报了个不存在的缺陷。
      if (isNoData(k)) { out[k] = true; continue }
      if (isCount(k)) { out[k] = 0; continue }
      if (loading) out[k] = off;
      else if (err) out[k] = off;
      else if (loaded) out[k] = true;
      else if (arr && !isChrome(k)) out[k] = [];
    } else if (state === 'loading') {
      if (isPermGate(k) || isReadyGate(k)) { out[k] = true; continue }
      if (isStateMachine(k)) { out[k] = stateValue(route, k, STATE_ENUM['loading']); continue }
      if (loading) out[k] = isObj ? objOf(true) : (isStr ? '加载中' : true);
      else if (err) out[k] = off;
      else if (loaded) out[k] = false;
    } else if (state === 'error') {
      if (isPermGate(k) || isReadyGate(k)) { out[k] = true; continue }
      if (isStateMachine(k)) { out[k] = stateValue(route, k, STATE_ENUM['error']); continue }
      if (loading) out[k] = off; else if (err) out[k] = on; else if (loaded) out[k] = true;
    } else if (['normal', 'scrolled', 'filter', 'success', 'form', 'keyboard', 'long-value'].includes(state)) {
      // ⚠️ form/keyboard/long-value 原本一个分支都不进：baoming 的 pageState 停在
      // 初值 'loading'，整个表单区 wx:if="{{pageState === 'ready'}}" 不渲染，
      // 拍出来是骨架屏。它们本质都是「内容态的变体」，状态机该给 ready。
      if (isPermGate(k) || isReadyGate(k)) { out[k] = true; continue }
      if (isStateMachine(k)) { out[k] = stateValue(route, k, STATE_ENUM[state] || 'ready'); continue }
      if (isNoData(k)) { out[k] = false; continue }
      // 内容态：计数要跟占位列表对得上（占位给 1 条），否则页面会同时渲染内容和空态
      if (isCount(k)) { out[k] = 1; continue }
      if (loading) out[k] = off;
      else if (err) out[k] = off;
      else if (loaded) out[k] = true;
      // 内容态必须有内容，否则页面回落到空态、forbid 断言红。优先用矩阵原
      // fixture 里那份有真实感的 mock，没有才退到占位对象。
      //
      // ⚠️ tab 页要**只填当前 tab 的列表**：relation 页有 clubList/merchantList/
      // customerList 三个数组，三张图的差别全在 activeTab。第一版把三个都填满，
      // 切 tab 也长得一样 —— 三张图 md5 撞在一起，看着像页面 bug，其实是 fixture
      // 没把状态区分开。另外 matrixArrays.shift() 是**按顺序**取的，不看字段语义，
      // 会把 club 的 mock 发给 customerList。
      else if (arr && listSrc.includes(k)) {
        // tab 页只填当前 tab 的列表。⚠️ 判据必须是「这个字段名确实以某个 tab 的
        // key 开头」，不能只看它像不像列表名：ledger 的 visibleOrders 以 s 结尾、
        // activeTab 是 'order'，第一版规则把它判成「别的 tab 的列表」清空了，
        // 于是 normal 态渲染出空态、和 empty 态撞 md5。
        // 只有当**页面里确实存在多个以不同 tab key 打头的列表**时，这条才该生效。
        const tabKeyPrefix = activeTabValue ? String(activeTabValue).toLowerCase() : null;
        const siblings = tabKeyPrefix
          ? [...keys.keys()].filter((n) => isArr(keys.get(n)) && listSrc.includes(n))
          : [];
        const looksTabbed = tabKeyPrefix && siblings.length > 1
          && siblings.some((n) => n.toLowerCase().startsWith(tabKeyPrefix));
        if (looksTabbed && !k.toLowerCase().startsWith(tabKeyPrefix)) out[k] = [];
        // ⚠️ 占位只给 {id:1} 会把卡片渲染成空壳（标题/金额/图全 undefined），
        // 看图时像「卡片空白」的缺陷 —— A32「我的订单」那个空矩形、A05 广场那张
        // 只剩头像和点赞图标的帖子，都是这么来的。字段从 wxml 现取，别靠猜。
        else out[k] = matrixArrays.shift() || (isStrArr(v) ? ['示例灵感一', '示例灵感二']
          : [{ ...(itemFields || {}), id: 1 }]);
      }
    }
  }
  return out;
}

const fixtures = {};
const stats = { total: 0, noPage: 0, droppedKeys: 0, shotsWithDrop: 0 };
for (const s of SHOTS) {
  if (s.blocked) continue;
  stats.total += 1;
  // 深链壳本身没有正文状态；fixture 的字段属于壳内唯一正文组件。
  // 这类行由矩阵显式给出组件 selector 与真实 data，不能再拿页面 data 键过滤，
  // 否则 D07 的订单字段、D22/D28/D29/D33 的列表字段会全部被静默丢掉。
  if (s.fixtureTarget) {
    fixtures[s.id] = { data: s.data || null, target: s.fixtureTarget, dropped: [], switchKeys: [],
      ...(FIXTURE_ACTIONS[s.id] ? { calls: FIXTURE_ACTIONS[s.id] } : {}),
    };
    continue;
  }
  if (EXACT_MATRIX_FIXTURES.has(s.id)) {
    fixtures[s.id] = { data: s.data || null, dropped: [], switchKeys: [] };
    continue;
  }
  const keys = pageDataKeys(s.route);
  if (!keys) {
    stats.noPage += 1;
    fixtures[s.id] = {
      data: s.data || null,
      dropped: [],
      switchKeys: [],
      ...(FIXTURE_ACTIONS[s.id] ? { calls: FIXTURE_ACTIONS[s.id] } : {}),
    };
    continue;
  }
  const dropped = [];
  const kept = {};
  for (const [k, v] of Object.entries(s.data || {})) {
    // 'form.id' 这种路径式 key 只看根段
    const rootKey = k.split('.')[0];
    if (keys.has(rootKey)) kept[k] = v; else dropped.push(k);
  }
  const matrixArrays = Object.values(s.data || {}).filter((v) => Array.isArray(v) && v.length);
  // 当前激活的是哪个 tab —— 用来决定只填哪一个列表（见 switchesFor 里的说明）
  // 矩阵里写的 tab 字段名多半是猜的（relation 页矩阵写 activeTab，页面真实字段是
  // activeTopTab）。所以取值来自矩阵，但**落到页面真实存在的那个键**上。
  const tabKey = Object.keys(s.data || {}).find((k) => /^(activeTab|tab|active)/i.test(k));
  // ⚠️ 别限定「矩阵写的是字符串才算」：mylike 矩阵写的是数字 2、页面声明是字符串 '0'，
  // 限定成字符串后 activeTabValue 直接为 null，转换逻辑压根没跑，kept 里那个数字 2
  // 原样留下 ⇒ wx:if="{{activeTab === '2'}}" 不成立，三个 tab 一个都不渲染、
  // 空态的 cy-empty 也就找不到。取任意标量，类型交给下面按页面声明转。
  // 目标键：矩阵写的字段名页面真有就用它（talent/list 的 activeTopTab），没有才
  // 接到页面真实的那个 tab 字段上（ledger 矩阵写 activeTab、页面字段是 tab）。
  // ⚠️ 「页面有这个键」不等于「不用管」：mylike 页面确实有 activeTab，但声明是
  //   字符串 '0'、矩阵写的是数字 2 —— 早先据此跳过处理，kept 里那个数字原样留下，
  //   wx:if="{{activeTab === '2'}}" 不成立、三个 tab 一个都不渲染。
  //   要做的是**按目标键的声明类型转换**，不是决定要不要插手。
  const rawTab = tabKey ? s.data[tabKey] : undefined;
  const activeTabValue = (typeof rawTab === 'string' || typeof rawTab === 'number') ? rawTab : null;
  const realTabKey = [...keys.keys()].find((k) => /^active.*tab$|^tab$|^activeTab$/i.test(k));
  const sw = switchesFor(keys, s.state, forSources(s.route), matrixArrays, activeTabValue, s.route, itemFieldsFor(s.route));
  // form 门放在 sw 之前合并：矩阵若显式给了 step，以矩阵的为准（它指定了第几步）
  const gate = ['form', 'keyboard', 'long-value'].includes(s.state) ? formGate(s.route, keys) : {};
  // 状态开关放后面：内容里若也带了同名开关，以按页面真实语义算出来的为准
  // 矩阵给了 tab 值、页面又确实有 tab 字段时，把值接到真实字段上
  // ⚠️ 别无脑把矩阵的 tab 值塞给页面字段：ledger 的 tab 是**数字** 0-3，矩阵写的是
  // 'order' 这种字符串，塞进去后 wx:if="{{tab===0}}" 不成立、整个订单区不渲染，
  // 空态跟着消失 —— 拍出来和 normal 态一模一样。按页面 data 声明的类型决定塞不塞。
  // ⚠️ 类型要**按页面声明转换**，不是「不匹配就不塞」。两个方向都栽过：
  //   ledger 的 tab 声明是数字 0-3，矩阵写 'order' ⇒ wx:if="{{tab===0}}" 不成立；
  //   mylike 的 activeTab 声明是字符串 '0'，矩阵写 2   ⇒ wx:if="{{activeTab==='0'}}" 不成立。
  // 后者当初被 tabIsNumeric 判成「非数字、可以塞」，塞进去还是错类型。
  const tabTargetKey = (tabKey && keys.has(tabKey)) ? tabKey : realTabKey;
  const tabDecl = tabTargetKey ? keys.get(tabTargetKey) : null;
  const tabIsNumeric = !!tabDecl && /^[-\d]/.test(tabDecl);
  const tabIsStr = tabDecl === "'" || tabDecl === '"' || tabDecl === '`';
  let tabValue = null;
  if (activeTabValue != null && tabTargetKey) {
    if (tabIsNumeric) tabValue = /^-?\d+$/.test(String(activeTabValue)) ? Number(activeTabValue) : null;
    else if (tabIsStr) tabValue = String(activeTabValue);
    else tabValue = activeTabValue;
  }
  // 转不过去要出声，别静默丢：ledger 矩阵写 activeTab:'settle'、页面字段是数字 tab，
  // 转换失败后 tab 停在初值 0（订单），而订单列表是空的 ⇒ 拍出空态、forbid 断言红。
  // 静默丢的后果是「看起来像页面 bug」，查到矩阵才知道是数据没对上。
  if (activeTabValue != null && tabTargetKey && tabValue == null) {
    console.log(`  ⚠️ ${s.id} tab 值转不过去：矩阵给 ${JSON.stringify(activeTabValue)}，`
      + `页面 ${tabTargetKey} 声明是${tabIsNumeric ? '数字' : tabIsStr ? '字符串' : '其它'} —— 改矩阵`);
  }
  const tabPatch = tabValue != null ? { [tabTargetKey]: tabValue } : {};
  // ⚠️ sw 覆盖 kept 只该管布尔开关（loading/error）。数组不行：A15 矩阵显式写了
  // nearbyClubs: [苏州河骑行社]，switchesFor 自己挑中 clubMembers 当「主列表」，
  // 把样本塞给它、顺手把 nearbyClubs 清成 [] —— content 态于是拍成空态，跟
  // A16(empty) 撞 md5。矩阵里的数组是人按页面结构写的，比推断准，回写盖回去。
  const keptArrays = Object.fromEntries(Object.entries(kept).filter(([, v]) => Array.isArray(v)));
  // ⚠️ 矩阵**显式写了值**的状态机字段，不许被 switchesFor 的通用规则盖掉：
  // passport 的空态要 loadState='ready'（它的空态在 wx:else 那支里，
  // 上层 wx:if 是 loadState=='error'），而通用规则见 'empty' 不在该页枚举里就给 ''，
  // 结果空态永远走不到。人写的比推的准 —— 跟 keptArrays 同一个道理。
  const keptStates = Object.fromEntries(
    Object.entries(kept).filter(([k, v]) => isStateMachine(k) && typeof v === 'string'));
  const data = { ...gate, ...kept, ...sw, ...tabPatch, ...keptArrays, ...keptStates, ...(FIXTURE_OVERRIDES[s.id] || {}) };
  // B59 的顶栏右侧避让由运行时微信胶囊坐标计算；fixture 只能保留实算值，不能把
  // data 初值 false 当作普通状态开关回灌，否则截图会制造一条并不存在的顶栏错位。
  if (s.id === 'B59') {
    delete data.headerRightInset;
    delete sw.headerRightInset;
  }
  if (dropped.length) { stats.droppedKeys += dropped.length; stats.shotsWithDrop += 1; }
  // calls 驱动的截图靠产品方法进入目标态；再注入通用 total/doneCount 等开关会
  // 提前伪造完成事实。此类夹具只执行显式 action，并只允许定点 override 覆盖。
  const calls = FIXTURE_ACTIONS[s.id];
  const generatedData = calls ? (FIXTURE_OVERRIDES[s.id] || {}) : (Object.keys(data).length ? data : null);
  const finalDropped = dropped.filter((key) => !generatedData || !Object.prototype.hasOwnProperty.call(generatedData, key));
  fixtures[s.id] = {
    data: generatedData,
    dropped: calls ? [] : finalDropped,
    switchKeys: calls ? [] : Object.keys(sw),
    ...(calls ? { calls } : {}),
  };
}
fs.writeFileSync(OUTPUT, JSON.stringify(fixtures, null, 1));
console.log(JSON.stringify(stats));
