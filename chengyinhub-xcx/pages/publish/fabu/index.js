const templateAdoption = require('../utils/publish/template-adoption.js'); const checkContext = require('../utils/publish/check-context.js');
const { readReducedMotion } = require('../../../utils/motion-preference.js');
const modal = require('../../../utils/modal.js');
const cyLoading = require('../../../utils/loading.js');
const cyToast = require('../../../utils/toast.js');
const { toTimestamp } = require('../../../utils/datetime.js');
let app = getApp();
const { memberId, failureStatus, retryLogin, waitForInitialSession, recoverReadSession } = require('../../../utils/session/login-reload.js');
const AI_DRAFT_LEGACY_KEY = 'ai_topic_draft';
function aiDraftStorageKey() {
  const memberId = app.getUserID && app.getUserID();
  return memberId === null || memberId === undefined || memberId === ''
    ? '' : AI_DRAFT_LEGACY_KEY + ':m' + memberId;
}
function readAiDraft() {
  wx.removeStorageSync(AI_DRAFT_LEGACY_KEY);
  const key = aiDraftStorageKey();
  return key ? wx.getStorageSync(key) : null;
}
function writeAiDraft(draft) {
  wx.removeStorageSync(AI_DRAFT_LEGACY_KEY);
  const key = aiDraftStorageKey();
  if (key) wx.setStorageSync(key, draft);
}
const supportMatrix = require('../utils/publish/gameplay-support-matrix.js');
const analytics = require('../../../utils/analytics.js');
const roleGuard = require('../../../utils/roleGuard.js');
const { createErrorBag, nonEmpty } = require('../utils/publish/publish-validator.js');
const { cityOrientationScheduleIssues, freeExplorationSaleScheduleIssues, freeExplorationValidityScheduleIssues } = require('../utils/publish/publish-ticket-schedule.js');
const { createPublishWorkflow } = require('../utils/publish/publish-workflow.js');
const { createServerDraftSaver, topicIdOf, confirmedPublication } = require('../utils/publish/server-draft.js');
const publishIntent = require('../../../utils/publish/publish-intent.js');
const proEditorPolicy = require('../utils/publish/pro-editor-policy.js');
const proEditorDraft = require('../utils/publish/pro-editor-draft.js');

function emptyChapterForm(name = '', opening = false) {
  return {
    name,
    opening,
    ending: null,
    description: '',
    imgArr: '',
    nodes: [],
    calculatedDistance: 0,
    category: '',
    categoryId: null,
    recruitEnabled: 0,
    termsMode: 'PERK',
    perkMinValue: null,
    maxPerHeadFee: null,
    maxMerchant: 0,
    atmospherePreset: 'DEFAULT',
    // 章节背景旁白,一章最多一段(2026-09-04 拍板)。玩家进入本章自动播放。
    audioUrl: '',
    audioDuration: 0,
    audioFileName: '',
    // 玩法边界:空 = 不限(存量语义)。承接通过时冻进 offer,之后按冻结的那份校验。
    allowedValidationMethods: '',
    maxNodeXp: null
  };
}

const proEditorMaterials = require('../utils/publish/pro-editor-materials.js');
const proEditorStory = require('../utils/publish/pro-editor-story.js');
const { buildTopicPreview } = require('../utils/publish/topic-draft-preview.js');
const publishStats = require('../utils/publish/publish-stats.js');
const publishDate = require('../utils/publish/publish-datetime.js');
const { pickLocation } = require('../../../utils/location/location-manager.js');
const subscribe = require('../../../utils/subscribe.js');
const { TOPIC_EDITOR_SWITCH_COLOR } = require('../../../utils/play-visual-tokens.js');
const { resolveMenuChrome } = require('../../../utils/nav-safe-area.js');
const { PRESETS: CHAPTER_ATMOSPHERES, normalizeAtmosphere } = require('../../../utils/chapter-atmosphere.js');
// 氛围块用的配色 = 章节那套(2026-09-06 用户拍板的黑/蓝/红/黄/白纯色,无渐变),
// 不另立一份名单 —— mood 块只是把「整章一个颜色」变成「段落级可切换」。
const MOOD_PRESET_KEYS = CHAPTER_ATMOSPHERES.map(function (item) { return item.value; });
// 思维阁:从 journeyRules 里数出配了几个念头。读不出来就当没配 —— 入口卡的副标题
// 不值得为一份坏配置把整页拦住,真正的闸在保存与发布。
function thoughtListOf(raw) {
  try {
    const rules = typeof raw === 'string' ? (raw.trim() ? JSON.parse(raw) : {}) : (raw || {});
    return Array.isArray(rules.thoughts) ? rules.thoughts : [];
  } catch (e) { return []; }
}

/* 伏笔的下拉选项与名字表一起算。名字表给编辑器里的伏笔块显示「那扇窗」而不是随机键「tmud5hgwt」。
   ⚠️ 三条路都要走它:服务端回填、本地草稿恢复、保存伏笔 —— 只在一条路上算,另外两条就是空下拉 / 显示键。 */
function thoughtPatchOf(raw) {
  const list = thoughtListOf(raw);
  const nameOf = {};
  list.forEach((t) => { if (t && t.key) nameOf[t.key] = t.name || t.key; });
  return { thoughtOptions: list, thoughtNameOf: nameOf };
}

function thoughtCountOf(raw) {
  try {
    const rules = typeof raw === 'string' ? (raw.trim() ? JSON.parse(raw) : {}) : (raw || {});
    return Array.isArray(rules.thoughts) ? rules.thoughts.length : 0;
  } catch (e) { return 0; }
}

const MOOD_LABEL_OF = CHAPTER_ATMOSPHERES.reduce(function (map, item) {
  map[item.value] = item.label;
  return map;
}, {});
const { isRecord, isRecordList } = require('../../../utils/response-shape.js');
const advancedGameConfig = require('../utils/publish/advanced-game-config.js');
const topicRouteGraph = require('../utils/publish/topic-route-graph.js');
const storyRuleForm = require('../utils/publish/story-rule-form.js');
const attributeBinding = require('../utils/publish/attribute-binding.js');
const nodeGameCatalog = require('../utils/publish/node-game-catalog.js');
const storyInitialState = require('../utils/publish/story-initial-state.js');
const customInitialValues = attributes => (attributes || []).map((row, index) => Object.assign({ index }, row))
  .filter(row => /^counter\./.test(row.key || ''))
  .map(row => Object.assign(row, { invalidInitial: row.initial === '' || row.initial == null || !Number.isInteger(Number(row.initial)) || Number(row.initial) < Number(row.min) || Number(row.initial) > Number(row.max) }));
const templateBinding = require('../utils/publish/template-binding.js');
const publishIssueTarget = require('../utils/publish/publish-issue-target.js');
const templateAuthorFields = require('../utils/publish/template-author-fields.js');
const storyEnding = require('../utils/publish/story-ending');
const routeMapView = require('../utils/publish/route-map-view.js');
const { drawPlayStateMarker } = require('../../../utils/play-marker-draw.js');
const { MAP_STYLE } = require('../../../utils/map-style.js');
const publishValidationBag = require('../utils/publish/publish-validation-bag.js');
const publisherIdentity = require('../../../utils/publisher-identity.js');
const nodeOutcomeContract = require('../utils/publish/node-outcome-contract.js');
const { sendUiStateRequest } = require('../../../utils/ui-state-request.js');

// 时/分选项与 HH:mm 格式化的单一真源(补零口径全仓一致)
const timeOptions = require('../../../utils/time-picker-options.js');

// CU-C-160:发布前检查里每条缺项的「去填写」要落到字段真正所在的那一屏。
// 票务与档期(editorPage 2)与主题详情弹窗是两块各自渲染的容器,同一个锚点只在
// 所属那一屏里存在 —— 所以定位要先切屏/开层、再滚锚点,顺序反了就是一次空滚。
const ISSUE_PAGE2_ANCHORS = ['fieldDates', 'fieldRecruitDeadline', 'ticketSection'];
const ISSUE_SHEET_ANCHORS = ['fieldDescription', 'fieldVisual', 'fieldCategory', 'fieldCompletionRule'];

// CU-C-87:章节标题 = 位置序号 + 用户填的名字;名字自带编号(「第1章 隔离书店站」)时
// 不再叠一层,否则编辑页与发布确认页都会出现「第1章 第1章 隔离书店站」。
// ⚠️ utils/wxs/chapter-title.wxs 是这份的第二份实现(章节卡在 wxml 里渲染,WXS 不能
// require 普通 JS 模块)。两份不许漂移,tests/unit/chapter-title-dedupe-contract.test.js 钉住。
const CHAPTER_ORDINAL = /^第.{1,3}章/;
function chapterCardTitle(index, name) {
  const raw = name === null || name === undefined ? '' : String(name);
  const seq = '第' + (index + 1) + '章';
  if (CHAPTER_ORDINAL.test(raw)) return raw;
  return raw ? seq + ' ' + raw : seq;
}
function ticketCoordinate(ticket, formField, storedField) {
  const current = ticket[formField];
  if (current !== null && current !== undefined && current !== '') return current;
  const stored = ticket[storedField];
  return stored === null || stored === undefined ? '' : stored;
}
function isEditingTopicPayload(detail) {
  if (!isRecord(detail) || !isRecord(detail.topic)) return false;
  if (detail.chapters != null && !isRecordList(detail.chapters)) return false;
  if (detail.tickets != null && !isRecordList(detail.tickets)) return false;
  if (detail.collaboratorIds != null && (!Array.isArray(detail.collaboratorIds)
      || !detail.collaboratorIds.every(function (id) {
        if (typeof id === 'number') return Number.isSafeInteger(id) && id > 0;
        return typeof id === 'string' && /^\d+$/.test(id)
          && Number.isSafeInteger(Number(id)) && Number(id) > 0;
      }))) return false;
  return (detail.chapters || []).every(function (chapter) {
    return chapter.cmsTopicNodeList == null || isRecordList(chapter.cmsTopicNodeList);
  });
}

// —— 路线地图(Strava 化)——
// 章节分段色(<map> 颜色只能在 JS 设)
// ⚠️ <map> 的颜色只能在 JS 里设,吃不到 CSS var —— 这是本色板必须留一份 JS 字面量
//    的原因,不是漏改。它与 tokens.wxss 的 --cy-route* 是**两份要手动同步的真源**。
// 2026-08-05 去紫收口:首色 2026-08-01 已改中性 #1A1A1A,当时注释里留了两条待裁决
//    (「--cy-route 仍是 #7A5CFF」「索引 3 的 #7048E8 也带紫相」)。用户已裁决紫色
//    只在极特殊情况用,两条一并清掉:tokens 的 --cy-route 改 #1A1A1A、--cy-route-4
//    改青 #0CA5A5,这里的索引 3 同步。六色现在的色相是 0/221/162/180/39/339,互相拉开。
const ROUTE_COLORS = ['#1A1A1A', '#155DFC', '#12B886', '#0CA5A5', '#F59F00', '#E64980'];
// 节点照片裁剪比例:消费面是 topic 详情节点缩略图(common.wxss .item-pic image 234×160rpx ≈ 1.46),
// 微信 cropScale 里最贴近的横图档就是 4:3。两个上传入口(内联行 / 抽屉)必须同比例,
// 否则同一批节点图在详情页里高矮不一。
const NODE_PHOTO_RATIO = '4:3';
// 地图图钉。2026-08-11 换掉 /images/d_location.png:那张只有 24×25px,而 marker 要显示到
// 30 / 42px(选中态),位图放大必糊 —— 这才是"地图上的节点标记看着不对"的真原因,
// 不是没有 icon。新图 144×144 正方形(marker 的 width/height 传同一个值,非正方会被拉伸)、
// 尖端落到底边(marker 默认锚点是底部中心,尖端不到底边则标点整体上偏)。
// ⚠️ 放分包 pages/publish/images/,不进主包 —— #697 刚做完主包瘦身,别往回加。
// ⚠️ 只做一张黑图钉:选中态靠"放大 + 序号标签变色"表达,白图钉压在浅色地图瓦片上反而看不见。
const MAP_PIN = '/pages/publish/images/map-pin.png';
// pin 状态色:起点固定绿 / 终点固定红 / 未配游戏的草稿节点灰(已配游戏仍用章节色)
const START_COLOR = '#12B886'; // 起点
const END_COLOR = '#FA5252';   // 终点
const DRAFT_COLOR = '#ADB5BD'; // 草稿(未配游戏)
const SELECTED_COLOR = '#1A1A1A'; // 列表↔地图联动选中态(去紫,与 route-map/topic 同值)
// BL-23:首屏占位与 route-map-view 同一份兜底坐标(last-location 固定坐标);有节点/上次定位后由 buildRouteMapView 覆盖
const MAP_DEFAULT_CENTER = routeMapView.MAP_DEFAULT_CENTER;
const UNDO_CAP = 20; // 撤销栈上限
const IMG_ARR_MAX = 9; // 横版封面张数上限(与门店相册一致)
// AI 频控超限文案前缀:后端运行时拼上限数字(`今日AI次数已用完(20/天),明天再来吧`),只能前缀匹配
const AI_QUOTA_HINT = '今日AI次数已用完';
// AI 预检的输入(_buildPrecheckReq)里根本没有这些字段 ⇒ 模型的判断是凭空猜测,不能拿来阻断发布
const AI_BLIND_ISSUE_TYPES = ['missing_cover'];
/* 结果面板终态的停留时长(cy-result-sheet 的 duration 默认也是 2000)。
   保存成功后要跳走,面板时长与跳转延时必须是同一个数 —— 分开写会漂成
   「面板还在播就被顶掉」或者「收完了还空等一截」。 */
const RESULT_SHEET_MS = 2000;
// 云端草稿自动保存:每次编辑先落本机,停手 2 秒后合并成一次云端保存(连续输入不逐键发请求)。
const CLOUD_AUTOSAVE_MS = 2000;
// 标题旁状态胶囊:本机、上云中、云端已确认、失败、结果待确认必须分开说,不能把本机写入说成已保存。
const SAVE_STATE_LABELS = { local: '已存本机', localFailed: '本机未存', saving: '保存中', saved: '已存云端', failed: '云端未存', unknown: '待确认' };

const runtimeEditor = require('./theme-runtime.js'); const routeNames = require('./theme-route-names.js'); // 文档二 L3
const structure = require('./theme-runtime-structure.js'); // structure
const survivalEditor = require('./theme-runtime-survival.js'); /* survival */ const lookEditor = require('./theme-look-editor.js'); /* 主题画面风格 */
const zeroHpEditor = require('./theme-runtime-zero-hp.js'); // R5 HP 归零面板（与 runtimeEditor 内同一组方法，直接展开便于 handler 溯源）
const ownership = require('./theme-ownership.js'); const d20Page = require('./theme-d20-page.js'); const keysItems = require('./theme-keys-items.js'); const initialCarry = require('./theme-initial-carry.js'); // 文档二 L1;L2 线索与物品 · 物品一组;初始状态 · 开局带着
Page({
  ...runtimeEditor.methods, ...routeNames.methods,
  ...structure.methods, // structure
  ...survivalEditor.methods, // survival
  ...zeroHpEditor.methods, /* R5 HP 归零 */ ...lookEditor.methods, /* 画面风格 */
  ...ownership.methods, ...d20Page.methods, ...keysItems.methods, ...initialCarry.methods,
  _routeGraph: topicRouteGraph.emptyGraph(),
  _routeMappingSourceNodeId: '',
  _routeMappingReturnToNodeEditor: false,

  _nodeBindingSlots: [],
  _nodeBindingDraft: null,
  data: {
    creatorLoginRequired: false,
    ...runtimeEditor.data, ...routeNames.data,
    ...structure.data, // structure
    ...survivalEditor.data, /* survival */ ...lookEditor.data, /* 画面风格 */
    ...ownership.data, ...d20Page.data, ...keysItems.data, ...initialCarry.data,
    // 氛围块的选择器:cy-dropdown 吃 {value,label} 数组(全仓禁原生 picker),块上回显要 key→中文的映射。
    moodOptions: CHAPTER_ATMOSPHERES,
    storyWhenOptions: [], storyWhenLabelOf: {},
    voiceNames: ['规矩', '窗外', '心', '精密', '咔哒'],
    oddLevels: ['0 · 正常', '1 · 字偶尔闪', '2 · 褪色错位', '3 · 等宽字、声音改名'],
    // 思维阁的念头清单编辑态;count 给入口卡的副标题用。
    thoughtsEditor: { show: false, list: [], count: 0, error: '' },
    // 念头块的下拉选项(插入时按当前 journeyRules 取一次)
    thoughtOptions: [],
    thoughtNameOf: {},
    moodLabelOf: MOOD_LABEL_OF,
    // [B3b] 编辑既有主题:三个入口(项目列表草稿/未通过卡、模板「使用此模板」)都带 ?id= 跳进来。
    // 空 = 新建。非空时 onLoad 拉 /api/topic/edit-detail 回填,保存走 /api/topic/update 而不是 create
    // —— 原来页面压根不读 options.id,于是「编辑」= 在空表单上再建一条,草稿越编越多。
    editingTopicId: '',
    operationScope: '',
    // 后端下发的编辑窗口档位:FULL=全字段可改 / WHITELIST=已过审开卖,只能改文案与图
    editScope: 'FULL',
    // 正在编辑的是服务端草稿(is_draft=1):底栏给「保存草稿」,发布走「先存后发」publish-draft
    editIsDraft: false,
    draftSaving: false,
    themeSaveState: '',  // SAVE_STATE_LABELS 的键;空 = 不显示(已发布主题、空白新稿)
    themeSaveLabel: '',
    publicTempState: 'loading',
    // 能不能开商家池(只有俱乐部主理人可以)。初值 true = 「还没判出来」,与
    // _syncMerchantPoolEligibility 的「快照不可靠时不压值」同一口径:宁可多显示一个开关,
    // 也不要在判不出资格时把主理人的招商静默关掉。
    merchantPoolEditable: true,
    // 稿 E2(99:6181 / 99:6679):能不能设通关勋章(can_design_medal)。
    // 默认 true —— 拿不到权限快照时不擅自上锁,与上面招商资格同一条取舍。
    medalEditable: true,
    // 回填是否已到位。编辑态下没到位就不许保存 —— FULL 档保存会整体重建章节/票,
    // 拿一份空表单去重建 = 把原主题的路线和票删光
    editLoaded: false,
    editorPage: 1,          // 1 创作 / 2 票务。两页是固定的,不是向导 —— 页内不设门控
    modePickerShow: false,  // 主题模式选择器(城市定向 / 自由探索)
    modePickerMode: 1,      // 选择器里的暂选档;点「确定」才真的切
    viewMode: 'list',       // list | map —— 左下角药丸切换,只在第 1 页有意义
    openingIndex: -1,
    chapterNumbers: [],
    topicDetailShow: false, // 主题详情弹窗
    topicDetailSummary: '', // 入口卡上的摘要:还差几项
    topicDetailMissing: 0,  // P-4:主题详情还有几项必填没填(>0 时摘要标红)
    // P-4(V-25):票务页各分区还有几项必填没填;票务卡与分区标题右侧的红色「* 还有 N 项未填」都读它
    ticketMissing: { dates: 0, tickets: 0, identity: 0, total: 0 },
    chapterPreview: { show: false, number: 1, total: 1, title: '', subtitle: '' },
    chapterStates: [],      // 由 pro-editor-policy 算出的派生态,不进 formData(见 refreshPrimaryActionState)
    chapterActionLabels: [],
    creationAnchor: 'story',
    starterAction: null,
    pendingMaterials: [],
    pendingMaterialStates: {},
    // text 由各删除路径自带:故事流 ✕ 是真删,自由探索的删除是移到待编排区 ——
    // 语义不同,共用一句文案必然有一条在骗人。
    pendingUndo: { show: false, materialLocalId: '', text: '' },
    storyEditor: { show: false, chapterIndex: -1, insertMenuAt: -1, focusBlockKey: '', selectedBlockKey: '' },
    /* 故事变量选择器(契约 §3.1):列出这个主题里可用的 {键}。keys 在打开时现算 ——
       它跟着各节点玩法模板走,节点玩法改了它就得跟着变,存一份快照迟早对不上。 */
    storyVarSheet: { show: false, blockKey: '', keys: [] },
    pendingInsertAt: null,
    storyPendingMaterialLocalId: '',
    clubPlaceCaptureActive: false,
    draftUuid: '',
    baseRevision: '',
    draftMemberId: '',
    canPublish: false,
    editorState: 'idle', // 编辑器状态机 合法值:idle/addNode/moveNode/editNode/editGame/preview/publishCheck
    publishToCreative: true,
    aiSimple: false,
    themeSwitchColor: TOPIC_EDITOR_SWITCH_COLOR,
    // 发布前检查弹层(M4·A):blocking=硬必填(阻断),advisory=软建议(不阻断)
    publishCheck: { show: false, blocking: [], advisory: [], summary: [], passed: [], preview: null },
    // RUN-52 发布者实名(P-4 起在票务页最后一个分区)。registered 只有真/假 —— 接口不下发姓名与证件号;
    // 三格齐不齐由 _identityReady() 现算(只在点「完成」时用,不进渲染);字段刻意不放 formData,免得混进主题提交 payload。
    identityRegistered: false,
    identityRequired: true,
    // 旧草稿没记录商家/个人入口、用户还没确认归属:页内横幅 + 发布确认里的「需要先处理」都看它
    identityRealName: '',
    identityIdCard: '',
    identityConsented: false,
    identityError: '',
    identityHint: publisherIdentity.CONSENT_TEXT,
    // 通用设置 Tab 渐进折叠(M4·B,'_' 非前缀但纯前端态,不参与提交):票务/合作者卡可折叠,默认展开
    secCollapsed: { ticket: false, collab: false },
    statusBarHeight: getApp().globalData.statusBarHeight,
    navBarHeight: getApp().globalData.navBarHeight,
    chrome: { actionTop: 28, actionRight: 12, contentTop: 76, sheetTop: 69 },

    // 通用编辑弹框数据
    submitErrorWhy: false,   // 「为什么会这样」展开与否(稿 S15/S16)

    //章节参数
    popChapter: false, //显示添加章节弹框
    // 编辑章节弹窗是不是从故事流底栏「下一步」进来的 —— 决定它叠在故事流上还是单层,
    // 以及「完成」要不要连故事流一起收(用户 2026-09-05 定的两种呈现)
    chapterSettingsFromStory: false,
    popChapterAction: 0, //0添加 1编辑
    popChapterIndex: 0, //章节索引

    //节点参数
    popChapterNodes: false, //显示添加节点弹框
    nodeSheetView: 'detail', //节点抽屉视图 'detail' | 'games'
    popChapterNodesAction: 0, //0添加 1编辑
    popChapterNodesIndex: 0, //节点索引
    editTargetChapterLid: '', //当前编辑/添加目标章节 _localId
    editTargetNodeLid: '', //当前编辑目标节点 _localId
    editingPendingLocalId: '',
    nodeDraftDestination: 'formal', // formal | pending
    assignPendingLocalId: '',

    selectedCategoryIds: [], // 选中的分类ID数组
    selectedCategoryNames: [], // 选中的分类名称数组
    categorySheetVisible: false,
    categorySheetIds: '',
    selectedCategoryNamesStr: '', // 选中的分类名称字符串，用于显示
    categoryList: [],
    categoryIndex: 0,
    // 章节承接只认商家档案同口径的受控分类；与主题分类列表分开，避免互相污染选择态。
    merchantCategoryList: [],
    merchantCategoryLoading: false,
    atmosphereOptions: CHAPTER_ATMOSPHERES,

    minuteOptions: ['30', '60', '90', '120'], // 分钟选项
    minuteIndex: 0, // 默认选中的索引

    tempList: [], //我自己的模板
    publicTempList: [], //公共玩法库(平台精选/热门/最新)
    searchKeyword: '', // 搜索关键词
    selectedTempId: null, // 当前选中的模板ID
    selectedTempSource: '', // 'public' = 公共库 id(须先采用成我的草稿);'mine' = 会员模板 id。两库 id 会重号,不能只凭 id 区分
    selectedTempInfo: null, // 选中的模板完整信息

    //章节数据
    chapterForm: emptyChapterForm(),
    // 与 cms_member_template.validation_method 取值域一致;后端 assertAllowedValidationMethods 只收 1-5
    validationMethodOptions: [
      { v: '1', label: '文字作答' },
      { v: '3', label: '选项问答' },
      { v: '2', label: '拍照打卡' },
      { v: '4', label: '到店扫码' },
      { v: '5', label: 'GPS 到达' }
    ],

    //节点数据
    nodesForm: {
      description: '',
      name: '',
      subtitle: '',
      address: '',
      longitude: '',
      latitude: '',
      imgUrl: '',
      nodeTime: 0,
      showTemplate: false,
      templateId: 0,
      templateInfo: {},
      templateName: '',
      businessTime: '',
      sortID: 1,
      // 自由定向(mode2)选填叙事文案:留空后端回退用 description
      hookText: '',
      cardHookLong: '',
      fragmentText: ''
    },
    nodesFormLocationReady: false,
    nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
    nodeSharedSites: [], nodeCopyBusy: false, nodeCopyUncertain: false, nodeCopyError: '',
    initialStateEditor: { show: false, rules: null, error: '' },
    attributeRegistrySnapshot: null,
    attributeRegistryError: '',
    initialStateOptions: storyInitialState.OPTIONS,
    supportNodeChallenge: false, supportNodeSettled: false,
    supportMatrix: null, supportMatrixLoading: false, supportMatrixLoadError: '', nodeSupportReason: '', storySupportReasons: {},
    publicSupportReasons: {}, draftSupportReasons: {}, mineSupportReasons: {},
    storyGameOptions: ['profile', 'typeIn', 'photoCheck', 'note', 'd20'].map(key => nodeGameCatalog.findGame(key)),
    // 表单数据
    // 结局编辑器(2026-09-22 接线)。list 是 story-ending.parse 出来的界面形状,
    // 保存时由同一个 serialize 转回服务端形状 —— 校验口径与 JourneyStoryValidator 一致,不另写一套。
    // 结局章(2026-09-24):章节设置里的「设为结局」—— 什么时候播 + 选哪个伏笔 / 选项 / 站点
    chapterEndingKinds: [{ key: 'fallback', label: '默认（其它结局都不满足时）' }, { key: 'thought', label: '某个伏笔应验了' },
      { key: 'tag', label: '选过某个选项' }, { key: 'node', label: '到过某个节点' }],
    chapterEndingKindIndex: 0, chapterEndingPickIndex: -1,
    storyDraftList: [],
    endingTagOptions: [],
    // 只列已经有服务端编号的站点:新建的站点只有 _localId,选了条件永远不成立且不报错。
    endingNodeOptions: [],
    formData: {
      name: '',
      subtitle: '',
      description: '',
      startDate: '',
      endDate: '',
      imgUrl: '',
      // 横版封面:多张,存逗号分隔 CSV(与章节 imgArr、utils/wxs/img.wxs 同约定)。
      // step3 画廊渲染用的展开数组是 imgArrList,两者由 _setImgArr 统一写。
      imgArr: '',
      categoryIds: [],
      chapters: [],
      collaboratorIds: [],
      collaboratorList: [],
      tickets: [],
      openMerchantPool: false, // 是否允许路线进入商家池(默认关=自办;能不能开由 merchantPoolEditable 决定)
      openClubPool: true, // 开放范围·俱乐部承接(P0-4):开=俱乐部可在合作池申请承接。2026-09-15 裁决 12B:默认开,主办可手动关;自由探索无此池(上送归一为 0)
      // 自由定向必须经招商→锁价链路，截止日不能由前端静默兜底。
      recruitDeadline: '',
      clubId: '', // 归属俱乐部ID(经典定向必选,自由定向可选挂靠·2026-07-02拍板;一人多俱乐部时由发布者选定,D3/D12)
      // M2 自玩票(仅经典定向):是否开放 + 价格 + 票数配额
      selfPlay: false,
      selfPlayPrice: '',
      selfPlayQuota: '',
      teamMode: 0,
      teamMaxMembers: 4,
      // P0.5 勋章(两模式;XP 自动分配零配置)
      finishMedalName: '',
      finishMedalImg: '',
      completeRewardCouponId: 0,
      completeRuleJson: '',
      journeyRules: '',
      themeRuntimeConfig: '', look: '', /* 主题画面风格(theme-look.js);空 = 默认星尘 */
      journeyStory: '',
      // 主题级路线真源。旧数据/空值保持 LINEAR；BRANCH_GRAPH 才序列化 routeGraphJson。
      routeMode: 'LINEAR',
      routeGraphJson: '',
      configVersion: ''
    },
    teamMemberOptions: [2, 3, 4],

    // formData.imgArr 的展开数组,只服务 step3 画廊渲染(WXML 不能 split)
    imgArrList: [],
    imgArrMax: IMG_ARR_MAX, // 给 WXML 用,别在模板里再写一遍 9
    completionRewardCouponName: '',

    completionRuleMode: 'ALL',
    completionRequiredCount: 1,
    completionRuleError: '',

    // 节点内的轻量「模板结果 → 下一站」编辑态。技术起点/终点由故事流顺序派生，
    // 创作者只处理当前节点的结果对应关系。
    routeMappingShow: false,
    routeMappingSourceLabel: '',
    routeMappingTemplateLabel: '',
    routeMappingRows: [],
    routeMappingFallbackLabel: '继续故事流',
    routeNodeMappingSummary: {},

    audioPreviewPlaying: false,
    // 故事流音频块预听(与章节旁白共用同一个播放器,见 onPreviewStoryAudio)。
    // storyAudioKey = 正在播/暂停的那一块 key;'' 表示没有块音频在播。
    storyAudioPlaying: false,
    storyAudioKey: '',

    // 归属俱乐部选择(达人多俱乐部:/api/club/my 的 owned[])
    myClubs: [], // 我创建的俱乐部列表 [{id,name}]
    clubIndex: -1, // 当前选中俱乐部在 myClubs 的索引,-1=未选

    // 错误信息
    errors: {},
    editLoading: false,
    editLoadError: '',
    editLoadIsAccess: false,
    submitting: false,
    submitError: '',
    /* 动作结果面板(cy-result-sheet)。loading 不自愈,终态 2s 自收。
       duration=0 表示不自愈 —— 失败原因要留够读的时间。 */
    resultSheet: { show: false, kind: 'success', title: '', sub: '', why: '', duration: 2000 },
    canUndo: false, // 撤销栈是否非空(控制撤销按钮显隐/置灰)

    // 统计信息
    totalDuration: '0h,0min',
    totalNodes: 0,
    // 宾果九宫格(主题级,存进 complete_rule_json)

    // 九格按 S 形依次呈现:第一行左→右,第二行右→左,第三行左→右。
    // rows 是给 wxml 用的视图序,pos 指回真正的格位。
    bingoRows: advancedGameConfig.BINGO_S_ORDER.map((pos, i) => ({
      pos, seq: i + 1, hint: advancedGameConfig.BINGO_DEFAULT_LABELS[pos],
      label: '', couponId: 0, couponName: '', feedbackText: ''
    })),
    bingoError: '',

    // 统计信息
    chapterStats: [], // 每个章节的统计信息
    chapterDescExpanded: {}, // 步骤2：章节概述展开态 { '0': true }
    nodeStoryExpanded: {}, // 步骤2：节点剧情展开态 { '0-0': true }
    narrativeEditor: {
      show: false,
      chapterIndex: -1,
      nodeIndex: -1,
      chapterName: '',
      nodeName: '',
      description: '',
      hookText: '',
      cardHookLong: '',
      fragmentText: ''
    },
    scrollIntoView: '',
    // 滚轮的选中条与上下渐隐罩。picker-view 的 indicator-style/mask-style 只吃内联字符串,
    // 读不到 WXSS 变量 —— 这是本页必须留一份颜色字面量的地方,不是漏改(同 ROUTE_COLORS)。
    // 2026-08-11:原值是给深色弹窗配的(白 8% 选中条 + #2C2C2E 渐隐),日期面板改浅色后
    // 变成浅底上盖深块,滚轮整个读不出来。换成浅端同构值。
    slopesPickerIndicator: 'height: 80rpx; background: rgba(17,17,17,0.06);',
    // ⚠️ 渐隐必须【中间收到全透明】。原写法两端 .95 → 另一端 .35,两层叠加后连正中间
    // 都压着 ~.58 的罩 —— 连选中那一行都被冲淡(深色版同样有这个毛病,只是深底上看不出来)。
    slopesPickerMask: 'background: linear-gradient(180deg, rgba(255,255,255,0.95), rgba(255,255,255,0) 35%), linear-gradient(0deg, rgba(255,255,255,0.95), rgba(255,255,255,0) 35%);',
    totalStats: { // 总统计信息
      totalDurationDisplay: '0min',
      totalDistance: '0km',
      totalNodes: 0,
      totalTemplates: 0
    },

    // —— 路线地图 Hero(Strava 化)——
    mapMarkers: [],        // 编号 marker(按章节分色)
    mapPolyline: [],       // 路线折线(每章一段)
    mapInclude: [MAP_DEFAULT_CENTER], // include-points 自动适配视野;空数组会让腾讯 SDK fitBounds 崩,兜底中心点
    mapCenter: MAP_DEFAULT_CENTER,
    mapScale: 12,
    // P-45 / G-12:编辑器地图与玩家地图同一套个性化样式(utils/map-style.js;key 未配置时是官方默认底图)
    mapSubkey: MAP_STYLE.subkey,
    mapLayerStyle: MAP_STYLE.layerStyle,
    hasRoute: false,       // 是否已有有效坐标节点(空态判断)
    selectedNodeLid: '',   // 列表↔地图双向高亮选中的节点 _localId
    rowHpx: 0,             // 节点卡片行高(px),movable-view y 用 px,onLoad 由 rpx 折算
    dragNodeLid: '',       // 当前被手柄按住进入拖拽的节点 _localId(门控,非空时其余行 disabled)
    dragNudge: 0,          // 松手后强制 movable-view 复位用的 1px 抖动(见 _resetDragOffsets)
    completeness: { percent: 0, missing: [] }, // 路线完整度评分
    _showCompletenessDetail: false, // 完整度 pill 是否展开缺失项
    inlineEdit: null,

    // 节点营业时间选择器数据
    timePicker: {
      show: false,
      startTime: {
        hour: 9,
        minute: 0
      },
      endTime: {
        hour: 18,
        minute: 0
      }
    },

    // 路线日期时间选择器数据
    startTimePicker: {
      show: false
    },
    startDateIndex: [0],
    startTimeIndex: [9, 0],

    endTimePicker: {
      show: false
    },
    endDateIndex: [0],
    endTimeIndex: [18, 0],

    // 时间选择相关
    dateList: [], // 日期列表（今天至30天内）
    hours: timeOptions.HOURS,     // '00'..'23'
    minutes: timeOptions.MINUTES, // '00'..'59'

    // 路线日期显示
    startDateTime: '开始时间',
    endDateTime: '结束时间',

    // 票种编辑器状态
    showTicketEditor: false,
    canSaveTicket: false,
    editingTicket: {
      editIndex: -1,
      name: '',
      price: 0,
      totalStock: 100,
      mode: 1,
      meetingPoint: '',
      meetingPointAddress: '',
      meetingPointLongitude: '',
      meetingPointLatitude: '',
      teamSize: 0,
      startTime: '',
      endTime: '',
      saleStartTime: '',
      saleEndTime: '',
      description: '',
      refundSupported: true,
      syncWithTheme: false
    },

    // 票种时间选择弹窗
    ticketTimePicker: {
      show: false,
      field: '',
      mode: 'date',
      title: '选择时间'
    },
    ticketDateIndex: [0],
    ticketTimeIndex: [9, 0],
  },

  goBack() {
    if (this.data.submitting) return;
    if (!this._draftAutosaveReady || this._persistDraftEnvelope()) {
      this._exitEditor();
      return;
    }
    modal.show({
      title: '草稿还没有保存',
      content: '本机存储空间可能不足，直接返回会丢失本次修改。',
      confirmText: '仍要返回',
      cancelText: '继续编辑',
      success: (res) => {
        if (res.confirm) this._exitEditor();
      }
    });
  },

  // 唯一离场口:根栈直链进入时 navigateBack 会 fail,落回模板库而不是卡死。
  _exitEditor() {
    wx.navigateBack({ delta: 1, fail: () => wx.switchTab({ url: '/pages/template/index' }) });
  },

  _draftIdentity() {
    if (this.data.editingTopicId) return { topicId: this.data.editingTopicId };
    if (this.data.draftUuid) return { draftUuid: this.data.draftUuid };
    return null;
  },

  _draftContentRevision() {
    return JSON.stringify([this.data.formData, this.data.pendingMaterials,
      this.data.selectedCategoryIds, this.data.selectedCategoryNames,
      this.data.startDateTime, this.data.endDateTime, this.data.completionRuleMode,
      this.data.completionRequiredCount, this.data.bingoRows, this.data.publishToCreative, this.data.aiSimple,
      this._routeRuleDrafts || {}]);
  },

  _showUnstoredPublishedChanges(rejected) {
    this._draftAutosaveReady = true;
    this._resultSheetNext = '';
    const reason = '本次提交的结果已确认，但当前修改未能保存到本机。请勿退出，腾出存储空间后继续编辑以重试保存。';
    this.setData({ submitting: false, draftSaving: false, editIsDraft: false, submitError: reason,
      themeSaveState: 'localFailed', themeSaveLabel: SAVE_STATE_LABELS.localFailed,
      resultSheet: { show: true, kind: 'fail', title: rejected ? '审核未通过，修改尚未保存' : '后续修改尚未保存',
        sub: '请勿离开本页', meta: '当前修改尚未保存',
        why: reason, duration: RESULT_SHEET_MS } });
  },

  _installDraftAutosave() {
    if (this._draftSetDataInstalled) return;
    this._draftSetDataInstalled = true;
    const nativeSetData = this.setData;
    this.setData = (patch, callback) => {
      // 是否算一次草稿编辑按「发起 setData 的那一刻」判定:真机回调是异步的,回填/换上回读版本时
      // (_draftAutosaveReady=false)发起的 patch,回调可能在 ready 复位后才执行,不能再回写恢复稿、把草稿标脏。
      const draftEdit = this._draftAutosaveReady && !this._draftHydrationDepth && this._isDraftPatch(patch);
      nativeSetData.call(this, patch, () => {
        const finish = () => {
          if (draftEdit && this._draftAutosaveReady) {
            this._onDraftEdited(this._persistDraftEnvelope());
          } else if (this._cloudAutosaveQueued && !this._cloudAutosaveTimer && !this._cloudDraftEditorOpen()) {
            // 弹窗关闭、在途保存结束后接力排队;排队条件不满足时 _queueCloudDraftAutosave 自己会放弃
            this._queueCloudDraftAutosave();
          }
          if (callback) callback();
        };
        const nodeLocationChanged = Object.keys(patch || {}).some((key) => {
          return key === 'nodesForm'
            || key === 'nodesForm.longitude'
            || key === 'nodesForm.latitude';
        });
        if (nodeLocationChanged) {
          // 2026-08-11:坐标文本(nodesFormCoordText)随「节点卡不显示经纬度」一起去掉。
          // ⚠️ 上一版这里写的是「ready 是"能不能发布"的判据」—— 那句是错的,已更正:
          // 发布校验走的是 proEditorPolicy.hasUsableCoords 现算(见 validateForm),
          // 根本不读这个 data 字段。随着 .node-location-ok 那行 UI 被删,
          // nodesFormLocationReady 目前【没有任何消费方】,只剩两条契约断言钉着
          // 「它和发布闸用同一个 hasUsableCoords」的口径。要么给它找回消费方、要么连
          // 断言一起清掉 —— 别再照着上面那句错注释以为它在拦发布。
          nativeSetData.call(this, {
            nodesFormLocationReady: proEditorPolicy.hasUsableCoords(this.data.nodesForm),
          }, finish);
        } else {
          finish();
        }
      });
    };
  },

  _setSaveState(state) {
    if (this._unloaded) return;
    this.setData({ themeSaveState: state, themeSaveLabel: SAVE_STATE_LABELS[state] || '' });
  },

  // 每次草稿内容变化:本机已写入后才排云端保存;本机没写进去要如实标出来
  _onDraftEdited(stored) {
    if (this._unloaded) return;
    // 回读换版本、模板详情补全等不改变云端内容的回写,不把「已存云端」打回去
    if (this.data.themeSaveState === 'saved' && this._cloudSavedKey && this._cloudDraftKey() === this._cloudSavedKey) return;
    this._cloudAutosaveQueued = true;
    this._setSaveState(stored ? 'local' : 'localFailed');
    this._queueCloudDraftAutosave();
  },

  // 换上本机恢复稿后:与云端已确认的内容相同就标已存云端;不同才按一次编辑排队
  // (与其它编辑一样,定时器由 setData 包装在回调里接力,加载/重试读取本身不直接调用保存)
  _markRestoredDraft(stored) {
    if (this._unloaded) return;
    if (this._cloudSavedKey && this._cloudDraftKey() === this._cloudSavedKey) { this._setSaveState('saved'); return; }
    this._cloudAutosaveQueued = true;
    this._setSaveState(stored ? 'local' : 'localFailed');
  },

  // 云端要保存的那份内容(与「保存草稿」同一个 payload builder);组装不出来返回 ''
  _cloudDraftKey() {
    try {
      const payload = this._buildSubmitPayload({ draft: true, quiet: true });
      return payload ? JSON.stringify(payload) : '';
    } catch (error) { return ''; }
  },

  _cancelCloudDraftAutosave() {
    if (this._cloudAutosaveTimer) clearTimeout(this._cloudAutosaveTimer);
    this._cloudAutosaveTimer = null;
  },

  // 编辑弹窗里的内容还没提交;此时保存并换上回读版本会打断弹窗,等关掉再存
  _cloudDraftEditorOpen() {
    const d = this.data;
    const open = key => !!(d[key] && d[key].show);
    return !!(d.popChapter || d.popChapterNodes || d.topicDetailShow || d.showTicketEditor
      || d.routeMappingShow || d.modePickerShow || d.categorySheetVisible
      || ['storyEditor', 'storyVarSheet', 'runtimeEditor', 'runtimeZeroEditor', 'survivalEditor', 'structureEditor',
        'valueEditor', 'initialStateEditor', 'thoughtsEditor', 'publishCheck', 'chapterPreview',
        'startTimePicker', 'endTimePicker', 'ticketTimePicker'].some(open));
  },

  // 自动保存只替当前账号、当前入口、仍是草稿(或尚未建题)且已命名的稿子工作;
  // 发布结果待确认、本机存储不可用、旧稿归属未确认、确定失败后都停下,等用户明确操作。
  _cloudAutosaveBlocked() {
    const owner = String(app.getUserID() || '');
    return !this._cloudAutosaveEnabled || this._hidden || this._unloaded || !this._draftAutosaveReady
      || this._draftStorageUnavailable || this._draftScopeUnconfirmed || this._cloudAutosavePaused
      || Object.keys(this._routeRuleDrafts || {}).length > 0
      || !!this._publishAttempt || !!this._serverDraftAttempt || this.data.creatorLoginRequired || !owner
      || String(this.data.draftMemberId || owner) !== owner
      || !!(this.data.editingTopicId && (!this.data.editLoaded || !this.data.editIsDraft))
      || !String((this.data.formData || {}).name || '').trim();
  },

  _queueCloudDraftAutosave() {
    this._cancelCloudDraftAutosave();
    if (!this._cloudAutosaveQueued || this._cloudAutosaveBlocked()) return;
    const owner = String(app.getUserID() || '');
    const scope = this.data.operationScope;
    // 连续输入合并为一次保存，避免每次按键重建章节、换号或触发接口限流。
    this._cloudAutosaveTimer = setTimeout(() => {
      this._cloudAutosaveTimer = null;
      if (owner !== String(app.getUserID() || '') || scope !== this.data.operationScope) {
        this._cloudAutosaveQueued = false;
        return;
      }
      // 在途保存/发布或弹窗未关:保留排队,结束或关闭时由 setData 接力
      if (this._serverDraftBusy() || this.data.submitting || this._cloudDraftEditorOpen()) return;
      this.saveServerDraft({ automatic: true });
    }, CLOUD_AUTOSAVE_MS);
  },

  _isDraftPatch(patch) {
    return Object.keys(patch || {}).some((key) => {
      return key === 'formData' || key.indexOf('formData.') === 0
        || key === 'pendingMaterials'
        || key === 'selectedCategoryIds'
        || key === 'selectedCategoryNames'
        || key === 'startDateTime'
        || key === 'endDateTime'
        || ['completionRuleMode', 'completionRequiredCount', 'bingoRows', 'publishToCreative', 'aiSimple'].includes(key);
    });
  },

  _persistDraftEnvelope() {
    const identity = this._draftIdentity();
    const memberId = this.data.draftMemberId || app.getUserID();
    if (this._draftStorageUnavailable || this._draftScopeUnconfirmed || !identity || !memberId || this._unloaded
        || String(memberId) !== String(app.getUserID() || '')) return false;
    try {
      proEditorDraft.saveDraft(wx, {
        memberId,
        operationScope: this.data.operationScope,
        topicId: identity.topicId,
        draftUuid: identity.draftUuid,
        baseRevision: this.data.baseRevision,
        publishAttempt: this._publishAttempt,
        serverDraftAttempt: this._serverDraftAttempt,
        formData: this.data.formData,
        pendingMaterials: this.data.pendingMaterials,
        editorMeta: {
          routeRuleDrafts: this._routeRuleDrafts || {},
          selectedCategoryIds: this.data.selectedCategoryIds || [],
          selectedCategoryNames: this.data.selectedCategoryNames || [],
          startDateTime: this.data.startDateTime,
          endDateTime: this.data.endDateTime,
          completionRuleMode: this.data.completionRuleMode,
          completionRequiredCount: this.data.completionRequiredCount,
          bingoRows: this.data.bingoRows,
          publishToCreative: this.data.publishToCreative,
          aiSimple: this.data.aiSimple,
        },
      });
      this._draftSaveWarned = false;
      return true;
    } catch (error) {
      if (!this._draftSaveWarned) {
        this._draftSaveWarned = true;
        cyToast('本地草稿保存失败，请检查存储空间');
      }
      return false;
    }
  },

  // 只包内部回填发起 patch 的同步片段，不跨原生回调等待，真实用户编辑仍按原路径计脏。
  _withDraftHydration(apply) {
    this._draftHydrationDepth = (this._draftHydrationDepth || 0) + 1;
    try { return apply(); }
    finally { this._draftHydrationDepth -= 1; }
  },

  _applyDraftEnvelope(envelope) {
    if (!envelope) return;
    this._invalidateCoverUpload('imgUrl');
    this._invalidateCoverUpload('imgArr');
    this._publishAttempt = envelope.publishAttempt || null;
    this._serverDraftAttempt = envelope.serverDraftAttempt || null;
    this._localDraftRestored = true;
    const formData = this._withEndingChapters(envelope.formData || Object.assign({}, this.data.formData, {
      chapters: envelope.chapters || [],
    }));
    const pendingMaterials = envelope.pendingMaterials || [];
    const meta = envelope.editorMeta || {};
    this._routeRuleDrafts = Object.assign(Object.create(null), meta.routeRuleDrafts || {});
    this._routeGraph = topicRouteGraph.normalizeTopicRoute(formData).graph;
    const patch = Object.assign({
      formData,
      imgArrList: this._parseImgArr(formData.imgArr),
      pendingMaterials,
    }, thoughtPatchOf(formData.journeyRules));
    if (Array.isArray(meta.selectedCategoryIds)) patch.selectedCategoryIds = meta.selectedCategoryIds;
    if (Array.isArray(meta.selectedCategoryNames)) {
      patch.selectedCategoryNames = meta.selectedCategoryNames;
      patch.selectedCategoryNamesStr = meta.selectedCategoryNames.join('、');
    }
    if (meta.startDateTime) patch.startDateTime = meta.startDateTime;
    if (meta.endDateTime) patch.endDateTime = meta.endDateTime;
    if (['ALL', 'AT_LEAST'].includes(meta.completionRuleMode)) patch.completionRuleMode = meta.completionRuleMode;
    if (Number.isFinite(meta.completionRequiredCount)) patch.completionRequiredCount = meta.completionRequiredCount;
    if (Array.isArray(meta.bingoRows)) patch.bingoRows = meta.bingoRows;
    if (typeof meta.publishToCreative === 'boolean') patch.publishToCreative = meta.publishToCreative;
    if (typeof meta.aiSimple === 'boolean') patch.aiSimple = meta.aiSimple;
    cyToast(pendingMaterials.length
        ? ('已恢复上次未编排完的 ' + pendingMaterials.length + ' 个素材')
        : '已恢复上次本地草稿');
    const restored = Object.assign({}, this.data, patch);
    this._withDraftHydration(() => this.setData({
      formData: restored.formData,
      imgArrList: restored.imgArrList,
      pendingMaterials: restored.pendingMaterials,
      thoughtOptions: restored.thoughtOptions,
      thoughtNameOf: restored.thoughtNameOf,
      selectedCategoryIds: restored.selectedCategoryIds,
      selectedCategoryNames: restored.selectedCategoryNames,
      selectedCategoryNamesStr: restored.selectedCategoryNamesStr,
      startDateTime: restored.startDateTime,
      endDateTime: restored.endDateTime,
      completionRuleMode: restored.completionRuleMode,
      completionRequiredCount: restored.completionRequiredCount,
      bingoRows: restored.bingoRows,
      publishToCreative: restored.publishToCreative,
      aiSimple: restored.aiSimple,
    }, () => this._withDraftHydration(() => {
      this._ensureLocalIds();
      this._materializeCityStoryChapters();
      this.updateAllStatistics();
    })));
  },

  _newDraftUuid(options, memberId) {
    const explicit = options && options.draftUuid ? String(options.draftUuid) : '';
    if (explicit) return explicit;
    const forceNew = options && String(options.newDraft) === '1';
    const hasIncomingAiDraft = !!readAiDraft();
    if (!forceNew && !hasIncomingAiDraft && memberId) {
      const active = proEditorDraft.readActiveNewDraft(wx, memberId, this.data.operationScope);
      if (active) return active;
    }
    return proEditorDraft.createDraftUuid();
  },

  _bootstrapNewLocalDraft(options) {
    const memberId = app.getUserID() || '';
    let draftUuid;
    try {
      draftUuid = this._newDraftUuid(options || {}, memberId);
    } catch (error) {
      this._markDraftStorageUnavailable();
      return false;
    }
    this.setData({ draftUuid, draftMemberId: memberId });
    if (!memberId) return false;
    const restored = proEditorDraft.loadDraft(wx, { draftUuid }, { memberId, operationScope: this.data.operationScope });
    if (restored.status === 'storage_unavailable') {
      this._markDraftStorageUnavailable();
      return false;
    }
    if (restored.status === 'member_mismatch' || restored.status === 'scope_mismatch') {
      draftUuid = proEditorDraft.createDraftUuid();
      this.setData({ draftUuid });
      cyToast('草稿属于其他账号，已新建草稿');
      return false;
    }
    if (restored.status !== 'ready') return false;
    const restoredForm = restored.envelope.formData || {};
    const modeMismatch = !options.draftUuid && options.mode != null
      && Number(restoredForm.productType) !== (Number(options.mode) === 2 ? 2 : 1);
    const clubMismatch = options.clubId != null && String(options.clubId)
      && String(restoredForm.clubId || '') !== String(options.clubId);
    if (modeMismatch || clubMismatch) {
      draftUuid = proEditorDraft.createDraftUuid();
      this.setData({ draftUuid });
      return false;
    }
    this._applyDraftEnvelope(restored.envelope);
    if (restored.scopeUnknown) this._persistDraftEnvelope(); // P-44:旧草稿静默归到当前入口,立即补记入口
    return true;
  },

  _finishEditingDraftBootstrap(detail) {
    const topic = (detail && detail.topic) || {};
    const memberId = app.getUserID() || topic.memberId || '';
    const baseRevision = topic.updateTime || topic.createTime || '';
    const requestToken = this._editLoadToken;
    const isCurrent = () => !this._unloaded
      && String(memberId) === String(app.getUserID() || '')
      && requestToken === this._editLoadToken;
    this.setData({ baseRevision, draftMemberId: memberId });
    // 「保存草稿」回读:本机内容刚被服务端接收并回读确认(server-draft.readbackIssue),
    // 这时才清本机恢复稿(新建时的 draftUuid 那份 + 主题号那份),不再拿它跟服务端版本比冲突。
    const adopted = this._adoptServerDraft;
    if (adopted) {
      this._adoptServerDraft = null;
      [adopted.previous, { topicId: this.data.editingTopicId }].forEach((identity) => {
        if (!identity) return;
        try { proEditorDraft.removeDraft(wx, identity, { memberId: adopted.memberId || memberId, operationScope: this.data.operationScope }); } catch (error) {}
      });
      this._draftAutosaveReady = true;
      return;
    }
    if (!memberId) {
      this._draftAutosaveReady = true;
      return;
    }
    const result = proEditorDraft.loadDraft(wx, { topicId: this.data.editingTopicId }, {
      memberId,
      operationScope: this.data.operationScope,
      currentBaseRevision: baseRevision,
    });
    if (result.status === 'storage_unavailable') {
      this._markDraftStorageUnavailable();
      return;
    }
    if (result.envelope && result.envelope.publishAttempt) {
      this._publishAttempt = result.envelope.publishAttempt;
    }
    if (result.envelope && result.envelope.serverDraftAttempt) {
      this._serverDraftAttempt = result.envelope.serverDraftAttempt;
    }
    const finish = () => { if (isCurrent()) this._draftAutosaveReady = true; };
    // Equal (including second-resolution) revisions do not settle a lost write response.
    // Only an explicit adoption of this known topic's saved version can clear UNKNOWN.
    const unknownExistingWrite = result.envelope && result.envelope.publishAttempt
      && result.envelope.publishAttempt.status === 'UNKNOWN'
      && !!this.data.editingTopicId
      && String(result.envelope.publishAttempt.topicId || '') === String(this.data.editingTopicId);
    // 服务端草稿的未知发布/保存有原号回读核对，不能借冲突选择删除；普通更新没有回读核对，
    // 仍由下方弹窗让作者明确采用已保存版本后解锁。
    const serverDraftUnknown = unknownExistingWrite && result.envelope.publishAttempt.kind === 'SERVER_DRAFT';
    if (serverDraftUnknown || this._serverDraftAttempt) {
      // 使用旧云端版本不是原写入已失败的证据，不能借冲突选择删除未知请求。
      this._applyDraftEnvelope(result.envelope);
      finish();
      this._setSaveState('unknown');
      return;
    }
    // 换上本机恢复稿之前记下云端版本的内容,用来判断恢复稿是否真的需要再上云
    this._cloudLoadedKey = this._cloudDraftKey();
    if (result.status === 'ready' && !unknownExistingWrite) {
      this._applyDraftEnvelope(result.envelope);
      if (result.scopeUnknown) this._persistDraftEnvelope(); // P-44:旧草稿静默归到当前入口,立即补记入口
      finish();
      return;
    }
    if (result.status === 'revision_conflict' || unknownExistingWrite) {
      modal.show({
        title: unknownExistingWrite ? '上次发布结果待确认' : '另一端草稿已更新',
        content: unknownExistingWrite
          ? '是否保留本机草稿，或使用当前已保存版本？'
          : '其他设备上的草稿已更新，是否用本机草稿覆盖？',
        confirmText: '使用本地草稿',
        cancelText: '使用已保存版本',
        success: (res) => {
          if (!isCurrent()) return;
          if (res.confirm) this._applyDraftEnvelope(result.envelope);
          else {
            proEditorDraft.removeDraft(wx, { topicId: this.data.editingTopicId }, { memberId, operationScope: this.data.operationScope });
            this._publishAttempt = null;
            if (this._publishWorkflow) this._publishWorkflow.destroy();
            this._publishWorkflow = null;
            this._persistDraftEnvelope();
          }
          finish();
          if (res.confirm && isCurrent()) this._markRestoredDraft(true); // 用本机稿覆盖:按一次编辑排自动保存
        },
      });
      return;
    }
    finish();
  },

  // 2026-10-27 P-44(业主):没有记录入口的旧草稿默认归到当前入口、静默恢复 ——
  // 「确认旧草稿的发布入口」弹窗、「旧草稿还没恢复」横幅和发布确认里的归属阻断项都已删除。
  // 恢复后立刻按当前入口重存一次(_persistDraftEnvelope 写 operationScope),下次进来就是已知入口。

  _markDraftStorageUnavailable() {
    this._draftStorageUnavailable = true;
    this._draftAutosaveReady = true; // Keep the existing unsaved-exit warning active.
    this.setData({ submitting: false, submitError: '本机草稿读取失败，请重新进入' });
  },

  _clearDraftEnvelope() {
    if (this._draftStorageUnavailable) return false;
    const identity = this._draftIdentity();
    const memberId = this.data.draftMemberId || app.getUserID();
    if (!identity) return true;
    try {
      proEditorDraft.removeDraft(wx, identity, { memberId, operationScope: this.data.operationScope });
      return true;
    } catch (error) { return false; }
  },

  clearLocalDraft() {
    if (this._draftStorageUnavailable) { this._markDraftStorageUnavailable(); return; }
    const memberId = String(app.getUserID() || '');
    if (this._unloaded) return;
    if (!memberId || String(this.data.draftMemberId || '') !== memberId) {
      cyToast('请重新进入当前账号的草稿');
      return;
    }
    const topicId = this.data.editingTopicId;
    const draftUuid = this.data.draftUuid;
    modal.show({
      title: '清空本地草稿？',
      content: '当前未发布的章节和待编排素材都会从本机删除。',
      confirmText: '清空',
      success: (res) => {
        if (!res.confirm || this._unloaded || this._draftStorageUnavailable
            || memberId !== String(app.getUserID() || '')
            || memberId !== String(this.data.draftMemberId || '')
            || topicId !== this.data.editingTopicId || draftUuid !== this.data.draftUuid) return;
        this._draftAutosaveReady = false;
        if (!this._clearDraftEnvelope()) {
          this._draftAutosaveReady = true;
          cyToast('本地草稿清空失败，请稍后重试');
          return;
        }
        const mode = Number(this.data.formData.productType) === 2 ? 2 : 1;
        const club = this.data.formData.clubId ? ('&clubId=' + encodeURIComponent(this.data.formData.clubId)) : '';
        wx.redirectTo({ url: '/pages/publish/fabu/index?newDraft=1&mode=' + mode + club });
      },
    });
  },


  onTapAddChapter() {
    this._createChapterDirect();
  },

  // 2026-08-20 用户拍板:添加章节不再走黑色弹窗,直接以默认名「第N章」落一章;
  // 改名/剧情/删除仍走章节卡上的「编辑章节」。复用 confrimChapter 的追加事务
  // (含 undo、城市定向自动进全屏故事流)。可选 assignPendingLocalId:同时把该
  // 待编排素材归入新章(createChapterForPending 用)。
  openTopicOpening() {
    const index = (this.data.formData.chapters || []).findIndex(chapter => chapter.opening === true);
    if (index >= 0) this.openStoryEditor({ currentTarget: { dataset: { index } } });
    else this._createChapterDirect('', true);
  },

  _createChapterDirect(assignPendingLocalId, opening = false) {
    const nextChapterNumber = (this.data.formData.chapters || []).filter(chapter => !chapter.opening).length + 1;
    this.setData({
      popChapterAction: 0,
      assignPendingLocalId: assignPendingLocalId || '',
      chapterForm: emptyChapterForm(opening ? '序章' : `第${nextChapterNumber}章`, opening)
    });
    this.confrimChapter();
  },

  _materializeCityStoryChapters() {
    const city = Number(this.data.formData && this.data.formData.productType) === 1;
    const chapters = JSON.parse(JSON.stringify((this.data.formData && this.data.formData.chapters) || []));
    chapters.forEach((chapter, index) => {
      if (!city && !Array.isArray(chapter.blocks)) return;
      try {
        chapters[index] = proEditorStory.materializeChapter(chapter, () => this._genLocalId());
      } catch (error) {
        // 单章坏数据不崩整页(onLoad / 草稿恢复 / 编辑回填三条路都走这):保留原章节,
        // 打开该章时 openStoryEditor 会再次拦截并 toast。
      }
    });
    this.setData({ 'formData.chapters': chapters });
  },

  _storyCommand(command, uiPatch) {
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapters = JSON.parse(JSON.stringify((this.data.formData && this.data.formData.chapters) || []));
    const chapter = chapters[chapterIndex];
    if (!chapter) throw new Error('未找到故事流章节');
    const result = proEditorStory.applyStoryCommand({
      chapter,
      pendingMaterials: this.data.pendingMaterials || [],
    }, command);
    chapters[chapterIndex] = result.draft.chapter;
    this.setData(Object.assign({
      'formData.chapters': chapters,
      pendingMaterials: result.draft.pendingMaterials,
    }, uiPatch || {}));
    this.updateAllStatistics();
    return result;
  },

  openStoryEditor(e) {
    this.destroyPreviewAudio(); // 换一章/重开编辑器:先停掉上一章里正在播的音频块
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const chapterIndex = Number(ds.index == null ? ds.chapterindex : ds.index);
    const chapters = JSON.parse(JSON.stringify((this.data.formData && this.data.formData.chapters) || []));
    if (!chapters[chapterIndex]) return;
    try {
      chapters[chapterIndex] = proEditorStory.materializeChapter(
        chapters[chapterIndex], () => this._genLocalId());
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '故事流内容不完整，请检查后重试'));
      return;
    }
    this._storyTextCursor = null;
    const when = this._storyWhenPatch();
    const rolePatch = this._storyRolePatch();
    this.setData({
      'formData.chapters': chapters,
      storyWhenOptions: when.storyWhenOptions,
      storyWhenLabelOf: when.storyWhenLabelOf,
      storyEditor: { show: true, chapterIndex, insertMenuAt: -1, focusBlockKey: '', selectedBlockKey: '' },
      storyNodeRoles: rolePatch.storyNodeRoles,
      storyRoleOptions: rolePatch.storyRoleOptions,
      storyVarSheet: { show: false, blockKey: '', keys: [] },
      pendingInsertAt: null,
      // ⚠️ 这里**不**探时长。探时长要 wx.createInnerAudioContext,而动作台账是按调用图
      //    判 actionClass 的 —— 一挂上来,openStoryEditor 连同 onTapAddChapter /
      //    confrimChapter / onMarkerTap 等 9 个控件会一起被判成 external(实测),
      //    "真实点击 vs 外部能力"的分母就废了。存量块只显示从地址派生的文件名;
      //    时长在**上传当下**探(见 insertStoryAudioAt),那条路本来就是 external。
    }, () => this._reflowStoryTextareas(chapterIndex));
  },

  /**
   * 逼 <textarea auto-height> 重算一次高度。
   *
   * ⚠️ 这不是装饰:auto-height 的**首次渲染**用的是 textarea 组件自身的默认高,不是内容高。
   *    实测(2026-09-06)一行文字首帧量到 136px,第二次渲染才收敛到 56px ——
   *    表现就是「打开一个已有文字的章节,每段文字下面空一大截」。
   *    CSS 够不着(height:auto / min-height 都试过,无效),只能再渲染一次。
   *    这里把同一份 blocks 原样写回去:不新增任何 data 字段(那会变成零消费债务),
   *    只借 setData 触发一帧。
   */
  _reflowStoryTextareas(chapterIndex) {
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    if (!chapter || !(chapter.blocks || []).some((b) => b && b.type === 'text')) return;
    wx.nextTick(() => {
      this.setData({ [`formData.chapters[${chapterIndex}].blocks`]: this.data.formData.chapters[chapterIndex].blocks });
    });
  },

  closeStoryEditor() {
    const current = (this.data.formData.chapters || [])[this.data.storyEditor.chapterIndex];
    if ((current && current.blocks || []).some(b => b.type === 'dream' && b.title != null && !b.images.length)) {
      cyToast('相册至少添加 1 张照片');
      return;
    }
    this.destroyPreviewAudio(); // 关编辑器停掉故事流预听,不留一段在后台继续响
    this._storyTextCursor = null;
    // 梦块卡上写着「一张都没有的梦不会保存,先加一张」,收编辑器就得真在本地草稿里丢掉 ——
    // 只在出 payload 那层过滤的话,回到章节卡再重进,那张 0/6 的空卡还在原地(CU-M-115)。
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapters = (this.data.formData && this.data.formData.chapters) || [];
    const discarded = chapterIndex >= 0 && proEditorStory.countEmptyDreamBlocks(chapters[chapterIndex]) > 0;
    if (discarded) {
      const next = JSON.parse(JSON.stringify(chapters));
      next[chapterIndex] = proEditorStory.discardEmptyDreamBlocks(next[chapterIndex]);
      this.setData({ 'formData.chapters': next });
      this.updateAllStatistics();
    }
    this.setData({
      storyEditor: { show: false, chapterIndex: -1, insertMenuAt: -1, focusBlockKey: '', selectedBlockKey: '' },
      storyVarSheet: { show: false, blockKey: '', keys: [] },
      pendingInsertAt: null,
      storyPendingMaterialLocalId: '',
    });
  },

  // 物理返回守卫(见 wxml 尾部 page-container):按层级关最上层浮层,而不是退出编辑器
  onNativeBackGuard() {
    if (this.data.structureEditor && this.data.structureEditor.show) { this.closeStructure(); } // structure
    else if (this.data.runtimeZeroEditor && this.data.runtimeZeroEditor.show) this.closeZeroHpEditor();
    else if (this.data.survivalEditor && this.data.survivalEditor.show) this.closeSurvivalEditor(); // survival
    else if (this.data.chapterPreview && this.data.chapterPreview.show) {
      this.closeChapterPreview();
    } else if (this.data.valueEditor && this.data.valueEditor.show) {
      this.closeValueEditor();
    } else if (this.data.initialStateEditor && this.data.initialStateEditor.show) {
      if (this.data.carryPicker && this.data.carryPicker.show) this.closeCarryPicker(); else this.closeInitialState(); // 开局带着 · 添加层先关
    } else if (this.data.routeMappingShow) {
      this.closeNodeRouteMapping();
    } else if (this.data.popChapterNodes) {
      // 节点抽屉:玩法选择视图先退回详情,详情视图才关抽屉
      if (this.data.nodeSheetView === 'games') this.backToNodeDetail();
      else this.cancelPopChapter();
    } else if (this.data.publishCheck && this.data.publishCheck.show) {
      this.closePublishCheck();
    } else if (this.data.popChapter) {
      this.cancelChapter();
    } else if (this.data.modePickerShow) {
      this.closeModePicker(); } else if (this.data.lookSheetShow) { this.closeLookEditor();
    } else if (this.data.topicDetailShow) {
      this.closeTopicDetail();
    } else if (this.data.storyVarSheet && this.data.storyVarSheet.show) {
      // 变量列表是故事流里叠的一层,返回键先收它再收故事流
      this.closeStoryVarSheet();
    } else if (this.data.storyEditor && this.data.storyEditor.show) {
      this.closeStoryEditor();
    }
    // 关掉一层后若还有受护浮层(浮层叠浮层),翻转一次让 page-container 重新挂上
    const stillGuarded = (this.data.runtimeZeroEditor && this.data.runtimeZeroEditor.show) || (this.data.chapterPreview && this.data.chapterPreview.show) || (this.data.initialStateEditor && this.data.initialStateEditor.show) || (this.data.valueEditor && this.data.valueEditor.show) || this.data.routeMappingShow || this.data.popChapterNodes || this.data.popChapter
      || (this.data.publishCheck && this.data.publishCheck.show)
      || (this.data.storyEditor && this.data.storyEditor.show)
      || this.data.modePickerShow || this.data.topicDetailShow;
    if (stillGuarded) {
      this.setData({ backGuardOff: true });
      setTimeout(() => this.setData({ backGuardOff: false }), 50);
    }
  },

  onStoryChapterNameInput(e) {
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    if (chapterIndex < 0) return;
    this.setData({ ['formData.chapters[' + chapterIndex + '].name']: e.detail.value });
  },

  toggleStoryInsertMenu(e) {
    const index = Number(e.currentTarget.dataset.index);
    const current = Number(this.data.storyEditor.insertMenuAt);
    this.setData({ 'storyEditor.insertMenuAt': current === index ? -1 : index });
  },

  insertStoryTextAt(e) {
    const index = Number(e.currentTarget.dataset.index);
    const blockKey = this._genLocalId();
    try {
      this._storyCommand({ type: 'insertTextAt', index, blockKey }, {
        'storyEditor.insertMenuAt': -1,
        'storyEditor.focusBlockKey': blockKey,
      });
      // 新建的 textarea 首帧同样是默认高,补一次渲染(见 _reflowStoryTextareas)
      this._reflowStoryTextareas(this.data.storyEditor.chapterIndex);
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '添加文字失败'));
    }
  },

  insertStoryNodeAt(e) {
    const index = Number(e.currentTarget.dataset.index);
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    if (!chapter) return;
    if (chapter.opening && e.currentTarget.dataset.kind !== 'game') {
      cyToast('开局请添加故事玩法');
      return;
    }
    const issue = proEditorPolicy.formalNodeCreationIssue(this.data.formData, chapterIndex);
    if (issue) {
      cyToast(issue.message || '请先添加并填写故事文字');
      return;
    }
    // 插地点前确认身份权限已加载，节点数量不限。
    if (e.currentTarget.dataset.kind !== 'game' && !this._canAddFormalNodes(1)) return;
    const pendingLocalId = this.data.storyPendingMaterialLocalId;
    const material = (this.data.pendingMaterials || []).find(item => item._localId === pendingLocalId);
    this._nodeBindingSlots = [];
    this._nodeBindingDraft = null;
    this.setData({
      pendingInsertAt: index,
      'storyEditor.insertMenuAt': -1,
      popChapterNodes: true,
      popChapterNodesAction: 0,
      nodeSheetView: 'detail',
      nodeDraftDestination: 'formal',
      editingPendingLocalId: material ? material._localId : '',
      editTargetChapterLid: chapter._localId,
      editTargetNodeLid: '',
      inlineEdit: null,
      nodesForm: e.currentTarget.dataset.kind === 'game'
        ? Object.assign(this._emptyNodesForm(), { _storyGame: true, showTemplate: true })
        : (material ? proEditorMaterials.nodeFromMaterial(material, 1) : this._emptyNodesForm()),
      nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
      nodePlanEndingRows: [], nodePlanEndingError: '',
    });
  },

  // 图片 / 音频块走与节点实拍同一条上传通道(app.chooseImage / app.chooseDocument)。
  // ⚠️ 先上传、拿到 url 再落块 —— 反过来先插一个空壳块再回填,上传失败时会留下一个
  // 既点不开也说不清的空块,而这块已经算进「每章 200 块」的额度里了。
  insertStoryImageAt(e) {
    if (this._storyMediaUploadOperation && !this._storyMediaUploadOperation.isFinished() && !this._storyMediaUploadOperation.isAborted()) return;
    const index = Number(e.currentTarget.dataset.index);
    const editor = this.data.storyEditor;
    const chapter = (this.data.formData.chapters || [])[editor.chapterIndex];
    if (!chapter) return;
    const chapterId = chapter._localId;
    const memberId = String(app.getUserID() || '');
    const blocks = chapter.blocks || [];
    const beforeKey = blocks[index - 1] && blocks[index - 1].key;
    const afterKey = blocks[index] && blocks[index].key;
    this.setData({ 'storyEditor.insertMenuAt': -1 });
    this._storyMediaUploadOperation = app.chooseImage((res) => {
      const current = (this.data.formData.chapters || [])[editor.chapterIndex];
      if (this._unloaded || this.data.storyEditor !== editor || !editor.show
          || memberId !== String(app.getUserID() || '') || !current || current._localId !== chapterId) return;
      const currentBlocks = current.blocks || [];
      if ((currentBlocks[index - 1] && currentBlocks[index - 1].key) !== beforeKey
          || (currentBlocks[index] && currentBlocks[index].key) !== afterKey) return;
      const url = res && res[0];
      if (!url) return;
      this._insertStoryMedia('image', index, { url });
    }, 1);
  },

  // 故事流里的音频块(2026-09-06 用户裁决恢复,与章节级背景旁白并存):
  // 章节那段是整章底噪、进本章自动播;这一段有位置,读到这儿才响。
  // ⚠️ 收 mp3 系列,不收 mp4 —— 用户 9-06 明确说的是 mp3;mp4 是视频容器,
  //    玩家端用的是 InnerAudioContext,喂视频容器不保证能解。
  // 稿(TE · Screens P8)的流程是两步:先落一个空音频块,再点那个块去选文件。
  // 不在这里直接拉起选择器 —— 那样中途取消就什么都不剩,人不知道自己刚才点没点中。
  insertStoryAudioAt(e) {
    const index = Number(e.currentTarget.dataset.index);
    this.setData({ 'storyEditor.insertMenuAt': -1 });
    try {
      this._insertStoryMedia('audio', index, { url: '' });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '添加音频块失败'));
    }
  },

  // 点空音频块 → 选文件 → 回填到这个块上。文件名当场就有;
  // 时长要等播放器能播才知道,所以先回填 url、再异步补(见 _fillStoryAudioMeta)。
  uploadStoryAudio(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    app.chooseDocument((res) => {
      const file = res && res[0];
      if (!file || !file.url) return;
      try {
        this._storyCommand({
          type: 'fillMedia',
          blockKey,
          url: file.url,
          name: file.filename || proEditorStory.fileNameOf(file.url),
        });
        this._fillStoryAudioMeta();
      } catch (error) {
        cyToast(app.getRequestErrorMessage(error, '音频保存失败'));
      }
    }, 1, ['mp3', 'm4a', 'aac']);
  },

  /**
   * 给故事流里还不知道时长的音频块补上时长。
   *
   * ⚠️ 只在**上传当下**调(insertStoryAudioAt)。时长没法从 url 派生,只能让播放器去读,
   *    而 createInnerAudioContext 会把调用方在动作台账里判成 external —— 挂到
   *    openStoryEditor 上会连累 9 个无关控件(实测),所以重开章节时那些块只显示文件名。
   *    这里对「有 url、没 _duration」的块各起一个一次性的 InnerAudioContext,
   *    onCanplay 拿到 duration 就写回并 destroy。
   * ⚠️ _duration 是本地字段(下划线开头):stripLocalFields 会剥掉,
   *    toPayloadChapter 也只重建 {type,url},所以它进不了 payload,不影响后端块形状。
   */
  _destroyStoryDurationProbes() {
    const cleanups = this._storyDurationProbeCleanups || [];
    this._storyDurationProbeCleanups = [];
    cleanups.forEach(done => done());
  },

  _fillStoryAudioMeta() {
    const chapter = (this.data.formData.chapters || [])[this.data.storyEditor.chapterIndex];
    if (!chapter || !chapter._localId) return;
    const chapterId = chapter._localId;
    const memberId = String(app.getUserID() || '');
    (chapter.blocks || []).forEach((block) => {
      if (!block || !block.key || block.type !== 'audio' || !block.url || block._duration) return;
      const blockKey = block.key;
      const url = block.url;
      const probe = wx.createInnerAudioContext();
      probe.src = url;
      probe.obeyMuteSwitch = false;
      let settled = false;
      const done = () => {
        if (settled) return;
        settled = true;
        this._storyDurationProbeCleanups = (this._storyDurationProbeCleanups || []).filter(item => item !== done);
        try { probe.destroy(); } catch (err) {}
      };
      this._storyDurationProbeCleanups = (this._storyDurationProbeCleanups || []).concat(done);
      probe.onCanplay(() => {
        if (settled) return;
        const chapters = this.data.formData.chapters || [];
        const chapterIndex = chapters.findIndex(item => item && item._localId === chapterId);
        const currentChapter = chapters[chapterIndex];
        const blockIndex = currentChapter ? (currentChapter.blocks || []).findIndex(item => item && item.key === blockKey) : -1;
        const currentBlock = currentChapter && (currentChapter.blocks || [])[blockIndex];
        const seconds = Math.round(probe.duration || 0);
        if (!this._unloaded && memberId === String(app.getUserID() || '')
            && currentBlock && currentBlock.type === 'audio' && currentBlock.url === url && seconds > 0) {
          this.setData({
            [`formData.chapters[${chapterIndex}].blocks[${blockIndex}]._duration`]: seconds,
          });
        }
        done();
      });
      probe.onError(done);
    });
  },

  _insertStoryMedia(mediaType, index, payload) {
    const blockKey = this._genLocalId();
    try {
      this._storyCommand(Object.assign({
        type: 'insertMediaAt', mediaType, index, blockKey,
      }, payload), {
        'storyEditor.insertMenuAt': -1,
        'storyEditor.selectedBlockKey': '',
      });
      return true;
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '添加失败'));
      return false;
    }
  },

  /**
   * 梦块(契约 §3.2):插入缝第 5 个入口。先落一个空块,再一张张加图 ——
   * 微信的 chooseImage 一次只给一组,做不到「一次配好 1–6 张、每张一句话」,
   * 分次添加才让每张图各有一行字可写。
   */
  /**
   * 氛围块:插入缝第 6 个入口。落块时给第一个预设(「无」),作者再在块上换 ——
   * 和音频块同一条规矩:先落块再配,中途取消不会什么都不剩。
   */
  /**
   * 念头块:在正文的这个位置「得到一个念头」。块里只放引用 —— 没配过念头就没得引,
   * 与其插一个空壳让它在发布闸上被拦下,不如当场说清楚去哪配。
   */
  insertStoryThoughtAt(e) {
    // 只认已保存的伏笔(journeyRules)—— 伏笔面板里的列表可能是作者没点「完成」的半截
    const patch = thoughtPatchOf(this.data.formData.journeyRules);
    const options = patch.thoughtOptions;
    if (!options.length) { cyToast('先在「伏笔」那张卡里配一个'); return; }
    const index = Number(e.currentTarget.dataset.index);
    this.setData({ thoughtOptions: options, thoughtNameOf: patch.thoughtNameOf });
    this._insertStoryMedia('thought', index, { thoughtKey: options[0].key });
  },

  // cy-dropdown 回调:换这个块引用哪个念头
  onStoryThoughtChange(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const picked = (this.data.thoughtOptions || [])[Number(e.detail.value)];
    if (!picked) return;
    try {
      this._storyCommand({ type: 'updateThought', blockKey, thoughtKey: picked.key });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '换伏笔失败'));
    }
  },

  /** 「谁能看到」下拉:所有人 / 选过某个选项(本主题玩法里挂了「记住这个选择」的)/ 某个伏笔应验了。 */
  _storyWhenPatch() {
    const options = [{ value: '', label: '所有人都看得到' }];
    storyInitialState.optionTagsOf(this.data.formData.chapters).forEach((t) => options.push({ value: t.tag, label: '选过「' + t.label + '」' }));
    thoughtPatchOf(this.data.formData.journeyRules).thoughtOptions.forEach((t) => options.push({ value: 'thought.' + t.key + '.done', label: '想通「' + t.name + '」' }));
    const labelOf = {};
    options.forEach((o) => { labelOf[o.value] = o.label; });
    return { storyWhenOptions: options, storyWhenLabelOf: labelOf };
  },
  onStoryWhenPick(e) {
    const picked = (this.data.storyWhenOptions || [])[Number(e.detail.value)];
    if (!picked) return;
    try { this._storyCommand({ type: 'setBlockWhen', blockKey: e.currentTarget.dataset.blockkey, value: picked.value }); } catch (error) { cyToast(app.getRequestErrorMessage(error, '修改失败')); }
  },

  // 内心声音 / 异常:两种表现块共用插入与改字段(形状在 pro-editor-story 的 PLAIN_BLOCKS)
  insertStoryPlainAt(e) {
    const d = e.currentTarget.dataset;
    this._insertStoryMedia(d.type, Number(d.index), d.type === 'odd' ? { level: 1 } : { who: d.type === 'voice' ? '规矩' : '', content: '' });
  },
  onStoryPlainPick(e) {
    const d = e.currentTarget.dataset;
    const i = Number(e.detail.value);
    const patch = d.field === 'who' ? { who: this.data.voiceNames[i] } : { level: i };
    try { this._storyCommand({ type: 'updatePlainBlock', blockKey: d.blockkey, patch }); } catch (error) { cyToast(app.getRequestErrorMessage(error, '修改失败')); }
  },
  onStoryPlainInput(e) {
    const d = e.currentTarget.dataset;
    try { this._storyCommand({ type: 'updatePlainBlock', blockKey: d.blockkey, patch: { [d.field]: e.detail.value } }); } catch (error) { cyToast(app.getRequestErrorMessage(error, '修改失败')); }
  },


  // cy-dropdown 回调:拿到的是下标,换算成 key 再进块(存规范值,显示中文)。
  onStoryMoodChange(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const mood = MOOD_PRESET_KEYS[Number(e.detail.value)];
    if (!mood) return;
    try {
      this._storyCommand({ type: 'updateMood', blockKey, mood });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '换氛围失败'));
    }
  },

  // 故事中的玩法选择：相册物化为展示块，其余模板继续走节点配置。
  insertStoryGameAt(e) {
    this.insertStoryNodeAt({ currentTarget: { dataset: { index: e.currentTarget.dataset.index, kind: 'game' } } });
    this.showTemp();
  },
  _insertAlbumTemplate(template) {
    const parsed = advancedGameConfig.parse(template.advancedConfigJson);
    if (parsed.error) { cyToast(parsed.error); return true; }
    if (!parsed.value.album || !parsed.value.album.enabled) return false;
    const error = advancedGameConfig.validate(parsed.value);
    if (error) { cyToast(error); return true; }
    if (!this.data.storyEditor.show || this.data.pendingInsertAt == null) {
      cyToast('请在故事流中添加相册');
      return true;
    }
    const inserted = this._insertStoryMedia('dream', this.data.pendingInsertAt, {
      title: template.title || '相册', images: parsed.value.album.images,
    });
    if (inserted) this.setData({ popChapterNodes: false, pendingInsertAt: null, selectedTempId: null, selectedTempInfo: null });
    return true;
  },
  onAlbumTitleInput(e) {
    try { this._storyCommand({ type: 'updateAlbumTitle', blockKey: e.currentTarget.dataset.blockkey, title: e.detail.value }); }
    catch (error) { cyToast(app.getRequestErrorMessage(error, '修改相册名称失败')); }
  },




  // 加一张:先选图拿到 url 再进块(与 insertStoryImageAt 同一条规矩,不留空壳)。
  addStoryDreamImage(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    app.chooseImage((res) => {
      const url = res && res[0];
      if (!url) return;
      try {
        this._storyCommand({ type: 'appendDreamImage', blockKey, url });
      } catch (error) {
        cyToast(app.getRequestErrorMessage(error, '添加失败'));
      }
    }, 1);
  },

  onStoryDreamLineInput(e) {
    try {
      this._storyCommand({
        type: 'updateDreamLine',
        blockKey: e.currentTarget.dataset.blockkey,
        index: Number(e.currentTarget.dataset.index),
        line: e.detail.value,
      });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '保存失败'));
    }
  },

  removeStoryDreamImage(e) {
    try {
      this._storyCommand({
        type: 'removeDreamImage',
        blockKey: e.currentTarget.dataset.blockkey,
        index: Number(e.currentTarget.dataset.index),
      });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '删除失败'));
    }
  },

  // 媒体块没有「内容为空自己消失」这条路(它要么有 url 要么不存在),所以 ✕ 是唯一删除入口。
  // 与文字块一致:长按选中后才出现 ✕。
  removeStoryMedia(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    // 删掉的正好是当前这一块时,先把播放器停掉再删,免得删完还在响。
    // ⚠️ 按 _previewSource(建播放器时就登记)判断,不能按 data.storyAudioKey ——
    //    后者要等 onPlay,网络缓冲期删块会匹配不到,destroy 不掉,缓冲完照样响(F14 P2)。
    const source = this._previewSource;
    if (blockKey && source && source.kind === 'story' && source.key === blockKey) this.destroyPreviewAudio();
    try {
      this._storyCommand({ type: 'removeMedia', blockKey }, { 'storyEditor.selectedBlockKey': '' });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '删除失败'));
    }
  },

  onStoryTextInput(e) {
    /* 光标只服务「插入变量」,记在实例上不进 data —— 每敲一个字都 setData 一次
       光标位置只为偶尔一次的插入,不值。 */
    const cursor = Number(e.detail && e.detail.cursor);
    this._storyTextCursor = {
      blockKey: e.currentTarget.dataset.blockkey,
      index: Number.isInteger(cursor) ? cursor : -1,
    };
    try {
      this._storyCommand({
        type: 'editText',
        blockKey: e.currentTarget.dataset.blockkey,
        content: e.detail.value,
      });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '文字保存失败'));
    }
  },

  /**
   * 打开「插入变量」列表(契约 §3.1)。可用键跟着主题里各节点的玩法模板走
   * (建档的问题 key + 会产出结果的玩法),所以每次打开现算 —— 存快照迟早对不上。
   */
  openStoryVarSheet(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    const block = chapter && (chapter.blocks || []).find((item) => item && item.key === blockKey);
    /* 点这颗键会让 textarea 失焦;空文字块在失焦时是按既有语义**被删掉**的
       (见 onStoryTextBlur),所以到这儿可能已经没有可插的块了 —— 提示而不是开一张空列表。 */
    if (!block || block.type !== 'text') {
      cyToast('先写几个字，再把变量插到光标处');
      return;
    }
    const keys = proEditorStory.collectStoryVars(this.data.formData.chapters || []);
    // 先收起插入缝,免得两个浮层叠在一起
    this.setData({
      'storyEditor.insertMenuAt': -1,
      storyVarSheet: { show: true, blockKey: blockKey, keys: keys },
    });
  },

  closeStoryVarSheet() {
    this.setData({ storyVarSheet: { show: false, blockKey: '', keys: [] } });
  },

  /**
   * 点一下变量 → 插到光标处(没记到光标就追加到末尾)。
   * ⚠️ 插入后把光标移到 token 后面这件事小程序 textarea 做不到可靠定位
   * (selection-start 要基础库 2.33+ ,且 auto-height 下二次渲染会错位),
   * 所以只保证「插进去了」,连点两次会接在上一段后面(见 _storyTextCursor 的推进)。
   */
  insertStoryVar(e) {
    const sheet = this.data.storyVarSheet;
    const key = e.currentTarget.dataset.key;
    if (!sheet.show || !sheet.blockKey || !key) return;
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    const block = chapter && (chapter.blocks || []).find((item) => item && item.key === sheet.blockKey);
    if (!block || block.type !== 'text') { this.closeStoryVarSheet(); return; }
    const content = String(block.content == null ? '' : block.content);
    const cursor = this._storyTextCursor && this._storyTextCursor.blockKey === sheet.blockKey
      ? this._storyTextCursor.index : -1;
    const at = proEditorStory.varInsertIndex(content, cursor);
    try {
      this._storyCommand({
        type: 'editText',
        blockKey: sheet.blockKey,
        content: proEditorStory.insertVarAt(content, key, cursor),
      }, { 'storyVarSheet.show': false });
      this._storyTextCursor = { blockKey: sheet.blockKey, index: at + String(key).length + 2 };
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '插入变量失败'));
    }
  },

  // 文字块删除:2026-08-10 用户定「直接手动键盘删除就行」,所以页面上不再挂红色
  // 「删除文字块」按钮 —— 一个块的内容全删光,它本身就该消失,再让人点一次按钮 +
  // 确认一次弹窗是三步做一件事。
  // ⚠️ 判空必须在 blur 而不是 input:input 里一路退格,退到最后一个字时块当场被移除,
  // 光标连同 textarea 一起消失,人还在按退格 —— 相当于输入中途把编辑器抽走。
  // ⚠️ 也不弹二次确认:内容已经是空的,没有什么可丢的。
  // 选中态:✕ 只在选中的块上出现(用户 2026-08-10 定「选中的时候再出现」)。
  // 触屏没有 hover,所以映射成两种最不抢主路径的手势:
  //   文字块 = 聚焦即选中(正在写的那块就是当前块);
  //   节点块 = 长按选中(单击仍然是"打开节点弹窗",那是主路径,不能被选中抢走)。
  selectStoryBlock(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    if (this.data.storyEditor.selectedBlockKey === blockKey) return;
    this.setData({ 'storyEditor.selectedBlockKey': blockKey });
  },

  // ✕ 删文字块。内容为空时 blur 会自己删掉(见 onStoryTextBlur),这里管的是
  // 「写了字但整段不要了」—— 有内容才值得二次确认。
  removeStoryText(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    modal.show({
      title: '删除这段文字？',
      content: '前后的文字块会保持原样，不会自动合并。',
      success: (res) => {
        if (!res.confirm) return;
        try {
          this._storyCommand({ type: 'removeText', blockKey }, { 'storyEditor.selectedBlockKey': '' });
        } catch (error) {
          cyToast(app.getRequestErrorMessage(error, '删除失败'));
        }
      },
    });
  },

  onStoryTextBlur(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const blocks = ((this.data.formData.chapters[this.data.storyEditor.chapterIndex] || {}).blocks) || [];
    const block = blocks.filter((b) => b && b.key === blockKey)[0];
    if (!block || block.type !== 'text') return;
    if (String(block.content == null ? '' : block.content).trim() !== '') {
      if (this.data.storyEditor.selectedBlockKey === blockKey) {
        this.setData({ 'storyEditor.selectedBlockKey': '' });
      }
      return;
    }
    try {
      this._storyCommand({ type: 'removeText', blockKey }, { 'storyEditor.selectedBlockKey': '' });
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '删除失败'));
    }
  },

  editStoryNode(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const chapterIndex = Number(this.data.storyEditor.chapterIndex);
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    const block = chapter && (chapter.blocks || []).find(item => item.key === blockKey);
    const nodeIndex = chapter && (chapter.nodes || []).findIndex(item => item._localId === (block && block.nodeKey));
    if (!chapter || !block || nodeIndex < 0) return;
    this.showEditNodes({ currentTarget: { dataset: { chapterindex: chapterIndex, nodeindex: nodeIndex } } });
  },

  removeStoryNode(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    modal.show({
      title: '确认删除节点',
      content: '删除后 5 秒内可以撤销，过后不可恢复。',
      success: (res) => {
        if (res.confirm) this._removeStoryNodeNow(blockKey);
      },
    });
  },

  _appendPendingRemoval(removal) {
    const current = this._pendingUndoSnapshot;
    let removals = [];
    if (current && Array.isArray(current.removals)) {
      removals = current.removals.slice();
    } else if (current && Array.isArray(current.storyRemovals)) {
      removals = current.storyRemovals.map((item) => Object.assign({ kind: 'story' }, item));
    } else if (current && current.block && current.node) {
      removals = [Object.assign({ kind: 'story' }, current)];
    } else if (current && current.material) {
      removals = [Object.assign({ kind: 'material' }, current)];
    }
    this._pendingUndoSnapshot = {
      removals: removals.concat([removal]),
      route: removals.length && current.route ? current.route : {
        mode: this.data.formData.routeMode,
        json: this.data.formData.routeGraphJson,
        graph: JSON.parse(JSON.stringify(this._routeGraph)),
      },
    };
  },

  _removeStoryNodeNow(blockKey) {
    const previousRouteNodes = this._routeNodes();
    try {
      const result = this._storyCommand({ type: 'removeNode', blockKey }, {
        popChapterNodes: false,
        popChapterNodesAction: 0,
        'storyEditor.selectedBlockKey': '',
        pendingUndo: { show: true, materialLocalId: '', text: '节点已删除' },
      });
      const removed = result.removed;
      // ⚠️ 2026-08-11:removeNode 改真删后,removed 里【不再有 material】(不留影子副本)。
      // 这里原先还在读 removed.material._localId —— 上一轮改命令时漏改的调用方,
      // 结果是:_storyCommand 已经把节点删掉并落盘,下一行才抛 TypeError,被本函数外层
      // 那个 try 吞成一句英文报错 toast,而后面的 setTimeout 根本没跑 ⇒
      // 「节点已经删了、提示却说出错、撤销条永远挂在屏幕上」。
      // 撤销认的是 node._localId,materialLocalId 只是提示条上的标识,同源即可。
      const removalSnapshot = {
        chapterLid: result.draft.chapter._localId,
        blockIndex: removed.blockIndex,
        block: removed.block,
        node: removed.node,
        binding: this._removeNodeTemplateBinding(removed.node),
        ruleRefs: this._removeNodeRuleRefs(removed.node),
      };
      this._appendPendingRemoval(Object.assign({ kind: 'story' }, removalSnapshot));
      this._realignStoryRouteGraph(previousRouteNodes);
      this._pruneRouteGraphToCurrentNodes();
      this._commitNodeRouteEdit();
      this.setData({ 'pendingUndo.materialLocalId': removed.node._localId });
      if (this._pendingUndoTimer) clearTimeout(this._pendingUndoTimer);
      this._pendingUndoTimer = setTimeout(() => {
        this._pendingUndoTimer = null;
        this._pendingUndoSnapshot = null;
        this.setData({ pendingUndo: { show: false, materialLocalId: '', text: '' } });
      }, 5000);
      if (this._pendingUndoTimer && typeof this._pendingUndoTimer.unref === 'function') {
        this._pendingUndoTimer.unref();
      }
      cyToast(removalSnapshot.ruleRefs && removalSnapshot.ruleRefs.note ? '节点已删除，' + removalSnapshot.ruleRefs.note : '节点已删除');
    } catch (error) {
      cyToast(app.getRequestErrorMessage(error, '删除失败'));
    }
  },

  _createEmptyNode(sortId) {
    return {
      _localId: this._genLocalId(),
      name: '',
      subtitle: '',
      description: '',
      address: '',
      longitude: '',
      latitude: '',
      imgUrl: '',
      nodeTime: 30,
      showTemplate: false,
      templateId: 0,
      templateInfo: {},
      templateName: '',
      businessTime: '',
      sortID: sortId || 1,
      // 自由定向(mode2)选填叙事文案:留空后端回退用 description
      hookText: '',
      cardHookLong: '',
      fragmentText: ''
    };
  },

  addChapterDirect() {
    // 兼容旧调用名，但创建必须走弹窗事务：取消时不留下空章节/空节点。
    this.onTapAddChapter();
  },

  isBlankCreationCanvas() {
    const chapters = (this.data.formData && this.data.formData.chapters) || [];
    return chapters.length === 0 && !(this.data.pendingMaterials || []).length;
  },

  onTapPrimaryStarter() {
    const policy = proEditorPolicy.evaluateProfessionalDraft({
      formData: this.data.formData,
      clubId: this.data.formData && this.data.formData.clubId,
      pendingMaterials: this.data.pendingMaterials,
      categoryIds: this.data.selectedCategoryIds,
    });
    const action = this.data.starterAction || policy.starterAction;
    if (!action) return;
    // 2026-09-05 用户裁决:三条起点收敛成同一颗「创建章节」——
    // 原来的三个起手动作(写第一章 / 添加第一个地点 / 添加第一个探索节点)落点其实是同一个:
    // 都要先有章节。三句话只是把同一件事说了三遍,反而让人以为选错起点就走不通。
    //
    // ⚠️ 只统一「按钮文案与第一步」,后面那一步照旧按模式分 —— 建完章节把人带到能干活的地方:
    //   城市定向:confrimChapter 内部会打开故事流(cityStoryFlow 分支),这里不用再管;
    //   自由探索:没有故事流,沿用 2026-08-20 的拍板,直接把节点弹窗开在第 1 章上,
    //            否则建完章节人停在一张空章节卡前,不知道下一步点哪。
    // startClubPlaceCapture 没删:待编排区的「继续录入地点」还在用它。
    const hadChapters = (this.data.formData.chapters || []).length > 0;
    this._createChapterDirect();
    if (Number(this.data.formData.productType) !== 1 && !hadChapters) {
      this.showAddNode({ currentTarget: { dataset: { chapterIndex: 0 } } });
    }
  },

  _emptyNodesForm() {
    return {
      name: '', subtitle: '', description: '', address: '', longitude: '', latitude: '',
      imgUrl: '', nodeTime: 30, showTemplate: false, templateId: 0, templateInfo: {},
      templateName: '', businessTime: '', sortID: 1, hookText: '', cardHookLong: '',
      fragmentText: '',
    };
  },

  openPendingNodeDraft() {
    this._nodeBindingSlots = [];
    this._nodeBindingDraft = null;
    this.setData({
      popChapterNodes: true,
      popChapterNodesAction: 0,
      nodeSheetView: 'detail',
      nodeDraftDestination: 'pending',
      editingPendingLocalId: '',
      editTargetChapterLid: '',
      editTargetNodeLid: '',
      inlineEdit: null,
      nodesForm: this._emptyNodesForm(),
      nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
      nodePlanEndingRows: [], nodePlanEndingError: '',
    });
  },

  onNodeNameInput(e) {
    this.setData({ 'nodesForm.name': e.detail.value });
  },

  editPendingMaterial(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const localId = ds.localid || ds.localId;
    const material = (this.data.pendingMaterials || []).find(item => item._localId === localId);
    if (!material) {
      cyToast('未找到待编排素材');
      return;
    }
    const pendingNode = proEditorMaterials.nodeFromMaterial(material, 1);
    this.setData({
      popChapterNodes: true,
      popChapterNodesAction: 0,
      nodeSheetView: 'detail',
      nodeDraftDestination: 'pending',
      editingPendingLocalId: localId,
      editTargetChapterLid: '',
      editTargetNodeLid: '',
      inlineEdit: null,
      nodesForm: pendingNode,
    });
    this._openNodeBinding(pendingNode);
  },

  _savePendingMaterial(material, message) {
    const pending = (this.data.pendingMaterials || []).slice();
    const index = pending.findIndex(item => item._localId === material._localId);
    if (index >= 0) pending[index] = material;
    else pending.push(material);
    this.setData({
      pendingMaterials: pending,
      popChapterNodes: false,
      popChapterNodesAction: 0,
      nodeSheetView: 'detail',
      editingPendingLocalId: '',
      nodeDraftDestination: 'formal',
      nodesForm: this._emptyNodesForm(),
    });
    this.updateAllStatistics();
    if (message) cyToast(message);
  },

  removePendingMaterial(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const localId = ds.localid || ds.localId;
    const material = (this.data.pendingMaterials || []).find(item => item._localId === localId);
    if (!material) return;
    modal.show({
      title: '删除这个素材？',
      content: '素材尚未进入正式章节，删除后无法恢复。',
      success: (res) => {
        if (!res.confirm) return;
        this.setData({
          pendingMaterials: (this.data.pendingMaterials || []).filter(item => item._localId !== localId),
        });
        this.updateAllStatistics();
      },
    });
  },

  _canAddFormalNodes(additionalCount) {
    if (!roleGuard.hasSnapshot()) {
      // 快照没拿到(比如部署重启窗口里 /api/role/info 回了 502)就顺手重拉一次 ——
      // 不重拉的话这句「稍候重试」是假的:重试多少次都一样,作者只能退出页面,弹层里配好的玩法白配。
      roleGuard.load();
      cyToast('权限加载中，请稍候重试');
      return false;
    }
    return true; // 节点数量不限；上面只确认身份权限已加载。
  },

  arrangePendingIntoChapter(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const localId = ds.localid || ds.localId;
    const chapterIndex = Number(e && e.detail && e.detail.value);
    const pending = (this.data.pendingMaterials || []).slice();
    const materialIndex = pending.findIndex(item => item._localId === localId);
    const chapters = JSON.parse(JSON.stringify((this.data.formData && this.data.formData.chapters) || []));
    const material = pending[materialIndex];
    const chapter = chapters[chapterIndex];
    if (!material || !chapter) {
      cyToast('未找到素材或章节');
      return;
    }
    if (!proEditorPolicy.hasUsableCoords(material)) {
      cyToast('请先补齐有效地点坐标');
      this.editPendingMaterial({ currentTarget: { dataset: { localid: localId } } });
      return;
    }
    if (Number(this.data.formData.productType) === 1) {
      this.setData({ storyPendingMaterialLocalId: localId });
      this.openStoryEditor({ currentTarget: { dataset: { index: chapterIndex } } });
      cyToast(proEditorPolicy.hasRealStory(chapter)
          ? '请点击目标缝插入节点' : '请先添加文字，再点击目标缝插入节点');
      return;
    }
    const issue = proEditorMaterials.arrangementIssue({
      productType: this.data.formData.productType,
      chapter,
      material,
    });
    if (issue) {
      cyToast(issue.message);
      if (issue.field === 'chapterStory') {
        this.showEditChapter({ currentTarget: { dataset: { index: chapterIndex } } });
      } else {
        this.editPendingMaterial({ currentTarget: { dataset: { localid: localId } } });
      }
      return;
    }
    if (!this._canAddFormalNodes(1)) return;
    chapter.nodes = chapter.nodes || [];
    chapter.nodes.push(proEditorMaterials.nodeFromMaterial(material, chapter.nodes.length + 1));
    pending.splice(materialIndex, 1);
    this._pushUndo();
    this.setData({ 'formData.chapters': chapters, pendingMaterials: pending });
    this.updateAllStatistics();
    cyToast.success('已放入' + (chapter.name || '章节'));
  },

  createChapterForPending(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const localId = ds.localid || ds.localId;
    const material = (this.data.pendingMaterials || []).find(item => item._localId === localId);
    if (!material) return;
    if (!proEditorPolicy.hasUsableCoords(material)) {
      cyToast('请先补齐有效地点坐标');
      this.editPendingMaterial({ currentTarget: { dataset: { localid: localId } } });
      return;
    }
    // 2026-08-20:归章同样不弹窗,直接落「第N章」并把素材归入
    this._createChapterDirect(localId);
  },

  _scheduleClubPoiContinuation(callback) {
    setTimeout(callback, 80);
  },

  startClubPlaceCapture() {
    if (this.data.clubPlaceCaptureActive) return;
    this.setData({ clubPlaceCaptureActive: true });
    this._pickNextClubPlace();
  },

  _pickNextClubPlace() {
    if (!this.data.clubPlaceCaptureActive) return;
    pickLocation({
      onPick: (poi) => {
        const node = this._emptyNodesForm();
        node._localId = this._genLocalId();
        node.name = poi.name || '';
        node.address = poi.address || poi.name || '';
        node.longitude = poi.longitude;
        node.latitude = poi.latitude;
        const material = proEditorMaterials.materialFromNode(node, 'place');
        const pending = (this.data.pendingMaterials || []).concat([material]);
        this.setData({ pendingMaterials: pending });
        this.updateAllStatistics();
        cyToast('已录入地点，可继续添加');
        this._scheduleClubPoiContinuation(() => this._pickNextClubPlace());
      },
      onCancel: () => this.setData({ clubPlaceCaptureActive: false }),
      onFail: (error) => {
        if (error && (error.code === 'POI_COORDS_MISSING' || error.code === 'POI_CITY_ONLY')) {
          this._scheduleClubPoiContinuation(() => this._pickNextClubPlace());
        } else {
          this.setData({ clubPlaceCaptureActive: false });
        }
      },
    });
  },

  _prepareInlineNode(chapterIndex, nodeIndex) {
    const ci = Number(chapterIndex);
    const ni = Number(nodeIndex);
    const chapters = this.data.formData.chapters || [];
    const chapter = chapters[ci];
    if (!chapter || !chapter.nodes || !chapter.nodes[ni]) return null;
    const node = chapter.nodes[ni];
    this.setData({
      inlineEdit: { chapterIndex: ci, nodeIndex: ni },
      editTargetChapterLid: chapter._localId,
      editTargetNodeLid: node._localId,
      nodesForm: Object.assign({}, node)
    });
    return node;
  },

  _commitInlineNodeAt(chapterIndex, nodeIndex, updates) {
    const ci = Number(chapterIndex);
    const ni = Number(nodeIndex);
    if (isNaN(ci) || isNaN(ni)) return false;
    const chapters = JSON.parse(JSON.stringify(this.data.formData.chapters || []));
    const chapter = chapters[ci];
    if (!chapter || !Array.isArray(chapter.nodes) || !chapter.nodes[ni]) {
      cyToast('未找到节点，请重试');
      return false;
    }
    const node = chapter.nodes[ni];
    Object.assign(node, updates);
    if (!node.description) {
      node.description = node.name || node.address || '';
    }
    chapter.nodes[ni] = node;
    chapters[ci] = chapter;
    this.setData({
      'formData.chapters': chapters,
      selectedNodeLid: node._localId,
      inlineEdit: { chapterIndex: ci, nodeIndex: ni },
      nodesForm: Object.assign({}, node)
    });
    this.updateAllStatistics();
    return true;
  },

  _startMapPickForNode(chapterIndex, nodeIndex) {
    const ci = Number(chapterIndex);
    const ni = Number(nodeIndex);
    const chapter = (this.data.formData.chapters || [])[ci];
    const node = chapter && chapter.nodes && chapter.nodes[ni];
    if (!node) {
      cyToast('未找到节点');
      return;
    }
    this.setData({
      inlineEdit: { chapterIndex: ci, nodeIndex: ni },
      editTargetChapterLid: chapter._localId,
      editTargetNodeLid: node._localId,
      nodesForm: Object.assign({}, node)
    });
    this.setEditorState('addNode');
    cyToast('拖动地图对准位置后确认');
  },

  pickNodePhoto(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    if (chapterindex == null || nodeindex == null) return;
    const that = this;
    const ch = (this.data.formData.chapters || [])[chapterindex];
    const node = ch && ch.nodes && ch.nodes[nodeindex];
    if (!ch || !node || !ch._localId || !node._localId) return;
    const chapterId = ch._localId;
    const nodeId = node._localId;
    const memberId = String(app.getUserID() || '');
    const drawerForm = this.data.nodesForm;
    const inlineEdit = this.data.inlineEdit;
    const cur = (node && node.imgUrl) ? node.imgUrl.split(',').filter(Boolean) : [];
    const remain = 9 - cur.length;
    if (remain <= 0) { cyToast('每个节点最多9张'); return; }
    app.chooseImage(function (res) {
      if (that._unloaded || memberId !== String(app.getUserID() || '')
          || drawerForm !== that.data.nodesForm || inlineEdit !== that.data.inlineEdit) return;
      const chapters = that.data.formData.chapters || [];
      const ci = chapters.findIndex(item => item && item._localId === chapterId);
      const chapter = chapters[ci];
      const ni = chapter ? (chapter.nodes || []).findIndex(item => item && item._localId === nodeId) : -1;
      const currentNode = chapter && (chapter.nodes || [])[ni];
      if (!currentNode) return;
      const current = String(currentNode.imgUrl || '').split(',').filter(Boolean);
      const next = current.concat(Array.isArray(res) ? res : []).slice(0, 9);
      that._commitInlineNodeAt(ci, ni, { imgUrl: next.join(',') });
    }, remain, { crop: true, cropScale: NODE_PHOTO_RATIO });
  },

  pickNodeLocation(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    if (chapterindex == null || nodeindex == null) {
      cyToast('节点信息不完整，请检查后重试');
      return;
    }
    const that = this;
    // pickLocation 已经保证:回调进来的 poi 一定带可用坐标(没坐标它自己 toast + 走 onFail),
    // 所以这里不再判空写空串 —— 那正是「地址显示得好好的、下一步却说没加地点」的成因。
    pickLocation({
      onPick(poi) {
        that._commitInlineNodeAt(chapterindex, nodeindex, {
          name: ((that.data.formData.chapters || [])[chapterindex].nodes || [])[nodeindex].name || poi.name || '',
          address: poi.address || poi.name || '',
          longitude: String(poi.longitude),
          latitude: String(poi.latitude)
        });
        that.setData({
          mapCenter: { longitude: poi.longitude, latitude: poi.latitude },
          mapScale: Math.max(that.data.mapScale || 12, 16)
        });
      }
    });
  },

  onTapAddNode(e) {
    const chapterIndex = e.currentTarget.dataset.chapterIndex;
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    if (!chapter) return;
    if (Number(this.data.formData.productType) === 1) {
      this.openStoryEditor({ currentTarget: { dataset: { index: chapterIndex } } });
      cyToast('请点击目标缝添加节点');
      return;
    }
    const issue = proEditorPolicy.formalNodeCreationIssue(this.data.formData, chapterIndex);
    if (issue) {
      cyToast(issue.message);
      this.showEditChapter({ currentTarget: { dataset: { index: chapterIndex } } });
      return;
    }
    this.showAddNode({ currentTarget: { dataset: { chapterIndex } } });
  },

  onTapQuickAdd() {
    this.onTapPrimaryStarter();
  },

  onNodeStoryChange(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const value = e.detail.value;
    this.setData({
      [`formData.chapters[${chapterindex}].nodes[${nodeindex}].description`]: value
    });
  },

  // 自由定向(mode2)选填叙事三字段:直写 formData.chapters,与 onNodeStoryChange 同一模式
  onNodeHookInput(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    this.setData({
      [`formData.chapters[${chapterindex}].nodes[${nodeindex}].hookText`]: e.detail.value
    });
  },

  onNodeCardHookInput(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    this.setData({
      [`formData.chapters[${chapterindex}].nodes[${nodeindex}].cardHookLong`]: e.detail.value
    });
  },

  onNodeFragmentInput(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    this.setData({
      [`formData.chapters[${chapterindex}].nodes[${nodeindex}].fragmentText`]: e.detail.value
    });
  },

  onChapterDescChange(e) {
    const { chapterindex } = e.currentTarget.dataset;
    this.setData({
      [`formData.chapters[${chapterindex}].description`]: e.detail.value
    });
  },

  toggleChapterDescExpand(e) {
    const { chapterindex } = e.currentTarget.dataset;
    const key = String(chapterindex);
    const expanded = !!(this.data.chapterDescExpanded && this.data.chapterDescExpanded[key]);
    this.setData({
      [`chapterDescExpanded.${key}`]: !expanded
    });
  },

  toggleNodeStoryExpand(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const key = chapterindex + '-' + nodeindex;
    const expanded = !!(this.data.nodeStoryExpanded && this.data.nodeStoryExpanded[key]);
    this.setData({
      [`nodeStoryExpanded.${key}`]: !expanded
    });
  },

  openNarrativeEditor(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const chapter = (this.data.formData.chapters || [])[chapterindex];
    const node = chapter && chapter.nodes && chapter.nodes[nodeindex];
    if (!chapter || !node) return;
    const textParts = [node.description, node.hookText, node.cardHookLong, node.fragmentText]
      .map(item => (item || '').trim())
      .filter((item, index, arr) => item && arr.indexOf(item) === index);
    this.setData({
      narrativeEditor: {
        show: true,
        chapterIndex: Number(chapterindex),
        nodeIndex: Number(nodeindex),
        chapterName: chapter.name || '',
        nodeName: node.name || '',
        description: textParts.join('\n'),
        hookText: '',
        cardHookLong: '',
        fragmentText: ''
      }
    });
  },

  closeNarrativeEditor() {
    this.setData({
      'narrativeEditor.show': false
    });
  },

  onNarrativeEditorInput(e) {
    const field = e.currentTarget.dataset.field;
    if (!field) return;
    this.setData({
      [`narrativeEditor.${field}`]: e.detail.value
    });
  },

  saveNarrativeEditor() {
    const editor = this.data.narrativeEditor || {};
    const ci = editor.chapterIndex;
    const ni = editor.nodeIndex;
    if (ci < 0 || ni < 0) return;
    this.setData({
      [`formData.chapters[${ci}].nodes[${ni}].description`]: editor.description || '',
      [`formData.chapters[${ci}].nodes[${ni}].hookText`]: editor.hookText || '',
      [`formData.chapters[${ci}].nodes[${ni}].cardHookLong`]: editor.cardHookLong || '',
      [`formData.chapters[${ci}].nodes[${ni}].fragmentText`]: editor.fragmentText || '',
      'narrativeEditor.show': false
    });
  },

  removeNodeGame(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const chapters = [...this.data.formData.chapters];
    const chapter = chapters[chapterindex];
    if (!chapter || !chapter.nodes || !chapter.nodes[nodeindex]) return;
    this._pushUndo();
    const node = { ...chapter.nodes[nodeindex] };
    node.templateId = 0;
    node.templateInfo = {};
    node.showTemplate = false;
    node.templateName = '';
    chapters[chapterindex].nodes[nodeindex] = node;
    this.setData({ 'formData.chapters': chapters });
    this._removeNodeTemplateBinding(node);
    this.updateAllStatistics();
    this.buildRouteMap();
  },

  openNodeGame(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const chapter = this.data.formData.chapters[chapterindex];
    const node = chapter && chapter.nodes && chapter.nodes[nodeindex];
    this.showEditNodes({
      currentTarget: { dataset: { chapterindex, nodeindex } }
    });
    this.setData({
      nodeSheetView: 'games',
      selectedTempId: node && node.templateId ? node.templateId : null,
      selectedTempSource: node && node.templateId ? 'mine' : '',
      selectedTempInfo: node && node.templateInfo && node.templateInfo.title ? node.templateInfo : null
    });
  },


  // editorState 状态机切换器 合法值:idle/addNode/moveNode/editNode/editGame/preview/publishCheck
  setEditorState(s) {
    this.setData({ editorState: s });
  },

  // —— 地图中心准星加点/移点(对标 Strava 移动端落点)——
  // idle 态浮动入口:进入地图加点态
  enterAddNodeOnMap() {
    this.setEditorState('addNode');
  },

  // 进入移点态(供后续移动已有节点用):接 data-chapterlid/data-nodelid,缺省回退到 editTargetChapterLid/NodeLid
  enterMoveNode(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    this.setData({
      editTargetChapterLid: ds.chapterlid || this.data.editTargetChapterLid,
      editTargetNodeLid: ds.nodelid || this.data.editTargetNodeLid
    });
    this.setEditorState('moveNode');
  },

  // 地图拖动结束:仅记录中心点到实例变量,不 setData(性能 R7)
  onRegionChange(e) {
    if (e.type !== 'end') return;
    const that = this;
    // getCenterLocation 返回坐标系为 gcj02,与 <map> 一致,无需转换
    wx.createMapContext('routeMap', this).getCenterLocation({
      success(r) {
        that._pickCenter = { longitude: r.longitude, latitude: r.latitude };
      }
    });
  },

  // 取消地图加点/移点
  cancelMapEdit() {
    this.setEditorState('idle');
    this._pickCenter = null;
  },

  // 保存当前中心点为节点坐标
  saveNodeLocation() {
    const that = this;
    const proceed = (center) => {
      const state = that.data.editorState;
      if (state === 'addNode') {
        const ie = that.data.inlineEdit;
        if (ie) {
          const addr = that.data.nodesForm.address || '';
          that._commitInlineNodeAt(ie.chapterIndex, ie.nodeIndex, {
            longitude: String(center.longitude),
            latitude: String(center.latitude),
            address: addr
          });
          that.setData({
            mapCenter: { longitude: center.longitude, latitude: center.latitude },
            mapScale: Math.max(that.data.mapScale || 12, 16)
          });
          that.setEditorState('idle');
          that._pickCenter = null;
          return;
        }
        const chapters = (that.data.formData && that.data.formData.chapters) || [];
        const firstChapter = chapters[0];
        if (!firstChapter) {
          cyToast('请先添加章节');
          return;
        }
        that.setData({
          editTargetChapterLid: firstChapter._localId,
          editTargetNodeLid: '',
          popChapterNodes: true,
          popChapterNodesAction: 0,
          'nodesForm.name': '',
          'nodesForm.address': '',
          'nodesForm.description': '',
          'nodesForm.imgUrl': '',
          'nodesForm.longitude': center.longitude,
          'nodesForm.latitude': center.latitude,
          mapCenter: { longitude: center.longitude, latitude: center.latitude },
          mapScale: Math.max(that.data.mapScale, 16)
        }, function () {
          that.buildRouteMap();
        });
        that.setEditorState('idle');
      } else if (state === 'moveNode') {
        const chapters = [...((that.data.formData && that.data.formData.chapters) || [])];
        const ci = chapters.findIndex(c => c._localId === that.data.editTargetChapterLid);
        if (ci < 0) { cyToast('未找到章节'); return; }
        const nodes = [...(chapters[ci].nodes || [])];
        const ni = nodes.findIndex(n => n._localId === that.data.editTargetNodeLid);
        if (ni < 0) { cyToast('未找到节点'); return; }
        nodes[ni] = Object.assign({}, nodes[ni], {
          longitude: center.longitude,
          latitude: center.latitude
        });
        chapters[ci].nodes = nodes;
        that.setData({ 'formData.chapters': chapters });
        that.updateAllStatistics();
        that.setEditorState('idle');
      }
      that._pickCenter = null;
    };

    if (this._pickCenter) {
      proceed(this._pickCenter);
    } else {
      wx.createMapContext('routeMap', this).getCenterLocation({
        success(r) { proceed({ longitude: r.longitude, latitude: r.latitude }); },
        fail() { cyToast('获取中心点失败'); }
      });
    }
  },

  /**
   * 生命周期函数--监听页面加载
   */
  // 页内隐私弹窗:没有它 app.js 会回退到 navigateTo(/pages/privacy/index),
  // 把本页整个盖住 —— 审计里那批「route 回读为隐私页、节点数 0」就是这么来的。
  showPrivacyGate() {
    this.setData({ privacyGateShow: true });
  },
  onPrivacyGateSettled() {
    this.setData({ privacyGateShow: false });
  },

  onCreatorLogin() {
    return retryLogin(this, app, this._resumeCreatorLoad);
  },
  _resumeCreatorLoad() { if (this._creatorOwnerChanged()) return; this.onLoad(this._creatorEntryOptions || {}); },
  _creatorOwnerChanged() {
    if (!this._creatorOwner || this._creatorOwner === memberId(app)) return false;
    this.setData({ creatorLoginRequired: true });
    wx.switchTab({ url: '/pages/template/index' });
    return true;
  },

  onLoad(options) {
    this._creatorEntryOptions = options || {};
    if (!memberId(app)) {
      this.setData({ creatorLoginRequired: true });
      waitForInitialSession(this, app, this._resumeCreatorLoad);
      return;
    }
    this._creatorOwner = memberId(app);
    this._cloudAutosaveEnabled = true;
    this.setData({ creatorLoginRequired: false });
    options = options || {};
    this._loadSupportMatrix();
    this._routeGraph = topicRouteGraph.emptyGraph();
    this._routeMappingSourceNodeId = '';
    this._routeMappingReturnToNodeEditor = false;
    this._routeRuleDrafts = {};
    this._installDraftAutosave();
    this._draftAutosaveReady = false;
    const editingTopicId = options.id ? String(options.id) : '';
    const entryMode = Number(options.mode) === 2 ? 2 : 1;
    let windowInfo = null;
    let menuButtonInfo = null;
    try {
      windowInfo = (wx.getWindowInfo && wx.getWindowInfo()) || wx.getSystemInfoSync();
      const getMenuButtonInfo = wx.getMenuButtonBoundingClientRect || wx.getMenuButtonBoundingRect;
      menuButtonInfo = getMenuButtonInfo && getMenuButtonInfo.call(wx);
    } catch (e) {}
    // 2026-09-06 从 clubId 入口进来 = 归属俱乐部已锁死。它只喂 applyAiDraft 的回填判断,
    // 不上屏 —— 原来那句唯一的 WXML 用法(「愿意商家参与 / 开放给商家市场」按入口切文案)
    // 已按 0905 用户拍板删掉,再走 setData 就是白渲染一次。放实例属性。
    this._lockClub = !!options.clubId;
    this.setData({
      editingTopicId,
      editLoading: !!editingTopicId,
      editLoadError: '',
      operationScope: options.scope === 'MERCHANT' ? 'MERCHANT' : '',
      'formData.name': options.templateName ? decodeURIComponent(options.templateName) : '',
      'formData.clubId': options.clubId || '',   // 从俱乐部入口发团:预选并锁定归属
      'editingTicket.mode': entryMode,
      chrome: resolveMenuChrome(windowInfo, menuButtonInfo),
    });

    // movable-view y 单位是 px,把行定高按屏宽折算成 px。
    // ⚠️ CARD_H_RPX 必须等于 index.wxss 里 .slopes-node-row 的 height(120) + margin-bottom(16)。
    //    两边对不上 → 松手后落点算偏,拖一行掉两行。
    const CARD_H_RPX = 136;
    const sys = wx.getSystemInfoSync();
    this.setData({ rowHpx: Math.round(CARD_H_RPX * sys.windowWidth / 750) });

    this._syncMerchantPoolEligibility();       // 先按现有快照算一次,首帧不闪
    roleGuard.load(() => {                     // 预热权限缓存
      this._syncMerchantPoolEligibility();     // 快照刷新后重算
    });
    this.initData();
    // 顺序不可颠倒:两者都以 isBlankTopicDraft() 为门控,谁先跑谁赢。
    // starter 先跑会灌满 formData → applyAiDraft 门控直接 return → AI 草稿静默失效(且 ai_topic_draft 不被清、滞留到下次)。
    // ⚠️ 编辑既有主题时两条都跳过:它们是「新建时灌初值」的路径,会跟异步回填抢同一批字段
    //    (谁后到谁赢),等于让草稿内容随网络快慢随机被覆盖。
    let restoredLocalDraft = false;
    if (!editingTopicId) {
      restoredLocalDraft = this._bootstrapNewLocalDraft(options);
    }
    if (!editingTopicId && !restoredLocalDraft) {
      this.applyAiDraft(options);
    }
    const restoredMode = Number(this.data.formData.productType) === 2 ? 2 : 1;
    this.applyEntryMode(restoredLocalDraft && options.mode == null ? restoredMode : entryMode);
    this.captureEntryTicketSnapshot();
    this.getUserData();
    this.loadMyClubs();
    this._ensureLocalIds();
    this._materializeCityStoryChapters();
    this.setDefaultDates();
    this.getTempList();
    this.getPublicTempList();

    // 生成日期列表
    this.generateDateList();

    // 初始化统计信息
    this.updateAllStatistics();

    if (!editingTopicId) {
      this._draftAutosaveReady = true;
      // AI 草稿在 onLoad 内会被消费；这里立刻把当前完整页面快照接力到专业草稿信封，
      // 避免用户尚未触发下一次输入就杀进程时丢失已消费内容。
      const storedLocally = this._persistDraftEnvelope();
      // 恢复出的本机稿可能还没上过云:按一次编辑处理(标本机状态并排自动保存);
      // 仅带入口参数打开、用户还没动过的新稿不自动建云端主题
      if (restoredLocalDraft) this._onDraftEdited(storedLocally);
      if (options.clubId && !restoredLocalDraft && this.isBlankCreationCanvas()) {
        setTimeout(() => this.startClubPlaceCapture(), 0);
      }
    }

    // 放在最后:回填是异步的,要盖掉上面所有「新建默认值」(setDefaultDates/applyEntryMode 等)
    if (editingTopicId) {
      this.loadEditingTopic(editingTopicId);
    }
    // 实名状态开页查一次:已登记的人不该在发布确认里再看到那三格(值也永不下发,只有真/假)
    const identityStatusEpoch = (this._identityStatusEpoch || 0) + 1;
    this._identityStatusEpoch = identityStatusEpoch;
    const identityMemberId = String((app.getUserID && app.getUserID()) || '');
    publisherIdentity.loadIdentityStatus((registered) => {
      if (!registered || identityStatusEpoch !== this._identityStatusEpoch
          || identityMemberId !== String((app.getUserID && app.getUserID()) || '')) return;
      this.setData({ identityRegistered: true }, () => {
        this.syncIdentityReady();
        if (this.data.publishCheck.show) {
          this.setData({ 'publishCheck.passed': this._buildPublishPassed(this._publishPrecheckOk) });
        }
      });
    });
  },

  /** [B3b] 带 ?id= 进来 → 拉本人主题全量详情回填表单。 */
  loadEditingTopic(id) {
    const that = this;
    const owner = memberId(app);
    if (!owner) { this.setData({ creatorLoginRequired: true }); return; }
    const requestToken = (this._editLoadToken || 0) + 1;
    this._editLoadToken = requestToken;
    const isCurrent = () => !that._unloaded && owner === memberId(app) && requestToken === that._editLoadToken;
    this.setData({ editLoading: true, editLoadError: '', editLoadIsAccess: false });
    const recover = (res, status) => !that._unloaded && requestToken === that._editLoadToken && recoverReadSession(that, app, res,
      function (anonymous) { this._editLoadToken++; this.setData({ 'formData.chapters': [], 'formData.tickets': [], 'formData.name': '', 'formData.description': '', editLoading: false, editLoadError: anonymous ? '请登录后读取主题' : '请重新读取主题', creatorLoginRequired: anonymous, editLoadIsAccess: false }); },
      () => that.loadEditingTopic(id), status);
    app.sendRequest({
      url: '/api/topic/author-edit-detail',
      method: 'POST',
      hideLoading: true,
      silentError: true,
      data: { id, scope: this.data.operationScope },
      success(res) {
        if (requestToken !== that._editLoadToken || owner !== memberId(app) || that._unloaded) return;
        if (res.code != '200' || !isEditingTopicPayload(res.data)) {
          that._showEditingTopicLoadError(res, '主题内容不完整，请重试');
          return;
        }
        // Keep the local envelope intact until the existing conflict decision is resolved.
        that._draftAutosaveReady = false;
        that.applyEditingTopic(res.data);
        that.setData({ editLoading: false, editLoadError: '' });
        // 刚读回的云端草稿且没有换上本机恢复稿:当前内容就是云端版本
        if (res.data.isDraft === true && !that._localDraftRestored) {
          that._cloudSavedKey = that._cloudDraftKey();
          that._setSaveState('saved');
        } else if (res.data.isDraft === true && that._cloudLoadedKey) {
          // 换上了本机恢复稿:与云端相同则自动保存时直接标已存云端,不同才上传
          that._cloudSavedKey = that._cloudLoadedKey;
          that._markRestoredDraft(true);
        }
      },
      fail(res, status) {
        if (recover(res, status)) return;
        if (requestToken !== that._editLoadToken || owner !== memberId(app) || that._unloaded) return;
        that._showEditingTopicLoadError(res, '网络暂时不可用，主题未加载');
      },
      successStatusAbnormal(res, status) {
        if (recover(res, status)) return;
        if (requestToken !== that._editLoadToken || owner !== memberId(app) || that._unloaded) return;
        that._showEditingTopicLoadError(res, '主题未加载', status);
      },
      complete() {
        if (isCurrent() && that.data.editLoading) {
          that.setData({ editLoading: false });
        }
      }
    });
  },

  _showEditingTopicLoadError(res, fallback, statusCode) {
    const status = failureStatus(res, statusCode);
    this.setData({ editLoading: false, creatorLoginRequired: status === 401,
      editLoadIsAccess: [400, 403, 422].includes(status),
      editLoadError: app.getRequestErrorMessage ? app.getRequestErrorMessage(res, fallback) : res && res.msg || fallback });
  },

  retryLoadEditingTopic() {
    if (this.data.editingTopicId) this.loadEditingTopic(this.data.editingTopicId);
  },

  /**
   * 后端形状 → 编辑器 formData。
   *
   * ⚠️ 字段名两边不一致的地方都在这一层落差:
   *   merchantStatus → openMerchantPool、totalInventory → totalStock、radiusM → radius、
   *   chapter.cmsTopicNodeList → chapter.nodes。漏一个就是「编辑一次静默丢一批配置」。
   */
  applyEditingTopic(detail) {
    if (!isEditingTopicPayload(detail)) return false;
    const topic = detail.topic || {};
    const productType = Number(topic.productType) === 2 ? 2 : 1;
    const rawChapters = (detail.chapters || []).map((chapter) => Object.assign({}, chapter, {
      opening: detail.openingChapterId != null && String(chapter.id) === String(detail.openingChapterId),
      atmospherePreset: normalizeAtmosphere(chapter.atmospherePreset),
      nodes: (chapter.cmsTopicNodeList || []).map((node) => Object.assign({}, node)),
      cmsTopicNodeList: undefined
    }));
    // 结局 = 结局章(2026-09-24):带 chapterId 的结局挂回那一章;老结局(没有 chapterId)转成新的结局章,
    // 下次保存就按章存 —— 作者不用手工搬。
    const attached = storyEnding.attachEndingsToChapters(rawChapters, detail.journeyStory || topic.journeyStoryJson);
    const chapters = attached.chapters.concat(attached.legacy.map((item) => this._endingChapterFrom(item)));
    const tickets = (detail.tickets || []).map((ticket) => Object.assign({}, ticket, {
      mode: productType,
      totalStock: ticket.totalInventory,
      meetingPointLongitude: ticketCoordinate(ticket, 'meetingPointLongitude', 'gatherLng'),
      meetingPointLatitude: ticketCoordinate(ticket, 'meetingPointLatitude', 'gatherLat')
    }));
    const categoryIds = topic.categoryIds ? String(topic.categoryIds).split(',').filter(Boolean) : [];
    const completionRule = advancedGameConfig.parseTopicCompletion(topic.completeRuleJson || '');
    const bingo = advancedGameConfig.parseTopicBingo(topic.completeRuleJson || '');
    const topicRoute = topicRouteGraph.normalizeTopicRoute(topic);
    this._loadedAttributeRules = detail.journeyRules || topic.journeyRulesJson || '';
    const thoughtPatch = thoughtPatchOf(detail.journeyRules || topic.journeyRulesJson);
    this._routeGraph = topicRoute.graph;

    this.setData({
      editScope: detail.editScope || 'FULL',
      editIsDraft: detail.isDraft === true,
      editLoaded: true,

      bingoRows: advancedGameConfig.BINGO_S_ORDER.map((pos, i) => Object.assign(
        { pos, seq: i + 1, hint: advancedGameConfig.BINGO_DEFAULT_LABELS[pos], couponName: '' },
        bingo.cells[pos])),
      bingoError: bingo.error,
      selectedCategoryIds: categoryIds,
      publishToCreative: false, // 编辑不重复往创意广场发帖
      aiSimple: topic.publishMode === 'ai_simple',
      'formData.name': topic.name || '',
      'formData.subtitle': topic.subtitle || '',
      'formData.description': topic.description || '',
      'formData.startDate': topic.startDate || '',
      'formData.endDate': topic.endDate || '',
      startDateTime: topic.startDate || '开始时间',
      endDateTime: topic.endDate || '结束时间',
      'formData.imgUrl': topic.imgUrl || '',
      'formData.imgArr': topic.imgArr || '',
      'formData.productType': productType,
      'formData.chapters': chapters,
      'formData.tickets': tickets,
      'formData.collaboratorIds': (detail.collaboratorIds || []).map(Number),
      'formData.openMerchantPool': Number(topic.merchantStatus) === 1,   // 存量可能是无资格者开的,回填后由 _syncMerchantPoolEligibility 复核
      'formData.openClubPool': Number(topic.openClubPool) === 1,
      'formData.recruitDeadline': topic.recruitDeadline || '',
      'formData.clubId': topic.clubId || '',
      'formData.selfPlay': Number(topic.selfPlay) === 1,
      'formData.selfPlayPrice': topic.selfPlayPrice || '',
      'formData.selfPlayQuota': topic.selfPlayQuota || '',
      'formData.teamMode': Number(topic.teamMode) || 0,
      'formData.teamMaxMembers': Number(topic.teamMaxMembers) || 4,
      'formData.finishMedalName': topic.finishMedalName || '',
      'formData.finishMedalImg': topic.finishMedalImg || '',
      'formData.completeRewardCouponId': topic.completeRewardCouponId || 0,

      'formData.completeRuleJson': topic.completeRuleJson || '',
      'formData.themeRuntimeConfig': detail.themeRuntimeConfig || topic.themeRuntimeConfigJson || '', 'formData.look': detail.look || '',
      'formData.journeyRules': detail.journeyRules || topic.journeyRulesJson || '',
      'formData.journeyStory': detail.journeyStory || topic.journeyStoryJson || '',
      // 思维阁入口卡的副标题要跟着回填 —— 不然编辑已有主题时明明配过念头,卡上却写「还没配」
      'thoughtsEditor.count': thoughtCountOf(detail.journeyRules || topic.journeyRulesJson),
      thoughtOptions: thoughtPatch.thoughtOptions, thoughtNameOf: thoughtPatch.thoughtNameOf,
      'formData.routeMode': topicRoute.routeMode,
      'formData.routeGraphJson': topic.routeGraphJson || '',
      'formData.configVersion': topicRoute.configVersion,
      'editingTicket.mode': productType,
      completionRuleMode: completionRule.mode,
      completionRequiredCount: completionRule.requiredCount,
      completionRuleError: completionRule.error
    });
    // imgArr(CSV)与 imgArrList(数组)必须走同一个写入口,单独 setData 其一会让 step3 画廊与提交值分家
    this._setImgArr(this._parseImgArr(topic.imgArr || ''));
    this._syncRuntimeStats();
    this._ensureLocalIds();
    this._materializeCityStoryChapters();
    this.captureEntryTicketSnapshot();
    this._captureLockedSnapshot();
    this.updateAllStatistics();
    this.refreshRouteGraph();
    this._syncMerchantPoolEligibility();   // 回填之后复核:存量主题可能是无资格者开的池
    this._finishEditingDraftBootstrap(detail);
    try {
      if (attributeBinding.parse(this._loadedAttributeRules).attributeBinding) this.loadAttributeRegistry();
    } catch (invalid) { this.setData({ attributeRegistryError: invalid.message }); }
    const boundIds = new Set();
    (this.data.formData.chapters || []).forEach(chapter => (chapter.nodes || []).forEach(node => {
      if (templateAdoption.id(node.templateId) && !(node.templateInfo && Object.prototype.hasOwnProperty.call(node.templateInfo, 'advancedConfigJson')))
        boundIds.add(templateAdoption.id(node.templateId));
    }));
    boundIds.forEach(id => this._loadNodeTemplateDetail({ templateId: id }));
    return true;
  },

  /**
   * 招商资格(2026-08-11 拍板):**只有俱乐部主理人能开商家池**,纯玩家不招商。
   *
   * ⚠️ 快照拿不到时**按可编辑处理,不擅自压值** —— roleGuard 的 load() 失败会回落本地缓存,
   *    缓存为空时 isClubLeader() 返 false。若据此把 openMerchantPool 压成 0,一个主理人
   *    编辑正在招商的主题就会**静默关掉招商、孤立已报名商家**,而这正是后端
   *    resolveMerchantPoolFlag 的注释里明令拒绝的那件事。判不出资格就交后端裁决:
   *    它有权威身份数据,拒也会拒得有声。
   *
   * ⚠️ 确证无资格才清 formData —— 草稿复制/编辑回填带来的旧值必须清掉,否则开关已不露面,
   *    pro-editor-policy 仍按「要招商」校验(merchantRequired 直接读 formData),玩家卡死且看不到原因。
   */
  _syncMerchantPoolEligibility() {
    const editable = !roleGuard.hasSnapshot() || roleGuard.isClubLeader();
    /* 稿 E2 的锁态跟着同一份快照算:此前这张卡三端都能编,而「我的权益」页上写着
       「未解锁勋章设计」—— 写着不能用、点进来却能用,是假保证。
       ⚠️ 锁态**只读不清空**:formData.finishMedal* 一个字都不动 ——
          存量勋章可能是有资格的时候设的,清掉等于替用户删图。
       ⚠️ 真正的闸在后端(ApiTopicController 的 checkDesignMedal),前端这层只是不让人误填;
          只做前端就是又一个假保证。 */
    const patch = {
      merchantPoolEditable: editable,
      medalEditable: !roleGuard.hasSnapshot() || roleGuard.can('canDesignMedal'),
    };
    if (!editable && this.data.formData && this.data.formData.openMerchantPool) {
      patch['formData.openMerchantPool'] = false;
    }
    this.setData(patch);
  },

  // WHITELIST 档三类锁定字段(日期 / 章节站点结构 / 票)的回填原样,用来判「用户到底改没改它们」
  _lockedFingerprint() {
    const f = this.data.formData || {};
    return JSON.stringify({
      startDate: f.startDate, endDate: f.endDate, chapters: f.chapters, tickets: f.tickets
    });
  },

  _captureLockedSnapshot() {
    this._lockedSnapshot = this._lockedFingerprint();
  },

  /**
   * 已开卖的主题改了锁定字段 → 当场说清楚,别让它变成「保存成功但没生效」。
   *
   * ⚠️ 这一条是本次改动的要害:后端在 WHITELIST 档是拒收这三类字段的,而前端为了让「只改文案」
   *    还能存得下去会把它们剔掉。若不在剔之前比一次,用户改了票价 → 被剔 → 后端只收到文案 →
   *    回来一句「已保存」而价格纹丝不动,零提示。静默忽略盖住的恰好是资损面。
   */
  _assertLockedFieldsUntouched() {
    if (this._lockedSnapshot === this._lockedFingerprint()) return true;
    modal.show({
      title: '这些改动存不下来',
      content: '主题已过审开卖，开始/结束日期、章节节点结构、票种与价格都不能再改了。'
        + '要改这些只能先下架 → 退款 → 重发。其余文案与图片的修改可以正常保存。',
      showCancel: false,
      confirmText: '知道了'
    });
    return false;
  },

  applyEntryMode(mode) {
    const nextMode = Number(mode) === 2 ? 2 : 1;
    const tickets = (this.data.formData && this.data.formData.tickets) ? this.data.formData.tickets.slice() : [];
    if (tickets.length) {
      // [P0-02] 全量覆盖,不能只改 tickets[0] —— 只改首张会把 [1..n] 留在旧 mode 上 = 混票。
      this.setData({
        'formData.tickets': tickets.map((t) => Object.assign({}, t, { mode: nextMode })),
        'formData.productType': nextMode,
        'editingTicket.mode': nextMode
      });
    } else {
      this.setData({
        'formData.tickets': [this.createDefaultTicket(nextMode)],
        'formData.productType': nextMode,
        'editingTicket.mode': nextMode
      });
    }
  },

  captureEntryTicketSnapshot() {
    this._entryTicketSnapshot = JSON.stringify((this.data.formData && this.data.formData.tickets) || []);
  },

  hasUserDraftConfig() {
    const formData = this.data.formData || {};
    const hasRouteContent = !!(formData.name || formData.subtitle || formData.description || formData.imgUrl || formData.imgArr)
      || (formData.chapters || []).some(chapter => chapter && (chapter.name || chapter.description || chapter.imgArr
        || (chapter.nodes || []).some(node => node && (node.name || node.description || node.address || node.imgUrl))));
    const tickets = JSON.stringify(formData.tickets || []);
    return hasRouteContent || (this.data.pendingMaterials || []).length > 0
      || tickets !== (this._entryTicketSnapshot || '[]');
  },

  _buildCopiedDraft(mode, aiSimple) {
    const nextMode = Number(mode) === 2 ? 2 : 1;
    const formData = JSON.parse(JSON.stringify(this.data.formData || {}));
    const pendingMaterials = JSON.parse(JSON.stringify(this.data.pendingMaterials || []));
    const nodeKeys = new Map();
    (formData.chapters || []).flatMap(ch => ch.nodes || []).concat(pendingMaterials).forEach(node => {
      node._localId = node._localId || this._genLocalId();
      node.clientNodeKey = node.clientNodeKey || node._localId;
      if (node.id) nodeKeys.set(String(node.id), 'client:' + node.clientNodeKey);
      delete node.id;
    });
    const reselect = [];
    if (formData.themeRuntimeConfig) {
      const runtime = templateBinding.parse(formData.themeRuntimeConfig);
      // 互斥组与额外站点(含共用额度关系)跟着站点走:编号换成新草稿的 client:<key>,首次保存由服务端换回真实编号。
      // 只有找不到对应站点的引用才要求重选,不整段丢掉。
      (runtime.chapters || []).forEach(ch => {
        const lost = [];
        if (ch.exclusiveGroups) {
          ch.exclusiveGroups = ch.exclusiveGroups.map(group => group.map(id => nodeKeys.get(String(id)))).filter(group => {
            const ok = group.length >= 2 && group.every(Boolean);
            if (!ok) lost.push('互斥选项');
            return ok;
          });
          if (!ch.exclusiveGroups.length) delete ch.exclusiveGroups;
        }
        if (ch.extraNodes) {
          ch.extraNodes = ch.extraNodes.map(item => Object.assign({}, item, { nodeId: nodeKeys.get(String(item.nodeId)) })).filter(item => {
            if (!item.nodeId) lost.push('额外节点');
            return !!item.nodeId;
          });
          if (!ch.extraNodes.length) delete ch.extraNodes;
        }
        if (lost.length) reselect.push('第' + (ch.index + 1) + '章' + Array.from(new Set(lost)).join('/'));
      });
      (runtime.scenes || []).forEach(scene => {
        if (!scene.nodeConfigs) return;
        const configs = {};
        Object.entries(scene.nodeConfigs).forEach(([key, value]) => {
          const target = /^\d+$/.test(key) ? nodeKeys.get(key) : key;
          if (target) configs[target] = value;
          else if (!reselect.includes('场景节点玩法')) reselect.push('场景节点玩法');
        });
        scene.nodeConfigs = configs;
      });
      structure.copyReferences(runtime, reselect); // structure
      const survival = runtime.gameplayRuntime && runtime.gameplayRuntime.survival;
      if (survival && (survival.zeroChallenge || survival.zeroHpPolicy === 'CHALLENGE')) {
        reselect.push('归零挑战需重新选择挑战节点');
        delete survival.zeroChallenge;
        if (survival.zeroHpPolicy === 'CHALLENGE') delete survival.zeroHpPolicy;
        if (!Object.keys(survival).length) delete runtime.gameplayRuntime.survival;
        if (!Object.keys(runtime.gameplayRuntime).length) delete runtime.gameplayRuntime;
      }
      if (runtime.templateBindings) {
        const bindings = {};
        Object.entries(runtime.templateBindings).forEach(([key, value]) => {
          const target = /^\d+$/.test(key) ? nodeKeys.get(key) : key;
          if (target) bindings[target] = value;
          else if (!reselect.includes('节点玩法资源绑定')) reselect.push('节点玩法资源绑定');
        });
        runtime.templateBindings = bindings;
      }
      survivalEditor.copyReferences(runtime, reselect); // survival
      ownership.copyReferences(runtime, reselect);
      formData.themeRuntimeConfig = JSON.stringify(runtime);
    }
    formData.productType = nextMode;
    formData.tickets = (formData.tickets || []).map(ticket => Object.assign({}, ticket, { mode: nextMode }));
    return {
      formData,
      selectedCategoryIds: (this.data.selectedCategoryIds || []).slice(),
      selectedCategoryNames: (this.data.selectedCategoryNames || []).slice(),
      pendingMaterials,
      copyReferenceNotice: reselect.length ? '复制后需要重新选择：' + reselect.join('、') : '',
      aiSimple: !!aiSimple,
      productType: nextMode
    };
  },

  _openCopiedDraft(mode, aiSimple, replaceCurrent) {
    const nextMode = Number(mode) === 2 ? 2 : 1;
    writeAiDraft(this._buildCopiedDraft(nextMode, aiSimple));
    const navigate = replaceCurrent ? wx.redirectTo : wx.navigateTo;
    navigate({ url: '/pages/publish/fabu/index?mode=' + nextMode });
  },

  // 模式选择器。左卡原来是「点一下就对切」,两种模式的差别全靠创作者自己知道;
  // 切错了整条票务链路的必填项都跟着变(定向要集合时间地点、探索只要有效期)。
  // 拆成「先摊开讲、再让人选」,真正的切换动作仍然落回 requestModeSwitch,不复制一份逻辑。
  openModePicker() {
    if (this.data.operationScope === 'MERCHANT') return;   // 商家档模式已固定,见 wxml 注释
    this.setData({
      modePickerShow: true,
      modePickerMode: Number(this.data.formData.productType) === 2 ? 2 : 1,
    });
  },
  closeModePicker() { this.setData({ modePickerShow: false }); },

  // 点选项只【暂选】,不落地 —— 切模式会连带复制一份新草稿,那是不该被"手滑点中"触发的动作。
  onPickMode(e) {
    this.setData({ modePickerMode: Number(e.currentTarget.dataset.mode) === 2 ? 2 : 1 });
  },

  confirmModePicker() {
    const mode = this.data.modePickerMode;
    this.setData({ modePickerShow: false });
    if (Number(this.data.formData.productType) === mode) return;
    this.requestModeSwitch({ currentTarget: { dataset: { mode } } });
  },

  requestModeSwitch(e) {
    // ★ 这是模式的唯一写入口 —— 商家档的闸必须落在这里,而不是只挡 openModePicker。
    //   模式选择器是看得见的那条路,confirmModePicker 之外还有草稿恢复等路径也会走到这。
    if (this.data.operationScope === 'MERCHANT') return;
    const nextMode = Number(e.currentTarget.dataset.mode) === 2 ? 2 : 1;
    if (Number(this.data.formData.productType) === nextMode) return;
    if (!this.hasUserDraftConfig()) {
      this.applyEntryMode(nextMode);
      this.captureEntryTicketSnapshot();
      return;
    }
    modal.show({
      title: '复制为新草稿？',
      content: '当前草稿会保留不变；将复制一份并切换主题模式。',
      confirmText: '复制并切换',
      success: (res) => {
        if (res.confirm) this._openCopiedDraft(nextMode, false, false);
      }
    });
  },

  copyToProfessionalDraft() {
    this._openCopiedDraft(this.data.formData.productType, false, true);
  },

  createDefaultTicket(mode) {
    const nextMode = Number(mode) === 2 ? 2 : 1;
    const now = new Date();
    const start = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    const end = new Date(now.getTime() + 8 * 24 * 60 * 60 * 1000);
    const pad = n => String(n).padStart(2, '0');
    const fmt = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    return {
      name: nextMode === 2 ? '自由探索票' : '城市定向票',
      price: 0,
      totalStock: 100,
      mode: nextMode,
      meetingPoint: '',
      meetingPointAddress: '',
      meetingPointLongitude: '',
      meetingPointLatitude: '',
      teamSize: 0,
      startTime: nextMode === 2 ? fmt(start) : '',
      endTime: nextMode === 2 ? fmt(end) : '',
      saleStartTime: fmt(now),
      saleEndTime: fmt(start),
      description: nextMode === 2 ? '全点开放，玩家可在有效期内自由安排顺序。' : '集合出发，按路线完成现场节点互动。',
      refundSupported: true,
      syncWithTheme: true
    };
  },

  // 页面销毁:让在途发布回调失效,避免 setData / redirect after unload
  onHide() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._hidden = true;
    this._precheckToken = (this._precheckToken || 0) + 1;
    // 保存/发布意图已在发请求前落盘。隐藏后只允许下一次显式核对，旧回包不结算新页面。
    if (this._serverDraftSaver) {
      this._serverDraftSaver.destroy();
      this._serverDraftSaver = null;
    }
    if (this._publishWorkflow) {
      this._publishWorkflow.destroy();
      this._publishWorkflow = null;
    }
    if (this.data.draftSaving || this.data.submitting) {
      cyLoading.hide();
      this.setData({ draftSaving: false, submitting: false,
        themeSaveState: 'unknown', themeSaveLabel: SAVE_STATE_LABELS.unknown });
    }
    // 每次编辑已先落本机;离开前不承诺一定存上云,只取消过时调度,回来(onShow)再接力
    this._cancelCloudDraftAutosave();
  },

  onUnload() { this._loginContinuationEpoch = (this._loginContinuationEpoch || 0) + 1;
    this._unloaded = true;
    this._cloudAutosaveEnabled = false;
    this._cancelCloudDraftAutosave();
    this._destroyChapterDurationAudio();
    this._destroyStoryDurationProbes();
    this.destroyPreviewAudio();
    if (this._pendingUndoTimer) clearTimeout(this._pendingUndoTimer);
    this._pendingUndoTimer = null;
    this._pendingUndoSnapshot = null;
    this.data.clubPlaceCaptureActive = false;
    this._precheckToken = (this._precheckToken || 0) + 1; // 让在途预检回调失效
    this._identityStatusEpoch = (this._identityStatusEpoch || 0) + 1;
    this._identityRequestEpoch = (this._identityRequestEpoch || 0) + 1;
    if (this._identityRegistering) cyLoading.hide();
    this._identityRegistering = false;
    this._editLoadToken = (this._editLoadToken || 0) + 1;
    if (this._publishWorkflow) {
      this._publishWorkflow.destroy();
    }
    if (this._serverDraftSaver) {
      if (this._serverDraftSaver.isBusy()) cyLoading.hide();
      this._serverDraftSaver.destroy();
    }
  },

  // 生成日期列表（今天至30天内）
  generateDateList() {
    this.setData({
      dateList: publishDate.buildDateList(new Date())
    });
  },

  // 显示开始时间选择器
  showStartTimePicker() {
    this.showThemeTimePicker('start');
  },

  // 显示结束时间选择器
  showEndTimePicker() {
    this.showThemeTimePicker('end');
  },

  showThemeTimePicker(type) {
    const saved = publishDate.normalizeDateTime(
      this.data.formData[`${type}Date`], type === 'start' ? '09:00:00' : '18:00:00'
    );
    const match = saved && /^(\d{4}-\d{2}-\d{2}) (\d{2}):(\d{2}):/.exec(saved);
    const dateIndex = match && Number.isFinite(toTimestamp(saved))
      ? this.data.dateList.findIndex(item => item.date === match[1]) : -1;
    if (type === 'start') {
      this.setData({
        'startTimePicker.show': true,
        startDateIndex: dateIndex >= 0 ? [dateIndex] : this.data.startDateIndex,
        startTimeIndex: dateIndex >= 0 ? [Number(match[2]), Number(match[3])] : this.data.startTimeIndex,
      });
    } else {
      this.setData({
        'endTimePicker.show': true,
        endDateIndex: dateIndex >= 0 ? [dateIndex] : this.data.endDateIndex,
        endTimeIndex: dateIndex >= 0 ? [Number(match[2]), Number(match[3])] : this.data.endTimeIndex,
      });
    }
  },

  

  // 开始日期变化
  onStartDateChange(e) {
    const value = e.detail.value;
    this.setData({
      startDateIndex: value
    });
  },

  // 开始时间变化 - 路线日期选择器（重命名避免冲突）
  onStartTimePickerChange(e) {
    const value = e.detail.value;
    this.setData({
      startTimeIndex: value
    });
  },

  // 关闭开始时间选择器
  closeStartTimePicker() {
    this.setData({
      'startTimePicker.show': false
    });
  },

  // 确认开始时间
  confirmStartTime() {
    const {
      startDateIndex,
      startTimeIndex,
      dateList,
      hours,
      minutes
    } = this.data;

    const selectedDate = dateList[startDateIndex[0]];
    const hour = hours[startTimeIndex[0]];
    const minute = minutes[startTimeIndex[1]];

    // 格式化开始时间为 2025-11-19 09:00:00 格式
    const startDateTime = this.formatDateTimeForAPI(selectedDate.date, hour, minute);
    const displayText = `${selectedDate.display} ${timeOptions.formatHM(hour, minute)}`;


    // 更新显示
    this.setData({
      startDateTime: displayText,
      'formData.startDate': startDateTime,
      'startTimePicker.show': false
    }, () => this.refreshPrimaryActionState());
    this.updateTicketTimes(startDateTime, this.data.formData.endDate);

    // 显示结束时间选择器
    setTimeout(() => {
      this.showEndTimePicker();
    }, 300);
  },

  // [P1-41] 日期时间归一为 'YYYY-MM-DD HH:mm:ss'。formData/票时间的写入点格式不一
  // (纯日期 / 'HH:mm' / 'HH:mm:ss' / ISO T…Z),原提交处无脑追加 ':00'/' 00:00:00' 会拼出
  // 非法串(如 '…09:00:00 00:00:00'),后端 Date 反序列化必败。格式不符传 null,由后端兜底。
  _normalizeDateTime(v, defaultTime) {
    return publishDate.normalizeDateTime(v, defaultTime);
  },

  // 工具方法：格式化日期时间为 API 需要的格式 (2025-11-19 09:00:00)
  formatDateTimeForAPI(dateStr, hour, minute) {
    return publishDate.formatDateTimeForAPI(dateStr, hour, minute);
  },

  // 结束日期变化
  onEndDateChange(e) {
    const value = e.detail.value;
    this.setData({
      endDateIndex: value
    });
  },

  // 结束时间变化 - 路线日期选择器（重命名避免冲突）
  onEndTimePickerChange(e) {
    const value = e.detail.value;
    this.setData({
      endTimeIndex: value
    });
  },

  // 关闭结束时间选择器
  closeEndTimePicker() {
    this.setData({
      'endTimePicker.show': false
    });
  },

  // 确认结束时间
  confirmEndTime() {
    const {
      endDateIndex,
      endTimeIndex,
      dateList,
      hours,
      minutes,
      formData
    } = this.data;

    const selectedDate = dateList[endDateIndex[0]];
    const hour = hours[endTimeIndex[0]];
    const minute = minutes[endTimeIndex[1]];

    // 格式化结束时间为 2025-11-19 18:00:00 格式
    const endDateTime = this.formatDateTimeForAPI(selectedDate.date, hour, minute);
    const displayText = `${selectedDate.display} ${timeOptions.formatHM(hour, minute)}`;


    // 验证时间合理性
    const startTime = toTimestamp(formData.startDate);
    const endTime = toTimestamp(endDateTime);

    if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime <= startTime) {
      cyToast('结束时间必须晚于开始时间');
      return;
    }

    // 更新显示
    this.setData({
      endDateTime: displayText,
      'formData.endDate': endDateTime,
      'endTimePicker.show': false
    }, () => this.refreshPrimaryActionState());
    this.updateTicketTimes(this.data.formData.startDate, endDateTime);

    cyToast.success('时间设置完成');
  },

  // 节点营业时间相关方法
  formatTime: function (hour, minute) {
    return publishDate.formatTime(hour, minute);
  },

  // cy-date-sheet 组件事件桥接(替代原 data-action dispatch)
  onBusinessTimeCancel() {
    this.setData({ 'timePicker.show': false });
  },
  onBusinessTimeConfirm() {
    this.handleTimePopup({ currentTarget: { dataset: { action: 'confirm' } } });
  },

  handleTimePopup: function (e) {
    const {
      action
    } = e.currentTarget.dataset;
    if (action === 'open') {
      let startTimeIndex = [9, 0];
      let endTimeIndex = [18, 0];

      if (this.data.nodesForm.businessTime) {
        const timeParts = this.parseBusinessTime(this.data.nodesForm.businessTime);
        if (timeParts) {
          startTimeIndex = [timeParts.startHour, timeParts.startMinute];
          endTimeIndex = [timeParts.endHour, timeParts.endMinute];
        }
      }

      this.setData({
        'timePicker.show': true,
        startTimeIndex: startTimeIndex,
        endTimeIndex: endTimeIndex,
        'timePicker.startTime': {
          hour: startTimeIndex[0],
          minute: startTimeIndex[1]
        },
        'timePicker.endTime': {
          hour: endTimeIndex[0],
          minute: endTimeIndex[1]
        }
      });
    } else if (action === 'confirm') {
      const startTime = this.data.timePicker.startTime;
      const endTime = this.data.timePicker.endTime;

      const businessTime = this.formatTime(startTime.hour, startTime.minute) +
        '-' +
        this.formatTime(endTime.hour, endTime.minute);

      this.setData({
        'nodesForm.businessTime': businessTime,
        'timePicker.show': false
      });
    } else if (action === 'close') {
      this.setData({
        'timePicker.show': false
      });
    }
  },

  // 节点营业时间变化
  onStartTimeChange: function (e) {
    const value = e.detail.value;
    this.setData({
      'timePicker.startTime': {
        hour: this.data.hours[value[0]],
        minute: this.data.minutes[value[1]]
      }
    });
  },

  onEndTimeChange: function (e) {
    const value = e.detail.value;
    this.setData({
      'timePicker.endTime': {
        hour: this.data.hours[value[0]],
        minute: this.data.minutes[value[1]]
      }
    });
  },

  parseBusinessTime: function (businessTime) {
    return publishDate.parseBusinessTime(businessTime);
  },

  // 初始化数据
  initData() {
    // let today = new Date();
    // let tomorrow = new Date(today);
    // tomorrow.setDate(tomorrow.getDate() + 30); // 默认结束日期为30天后

    // let formatDate = (date) => {
    //   return `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')}`;
    // };

    // let startDate = formatDate(today);
    // let endDate = formatDate(tomorrow);

    // this.setData({
    //   startdate: startDate,
    //   enddate: endDate,
    //   'formData.startDate': startDate,
    //   'formData.endDate': endDate,
    //   'formData.tickets[0].startTime': startDate,
    //   'formData.tickets[0].endTime': endDate
    // });
  },

  applyStarterContent(options = {}) {
    const storageKey = 'topic_starter_prefilled_v1';
    const hasSeenStarter = !!wx.getStorageSync(storageKey);
    const hasTemplateName = !!options.templateName;
    const isBlankDraft = this.isBlankTopicDraft();

    if (hasSeenStarter || !isBlankDraft) {
      return;
    }

    const now = new Date();
    const start = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    const end = new Date(now.getTime() + 8 * 24 * 60 * 60 * 1000);
    const pad = n => String(n).padStart(2, '0');
    const fmt = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const startDate = `${fmt(start)} 10:00`;
    const endDate = `${fmt(end)} 18:00`;
    const name = this.data.formData.name || (hasTemplateName ? decodeURIComponent(options.templateName) : '城市街角探索');

    this.setData({
      // 分类必须经标签页选真实分类(留空让 validateForm 拦截,避免发出 mock 分类id)
      selectedCategoryIds: [],
      selectedCategoryNames: [],
      selectedCategoryNamesStr: '',
      categoryList: [],
      startDateTime: startDate,
      endDateTime: endDate,
      'formData.name': name,
      'formData.subtitle': '沿着街角线索，完成一次可报名的城市路线。',
      'formData.description': '这是一条可直接改写的路线草稿：参与者从起点出发，依次完成观察、问答、拍照和协作节点互动，最终拼出关于这片街区的隐藏故事。你可以保留结构，只替换地点、剧情和互动。',
      'formData.startDate': startDate,
      'formData.endDate': endDate,
      // 封面置空,逼用户上传真封面(原 mock 本地路径会发出坏图)
      'formData.imgUrl': '',
      'formData.imgArr': '',
      imgArrList: [],
      'formData.categoryIds': '',
      'formData.tickets': [{
        name: '体验票',
        price: 0,
        totalStock: 100,
        mode: 2,
        meetingPoint: '',
        teamSize: 0,
        startTime: startDate,
        endTime: endDate,
        saleStartTime: fmt(now),
        saleEndTime: fmt(start),
        description: '适合线下试玩',
        refundSupported: true,
        syncWithTheme: true
      }],
      'formData.chapters': [{
        name: '第1章',
        description: '从城市入口开始，寻找第一组可被验证的现场线索。',
        imgArr: '',
        calculatedDistance: 0,
        nodes: [{
          name: '街角集合点',
          subtitle: '观察入口',
          description: '找到现场最醒目的招牌或公共艺术，记录它和路线之间的关系。',
          address: '上海市徐汇区钦州北路1001号',
          longitude: '121.384904',
          latitude: '31.167199',
          imgUrl: '',
          nodeTime: 30,
          showTemplate: false,
          businessTime: '10:00-18:00',
          sortID: 1
        }]
      }]
    });
    wx.setStorageSync(storageKey, true);
    this.updateAllStatistics();
  },

  /** AI 创作助手回填:读 ai_topic_draft → 灌入 formData(幂等门控:blank 才灌,灌完即清) */
  applyAiDraft(options) {
    options = options || {};
    if (!this.isBlankTopicDraft()) return;
    const source = options.from === 'ai' ? options : null;
    const draft = source || readAiDraft();
    if (!draft) return;
    const sourceData = draft.formData || draft;
    const productType = Number(sourceData.productType) === 2 ? 2 : 1;
    const lockedClubId = this._lockClub ? (this.data.formData.clubId || options.clubId || '') : '';
    const chapters = (sourceData.chapters || []).map(ch => ({
      name: ch.name || '第1章',
      description: ch.description || '',
      atmospherePreset: normalizeAtmosphere(ch.atmospherePreset),
      schemaVersion: ch.schemaVersion,
      blocks: ch.blocks,
      required: ch.required,
      imgArr: ch.imgArr || '',
      calculatedDistance: ch.calculatedDistance || 0,
      // 序章/结局章标记随复制保留,否则结局章被当普通章、与回填的 journeyStory 对不上
      opening: ch.opening,
      ending: ch.ending,
      nodes: (ch.nodes || []).map((n, i) => ({
        _localId: n._localId || '',
        clientNodeKey: n.clientNodeKey || n._localId || '',
        id: n.id,
        name: n.name || ('节点' + (i + 1)),
        subtitle: n.subtitle || '',
        description: n.description || n.address || '',
        address: n.address || '',
        longitude: String(n.longitude || ''),
        latitude: String(n.latitude || ''),
        imgUrl: n.imgUrl || '',
        nodeTime: parseInt(n.nodeTime) || 30,
        showTemplate: false,
        businessTime: n.businessTime || '',
        sortID: n.sortID || (i + 1),
        templateId: n.templateId || 0,
        templateName: n.templateName || '',
        hookText: n.hookText || '',
        cardHookLong: n.cardHookLong || '',
        fragmentText: n.fragmentText || ''
      }))
    }));
    const pendingMaterials = Array.isArray(draft.pendingMaterials)
      ? JSON.parse(JSON.stringify(draft.pendingMaterials)) : [];
    chapters.forEach((chapter) => {
      const canFormalize = productType !== 1 || proEditorPolicy.hasRealStory(chapter);
      const formalNodes = [];
      (chapter.nodes || []).forEach((node) => {
        if (canFormalize && proEditorPolicy.hasUsableCoords(node)) {
          formalNodes.push(node);
        } else {
          pendingMaterials.push(proEditorMaterials.materialFromNode(
            node, lockedClubId ? 'place' : 'node'
          ));
        }
      });
      chapter.nodes = formalNodes;
    });
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    const fmt = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const start = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    const end = new Date(now.getTime() + 8 * 24 * 60 * 60 * 1000);
    const startDate = `${fmt(start)} 10:00`;
    const endDate = `${fmt(end)} 18:00`;
    const restoredStartDate = sourceData.startDate || startDate;
    const restoredEndDate = sourceData.endDate || endDate;

    this.setData({
      aiSimple: !!draft.aiSimple,
      selectedCategoryIds: (draft.selectedCategoryIds || []).slice(),
      selectedCategoryNames: (draft.selectedCategoryNames || []).slice(),
      selectedCategoryNamesStr: (draft.selectedCategoryNames || []).join('、'), categoryList: [],
      startDateTime: restoredStartDate, endDateTime: restoredEndDate,
      'formData.name': sourceData.name || '',
      'formData.subtitle': sourceData.subtitle || '',
      'formData.description': (sourceData.description || '') + (draft.aiTraceId ? '  [AI 辅助生成]' : ''),
      'formData.startDate': restoredStartDate,
      'formData.endDate': restoredEndDate,
      'formData.imgUrl': sourceData.imgUrl || '',
      'formData.imgArr': sourceData.imgArr || '',
      imgArrList: this._parseImgArr(sourceData.imgArr),
      'formData.categoryIds': sourceData.categoryIds || '',
      'formData.collaboratorIds': sourceData.collaboratorIds || [],
      'formData.collaboratorList': sourceData.collaboratorList || [],
      'formData.productType': productType,
      'formData.openMerchantPool': !!sourceData.openMerchantPool,
      'formData.openClubPool': !!sourceData.openClubPool,
      'formData.recruitDeadline': sourceData.recruitDeadline || '',
      'formData.clubId': lockedClubId || sourceData.clubId || '',
      'formData.selfPlay': !!sourceData.selfPlay,
      'formData.selfPlayPrice': sourceData.selfPlayPrice || '',
      'formData.selfPlayQuota': sourceData.selfPlayQuota || '',
      'formData.teamMode': Number(sourceData.teamMode) || 0,
      'formData.teamMaxMembers': Number(sourceData.teamMaxMembers) || 4,
      'formData.finishMedalName': sourceData.finishMedalName || '',
      'formData.finishMedalImg': sourceData.finishMedalImg || '',
      'formData.completeRewardCouponId': sourceData.completeRewardCouponId || 0,
      // 模式切换复制(_buildCopiedDraft)整份带着这些主题规则;漏回填 = 初始状态、背包、结局、完成规则静默清空(复核 A09)
      'formData.journeyRules': sourceData.journeyRules || '',
      'formData.themeRuntimeConfig': sourceData.themeRuntimeConfig || '', 'formData.look': sourceData.look || '',
      'formData.journeyStory': sourceData.journeyStory || '',
      'formData.completeRuleJson': sourceData.completeRuleJson || '',

      'formData.tickets': sourceData.tickets || [{
        name: '体验票', price: 0, totalStock: 100, mode: 2,
        meetingPoint: '', teamSize: 0, startTime: startDate, endTime: endDate,
        saleStartTime: fmt(now), saleEndTime: fmt(start),
        description: 'AI 辅助生成的体验票', refundSupported: true, syncWithTheme: true
      }],
      'formData.chapters': chapters,
      pendingMaterials
    });
    const aiDraftKey = aiDraftStorageKey();
    if (aiDraftKey) wx.removeStorageSync(aiDraftKey);
    this.updateAllStatistics();
    if (draft.copyReferenceNotice) modal.show({ title: '复制提醒', content: draft.copyReferenceNotice, showCancel: false, confirmText: '知道了' });
  },

  isBlankTopicDraft() {
    const formData = this.data.formData || {};
    const chapters = formData.chapters || [];
    const hasNamedChapter = chapters.some(chapter => {
      if (!chapter) return false;
      if (chapter.description || chapter.imgArr) return true;
      return (chapter.nodes || []).some(node => node && (node.name || node.description || node.address || node.imgUrl));
    });
    return !formData.name &&
      !formData.subtitle &&
      !formData.description &&
      !formData.imgUrl &&
      !formData.imgArr &&
      !(formData.tickets && formData.tickets.length) &&
      !hasNamedChapter;
  },

  // 分钟选择改变事件
  bindMinuteChange: function (e) {
    const index = e.detail.value;
    const selectedMinuteStr = this.data.minuteOptions[index];
    const selectedMinute = parseInt(selectedMinuteStr, 10); // 转为数字（基数10避免进制问题）

    this.setData({
      minuteIndex: index,
      'nodesForm.nodeTime': selectedMinute // 正确：赋值数字
    });
  },

  // 分类选择弹窗化(2026-07-31):半屏 cy-category-sheet + 事件回传,不再跳转独立页面
  navigateToCategorySelect: function () {
    this.setData({
      categorySheetVisible: true,
      categorySheetIds: this.data.selectedCategoryIds.join(','),
      // CU-C-45:入口在主题详情面板内(topic-detail-sheet.wxml 的 id=fieldCategory),
      // 两个 sheet 同吃 --cy-z-sheet(800),而详情在 DOM 里更靠后 ⇒ 详情全屏遮罩+面板把
      // 分类弹层整个压住,点分类项/保存都落在详情遮罩上(顺手把详情关掉)。所以打开分类
      // 必须同时关掉详情 —— 与合作者选择器同一套互斥,不是靠遮罩顺带挡住。
      topicDetailShow: false,
      // 与合作者选择器互斥。两者都套 cy-sheet、同吃 --cy-z-sheet(800),谁盖谁只由 DOM 顺序决定。
      // 2026-08-01 automator 实测:同时 show 时 DOM 里能查到 2 个各自带 sh__mask 的 cy-sheet,
      // 两块 panel 几乎完全重叠(合作者 top=377/h=467、分类 top=387/h=457,都贴底 844),
      // 叠成两层全屏遮罩,下层面板被夹在遮罩之间发暗、顶部还露出约 10px。
      // 此前摸不到只是因为先开那个的全屏遮罩顺带挡住了另一个入口,不是代码级互斥 —— 这里把闸写进代码。
    });
  },

  onCategorySelect: function (e) {
    this.updateCategorySelection(e.detail.selectedIds, e.detail.selectedCategories);
  },

  onCategorySheetClose: function () {
    // CU-M-160:这一层是叠在「编辑主题」之上的,关掉最上层必须回到原编辑上下文。
    // 开分类时把详情关掉是为了躲同档遮罩互压(CU-C-45),不是要退出编辑主题 ——
    // 不补这一句,用户点完右上角 ✕ 直接落回主编辑器,填了一半的主题描述得重开一层才看得见。
    // 只有 topic-detail-sheet 的 id=fieldCategory 一处会打开这个弹层,所以「关分类 ⇒ 开详情」
    // 恒等于回到它上一层;两者仍是先后出现,不违反 dual-sheet-mutex 那条互斥。
    this.setData({ categorySheetVisible: false, topicDetailShow: true });
  },

  // 从分类选择弹窗回调更新选择 - 需要同时接收ID和名称
  updateCategorySelection: function (selectedIds, selectedCategories) {
    const that = this;

    const selectedCategoryIds = selectedIds || [];
    const selectedCategoryNames = selectedCategories ? selectedCategories.map(item => item.categoryName) : [];
    const selectedCategoryNamesStr = selectedCategoryNames.join('，');

    that.setData({
      selectedCategoryIds: selectedCategoryIds, // 正确更新 selectedCategoryIds
      selectedCategoryNames: selectedCategoryNames,
      selectedCategoryNamesStr: selectedCategoryNamesStr,
      categoryList: selectedCategories || [],
      'formData.categoryIds': selectedCategoryIds.join(',')
    }, () => that.refreshPrimaryActionState());


    // console.log('更新选中的分类数据:', {
//       ids: selectedCategoryIds,
//       names: selectedCategoryNames,
//       namesStr: selectedCategoryNamesStr,
//       list: selectedCategories
//     });
  },

  // 显示添加章节弹框
  showAddChapter(e) {
    this._syncRuntimeStats();
    const dataset = (e && e.currentTarget && e.currentTarget.dataset) || {};
    let chapterIndex = dataset.index;
    // 计算下一个章节的编号
    const currentChapterCount = this.data.formData.chapters.length;
    const nextChapterNumber = currentChapterCount + 1;
    const defaultChapterName = `第${nextChapterNumber}章`;
    this._openChapterRules(currentChapterCount);

    this.setData({
      popChapterAction: 0,
      popChapter: true,
      popChapterIndex: chapterIndex,
      assignPendingLocalId: '',
      chapterForm: emptyChapterForm(defaultChapterName)
    });
    if (Number(this.data.formData.productType) === 2) this.loadMerchantCategories();
  },
  // 折叠/展开章节(_collapsed 为 '_' 前缀前端态,提交时由 _stripLocalFields 自动剥离)
  toggleChapterCollapse(e) {
    const ci = e.currentTarget.dataset.chapterindex;
    const chapters = this.data.formData.chapters;
    if (ci == null || !chapters[ci]) return;
    chapters[ci]._collapsed = !chapters[ci]._collapsed;
    this.setData({ 'formData.chapters': chapters });
  },
  // 渐进折叠(M4·B):翻转通用设置 Tab 内可折叠卡(票务/合作者)的展开态
  toggleSection(e) {
    const sec = e.currentTarget.dataset.sec;
    if (!sec) return;
    const secCollapsed = { ...this.data.secCollapsed };
    secCollapsed[sec] = !secCollapsed[sec];
    this.setData({ secCollapsed });
  },
  // ★ setData 回调里的 updateAllStatistics 不是"顺手刷一下",少了它就是一条死锁
  //   (2026-09-05 用户实测:「先添加了,再删除了,就没办法再添加了」)。
  //   starterAction 是派生态,只在 refreshPrimaryActionState 里算:加了一章并写进内容后
  //   它变成 null;删掉这章若不重算,它就一直是 null —— 而空态卡的 CTA 挂
  //   `wx:if="{{starterAction}}"`、「添加章节」行挂 `wx:if="{{chapters.length || pendingMaterials.length}}"`,
  //   删掉最后一章时两个条件同时不成立,页面上**再没有任何添加章节的入口**。
  //   顺带 chapterStats / totalStats / completeness / canPublish 也都会停在删除前的值,
  //   底栏「发布」还是亮的,要到发布时才被打回。
  deleteChapter(e) {
    const that = this;
    let popChapterIndex = this.data.popChapterIndex;
    modal.show({
      title: '确认删除章节',
      // CU-C-158:这里原本写「删除后不可恢复(可撤销)」,前后语义相反。
      // _pushUndo() 确实压了栈,但 canUndo 在本页 WXML 零消费(已登记进
      // scripts/baselines/u4-dead-setdata-field.json),页面上没有绑定 undo() 的控件;
      // 唯一可见的「撤销」条属于待编排素材的 5 秒窗口(undoPendingRemoval),救不回章节。
      // 承诺给不出就等于骗人按下去,删掉括号。要恢复这条承诺得先做出可见入口。
      content: '删除后不可恢复。',
      success(r) {
        if (!r.confirm) return;
        that._pushUndo();
        that._removeRuntimeChapter(Number(popChapterIndex));
        that.destroyPreviewAudio(); // R9-36:删除章节同样关闭弹层,预听一并停
        that.setData({
          'formData.chapters': that.data.formData.chapters.filter((_, index) => index !== popChapterIndex),
          popChapter: false,
          chapterSettingsFromStory: false
        }, () => that.updateAllStatistics());
      }
    });
  },
  //显示编辑章节弹框
  showEditChapter(e) {
    this._syncRuntimeStats();
    var that = this
    let chapterIndex = e.currentTarget.dataset.index;
    this._openChapterRules(Number(chapterIndex));
    let newChapters = that.data.formData.chapters[chapterIndex];
    let chapterForm = Object.assign({}, newChapters, {
      categoryId: newChapters.categoryId || null,
      recruitEnabled: Number(newChapters.recruitEnabled) === 1 ? 1 : 0,
      termsMode: ['PERK', 'TRAFFIC', 'REVSHARE'].includes(newChapters.termsMode) ? newChapters.termsMode : 'PERK',
      perkMinValue: newChapters.perkMinValue == null ? null : String(newChapters.perkMinValue),
      maxPerHeadFee: newChapters.maxPerHeadFee == null ? null : String(newChapters.maxPerHeadFee),
      maxMerchant: Number.isInteger(Number(newChapters.maxMerchant)) ? Number(newChapters.maxMerchant) : 0,
      required: Number(newChapters.required) === 0 ? 0 : 1,
      atmospherePreset: normalizeAtmosphere(newChapters.atmospherePreset),
    });
    this.setData({
      chapterForm: chapterForm,
      popChapter: true,
      popChapterAction: 1,
      popChapterIndex: chapterIndex,
      assignPendingLocalId: ''
    }, () => this._syncChapterEndingOptions(chapterForm.ending));
    this._refreshStructureSummary(); // structure
    if (Number(that.data.formData.productType) === 2) that.loadMerchantCategories();
  },
  
  onSubtitleChange(e) {
    this.setData({
      'formData.subtitle': e.detail.value
    });
  },
  // 举办方式：是否允许路线进入商家池
  onMerchantPoolChange(e) {
    this.setData({
      'formData.openMerchantPool': e.detail.value
    });
  },

  // 开放范围·俱乐部承接(2026-09-15 裁决 12B:默认开,主办可手动关)
  onClubPoolChange(e) {
    this.setData({
      'formData.openClubPool': e.detail.value
    });
  },

  onTeamSupportChange(e) {
    this.setData({ 'formData.teamMode': e.detail.value ? 1 : 0 })
  },

  onTeamModeSelect(e) {
    this.setData({ 'formData.teamMode': Number(e.currentTarget.dataset.mode) })
  },

  onTeamMaxMembersChange(e) {
    const options = this.data.teamMemberOptions
    this.setData({ 'formData.teamMaxMembers': options[Number(e.detail.value)] })
  },

  // 章节承接的品类与 mms_merchant.category_id 同口径；parentid 必须显式传字符串 "0"，
  // ApiCategoryController 会直接调用 parentid.equals("0")。
  loadMerchantCategories() {
    if (this.data.merchantCategoryLoading || this.data.merchantCategoryList.length) return;
    const that = this;
    that.setData({ merchantCategoryLoading: true });
    app.sendRequest({
      hideLoading: true,
      url: '/api/category/list',
      method: 'POST',
      data: { type: 1, parentid: '0' },
      success(res) {
        if (res.code == '200' && Array.isArray(res.data)) {
          that.setData({ merchantCategoryList: res.data });
        }
      },
      complete() {
        that.setData({ merchantCategoryLoading: false });
      }
    });
  },

  _updateChapterRecruitConfig(index, patch) {
    const chapterIndex = Number(index);
    const chapters = (this.data.formData.chapters || []).slice();
    if (!Number.isInteger(chapterIndex) || chapterIndex < 0 || !chapters[chapterIndex]) return;
    chapters[chapterIndex] = Object.assign({}, chapters[chapterIndex], patch);
    // 章节招商配置进 _buildValidationBag(errors key 以 chapterRecruit 开头),会改 canPublish ——
    // 不重算的话底栏按钮状态停在改之前那一版。
    this.setData({ 'formData.chapters': chapters }, () => this.updateAllStatistics());
  },

  onChapterRecruitToggle(e) {
    const enabled = !!(e.detail && e.detail.value);
    const index = e.currentTarget.dataset.index;
    const chapter = (this.data.formData.chapters || [])[Number(index)] || {};
    this._updateChapterRecruitConfig(index, {
      recruitEnabled: enabled ? 1 : 0,
      // 打开时补 V1 默认值；关闭时保留配置，重新打开无需重填。
      termsMode: ['PERK', 'TRAFFIC', 'REVSHARE'].includes(chapter.termsMode) ? chapter.termsMode : 'PERK',
      perkMinValue: chapter.termsMode === 'TRAFFIC' ? null : chapter.perkMinValue,
      maxMerchant: Number.isInteger(Number(chapter.maxMerchant)) && Number(chapter.maxMerchant) >= 0
        ? Number(chapter.maxMerchant) : 0
    });
    if (enabled) this.loadMerchantCategories();
  },

  onChapterMerchantCategory(e) {
    const category = this.data.merchantCategoryList[Number(e.detail.value)];
    if (!category || category.id == null) return;
    this._updateChapterRecruitConfig(e.currentTarget.dataset.index, {
      categoryId: Number(category.id),
      category: category.categoryName || ''
    });
  },

  onChapterTermsMode(e) {
    const mode = e.currentTarget.dataset.mode;
    if (mode !== 'PERK' && mode !== 'TRAFFIC' && mode !== 'REVSHARE') return;
    if (mode === 'REVSHARE' && (Number(this.data.formData.productType) !== 2 || !(Number(this.data.formData.clubId) > 0))) return;
    const chapter = (this.data.formData.chapters || [])[Number(e.currentTarget.dataset.index)] || {};
    this._updateChapterRecruitConfig(e.currentTarget.dataset.index, {
      termsMode: mode,
      perkMinValue: mode === 'PERK' ? chapter.perkMinValue : null,
      maxPerHeadFee: mode === 'REVSHARE' ? chapter.maxPerHeadFee : null
    });
  },

  onChapterPerkMinValue(e) {
    let raw = String((e.detail && e.detail.value) || '').replace(/[^\d.]/g, '');
    const decimal = raw.indexOf('.');
    if (decimal >= 0) raw = raw.slice(0, decimal + 1) + raw.slice(decimal + 1).replace(/\./g, '');
    this._updateChapterRecruitConfig(e.currentTarget.dataset.index, {
      perkMinValue: raw === '' ? null : raw
    });
  },

  onChapterMaxMerchant(e) {
    const raw = String((e.detail && e.detail.value) || '').replace(/[^\d]/g, '');
    this._updateChapterRecruitConfig(e.currentTarget.dataset.index, {
      maxMerchant: raw === '' ? 0 : Number(raw)
    });
  },

  // 开放范围:是否开放给俱乐部承接(P0-4,俱乐部在合作池申请→商家发带条款邀约→接受成合作)

  // 加载我创建的俱乐部(达人多俱乐部:仅 owned[]),用于发布经典定向团时选归属
  loadMyClubs() {
    const that = this;
    app.sendRequest({
      hideLoading: true,
      url: '/api/club/my',
      method: 'POST',
      success(res) {
        if (res.code != '200' || !res.data) return;
        let owned = res.data.owned || [];
        if (owned && !Array.isArray(owned)) owned = [owned]; // 兼容单对象
        owned = owned.filter(c => c && (c.status === 1 || c.status === '1' || c.status == null));
        // 只取已通过(status=1)的俱乐部;单个则默认选中
        const patch = { myClubs: owned };
        const lockedId = that.data.formData.clubId;
        if (lockedId) {
          // 从俱乐部入口锁定:预选传入的归属俱乐部
          let lockedIdx = -1;
          for (let i = 0; i < owned.length; i++) {
            if (String(owned[i].id) === String(lockedId)) { lockedIdx = i; break; }
          }
          if (lockedIdx >= 0) patch.clubIndex = lockedIdx;
        } else if (owned.length === 1) {
          patch.clubIndex = 0;
          patch['formData.clubId'] = owned[0].id;
        }
        that.setData(patch, () => that.refreshPrimaryActionState());
      }
    });
  },

  // 选择归属俱乐部
  onClubChange(e) {
    const idx = Number(e.detail.value);
    const club = this.data.myClubs[idx];
    if (!club) return;
    this.setData({ clubIndex: idx, 'formData.clubId': club.id }, () => this.refreshPrimaryActionState());
  },
  // 章节名称输入框变化处理
  onNameChange(e) {
    this.setData({
      'formData.name': e.detail.value
    }, () => this.refreshPrimaryActionState());
  },
  // 章节名称输入框变化处理
  onChapterNameInputChange(e) {
    this.setData({
      'chapterForm.name': e.detail.value
    });
  },
  // 章节剧情:弹窗内唯一编辑入口(2026-08-10 从章节卡的页面内 textarea 迁来)
  onChapterDescInput(e) {
    this.setData({ 'chapterForm.description': e.detail.value });
  },

  onChapterAtmosphereSelect(e) {
    this.setData({ 'chapterForm.atmospherePreset': normalizeAtmosphere(e.currentTarget.dataset.value) });
  },
  // 章节描述输入框变化处理
  onChapterDescriptionInputChange(e) {
    this.setData({
      'chapterForm.description': e.detail.value
    });
  },

  onChapterRecruitToggleChange(e) {
    const enabled = !!(e.detail && e.detail.value);
    const form = this.data.chapterForm || {};
    this.setData({
      'chapterForm.recruitEnabled': enabled ? 1 : 0,
      'chapterForm.termsMode': ['PERK', 'TRAFFIC', 'REVSHARE'].includes(form.termsMode) ? form.termsMode : 'PERK',
      'chapterForm.maxMerchant': Number.isInteger(Number(form.maxMerchant)) ? Number(form.maxMerchant) : 0,
    });
    if (enabled) this.loadMerchantCategories();
  },

  onChapterMerchantCategoryChange(e) {
    const category = this.data.merchantCategoryList[Number(e.detail.value)];
    if (!category || category.id == null) return;
    this.setData({
      'chapterForm.categoryId': Number(category.id),
      'chapterForm.category': category.categoryName || '',
    });
  },

  onChapterMaxMerchantInput(e) {
    const raw = String((e.detail && e.detail.value) || '').replace(/[^\d]/g, '');
    this.setData({ 'chapterForm.maxMerchant': raw === '' ? 0 : Number(raw) });
  },

  onChapterRequiredChange(e) {
    this.setData({ 'chapterForm.required': e.detail.value ? 1 : 0 });
  },

  onChapterTermsModeChange(e) {
    const termsMode = e.currentTarget.dataset.mode;
    if (termsMode !== 'PERK' && termsMode !== 'TRAFFIC' && termsMode !== 'REVSHARE') return;
    if (termsMode === 'REVSHARE' && (Number(this.data.formData.productType) !== 2 || !(Number(this.data.formData.clubId) > 0))) return;
    this.setData({
      'chapterForm.termsMode': termsMode,
      'chapterForm.perkMinValue': termsMode === 'PERK' ? this.data.chapterForm.perkMinValue : null,
      'chapterForm.maxPerHeadFee': termsMode === 'REVSHARE' ? this.data.chapterForm.maxPerHeadFee : null
    });
  },

  /**
   * 玩法边界(2026-08-06 拍板):允许的验证方式 + 单节点探索值上限。
   *
   * ★这两项是**招商时公示、承接时冻结**的 —— 商家申请前就看得到,不是做完了才被告知超标。
   * 承接通过时冻进 offer(MerchantOfferServiceImpl.enroll),之后按冻结的那份校验,
   * 主办方事后改章节不会让已承接商家突然违规。
   *
   * 不填 = 不限。⚠️ 不限是**存量语义**,别为了"对称"给个默认值,那会把老章节一夜之间收紧。
   */
  onChapterValidationMethodToggle(e) {
    const v = String(e.currentTarget.dataset.v || '');
    const index = Number(e.currentTarget.dataset.index);
    if (!v) return;
    // ⚠️ 走 _updateChapterRecruitConfig(index, ...) 而不是 chapterForm ——
    // step3 那块渲染的是 formData.chapters 列表(wx:for 的 item),不是编辑弹窗的 chapterForm。
    // 写错对象的话:界面看着变了(其实没变),提交时带的还是旧值,且不报错。
    const chapter = (this.data.formData.chapters || [])[index] || {};
    const cur = String(chapter.allowedValidationMethods || '')
      .split(',').map((x) => x.trim()).filter(Boolean);
    const i = cur.indexOf(v);
    if (i >= 0) cur.splice(i, 1); else cur.push(v);
    // 排序后再存:后端只做集合判断,但存成稳定顺序便于人肉核对与 diff
    cur.sort();
    this._updateChapterRecruitConfig(index, { allowedValidationMethods: cur.join(',') });
  },

  onChapterMaxNodeXpInput(e) {
    const raw = String((e.detail && e.detail.value) || '').replace(/[^\d]/g, '');
    this._updateChapterRecruitConfig(e.currentTarget.dataset.index, {
      maxNodeXp: raw === '' ? null : Number(raw)
    });
  },

  onChapterPerkMinValueInput(e) {
    let raw = String((e.detail && e.detail.value) || '').replace(/[^\d.]/g, '');
    const decimal = raw.indexOf('.');
    if (decimal >= 0) raw = raw.slice(0, decimal + 1) + raw.slice(decimal + 1).replace(/\./g, '');
    this.setData({ 'chapterForm.perkMinValue': raw === '' ? null : raw });
  },

  onChapterMaxPerHeadFeeInput(e) {
    let raw = String((e.detail && e.detail.value) || '').replace(/[^\d.]/g, '');
    const decimal = raw.indexOf('.');
    if (decimal >= 0) raw = raw.slice(0, decimal + 1) + raw.slice(decimal + 1).replace(/\./g, '');
    this.setData({ 'chapterForm.maxPerHeadFee': raw === '' ? null : raw });
  },

  normalizeChapterMerchantConfig(chapter) {
    chapter.required = Number(chapter.required) === 0 ? 0 : 1;
    chapter.recruitEnabled = Number(chapter.recruitEnabled) === 1 ? 1 : 0;
    if (Number(this.data.formData.productType) !== 2 || chapter.recruitEnabled !== 1) return true;
    if (!(Number(chapter.categoryId) > 0)) {
      cyToast('请选择适合商家的招募品类');
      return false;
    }
    if (chapter.termsMode !== 'PERK' && chapter.termsMode !== 'TRAFFIC' && chapter.termsMode !== 'REVSHARE') {
      cyToast('请选择平台条款档');
      return false;
    }
    const maxMerchant = Number(chapter.maxMerchant);
    if (!Number.isInteger(maxMerchant) || maxMerchant < 0 || maxMerchant > 127) {
      cyToast('商家名额填 0 到 127 的整数');
      return false;
    }
    chapter.maxMerchant = maxMerchant;
    if (chapter.termsMode === 'REVSHARE') {
      const fee = chapter.maxPerHeadFee;
      if (!(Number(this.data.formData.clubId) > 0) || !/^\d+(\.\d{1,2})?$/.test(String(fee))
          || !Number.isFinite(Number(fee)) || Number(fee) <= 0) {
        cyToast('选俱乐部，填正数且最多两位小数');
        return false;
      }
      chapter.maxPerHeadFee = Number(fee);
      chapter.perkMinValue = null;
      return true;
    }
    chapter.maxPerHeadFee = null;
    if (chapter.termsMode !== 'PERK') {
      chapter.perkMinValue = null;
      return true;
    }
    if (chapter.perkMinValue === '' || chapter.perkMinValue == null) {
      chapter.perkMinValue = null;
      return true;
    }
    const perkMinValue = Number(chapter.perkMinValue);
    if (!Number.isFinite(perkMinValue) || perkMinValue <= 0) {
      cyToast('权益门槛请输入大于0的金额');
      return false;
    }
    chapter.perkMinValue = perkMinValue;
    return true;
  },

  //章节弹框 完成 按钮
  confrimChapter(e) {
    var that = this
    let chapterAction = that.data.popChapterAction;
    let newChapter = {
      ...this.data.chapterForm
    };
    newChapter.atmospherePreset = normalizeAtmosphere(newChapter.atmospherePreset);
    if (!this.normalizeChapterMerchantConfig(newChapter)) {
      return false;
    }
    const cityStoryFlow = Number(this.data.formData.productType) === 1 || newChapter.opening === true;

    if (chapterAction == 0) {
      // 如果用户没有修改名称，使用默认名称
      if (!newChapter.name || newChapter.name.trim() === '') {
        const currentChapterCount = this.data.formData.chapters.length;
        const nextChapterNumber = currentChapterCount + 1;
        newChapter.name = `第${nextChapterNumber}章`;
      }

      if (newChapter.name == '') {
        cyToast('请输入章节名称');
        return false
      }

      // 2026-08-10 删除「空剧情写暂无描述」:占位文案在库里和真实剧情长得一样,
      // 城市定向的剧情闸(没剧情不许建节点)会被它整个绕过。空值就保持空值。

      const pending = (this.data.pendingMaterials || []).slice();
      const pendingIndex = pending.findIndex(item => item._localId === this.data.assignPendingLocalId);
      if (this.data.assignPendingLocalId && pendingIndex < 0) {
        cyToast('待编排素材已不存在');
        return false;
      }
      if (pendingIndex >= 0 && !cityStoryFlow) {
        const issue = proEditorMaterials.arrangementIssue({
          productType: this.data.formData.productType,
          chapter: newChapter,
          material: pending[pendingIndex],
        });
        if (issue) {
          cyToast(issue.message);
          return false;
        }
        if (!this._canAddFormalNodes(1)) return false;
        newChapter.nodes = newChapter.nodes || [];
        newChapter.nodes.push(proEditorMaterials.nodeFromMaterial(pending[pendingIndex], 1));
        pending.splice(pendingIndex, 1);
      }
      newChapter._localId = newChapter._localId || this._genLocalId();
      newChapter.nodes = newChapter.nodes || [];
      if (cityStoryFlow) {
        newChapter.required = 1;
        newChapter.schemaVersion = 1;
        newChapter.blocks = [];
        newChapter.description = '';
      }
      if (!this._saveChapterRules()) return false;
      this._pushUndo();
      if (newChapter.opening) this._prependRuntimeChapter();
      let updatedChapters = newChapter.opening
        ? [newChapter, ...this.data.formData.chapters] : [...this.data.formData.chapters, newChapter];
      const pendingStoryMaterial = cityStoryFlow ? this.data.assignPendingLocalId : '';
      this.destroyPreviewAudio(); // R9-36:完成新增=弹层关闭,正在播的本章旁白一起收
      this.setData({
        'formData.chapters': updatedChapters,
        pendingMaterials: pending,
        'popChapter': false,
        assignPendingLocalId: '',
        // 重置表单，但为下一个章节预填名称
        chapterForm: emptyChapterForm(`第${updatedChapters.filter(chapter => !chapter.opening).length + 1}章`)
      }, () => {
        if (!cityStoryFlow) return;
        this.setData({ storyPendingMaterialLocalId: pendingStoryMaterial || '' });
        this.openStoryEditor({ currentTarget: { dataset: { index: newChapter.opening ? 0 : updatedChapters.length - 1 } } });
      });
    } else {
      // 编辑现有章节的逻辑保持不变
      let chapterIndex = that.data.popChapterIndex;
      // 同上:不再用占位文案凑完成态
      let chapters = that.data.formData.chapters;
      if (!this._saveChapterRules()) return false;
      this._pushUndo();
      chapters[chapterIndex] = newChapter
      this.destroyPreviewAudio(); // R9-36:完成编辑=弹层关闭,正在播的本章旁白一起收
      this.setData({
        'formData.chapters': chapters,
        'popChapter': false,
        assignPendingLocalId: '',
        // 重置表单
        chapterForm: emptyChapterForm()
      });
      // 从故事流「下一步」进来的:「完成」是整条编排的收尾,两层一起收。
      // 只收上层会把人丢回故事流,而他刚点过的正是"下一步"。
      if (this.data.chapterSettingsFromStory) {
        this.setData({ chapterSettingsFromStory: false });
        this.closeStoryEditor();
      }
    }

    this.updateAllStatistics();
  },

  // 故事流底栏「下一步」→ 编辑章节。与章节卡上的「编辑章节」是同一个弹窗,
  // 区别只在**从哪进来**(用户 2026-09-05 定):从这里进来是叠层(故事流留在下层),
  // 完成后两层一起收;从章节卡进来是单层,完成只收弹窗。
  // 叠不叠层不需要额外的层级机制 —— pop-model z-index=333 本来就压在 story-editor(240)之上,
  // 这里只是**不关故事流**而已;下层压暗由 .story-editor.is-stacked 负责。
  openChapterSettingsFromStory(e) {
    this.setData({ chapterSettingsFromStory: true });
    this.showEditChapter(e);
  },

  // 关闭章节弹框
  cancelChapter(e) {
    // R9-36:弹层里预听的是本章旁白,取消后弹层与播放入口一起消失,
    // 不停就会留一段在后台响。与 closeStoryEditor/openStoryEditor 同口径。
    this.destroyPreviewAudio();
    this.setData({
      popChapter: false,
      popChapterIndex: 0,
      assignPendingLocalId: '',
      chapterSettingsFromStory: false
    })
  },
  // 显示编辑节点弹框
  showEditNodes(e) {
    var that = this
    let chapterIndex = e.currentTarget.dataset.chapterindex;
    let nodeIndex = e.currentTarget.dataset.nodeindex;

    let chapter = that.data.formData.chapters[chapterIndex]
    let node = chapter['nodes'][nodeIndex]
    let nodesForm = Object.assign({}, node)
    if (this._templateDetailsById && this._templateDetailsById[node.templateId])
      nodesForm.templateInfo = this._templateDetailsById[node.templateId];
    // 玩法开关不入库,由 templateId 派生:绑过模板就是"开"。
    // 不派生的话,已配玩法的节点重开抽屉会显示成"关",看着像配置丢了。
    nodesForm.showTemplate = !!templateAdoption.id(node.templateId)
    this.setData({
      nodesForm: nodesForm,
      popChapterNodes: true,
      popChapterNodesAction: 1,
      nodeDraftDestination: 'formal',
      editingPendingLocalId: '',
      editTargetChapterLid: chapter._localId,
      editTargetNodeLid: node._localId,
      // 抽屉里的「文字说明」要跳二级页,而二级页按 index 定位,所以开抽屉时一并记下
      inlineEdit: { chapterIndex: Number(chapterIndex), nodeIndex: Number(nodeIndex) },
    })
    this._openNodeBinding(nodesForm);
    this._refreshStructureSummary(); // structure
    if (node.templateId && !(nodesForm.templateInfo && Object.prototype.hasOwnProperty.call(nodesForm.templateInfo, 'advancedConfigJson')))
      this._loadNodeTemplateDetail(node);
  },
  // 点地图 pin → 用 _markerMap 反查打开该节点编辑(复用 showEditNodes 效果)
  onMarkerTap(e) {
    const markerId = (e.detail && e.detail.markerId != null) ? e.detail.markerId : e.markerId;
    const m = this._markerMap && this._markerMap[markerId];
    if (!m) return;
    if (m.pendingLocalId) {
      this.editPendingMaterial({ currentTarget: { dataset: { localid: m.pendingLocalId } } });
      return;
    }
    const chapters = this.data.formData.chapters || [];
    const ci = chapters.findIndex(c => c._localId === m.chapterLid);
    if (ci < 0) return;
    const node = (chapters[ci].nodes || []).find(n => n._localId === m.nodeLid);
    if (!node) return;
    if (Number(this.data.formData.productType) === 1) {
      this.openStoryEditor({ currentTarget: { dataset: { index: ci } } });
      cyToast('已定位到故事流，请点节点卡编辑');
      return;
    }
    this.setEditorState('editNode');
    const nodesForm = Object.assign({}, node, { showTemplate: !!templateAdoption.id(node.templateId) });
    if (this._templateDetailsById && this._templateDetailsById[node.templateId])
      nodesForm.templateInfo = this._templateDetailsById[node.templateId];
    this.setData({
      nodesForm,
      popChapterNodes: true,
      popChapterNodesAction: 1,
      editTargetChapterLid: m.chapterLid,
      editTargetNodeLid: m.nodeLid,
      selectedNodeLid: m.nodeLid, // 点 marker → 列表同步高亮
    });
    this._openNodeBinding(nodesForm);
    this._refreshStructureSummary(); // structure
    if (node.templateId && !(nodesForm.templateInfo && Object.prototype.hasOwnProperty.call(nodesForm.templateInfo, 'advancedConfigJson')))
      this._loadNodeTemplateDetail(node);
    this.buildRouteMap();
  },
  // 点列表序号圈 → 仅定位+高亮(不打开编辑抽屉),并把地图中心移到该节点
  selectNode(e) {
    const { chapterindex, nodeindex } = e.currentTarget.dataset;
    const chapters = (this.data.formData && this.data.formData.chapters) || [];
    const chapter = chapters[chapterindex];
    if (!chapter) return;
    const node = (chapter.nodes || [])[nodeindex];
    if (!node) return;
    const lat = Number(node.latitude);
    const lng = Number(node.longitude);
    const patch = { selectedNodeLid: node._localId };
    if (lat && lng && !isNaN(lat) && !isNaN(lng)) {
      patch.mapCenter = { longitude: lng, latitude: lat };
      patch.mapScale = Math.max(this.data.mapScale, 16);
    }
    this.setData(patch);
    this.buildRouteMap();
  },
  //关闭节点编辑弹框
  cancelPopChapter(e) {
    this._nodeTemplateLoadToken = (this._nodeTemplateLoadToken || 0) + 1;
    this._loadingSelectedTemplate = 0;
    this._rollbackNodeRouteEdit();
    this._nodeBindingSlots = [];
    this._nodeBindingDraft = null;
    this.setData({
      popChapterNodes: false,
      nodeSheetView: 'detail',
      editingPendingLocalId: '',
      nodeDraftDestination: 'formal',
      pendingInsertAt: null,
      nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
      nodePlanEndingRows: [], nodePlanEndingError: '',
      'storyEditor.insertMenuAt': -1,
    })
  },
  // 显示添加节点弹框
  showAddNode(e) {
    let chapterIndex = e.currentTarget.dataset.chapterIndex;
    let chapter = this.data.formData.chapters[chapterIndex];
    if (!chapter) return;
    const issue = proEditorPolicy.formalNodeCreationIssue(this.data.formData, chapterIndex);
    if (issue) {
      cyToast(issue.message);
      return;
    }
    this._nodeBindingSlots = [];
    this._nodeBindingDraft = null;
    this.setData({
      popChapterNodes: true,
      popChapterNodesAction: 0,
      nodeSheetView: 'detail',
      nodeDraftDestination: 'formal',
      editingPendingLocalId: '',
      editTargetChapterLid: chapter._localId,
      editTargetNodeLid: '',
      inlineEdit: null,
      nodesForm: this._emptyNodesForm(),
      nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
      nodePlanEndingRows: [], nodePlanEndingError: '',
    })
  },
  // 节点描述输入框变化处理
  onNodesDescriptionInputChange(e) {
    this.setData({
      'nodesForm.description': e.detail.value
    });
  },

  // 删除节点
  deleteNode(e) {
    if (Number(this.data.formData.productType) === 1 && this.data.storyEditor.show) {
      const chapter = (this.data.formData.chapters || [])[this.data.storyEditor.chapterIndex];
      const block = chapter && (chapter.blocks || []).find(item => {
        return item.type === 'node' && item.nodeKey === this.data.editTargetNodeLid;
      });
      if (block) {
        this.removeStoryNode({ currentTarget: { dataset: { blockkey: block.key } } });
        return;
      }
    }
    const chapters = JSON.parse(JSON.stringify(this.data.formData.chapters || []));
    const ci = chapters.findIndex(c => c._localId === this.data.editTargetChapterLid);
    if (ci < 0) {
      cyToast('未找到章节');
      return;
    }
    const nodes = chapters[ci].nodes || [];
    const ni = nodes.findIndex(n => n._localId === this.data.editTargetNodeLid);
    if (ni < 0) {
      cyToast('未找到节点');
      return;
    }
    modal.show({
      title: '确认删除',
      content: '节点会移到待编排区，5 秒内可以撤销。',
      success: (r) => {
        if (!r.confirm) return;
        const previousRouteNodes = this._routeNodes();
        const removed = nodes.splice(ni, 1)[0];
        const material = proEditorMaterials.materialFromNode(removed, 'node');
        const binding = this._removeNodeTemplateBinding(removed);
        const ruleRefs = this._removeNodeRuleRefs(removed);
        chapters[ci].nodes = nodes;
        const pending = (this.data.pendingMaterials || [])
          .filter(item => item._localId !== material._localId)
          .concat([material]);
        this._appendPendingRemoval({
          kind: 'material',
          chapterLid: chapters[ci]._localId,
          nodeIndex: ni,
          material,
          node: removed,
          binding,
          ruleRefs,
        });
        if (this._pendingUndoTimer) clearTimeout(this._pendingUndoTimer);
        this.setData({
          'formData.chapters': chapters,
          pendingMaterials: pending,
          popChapterNodes: false,
          popChapterNodesAction: 0,
          pendingUndo: { show: true, materialLocalId: material._localId, text: '节点已移到待编排区' },
        });
        this._realignStoryRouteGraph(previousRouteNodes);
        this._pruneRouteGraphToCurrentNodes();
        this._commitNodeRouteEdit();
        this.updateAllStatistics();
        cyToast(ruleRefs && ruleRefs.note ? '已移到待编排区，' + ruleRefs.note : '已移到待编排区');
        this._pendingUndoTimer = setTimeout(() => {
          this._pendingUndoTimer = null;
          this._pendingUndoSnapshot = null;
          this.setData({ pendingUndo: { show: false, materialLocalId: '', text: '' } });
        }, 5000);
        if (this._pendingUndoTimer && typeof this._pendingUndoTimer.unref === 'function') {
          this._pendingUndoTimer.unref();
        }
      }
    });
  },

  undoPendingRemoval() {
    const snapshot = this._pendingUndoSnapshot;
    if (!snapshot) return;
    let removals = Array.isArray(snapshot.removals) ? snapshot.removals.slice() : [];
    if (!removals.length && Array.isArray(snapshot.storyRemovals)) {
      removals = snapshot.storyRemovals.map((item) => Object.assign({ kind: 'story' }, item));
    } else if (!removals.length && snapshot.block && snapshot.node) {
      removals = [Object.assign({ kind: 'story' }, snapshot)];
    } else if (!removals.length && snapshot.material) {
      removals = [Object.assign({ kind: 'material' }, snapshot)];
    }
    if (removals.length) {
      const chapterIds = new Set((this.data.formData.chapters || []).map((item) => item._localId));
      const pendingIds = new Set((this.data.pendingMaterials || []).map((item) => item._localId));
      const canRestore = removals.every((removal) => chapterIds.has(removal.chapterLid)
        && (removal.kind !== 'material'
          || (removal.material && pendingIds.has(removal.material._localId))));
      if (!canRestore) return;
      try {
        const restoredChapters = JSON.parse(JSON.stringify(this.data.formData.chapters || []));
        let restoredPending = JSON.parse(JSON.stringify(this.data.pendingMaterials || []));
        const restoredRuntime = templateBinding.parse(this.data.formData.themeRuntimeConfig);
        let restoredBindings = false;
        for (let index = removals.length - 1; index >= 0; index -= 1) {
          const removal = removals[index];
          if (removal.binding) {
            restoredRuntime.templateBindings = restoredRuntime.templateBindings || {};
            restoredRuntime.templateBindings[templateBinding.nodeKey(removal.node)] = removal.binding;
            restoredBindings = true;
          }
          if (removal.ruleRefs) {
            // 撤销删除:把删站点时移出的互斥组 / 额外站点原样放回
            removal.ruleRefs.before.forEach((row) => {
              restoredRuntime.chapters = restoredRuntime.chapters || [];
              const target = restoredRuntime.chapters.find((item) => item.index === row.index);
              if (!target) return;
              ['exclusiveGroups', 'extraNodes'].forEach((field) => {
                if (row[field]) target[field] = row[field]; else delete target[field];
              });
            });
            if (removal.ruleRefs.gameBefore) restoredRuntime.gameplayRuntime = removal.ruleRefs.gameBefore;
            restoredBindings = true;
          }
          if (removal.kind === 'material') {
            const chapter = restoredChapters.find((item) => item._localId === removal.chapterLid);
            const pendingIndex = restoredPending
              .findIndex((item) => item._localId === removal.material._localId);
            const insertAt = Math.max(0, Math.min((chapter.nodes || []).length, removal.nodeIndex));
            chapter.nodes = chapter.nodes || [];
            const restoredNode = removal.node
              ? JSON.parse(JSON.stringify(removal.node))
              : proEditorMaterials.nodeFromMaterial(removal.material, insertAt + 1);
            chapter.nodes.splice(insertAt, 0, restoredNode);
            chapter.nodes.forEach((node, nodeIndex) => { node.sortID = nodeIndex + 1; });
            restoredPending.splice(pendingIndex, 1);
          } else {
            const chapterIndex = restoredChapters
              .findIndex((item) => item._localId === removal.chapterLid);
            const result = proEditorStory.applyStoryCommand({
              chapter: restoredChapters[chapterIndex],
              pendingMaterials: restoredPending,
            }, {
              type: 'insertNodeAt',
              index: removal.blockIndex,
              blockKey: removal.block.key,
              node: removal.node,
            });
            restoredChapters[chapterIndex] = result.draft.chapter;
            restoredPending = result.draft.pendingMaterials;
          }
        }
        const restoredRoute = snapshot.route || {
          mode: this.data.formData.routeMode,
          json: this.data.formData.routeGraphJson,
          graph: this._routeGraph,
        };
        this._routeGraph = restoredRoute.graph;
        this.setData({
          'formData.chapters': restoredChapters,
          pendingMaterials: restoredPending,
          pendingUndo: { show: false, materialLocalId: '' },
          'formData.routeMode': restoredRoute.mode,
          'formData.routeGraphJson': restoredRoute.json,
          'formData.themeRuntimeConfig': restoredBindings ? JSON.stringify(restoredRuntime) : this.data.formData.themeRuntimeConfig,
        }, () => this.refreshRouteGraph());
      } catch (error) {
        if (this._pendingUndoTimer) clearTimeout(this._pendingUndoTimer);
        this._pendingUndoTimer = null;
        cyToast(app.getRequestErrorMessage(error, '撤销失败'));
        return;
      }
      if (this._pendingUndoTimer) clearTimeout(this._pendingUndoTimer);
      this._pendingUndoTimer = null;
      this._pendingUndoSnapshot = null;
      this.updateAllStatistics();
      cyToast.success('已撤销');
      return;
    }
  },
  // —— 节点重排:上移/下移(可靠兜底)+ movable-view 拖拽 ——
  // 行高常量(rpx):.item-box1 min-height 186 + .li margin-bottom 60(fabu 覆盖)≈ 246
  // 用于 movable-view bindchange/bindtouchend 把实时 y 折算成目标 index。
  // 定位全靠 _localId,重排后 buildRouteMap 经 _markerMap 自然跟随,不引 index 依赖。
  _reorderNode(chapterLid, nodeLid, dir) {
    let chapters = [...this.data.formData.chapters];
    let ci = chapters.findIndex(c => c._localId === chapterLid);
    if (ci < 0) return;
    let nodes = chapters[ci].nodes || [];
    let ni = nodes.findIndex(n => n._localId === nodeLid);
    if (ni < 0) return;
    let target = ni + dir;
    if (target < 0 || target >= nodes.length) return; // 首项禁上移、末项禁下移
    const previousRouteNodes = this._routeNodes();
    this._pushUndo();
    let tmp = nodes[ni];
    nodes[ni] = nodes[target];
    nodes[target] = tmp;
    nodes.forEach((n, idx) => { n.sortID = idx + 1; }); // 重算 sortID
    chapters[ci].nodes = nodes;
    this.setData({ 'formData.chapters': chapters });
    this._realignStoryRouteGraph(previousRouteNodes);
    this.updateAllStatistics(); // 末尾会重画地图
  },
  moveNodeUp(e) {
    const ds = e.currentTarget.dataset;
    this._reorderNode(ds.chapterlid, ds.nodelid, -1);
  },
  moveNodeDown(e) {
    const ds = e.currentTarget.dataset;
    this._reorderNode(ds.chapterlid, ds.nodelid, 1);
  },
  // —— movable-view 竖向拖拽重排(手柄门控,上下移按钮仍作兜底)——
  // 按住 .drag-handle 才进入拖拽态(dragNodeLid),其余行 disabled;松手折算 y→目标 index。
  onNodeDragStart(e) {
    this.setData({ dragNodeLid: e.currentTarget.dataset.nodelid });
  },
  onNodeDragChange(e) {
    if (e.detail.source === 'touch') this._dragY = e.detail.y; // 实例变量,拖拽中不 setData
  },
  onNodeDragEnd(e) {
    const ds = e.currentTarget.dataset;
    if (this._dragY == null) { this.setData({ dragNodeLid: '' }); this._resetDragOffsets(); return; }
    const chapters = (this.data.formData && this.data.formData.chapters) || [];
    const chapter = chapters.find(c => c._localId === ds.chapterlid);
    const maxIdx = chapter ? (chapter.nodes || []).length - 1 : 0;
    const toIdx = Math.max(0, Math.min(maxIdx, Math.round(this._dragY / this.data.rowHpx)));
    this._dragY = null;
    this._moveNodeToIndex(ds.chapterlid, ds.nodelid, toIdx);
    this.setData({ dragNodeLid: '' });
    this._resetDragOffsets();
  },

  // movable-view 只在 y 属性「变化」时才移动;拖回原位(toIdx===fromIdx)时行序没变、
  // y 算出来还是老值 —— 于是那一行就停在手指松开的地方不回弹。
  // 故意 nudge 1px 再收回,制造一次值变化把它拽回格子里。
  _resetDragOffsets() {
    this.setData({ dragNudge: 1 }, () => this.setData({ dragNudge: 0 }));
  },
  // 把节点移动到指定 index(_localId 定位,clamp,撤销快照,重算 sortID,重画地图)
  _moveNodeToIndex(chapterLid, nodeLid, toIdx) {
    let chapters = [...this.data.formData.chapters];
    let ci = chapters.findIndex(c => c._localId === chapterLid);
    if (ci < 0) return;
    let nodes = chapters[ci].nodes || [];
    let fromIdx = nodes.findIndex(n => n._localId === nodeLid);
    if (fromIdx < 0) return;
    toIdx = Math.max(0, Math.min(nodes.length - 1, toIdx));
    if (toIdx === fromIdx) return;
    const previousRouteNodes = this._routeNodes();
    this._pushUndo();
    let moved = nodes.splice(fromIdx, 1)[0];
    nodes.splice(toIdx, 0, moved);
    nodes.forEach((n, idx) => { n.sortID = idx + 1; }); // 重算 sortID
    chapters[ci].nodes = nodes;
    this.setData({ 'formData.chapters': chapters });
    this._realignStoryRouteGraph(previousRouteNodes);
    this.updateAllStatistics(); // 末尾重画地图;各行 y 经 nodeIndex*rowHpx 自然复位
  },
  // 添加节点
  confrimNode(e) {
    const nodesAction = this.data.popChapterNodesAction;
    const node = Object.assign({}, this.data.nodesForm);
    node.name = String(node.name || '').trim();
    if (!node.name) {
      cyToast('请填写节点名称');
      return;
    }
    const gameplayIssue = proEditorPolicy.storyGameIssue(node);
    if (gameplayIssue) { cyToast(gameplayIssue); return; }
    if (templateAdoption.id(node.templateId) && !(node.templateInfo
      && Object.prototype.hasOwnProperty.call(node.templateInfo, 'advancedConfigJson'))) {
      cyToast('玩法详情未加载，请稍后再完成节点');
      return;
    }
    node.nodeTime = Number(node.nodeTime || 30);

    const editingPending = this.data.editingPendingLocalId;
    const insertingStoryNode = Number(this.data.formData.productType) === 1
      && this.data.storyEditor.show
      && this.data.pendingInsertAt !== null;
    if ((this.data.nodeDraftDestination === 'pending' || editingPending) && !insertingStoryNode) {
      const existing = (this.data.pendingMaterials || []).find(item => item._localId === editingPending);
      const material = proEditorMaterials.materialFromNode(Object.assign({}, node, {
        _localId: editingPending || this._genLocalId(),
      }), existing && existing.kind === 'place' ? 'place' : 'node');
      this._savePendingMaterial(material, editingPending ? '素材已更新' : '已保存到待编排区');
      return;
    }

    const chapters = JSON.parse(JSON.stringify(this.data.formData.chapters || []));
    const chapterIndex = chapters.findIndex(chapter => chapter._localId === this.data.editTargetChapterLid);
    if (chapterIndex < 0) {
      cyToast('未找到章节');
      return;
    }
    const creationIssue = proEditorPolicy.formalNodeCreationIssue(this.data.formData, chapterIndex);
    if (creationIssue) {
      cyToast(creationIssue.message);
      return;
    }

    const currentNodes = chapters[chapterIndex].nodes || [];
    const nodeIndex = nodesAction === 1
      ? currentNodes.findIndex(item => item._localId === this.data.editTargetNodeLid) : -1;
    if (nodesAction === 1 && nodeIndex < 0) {
      cyToast('未找到节点');
      return;
    }
    node._localId = nodeIndex >= 0
      ? currentNodes[nodeIndex]._localId : (editingPending || this._genLocalId());

    // 故事玩法由模板完成；地点仍需真实坐标，否则留在待编排区。
    if (!node._storyGame && !proEditorPolicy.hasUsableCoords(node)) {
      if (Number(this.data.formData.productType) === 1
          && this.data.storyEditor.show && nodeIndex >= 0) {
        const storyBlock = (chapters[chapterIndex].blocks || []).find(item => {
          return item && item.type === 'node' && item.nodeKey === node._localId;
        });
        if (!storyBlock) {
          cyToast('未找到节点块');
          return;
        }
        try {
          this._pushUndo();
          // ⚠️ removeNode 2026-08-11 起是【真删】,不再自己往待编排区放一份。
          // 所以"移到待编排区"这件事必须在这里显式做完 —— 否则这段 toast 说的话
          // 和实际发生的事对不上,节点直接没了。
          const material = proEditorMaterials.materialFromNode(node, 'node');
          const pending = (this.data.pendingMaterials || [])
            .filter(item => item._localId !== material._localId)
            .concat([material]);
          this._storyCommand({
            type: 'removeNode',
            blockKey: storyBlock.key,
            node,
          }, {
            pendingMaterials: pending,
            popChapterNodes: false,
            popChapterNodesAction: 0,
            editingPendingLocalId: '',
            nodeDraftDestination: 'formal',
            pendingInsertAt: null,
            storyPendingMaterialLocalId: '',
            nodesForm: this._emptyNodesForm(),
          });
          this._removeNodeTemplateBinding(node);
          cyToast('地点待补，已移到待编排区');
        } catch (error) {
          cyToast(app.getRequestErrorMessage(error, '移动节点失败'));
        }
        return;
      }
      const material = proEditorMaterials.materialFromNode(node, 'node');
      const pending = (this.data.pendingMaterials || []).filter(item => item._localId !== material._localId);
      pending.push(material);
      if (nodeIndex >= 0) currentNodes.splice(nodeIndex, 1);
      chapters[chapterIndex].nodes = currentNodes;
      this.setData({
        'formData.chapters': chapters,
        pendingMaterials: pending,
        popChapterNodes: false,
        popChapterNodesAction: 0,
        editingPendingLocalId: '',
        nodeDraftDestination: 'formal',
        pendingInsertAt: null,
        storyPendingMaterialLocalId: '',
        nodesForm: this._emptyNodesForm(),
      });
      if (nodeIndex >= 0) this._removeNodeTemplateBinding(node);
      this.updateAllStatistics();
      cyToast('地点待补，已移到待编排区');
      return;
    }

    if (nodesAction === 0) {
      // 新建与待编排转正必须共用同一配额闸，避免先堆 pending 再归章绕过 Lite 上限。
      // 故事玩法不算节点名额(2026-09-23 用户裁决)。
      if (!node._storyGame && !this._canAddFormalNodes(1)) return;
    }

    if (insertingStoryNode && nodesAction === 0) {
      try {
        this._pushUndo();
        this._storyCommand({
          type: 'insertNodeAt',
          index: this.data.pendingInsertAt,
          blockKey: this._genLocalId(),
          node,
        }, {
          popChapterNodes: false,
          popChapterNodesAction: 0,
          editingPendingLocalId: '',
          nodeDraftDestination: 'formal',
          pendingInsertAt: null,
          storyPendingMaterialLocalId: '',
          nodesForm: this._emptyNodesForm(),
          selectedNodeLid: node._localId,
        });
        this._commitNodeTemplateBinding(node);
        cyToast.success('已插入故事流');
      } catch (error) {
        cyToast(app.getRequestErrorMessage(error, '插入节点失败'));
      }
      return;
    }

    if (Number(this.data.formData.productType) === 1
        && this.data.storyEditor.show && nodeIndex >= 0) {
      try {
        this._pushUndo();
        this._storyCommand({ type: 'editNode', node }, {
          popChapterNodes: false,
          popChapterNodesAction: 0,
          editingPendingLocalId: '',
          nodeDraftDestination: 'formal',
          nodesForm: this._emptyNodesForm(),
          selectedNodeLid: node._localId,
        });
        this._commitNodeTemplateBinding(node);
        this._commitNodeRouteEdit();
        cyToast.success('节点已更新');
      } catch (error) {
        cyToast(app.getRequestErrorMessage(error, '更新节点失败'));
      }
      return;
    }

    this._pushUndo();
    if (nodeIndex >= 0) currentNodes[nodeIndex] = node;
    else currentNodes.push(Object.assign(node, { sortID: currentNodes.length + 1 }));
    chapters[chapterIndex].nodes = currentNodes;
    if (Number(this.data.formData.productType) === 1) {
      chapters[chapterIndex] = proEditorStory.materializeChapter(
        chapters[chapterIndex], () => this._genLocalId());
    }
    this.setData({
      'formData.chapters': chapters,
      popChapterNodes: false,
      popChapterNodesAction: 0,
      editingPendingLocalId: '',
      nodeDraftDestination: 'formal',
      nodesForm: this._emptyNodesForm(),
      selectedNodeLid: node._localId,
    });
    this._commitNodeTemplateBinding(node);
    this._commitNodeRouteEdit();
    this.updateAllStatistics();
    cyToast.success(nodesAction === 0 ? '已添加到路线' : '节点已更新');
  },

  // —— 撤销栈 —— (栈放实例变量 this._undoStack,不进 data 避免渲染开销)
  // 结构性变更前调:formData 与待编排区必须进同一快照，否则“素材归章→撤销”会丢素材。
  _pushUndo() {
    if (!this._undoStack) this._undoStack = [];
    this._undoStack.push(JSON.parse(JSON.stringify({
      formData: this.data.formData,
      pendingMaterials: this.data.pendingMaterials || [],
      routeGraph: this._routeGraph,
    })));
    if (this._undoStack.length > UNDO_CAP) this._undoStack.shift();
    this.setData({ canUndo: this._undoStack.length > 0 });
  },
  // 撤销:恢复完整编辑快照并重画地图与统计
  undo() {
    if (!this._undoStack || this._undoStack.length === 0) return;
    const snapshot = this._undoStack.pop();
    this._routeGraph = snapshot.routeGraph || topicRouteGraph.emptyGraph();
    this.setData({
      formData: snapshot.formData,
      pendingMaterials: snapshot.pendingMaterials || [],
      // 快照整体覆盖 formData,imgArrList 是它的派生视图,必须跟着回滚
      imgArrList: this._parseImgArr(snapshot.formData.imgArr),
      canUndo: this._undoStack.length > 0
    });
    this.updateAllStatistics();
  },

  // 生成前端态主键(仅前端用,提交时剥离)
  _genLocalId() { return 'lid' + (this._lidSeq = (this._lidSeq || 0) + 1); },

  // 为章节、节点与待编排素材补齐 _localId；先扫描已恢复 ID 推进序号，避免重开后复用 lid1。
  _ensureLocalIds() {
    const chapters = this.data.formData.chapters || [];
    const pendingMaterials = this.data.pendingMaterials || [];
    let maxSeq = this._lidSeq || 0;
    const scan = (item) => {
      const match = item && /^lid(\d+)$/.exec(String(item._localId || item.key || ''));
      if (match) maxSeq = Math.max(maxSeq, Number(match[1]));
    };
    chapters.forEach(chapter => {
      scan(chapter);
      (chapter.nodes || []).forEach(scan);
      (chapter.blocks || []).forEach(scan);
    });
    pendingMaterials.forEach(scan);
    this._lidSeq = maxSeq;
    chapters.forEach(chapter => {
      if (!chapter._localId) chapter._localId = this._genLocalId();
      (chapter.nodes || []).forEach(node => {
        if (!node._localId) node._localId = this._genLocalId();
      });
    });
    pendingMaterials.forEach(material => {
      if (!material._localId) material._localId = this._genLocalId();
    });
    this.setData({ 'formData.chapters': chapters, pendingMaterials });
  },

  // 返回去掉所有 '_' 开头键的浅拷贝(提交时剥离前端态字段)
  _stripLocalFields(obj) {
    let res = {};
    Object.keys(obj).forEach(key => {
      if (key.charAt(0) !== '_') res[key] = obj[key];
    });
    return res;
  },

  // 设置默认日期
  setDefaultDates() {
    if (this.data.formData.startDate && this.data.formData.endDate) {
      return;
    }

    let today = new Date();
    let tomorrow = new Date(today);
    tomorrow.setDate(tomorrow.getDate() + 1);

    let formatDate = (date) => {
      return `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date.getDate().toString().padStart(2, '0')}`;
    };

    this.setData({
      startdate: formatDate(today),
      enddate: formatDate(tomorrow),
      'formData.startDate': formatDate(today),
      'formData.endDate': formatDate(tomorrow)
    });
  },
  // 输入框变化处理
  onInputChange(e) {
    let {
      field
    } = e.currentTarget.dataset;
    this.setData({
      [`formData.${field}`]: e.detail.value
    });
  },

  // 路线介绍输入处理
  onIntroductionChange: function (e) {
    const value = e.detail.value;
    this.setData({
      'formData.description': value
    }, () => this.refreshPrimaryActionState());
  },

  // 两页切换。两页都可直达 —— 没有「必须填完第 1 页」的门控,
  // 因为三种创作起点想先做的事不同,强加顺序总会别扭其中一种。
  switchEditorPage(e) {
    const page = Number(e.currentTarget.dataset.page) === 2 ? 2 : 1;
    if (page === this.data.editorPage) return;
    this.setData({ editorPage: page }, () => this._scrollSheetTo('sheetTop'));
  },


  previewDraft() {
    let data;
    try { data = buildTopicPreview(this.data.formData); }
    catch (error) { app.tips(error.message); return; }
    wx.navigateTo({ url: '/pages/play/index?preview=1',
      success: result => result.eventChannel.emit('previewData', data),
      fail: () => app.tips('预览未打开，请重试'),
    });
  },

  // 2026-09-05 用户裁决:删掉 previewTopic()。它跳的是玩家端主题详情页,读【服务端已保存
  // 那一版】—— 编辑器里刚写的改动不在里面;新建主题没有 editingTopicId 时更是点了不动,
  // 只弹一句 toast。底部药丸改成真正推进流程的「发布 →」(submitForm)。
  // ⚠️ 以后要补回「发布前看成品」,走**本地草稿预览**(主题详情页加 preview 入参 + 一条
  // 本地数据源分支),不要再把按钮接回 ?id= —— 那条路读的永远是服务端那一版。

  openTopicDetail() { this._syncRuntimeStats(); this._syncD20Rows(); this._loadPlaySetup(); this._syncKeysItems(); this._refreshLookView(); this.setData({ topicDetailShow: true }); },
  closeTopicDetail() { this.setData({ topicDetailShow: false }); },

  // 列表 ↔ 地图 视图切换(左下角药丸)。
  // 地图挂在 wx:if 上,切走会连 markers/polyline 一起销毁,所以切回来必须重画一次,
  // 否则是一张没有路线的空地图。
  switchViewMode(e) {
    const mode = e.currentTarget.dataset.mode === 'map' ? 'map' : 'list';
    if (mode === this.data.viewMode) return;
    this.setData({ viewMode: mode }, () => {
      if (mode === 'map') this.buildRouteMap();
    });
  },

  // 节点坐标是否可用。三处口径原本各写各的(下一步闸 ≥1 / 完整度 ≥2 / validateForm 压根不查),
  // 收敛成这一个判据:没坐标的节点在地图上不存在、玩家走不到,发布前必须全部补齐。
  nodesMissingCoords(chapters) {
    const bad = [];
    (chapters || []).forEach((chapter, ci) => {
      (chapter.nodes || []).forEach((node, ni) => {
        const lng = node && node.longitude;
        const lat = node && node.latitude;
        if (!lng || !lat || lng === '0' || lat === '0') {
          bad.push({ chapterIndex: ci, nodeIndex: ni, chapterName: (chapter && chapter.name) || ('第' + (ci + 1) + '章') });
        }
      });
    });
    return bad;
  },

  _scrollSheetTo(anchorId) {
    this.setData({ scrollIntoView: '' });
    if (!anchorId) return;
    setTimeout(() => {
      this.setData({ scrollIntoView: anchorId });
      setTimeout(() => {
        this.setData({ scrollIntoView: '' });
      }, 500);
    }, 220);
  },

  _anchorForErrorKey(key) {
    // CU-C-160 负控:认不出来就返回空锚点。原来末尾一把 `return 'ticketSection'` 兜底,
    // 等于任何缺项(包括页面上根本没有的那一条)都能「切到票务 + 滚到档区」—— 屏幕动了、
    // 层也开了,看着像定位成功,实际那一格并不在那儿。空锚点是各处的「不动」信号。
    if (!key) return '';
    if (key.indexOf('ticket') === 0) return 'ticketSection';
    if (key === 'name') return 'fieldName';
    if (key === 'description') return 'fieldDescription';
    if (key === 'startDate' || key === 'endDate') return 'fieldDates';
    if (key === 'recruitDeadline') return 'fieldRecruitDeadline';
    // 通关规则(必达数)在主题详情弹窗里,不在票务页 —— 漏这一条会掉到末尾的 ticketSection,
    // 「去填写」把人带去档期/票单,而他要改的那一格在另一层。
    if (key === 'completionRule') return 'fieldCompletionRule';
    if (key === 'imgUrl') return 'fieldVisual';
    if (key === 'categoryIds') return 'fieldCategory';
    if (key === 'pendingMaterials') return 'pendingMaterialsSection';
    if (key === 'routeGraph') return 'routeSection';
    if (key.indexOf('chapterRecruit') === 0) return 'chapterRecruitSection';
    if (key.indexOf('chapter') === 0 || key === 'chapters') return 'routeSection';
    // CU-C-160 负控:认不出的字段返回空锚点,而不是兜到 ticketSection。
    // 兜底等于「页面上根本没有的缺项」也能切屏 + 滚档区 —— 看着像定位成功,那一格却不在那儿。
    // 空锚点是 focusValidationError / _scrollSheetTo 共用的「不动」信号。
    return '';
  },

  // 票务信息变化处理
  onTicketChange(e) {
    let {
      field,
      index
    } = e.currentTarget.dataset;
    let value = e.detail.value;

    // 价格和库存需要转换为数字
    if (field === 'price' || field === 'totalStock') {
      value = value === '' ? 0 : Number(value);
      // 确保不为负数
      value = Math.max(0, value);
    }

    this.setData({
      [`formData.tickets[${index}].${field}`]: value
    }, () => this.refreshPrimaryActionState());
  },

  onTicketModeChange(e) {
    const { index, mode } = e.currentTarget.dataset;
    const newMode = Number(mode);
    let tickets = [...this.data.formData.tickets];
    if (newMode === 1) {
      tickets[index] = { ...tickets[index], mode: 1, startTime: '', endTime: '' };
    } else {
      tickets[index] = { ...tickets[index], mode: 2, meetingPoint: '', teamSize: 0 };
    }
    this.setData({ 'formData.tickets': tickets }, () => this.refreshPrimaryActionState());
  },

  // 票务信息变化处理
  onTicketNumChange(e) {
    let {
      field,
      index
    } = e.currentTarget.dataset;
    let value = e.detail.value;

    if (field === 'totalStock' || field === 'teamSize') {
      value = value === '' ? 0 : Number(value);
      value = Math.max(0, value);
    }

    this.setData({
      [`formData.tickets[${index}].${field}`]: value
    }, () => this.refreshPrimaryActionState());
  },

  // 日期选择
  bindDateChange(e) {
    let value = e.detail.value;
    this.setData({
      startdate: value,
      'formData.startDate': value
    });

    // 更新票务时间
    this.updateTicketTimes(value, this.data.enddate);
  },

  bindDateChange2(e) {
    let value = e.detail.value;
    this.setData({
      enddate: value,
      'formData.endDate': (() => { const d = new Date(value + 'T00:00:00+08:00'); d.setHours(23, 59, 59, 999); return d.toISOString(); })()
    });

    // 更新票务时间
    this.updateTicketTimes(this.data.startdate, value);
  },

  // 更新票务时间
  updateTicketTimes(startDate, endDate) {
    let tickets = this.data.formData.tickets.map(ticket => ticket.syncWithTheme ? ({
      ...ticket,
      startTime: startDate,
      endTime: endDate
    }) : ticket);

    this.setData({
      'formData.tickets': tickets
    });
  },

  // 上传节点照片(抽屉内,多张,存逗号分隔 CSV)
  uploadAddressPic() {
    let that = this;
    const memberId = String(app.getUserID() || '');
    const nodeForm = that.data.nodesForm;
    const chapterLid = that.data.editTargetChapterLid;
    const nodeLid = that.data.editTargetNodeLid;
    const cur = that.data.nodesForm.imgUrl ? that.data.nodesForm.imgUrl.split(',').filter(Boolean) : [];
    const remain = 9 - cur.length;
    if (remain <= 0) { cyToast('每个节点最多9张'); return; }
    app.chooseImage(function (res) {
      if (that._unloaded || !that.data.popChapterNodes
          || memberId !== String(app.getUserID() || '')
          || nodeForm !== that.data.nodesForm
          || chapterLid !== that.data.editTargetChapterLid || nodeLid !== that.data.editTargetNodeLid) return;
      const current = String(that.data.nodesForm.imgUrl || '').split(',').filter(Boolean);
      const next = current.concat(Array.isArray(res) ? res : []).slice(0, 9);
      that.setData({ 'nodesForm.imgUrl': next.join(',') });
    }, remain, { crop: true, cropScale: NODE_PHOTO_RATIO });
  },
  // 删除节点照片(抽屉内,按索引)
  removeNodePhoto(e) {
    const i = Number(e.currentTarget.dataset.imgindex);
    const arr = this.data.nodesForm.imgUrl ? this.data.nodesForm.imgUrl.split(',').filter(Boolean) : [];
    if (i < 0 || i >= arr.length) return;
    arr.splice(i, 1);
    this.setData({ 'nodesForm.imgUrl': arr.join(',') });
  },

  // 上传 step3「竖版封面」的必填封面(字段 imgUrl)
  // 主消费面是 topic 详情首屏 .slide1(height:100vh 全屏 hero),3:4 竖图在竖屏下最贴合。
  // ⚠️ imgUrl 是跨实体复用的字段名:template 首页的 .feat-media / .cml-pic 消费的是
  //    CmsTemplateLibrary(玩法模板,publish/temp 裁 16:9),与本字段无关,改比例时别顺手动它们。
  _coverUploadGuard(field) {
    const tokens = this._coverUploadTokens || (this._coverUploadTokens = {});
    const token = (tokens[field] || 0) + 1; tokens[field] = token;
    const context = () => JSON.stringify([String(app.getUserID() || ''), this.data.operationScope,
      this.data.draftUuid, this.data.editingTopicId, this._editLoadToken,
      typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined]);
    const original = context();
    let settled = false;
    return () => {
      if (settled || this._unloaded || this._hidden || tokens[field] !== token || context() !== original) return false;
      settled = true; return true;
    };
  },
  _invalidateCoverUpload(field) {
    const tokens = this._coverUploadTokens || (this._coverUploadTokens = {});
    tokens[field] = (tokens[field] || 0) + 1;
  },
  uploadBanner() {
    const current = this._coverUploadGuard('imgUrl');
    app.chooseImage((res) => {
      if (!current() || !Array.isArray(res) || typeof res[0] !== 'string' || !res[0].trim()) return;
      this.setData({ 'formData.imgUrl': res[0] }, () => this.refreshPrimaryActionState());
    }, 1, { crop: true, cropScale: '3:4' });
  },
  removeBanner() {
    this._invalidateCoverUpload('imgUrl');
    this.setData({ 'formData.imgUrl': '' }, () => this.refreshPrimaryActionState());
  },

  // ===== 章节音频(2026-09-04 拍板:一章最多一段,挂在章节上而不是故事流的块里)=====
  // ⚠️ 这三个方法原来写的是主题级 formData.audioUrl,但 fabu 里从来没有任何 WXML 绑定它们
  //    (ui-integrity 把 onPreviewAudio 记成零绑定债务)。这次改挂 chapterForm ——
  //    主题级 audioUrl 已按 2026-09-15 拍板清理(小程序不再读写;后端字段保留,存量数据不受影响)。
  uploadChapterAudio() {
    const that = this;
    const chapterForm = this.data.chapterForm;
    const chapterIndex = this.data.popChapterIndex;
    const memberId = String(app.getUserID() || '');
    const token = (this._chapterAudioToken || 0) + 1;
    this._chapterAudioToken = token;
    this._destroyChapterDurationAudio();
    app.chooseDocument(function (res) {
      if (that._unloaded || !that.data.popChapter
          || token !== that._chapterAudioToken || chapterForm !== that.data.chapterForm
          || chapterIndex !== that.data.popChapterIndex
          || memberId !== String(app.getUserID() || '')) return;
      if (res && res[0] && res[0].url) {
        const file = res[0];
        that.setData({
          'chapterForm.audioUrl': file.url,
          'chapterForm.audioFileName': file.filename || '',
          'chapterForm.audioDuration': 0
        });
        that.autoDetectAudioDuration(file.url);
      }
    }, 1, ['mp3', 'm4a', 'aac']);
  },

  _destroyChapterDurationAudio() {
    const audio = this._chapterDurationAudio;
    this._chapterDurationAudio = null;
    if (audio) audio.destroy();
  },

  autoDetectAudioDuration(url) {
    const that = this;
    const chapterForm = this.data.chapterForm;
    const chapterIndex = this.data.popChapterIndex;
    const memberId = String(app.getUserID() || '');
    const token = this._chapterAudioToken;
    this._destroyChapterDurationAudio();
    const audio = wx.createInnerAudioContext();
    this._chapterDurationAudio = audio;
    audio.src = url;
    audio.obeyMuteSwitch = false;
    audio.onCanplay(function () {
      if (that._chapterDurationAudio !== audio) return;
      const current = !that._unloaded && that.data.popChapter
        && token === that._chapterAudioToken && chapterForm === that.data.chapterForm
        && chapterIndex === that.data.popChapterIndex && that.data.chapterForm.audioUrl === url
        && memberId === String(app.getUserID() || '');
      const duration = Math.round(audio.duration || 0);
      if (current && duration > 0) that.setData({ 'chapterForm.audioDuration': duration });
      that._destroyChapterDurationAudio();
    });
    audio.onError(function () {
      if (that._chapterDurationAudio === audio) that._destroyChapterDurationAudio();
    });
  },

  clearChapterAudio() {
    this._chapterAudioToken = (this._chapterAudioToken || 0) + 1;
    this._destroyChapterDurationAudio();
    this.destroyPreviewAudio();
    this.setData({
      'chapterForm.audioUrl': '', 'chapterForm.audioDuration': 0,
      'chapterForm.audioFileName': ''
    });
  },

  // 来源与URL都一致才复用：同址不同来源须换监听器，同来源替换文件也须重建。
  onPreviewChapterAudio() {
    var that = this;
    var url = this.data.chapterForm.audioUrl;
    if (!url) return;
    var source = this._previewSource;
    if (this._previewAudio && source && source.kind === 'chapter' && this._previewAudio.src === url) {
      if (this.data.audioPreviewPlaying) {
        this._previewAudio.pause();
      } else {
        this._previewAudio.play();
      }
      return;
    }
    this.destroyPreviewAudio();
    var audio = wx.createInnerAudioContext();
    audio.src = url;
    audio.obeyMuteSwitch = false;
    this._previewSource = { kind: 'chapter' };
    this._previewAudio = audio;
    // 回调先认「还是当前这个播放器」:被换掉/销毁后迟到的回调必须弃权。
    audio.onPlay(function () { if (that._previewAudio !== audio) return; that.setData({ audioPreviewPlaying: true }); });
    audio.onPause(function () { if (that._previewAudio !== audio) return; that.setData({ audioPreviewPlaying: false }); });
    audio.onStop(function () { if (that._previewAudio !== audio) return; that.setData({ audioPreviewPlaying: false }); });
    audio.onEnded(function () { if (that._previewAudio !== audio) return; that.setData({ audioPreviewPlaying: false }); });
    audio.onError(function (err) {
      if (that._previewAudio !== audio) return;
      that.setData({ audioPreviewPlaying: false });
      cyToast(app.getRequestErrorMessage(err, '预听失败'));
    });
    audio.play();
  },

  // 故事流音频块预听:与章节旁白共用同一个 InnerAudioContext(_previewAudio),
  // 所以两者天然互斥 —— 谁后起播谁把对方顶掉,不会两段一起响。
  // ⚠️ 空 url 的块不建播放器:那是还没选文件的占位,点它走上传(uploadStoryAudio),不是预听。
  onPreviewStoryAudio(e) {
    const blockKey = e.currentTarget.dataset.blockkey;
    const chapterIndex = this.data.storyEditor.chapterIndex;
    const chapter = (this.data.formData.chapters || [])[chapterIndex];
    const block = ((chapter && chapter.blocks) || []).filter((b) => b && b.type === 'audio' && b.key === blockKey)[0];
    const url = block && block.url;
    if (!url) return;
    // 同一块再点 = 暂停/继续,不另起播放器。按来源身份判,不只比 URL。
    const source = this._previewSource;
    if (this._previewAudio && source && source.kind === 'story' && source.key === blockKey && this._previewAudio.src === url) {
      if (this.data.storyAudioPlaying) this._previewAudio.pause();
      else this._previewAudio.play();
      return;
    }
    this.destroyPreviewAudio();
    const that = this;
    const audio = wx.createInnerAudioContext();
    audio.src = url;
    audio.obeyMuteSwitch = false;
    // ★ 有效来源身份在**建播放器时**登记(不等 onPlay):网络缓冲期删这块时
    //   removeStoryMedia 要能匹配到并 destroy,否则缓冲完还会响(F14 P2)。
    this._previewSource = { kind: 'story', key: blockKey };
    this._previewAudio = audio;
    audio.onPlay(function () { if (that._previewAudio !== audio) return; that.setData({ storyAudioPlaying: true, storyAudioKey: blockKey }); });
    audio.onPause(function () { if (that._previewAudio !== audio) return; that.setData({ storyAudioPlaying: false }); });
    audio.onStop(function () { if (that._previewAudio !== audio) return; that.setData({ storyAudioPlaying: false }); });
    audio.onEnded(function () { if (that._previewAudio !== audio) return; that.setData({ storyAudioPlaying: false, storyAudioKey: '' }); });
    audio.onError(function (err) {
      if (that._previewAudio !== audio) return;
      that.setData({ storyAudioPlaying: false, storyAudioKey: '' });
      cyToast(app.getRequestErrorMessage(err, '预听失败'));
    });
    audio.play();
  },

  destroyPreviewAudio() {
    if (this._previewAudio) {
      try { this._previewAudio.destroy(); } catch (e) {}
      this._previewAudio = null;
    }
    this._previewSource = null;
    if (this.data.audioPreviewPlaying || this.data.storyAudioPlaying || this.data.storyAudioKey) {
      this.setData({ audioPreviewPlaying: false, storyAudioPlaying: false, storyAudioKey: '' });
    }
  },

  // formData.imgArr(CSV)与 imgArrList(数组)的唯一写入口,别绕过它单独 setData 其中一个
  _setImgArr(list) {
    const arr = (list || []).filter(Boolean).slice(0, IMG_ARR_MAX);
    this.setData({ 'formData.imgArr': arr.join(','), imgArrList: arr });
  },

  // 读 CSV。逗号是本字段约定,但首页 feed 的 decorateImgs 历史上按 ';' 拆,
  // 故两种分隔符都吃,免得存量/别处写进来的串在这里被当成单个坏 URL
  _parseImgArr(csv) {
    return String(csv || '').split(/[,;]/).map(s => s.trim()).filter(Boolean);
  },

  // 上传横版封面(step3「横版封面」画廊):多张 16:9,消费面是 topic/index 顶部轮播
  uploadImgArr() {
    const remain = IMG_ARR_MAX - this.data.imgArrList.length;
    if (remain <= 0) { cyToast('最多' + IMG_ARR_MAX + '张'); return; }
    const current = this._coverUploadGuard('imgArr');
    app.chooseImage((res) => {
      if (current() && Array.isArray(res)) {
        this._setImgArr(this.data.imgArrList.concat(res.filter(url => typeof url === 'string' && url.trim())));
      }
    }, remain, { crop: true, cropScale: '16:9' });
  },

  removeImgArr(e) {
    const raw = e && e.currentTarget && e.currentTarget.dataset.i;
    const i = Number(raw);
    if (raw == null || raw === '' || !Number.isInteger(i) || i < 0 || i >= this.data.imgArrList.length) return;
    this._invalidateCoverUpload('imgArr');
    const arr = this.data.imgArrList.slice();
    arr.splice(i, 1);
    this._setImgArr(arr);
  },

  // 上传横版图片
  uploadHorizontalImage() {
    let that = this;
    app.chooseImage(function (res) {
      that.setData({
        horizontalImage: res[0]
      });
    }, 1, { crop: true, cropScale: '16:9' });
  },

  getUserData: function () {
    let that = this;
    const memberId = String(app.getUserID() || '');
    if (app.getUserID() && !that._localDraftRestored) {
      that.setData({
        'formData.collaboratorList': [{
          name: app.getNickname(),
          nickname: app.getNickname(),
          avatar: app.getAvatar(),
          id: app.getUserID(),
          isOwner: 1
        }],
        'formData.collaboratorIds': [app.getUserID()]
      });
    }
    app.sendRequest({
      hideLoading: true,
      url: '/api/user/info',
      data: {
        member_id: app.getUserID()
      },
      method: "POST",
      success: function (res) {
        if (that._unloaded || memberId !== String(app.getUserID() || '')) return;
        if (res && res.code == "200" && res.data && typeof res.data === 'object' && !Array.isArray(res.data)) {
          let userData = {
            name: res.data.nickname,
            nickname: res.data.nickname,
            avatar: res.data.avatar,
            id: res.data.id,
            isOwner: 1
          }

          let collaboratorList = [];
          let collaboratorIds = [];

          collaboratorList = [...collaboratorList, userData];
          collaboratorIds = [...collaboratorIds, userData.id];

          const patch = {
            draftMemberId: that.data.draftMemberId || userData.id
          };
          // 完整本地信封里可能有多位合作者；恢复后这里只刷新当前用户展示信息，
          // 不能把已恢复列表重置成 owner 一人并立刻覆盖保存。
          if (!that._localDraftRestored && !that.data.editingTopicId) {
            patch['formData.collaboratorList'] = collaboratorList;
            patch['formData.collaboratorIds'] = collaboratorIds;
          }
          that.setData(patch, () => {
            // 编辑既有主题时必须等 edit-detail 回填完并打开 autosave 闸，
            // 否则用户资料请求先返回会把空表单写成该 topic 的本地草稿。
            if (that._draftAutosaveReady) that._persistDraftEnvelope();
          })
          cyLoading.hide();
        }
      },
      fail: function (res) {
      }
    })
  },
  // 显示分类选择弹窗
  showCategoryModal() {
    this.setData({
      showCategoryModal: true
    });
  },

  // 隐藏分类选择弹窗
  hideCategoryModal() {
    this.setData({
      showCategoryModal: false
    });
  },
  // 切换分类选择
  toggleCategorySelect(e) {
    let categoryId = e.currentTarget.dataset.id;
    let categoryName = e.currentTarget.dataset.name;
    let selectedIds = [...this.data.selectedCategoryIds];
    let selectedNames = [...this.data.selectedCategoryNames];

    let index = selectedIds.indexOf(categoryId);
    if (index > -1) {
      selectedIds.splice(index, 1);
      selectedNames.splice(index, 1);
    } else {
      selectedIds.push(categoryId);
      selectedNames.push(categoryName);
    }

    this.setData({
      selectedCategoryIds: selectedIds,
      selectedCategoryNames: selectedNames
    });
  },

  // 确认分类选择
  confirmCategorySelect() {
    this.setData({
      showCategoryModal: false,
      'formData.categoryIds': this.data.selectedCategoryIds
    });
  },

  // 移除分类
  removeCategory(e) {
    let index = e.currentTarget.dataset.index;
    let selectedIds = [...this.data.selectedCategoryIds];
    let selectedNames = [...this.data.selectedCategoryNames];

    selectedIds.splice(index, 1);
    selectedNames.splice(index, 1);

    this.setData({
      selectedCategoryIds: selectedIds,
      selectedCategoryNames: selectedNames,
      'formData.categoryIds': selectedIds
    });
  },

  // 添加票单
  // 打开票种编辑器（index=-1 表示新建）
  openTicketEditor(e) {
    const index = Number(e.currentTarget.dataset.index);
    if (index >= 0) {
      const ticket = this.data.formData.tickets[index];
      this.setData({
        showTicketEditor: true,
        editingTicket: {
          editIndex: index,
          id: ticket.id,
          name: ticket.name || '',
          price: ticket.price || 0,
          totalStock: ticket.totalStock == null || ticket.totalStock === '' ? 100 : ticket.totalStock,
          mode: this.data.formData.productType || 1,
          meetingPoint: ticket.meetingPoint || '',
          meetingPointAddress: ticket.meetingPointAddress || '',
          meetingPointLongitude: ticketCoordinate(ticket, 'meetingPointLongitude', 'gatherLng'),
          meetingPointLatitude: ticketCoordinate(ticket, 'meetingPointLatitude', 'gatherLat'),
          teamSize: ticket.teamSize || 0,
          startTime: ticket.startTime || '',
          endTime: ticket.endTime || '',
          saleStartTime: ticket.saleStartTime || '',
          saleEndTime: ticket.saleEndTime || '',
          description: ticket.description || '',
          refundSupported: ticket.refundSupported !== false,
          syncWithTheme: ticket.syncWithTheme || false
        }
      }, () => this.refreshTicketSaveState());
    } else {
      this.setData({
        showTicketEditor: true,
        editingTicket: {
          editIndex: -1,
          name: '',
          price: 0,
          totalStock: 100,
          // [P0-02] 新票跟主题走,不硬编码 1 —— 这是活的混票源:从自由定向入口进来再加一张票,
          // saveTicket 会把这张 mode=1 的票推进列表 ⇒ 混票主题当场产生。
          mode: this.data.formData.productType || 1,
          meetingPoint: '',
          meetingPointAddress: '',
          meetingPointLongitude: '',
          meetingPointLatitude: '',
          teamSize: 0,
          startTime: '',
          endTime: '',
          saleStartTime: '',
          saleEndTime: '',
          description: '',
          refundSupported: true,
          syncWithTheme: false
        }
      }, () => this.refreshTicketSaveState());
    }
  },

  closeTicketEditor() {
    this.setData({ showTicketEditor: false, canSaveTicket: false });
  },

  refreshTicketSaveState() {
    const canSaveTicket = !this._ticketBlockReason(this.data.editingTicket);
    if (canSaveTicket !== this.data.canSaveTicket) this.setData({ canSaveTicket });
  },

  // 一张票填没填全的唯一判据(保存票种的可用态、发布确认页的「已配置 N 种票单」都用它)。
  // CU-C-168:之前「已配置」只数数组长度,一张集合时间/地点全空的默认票既能以缺项身份
  // 出现在「还差什么」里,又能同时被算进「自动检查已通过」—— 同一张空票既红又绿。
  _ticketBlockReason(ticket) {
    const et = ticket || {};
    if (!(et.name || '').trim()) return '请填写票种名称';
    const issue = cityOrientationScheduleIssues(et)[0]
      || freeExplorationValidityScheduleIssues(et)[0] || freeExplorationSaleScheduleIssues(et)[0];
    if (issue) return issue.message;
    if (Number(et.mode) === 1 && !nonEmpty(et.meetingPoint)) return '请选择集合地点';
    return '';
  },

  // 票种保存的第一条拦截原因(与 refreshTicketSaveState 的可用态同一套判据)。
  _ticketSaveBlockReason() {
    return this._ticketBlockReason(this.data.editingTicket);
  },

  // 置灰的「保存票种」被点 → 报同一条原因,别静默按不动。
  onSaveTicketDisabledTap() {
    const reason = this._ticketSaveBlockReason();
    if (reason) cyToast(reason);
  },

  // 保存票种（新增 or 覆盖）
  saveTicket() {
    if (!this.data.canSaveTicket) return;
    const et = this.data.editingTicket;
    const blocked = this._ticketSaveBlockReason();
    if (blocked) {
      cyToast(blocked);
      return;
    }
    const stock = et.totalStock == null || et.totalStock === '' ? NaN : Number(et.totalStock);
    const ticketData = {
      name: et.name,
      price: Number(et.price) || 0,
      totalStock: Number.isFinite(stock) ? stock : 100,
      mode: this.data.formData.productType || 1,
      meetingPoint: et.meetingPoint,
      meetingPointAddress: et.meetingPointAddress,
      meetingPointLongitude: et.meetingPointLongitude,
      meetingPointLatitude: et.meetingPointLatitude,
      // 集合点经纬度落库:后端 OmsTicket 只有 gather_lng/gather_lat(无 meeting_point_longitude 列),
      // 故 meetingPointLongitude/Latitude 发出去会丢;这里以数字形式补 gatherLng/gatherLat(空则 null)。
      gatherLng: et.meetingPointLongitude !== '' && et.meetingPointLongitude != null ? Number(et.meetingPointLongitude) : null,
      gatherLat: et.meetingPointLatitude !== '' && et.meetingPointLatitude != null ? Number(et.meetingPointLatitude) : null,
      // 成团已下线(2026-07-15):不再派生 minPeople / signupDeadline / refundRule。
      // teamSize 保留(恒 0)以免连锁改动 editingTicket 的多处初始化;列保留不物理删。
      teamSize: Number(et.teamSize) || 0,
      startTime: et.syncWithTheme ? this.data.formData.startDate : et.startTime,
      endTime: et.syncWithTheme ? this.data.formData.endDate : et.endTime,
      saleStartTime: et.saleStartTime,
      saleEndTime: et.saleEndTime,
      description: et.description,
      refundSupported: et.refundSupported,
      syncWithTheme: et.syncWithTheme
    };
    const savedTicketId = Number(et.id);
    if (Number.isSafeInteger(savedTicketId) && savedTicketId > 0) ticketData.id = savedTicketId;
    let tickets = [...this.data.formData.tickets];
    if (et.editIndex >= 0) {
      tickets[et.editIndex] = ticketData;
    } else {
      tickets.push(ticketData);
    }
    this.setData({ 'formData.tickets': tickets, showTicketEditor: false }, () => {
      this.refreshRouteGraph();
      this.refreshPrimaryActionState();
    });
  },

  // 删除票种
  deleteTicket(e) {
    const index = e.currentTarget.dataset.index;
    let tickets = [...this.data.formData.tickets];
    tickets.splice(index, 1);
    this.setData({ 'formData.tickets': tickets }, () => {
      this.refreshRouteGraph();
      this.refreshPrimaryActionState();
    });
  },

  // 编辑器内字段变更
  onEditTicketChange(e) {
    const field = e.currentTarget.dataset.field;
    let value = e.detail.value;
    if (field === 'price') {
      const sanitized = value.replace(/[^\d.]/g, '');
      // 只拦「多于一个小数点」;保留 sanitized 字符串,别在输入中途 parseFloat→String
      // (受控输入下那样会把刚敲的小数点抹掉,导致小数价格永远输不进去)。数值化(saveTicket 里 Number())留到提交时做。
      if ((sanitized.match(/\./g) || []).length > 1) {
        cyToast('价格格式错误');
        return;
      }
      value = sanitized;
    } else if (field === 'totalStock' || field === 'teamSize') {
      value = Number(value) || 0;
    }
    this.setData({ [`editingTicket.${field}`]: value }, () => this.refreshTicketSaveState());
  },

  // 编辑器内模式切换
  onEditTicketModeChange(e) {
    const mode = Number(e.currentTarget.dataset.mode);
    this.setData({
      'editingTicket.mode': mode,
      'editingTicket.startTime': '',
      'editingTicket.endTime': ''
    }, () => this.refreshTicketSaveState());
  },

  // 退款 toggle

  // 与路线日期同步 toggle
  onEditTicketSyncToggle() {
    const sync = !this.data.editingTicket.syncWithTheme;
    const update = { 'editingTicket.syncWithTheme': sync };
    if (sync) {
      update['editingTicket.startTime'] = this.data.formData.startDate || '';
      update['editingTicket.endTime'] = this.data.formData.endDate || '';
    }
    this.setData(update, () => this.refreshTicketSaveState());
  },

  // 打开票种时间选择弹窗
  showTicketTimePicker(e) {
    const { field, mode } = e.currentTarget.dataset;
    const currentVal = this.data.editingTicket[field] || '';
    const { dateList, hours, minutes } = this.data;
    let dateIdx = 0;
    let hourIdx = 9;
    let minuteIdx = 0;

    if (currentVal) {
      const m = currentVal.match(/^(\d{4}-\d{2}-\d{2})(?:\s(\d{1,2}):(\d{1,2}))?/);
      if (m) {
        const dIdx = dateList.findIndex(d => d.date === m[1]);
        if (dIdx >= 0) dateIdx = dIdx;
        if (m[2]) hourIdx = hours.indexOf(Number(m[2]));
        if (m[3]) minuteIdx = minutes.indexOf(Number(m[3]));
        if (hourIdx < 0) hourIdx = 9;
        if (minuteIdx < 0) minuteIdx = 0;
      }
    }

    const titleMap = {
      saleStartTime: '选择售票开始日期',
      saleEndTime: '选择售票结束日期',
      startTime: '选择开始时间',
      endTime: '选择结束时间'
    };

    this.setData({
      ticketTimePicker: {
        show: true,
        field,
        mode: mode || 'date',
        title: titleMap[field] || '选择时间'
      },
      ticketDateIndex: [dateIdx],
      ticketTimeIndex: [hourIdx, minuteIdx]
    });
  },

  // 关闭票种时间选择弹窗
  closeTicketTimePicker() {
    this.setData({ 'ticketTimePicker.show': false });
  },

  // 日期列变化
  onTicketPickerDateChange(e) {
    this.setData({ ticketDateIndex: e.detail.value });
  },

  // 时分列变化
  onTicketPickerTimeChange(e) {
    this.setData({ ticketTimeIndex: e.detail.value });
  },

  // 确认票种时间
  confirmTicketTime() {
    const { ticketTimePicker, ticketDateIndex, ticketTimeIndex, dateList, hours, minutes } = this.data;
    const selectedDate = dateList[ticketDateIndex[0]];
    if (!selectedDate) {
      this.setData({ 'ticketTimePicker.show': false });
      return;
    }
    let val = selectedDate.date;
    if (ticketTimePicker.mode === 'datetime') {
      const hour = hours[ticketTimeIndex[0]];
      const minute = minutes[ticketTimeIndex[1]];
      const hourStr = hour.toString().padStart(2, '0');
      const minuteStr = minute.toString().padStart(2, '0');
      val = `${val} ${hourStr}:${minuteStr}`;
    }
    this.setData({
      [`editingTicket.${ticketTimePicker.field}`]: val,
      'ticketTimePicker.show': false
    }, () => this.refreshTicketSaveState());
  },

  // 选择集合地点（腾讯地图）
  chooseTicketMeetingPoint() {
    const that = this;
    pickLocation({
      onPick(poi) {
        that.setData({
          'editingTicket.meetingPoint': poi.name || '',
          'editingTicket.meetingPointAddress': poi.address || '',
          'editingTicket.meetingPointLongitude': poi.longitude || '',
          'editingTicket.meetingPointLatitude': poi.latitude || ''
        }, () => that.refreshTicketSaveState());
      }
    });
  },

  addTicket() {
    let tickets = [...this.data.formData.tickets];

    tickets.push({
      name: `票价${tickets.length + 1}`,
      price: 0,
      totalStock: 100,
      mode: this.data.formData.productType || 1,
      meetingPoint: '',
      teamSize: 0,
      startTime: '',
      endTime: '',
      description: ''
    });

    this.setData({
      'formData.tickets': tickets
    }, () => this.refreshPrimaryActionState());
  },

  // 删除票务项
  removeTicket(e) {
    const index = e.currentTarget.dataset.index;
    if (index === 0) {
      cyToast('至少保留一个票价');
      return;
    }

    let tickets = [...this.data.formData.tickets];
    tickets.splice(index, 1);

    this.setData({
      'formData.tickets': tickets
    }, () => this.refreshPrimaryActionState());
  },

  // 票务日期选择
  bindTicketDateChange(e) {
    const {
      field,
      index
    } = e.currentTarget.dataset;
    const value = e.detail.value;

    this.setData({
      [`formData.tickets[${index}].${field}`]: value
    });
  },

  // 修改参与人数（加减按钮）
  changeParticipantCount(e) {
    const {
      type,
      index
    } = e.currentTarget.dataset;
    let tickets = [...this.data.formData.tickets];
    let currentStock = tickets[index].totalStock || 0;

    if (type === 'add') {
      currentStock += 1;
    } else if (type === 'subtract' && currentStock > 0) {
      currentStock -= 1;
    }

    tickets[index].totalStock = currentStock;

    this.setData({
      'formData.tickets': tickets
    });
  },

  // 同步发布到创意广场开关
  onPublishToCreativeChange(e) {
    this.setData({
      publishToCreative: e.detail.value
    });
  },

  // P0.5 勋章。途中彩蛋整条链路已于 2026-09-07 下线(前后端一起拆,见 feat/decommission-route-eggs-0907)。
  onFinishMedalNameInput(e) {
    this.setData({ 'formData.finishMedalName': e.detail.value });
  },
  /**
   * 通关勋章图。这条链路后端与玩家端早就通了 —— cms_topic.finish_medal_img 建了列、
   * TopicCreateDTO 收得下、play/index.wxml 的 pp-reward__img 也画了 ——
   * 唯独创作端没有入口,于是线上主题的通关勋章一律无图。这里把入口补上。
   * 形态与玩法模板页的勋章上传保持一致:1:1 裁剪、单张、可清除。
   */
  uploadFinishMedal() {
    const that = this;
    app.chooseImage(function (res) {
      that.setData({ 'formData.finishMedalImg': res[0] });
    }, 1, { crop: true, cropScale: '1:1' });
  },
  clearFinishMedal() {
    this.setData({ 'formData.finishMedalImg': '' });
  },

  onCompletionRewardChange(e) {
    const detail = e.detail || {};
    const couponId = Number(detail.couponId || 0);
    this.setData({
      'formData.completeRewardCouponId': couponId,
      completionRewardCouponName: detail.couponName || '',

    });
  },
  onCompletionRuleToggle(e) {
    this.setData({
      completionRuleMode: e.detail.value ? 'AT_LEAST' : 'ALL',
      completionRuleError: '',
      'errors.completionRule': ''
    }, () => this.refreshPrimaryActionState());
  },
  onCompletionRequiredCountInput(e) {
    this.setData({ completionRequiredCount: Number(e.detail.value), completionRuleError: '', 'errors.completionRule': '' },
      () => this.refreshPrimaryActionState());
  },
  onRecruitDeadlineChange(e) {
    this.setData({ 'formData.recruitDeadline': e.detail.value || '' }, () => this.refreshPrimaryActionState());
  },

  // M2 自玩票(仅经典定向):开关 + 价格 + 票数
  onSelfPlayToggle(e) {
    this.setData({ 'formData.selfPlay': !!e.detail.value }, () => this.syncIdentityReady());
  },
  onSelfPlayPriceInput(e) {
    this.setData({ 'formData.selfPlayPrice': e.detail.value }, () => this.syncIdentityReady());
  },
  onSelfPlayQuotaInput(e) {
    this.setData({ 'formData.selfPlayQuota': e.detail.value });
  },

  // ===== 主题级分支路线编辑器 =====
  _routeOutcomeContract(node) {
    const templateInfo = node.templateInfo && typeof node.templateInfo === 'object' ? node.templateInfo : null;
    const source = templateInfo || node;
    const hasContractSnapshot = Number(node.templateId || 0) <= 0
      || source.validationMethod !== undefined
      || source.preferenceJson !== undefined
      || source.advancedConfigJson !== undefined;
    return hasContractSnapshot
      ? nodeOutcomeContract.extract(source)
      : { items: [], errors: [] };
  },

  _routeNodes() {
    return topicRouteGraph.flattenNodes((this.data.formData && this.data.formData.chapters) || []).map((node) => {
      const contract = this._routeOutcomeContract(node);
      return Object.assign({}, node, { outcomes: contract.items, outcomeErrors: contract.errors });
    });
  },

  _sequentialRouteGraph(nodes) {
    const list = nodes || this._routeNodes();
    const graph = topicRouteGraph.emptyGraph();
    if (!list.length) return graph;
    graph.startNodeId = list[0].id;
    graph.terminalNodeIds = [list[list.length - 1].id];
    graph.edges = list.slice(0, -1).map((node, index) => ({
      id: 'route_' + (index + 1), fromNodeId: node.id,
      trigger: { type: 'CHOICE', outcomeCode: 'COMPLETED' },
      conditions: [], effects: [], toNodeId: list[index + 1].id,
      priority: 0, weight: 1, once: true, maxVisits: 1, allowLoop: false
    }));
    return graph;
  },

  _nextStoryNodeId(sourceId, nodes) {
    const list = nodes || this._routeNodes();
    const index = list.findIndex((node) => node.id === String(sourceId || ''));
    return index >= 0 && index < list.length - 1 ? list[index + 1].id : '';
  },

  _realignStoryRouteGraph(previousNodes) {
    if (this.data.formData.routeMode !== 'BRANCH_GRAPH') return;
    const nextNodes = this._routeNodes();
    const graph = topicRouteGraph.normalizeGraph(this._routeGraph).graph;
    (previousNodes || []).forEach((source) => {
      const oldNextNodeId = this._nextStoryNodeId(source.id, previousNodes);
      const newNextNodeId = this._nextStoryNodeId(source.id, nextNodes);
      if (!oldNextNodeId || oldNextNodeId === newNextNodeId) return;
      const fallback = (graph.fallbacks || []).find((item) => item.fromNodeId === source.id);
      const followsStoryFlow = fallback && fallback.toNodeId === oldNextNodeId;
      if (followsStoryFlow) {
        if (newNextNodeId) fallback.toNodeId = newNextNodeId;
        else graph.fallbacks = graph.fallbacks.filter((item) => item !== fallback);
      }
      graph.edges = (graph.edges || []).filter((edge) => {
        const isStoryEdge = edge.fromNodeId === source.id
          && ((followsStoryFlow && edge.toNodeId === oldNextNodeId)
            || String(edge.id || '').indexOf('route_') === 0);
        if (!isStoryEdge) return true;
        if (!newNextNodeId) return false;
        edge.toNodeId = newNextNodeId;
        return true;
      });
    });
    graph.startNodeId = nextNodes.length ? nextNodes[0].id : '';
    this._routeGraph = graph;
  },

  _pruneRouteGraphToCurrentNodes() {
    if (this.data.formData.routeMode !== 'BRANCH_GRAPH') return;
    const nodes = this._routeNodes();
    const validIds = new Set(nodes.map((node) => node.id));
    const graph = topicRouteGraph.normalizeGraph(this._routeGraph).graph;
    graph.edges = (graph.edges || []).filter((edge) => {
      const endpointsRemain = validIds.has(edge.fromNodeId) && validIds.has(edge.toNodeId);
      const conditionsRemain = (edge.conditions || []).every((condition) => {
        if (condition.op !== 'NODE_COMPLETED') return true;
        return validIds.has(String(condition.nodeId || condition.nodeKey || ''));
      });
      return endpointsRemain && conditionsRemain;
    });
    graph.fallbacks = (graph.fallbacks || []).filter((item) => {
      return validIds.has(item.fromNodeId) && validIds.has(item.toNodeId);
    });
    graph.fallbacks.forEach((fallback, index) => {
      if (graph.edges.some((edge) => edge.fromNodeId === fallback.fromNodeId)) return;
      const source = nodes.find((node) => node.id === fallback.fromNodeId);
      const outcome = source && (source.outcomes || [])[0];
      graph.edges.push({
        id: 'route_repair_' + fallback.fromNodeId + '_' + (index + 1),
        fromNodeId: fallback.fromNodeId,
        trigger: {
          type: outcome ? outcome.triggerType : 'CHOICE',
          outcomeCode: outcome ? outcome.code : 'COMPLETED',
        },
        conditions: [], effects: [], toNodeId: fallback.toNodeId,
        priority: 0, weight: 1, once: true, maxVisits: 1, allowLoop: false,
      });
    });
    graph.nodeRequirements = (graph.nodeRequirements || [])
      .filter((item) => validIds.has(String(item.nodeId || '')));
    if (!validIds.has(graph.startNodeId)) graph.startNodeId = nodes.length ? nodes[0].id : '';
    const outgoingIds = new Set(graph.edges.map((edge) => edge.fromNodeId)
      .concat(graph.fallbacks.map((item) => item.fromNodeId)));
    graph.terminalNodeIds = nodes
      .filter((node) => !outgoingIds.has(node.id))
      .map((node) => node.id);
    this._routeGraph = graph;
    this.refreshRouteGraph();
  },

  _routeMappingSummary(nodes, graph) {
    const summary = {};
    nodes.forEach((node) => {
      const nextNodeId = this._nextStoryNodeId(node.id, nodes);
      const matching = (graph.edges || []).filter((edge) => edge.fromNodeId === node.id
        && (node.outcomes || []).some((outcome) => outcome.triggerType === edge.trigger.type
          && outcome.code === edge.trigger.outcomeCode));
      const hasChoice = (node.outcomes || []).length > 1;
      const hasChangedTarget = matching.some((edge) => edge.toNodeId !== nextNodeId);
      if (matching.length && (hasChoice || hasChangedTarget)) {
        const label = '下一站 ' + matching.length + ' 条';
        summary[node.id] = label;
        if (node.clientNodeKey) summary[node.clientNodeKey] = label;
      }
    });
    return summary;
  },

  _routeSavedTicketOptions() {
    return (this.data.formData.tickets || []).map((ticket) => ({
      id: Number(ticket.id), label: ticket.name || ticket.ticketName || ('票种 #' + ticket.id)
    })).filter((ticket) => Number.isSafeInteger(ticket.id) && ticket.id > 0);
  },

  _ensureRouteFallbacks(graph, nodes) {
    const fallbackSources = new Set((graph.fallbacks || []).map((item) => String(item.fromNodeId)));
    const edgesBySource = new Map();
    (graph.edges || []).forEach((edge) => {
      const sourceId = String(edge.fromNodeId || '');
      if (!sourceId) return;
      if (!edgesBySource.has(sourceId)) edgesBySource.set(sourceId, []);
      edgesBySource.get(sourceId).push(edge);
    });
    edgesBySource.forEach((edges, sourceId) => {
      const source = (nodes || []).find((node) => node.id === sourceId);
      const hasUnmatchedOutcome = !!source && (source.outcomes || []).some((outcome) => {
        return !edges.some((edge) => edge.trigger.type === outcome.triggerType
          && edge.trigger.outcomeCode === outcome.code);
      });
      const hasConditionalEdge = edges.some((edge) => (edge.conditions || []).length);
      if (fallbackSources.has(sourceId)
        || (edges.length < 2 && !hasUnmatchedOutcome && !hasConditionalEdge)) return;
      const storyNextNodeId = this._nextStoryNodeId(sourceId, nodes);
      if (storyNextNodeId) graph.fallbacks.push({ fromNodeId: sourceId, toNodeId: storyNextNodeId });
    });
    return graph;
  },

  refreshRouteGraph() {
    const nodes = this._routeNodes();
    const graph = this._ensureRouteFallbacks(topicRouteGraph.normalizeGraph(this._routeGraph).graph, nodes);
    const ticketOptions = this._routeSavedTicketOptions();
    const preview = topicRouteGraph.buildPreview(graph, nodes, {}, {
      allowedTicketIds: ticketOptions.map((ticket) => ticket.id)
    });
    this._routeGraph = graph;
    this.setData({
      routeNodeMappingSummary: this._routeMappingSummary(nodes, graph),
      'formData.routeGraphJson': this.data.formData.routeMode === 'BRANCH_GRAPH'
        ? topicRouteGraph.serialize(graph) : this.data.formData.routeGraphJson,
    });
    return preview;
  },

  openNodeRouteMapping(e) {
    if (Number(this.data.formData.productType) === 2) return;
    const dataset = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const requestedId = String(dataset.nodeid || this.data.editTargetNodeLid || '');
    const nodes = this._routeNodes();
    let source = nodes.find((node) => node.id === requestedId || node.clientNodeKey === requestedId);
    if (!source) {
      cyToast('请先保存节点');
      return;
    }
    const returnToNodeEditor = !!this.data.popChapterNodes;
    const editingSource = returnToNodeEditor
      && (source.id === String(this.data.editTargetNodeLid || '')
        || source.clientNodeKey === String(this.data.editTargetNodeLid || ''));
    if (editingSource) {
      const liveNode = Object.assign({}, source, this.data.nodesForm || {}, {
        id: source.id,
        clientNodeKey: source.clientNodeKey,
        label: (this.data.nodesForm && this.data.nodesForm.name) || source.label,
      });
      const liveContract = this._routeOutcomeContract(liveNode);
      source = Object.assign(liveNode, {
        outcomes: liveContract.items,
        outcomeErrors: liveContract.errors,
      });
      if (!this._nodeRouteGraphBeforeEdit) {
        this._nodeRouteGraphBeforeEdit = {
          routeMode: this.data.formData.routeMode,
          routeGraphJson: this.data.formData.routeGraphJson,
          routeGraph: JSON.parse(JSON.stringify(this._routeGraph)),
        };
      }
    }
    if (!(source.outcomes || []).length) {
      cyToast('请先重新选择玩法模板');
      return;
    }
    const sourceId = source.id;
    const graph = topicRouteGraph.normalizeGraph(this._routeGraph).graph;
    const nextNodeId = this._nextStoryNodeId(sourceId, nodes);
    const targetOptions = [{ id: '', label: '继续故事流' }].concat(nodes
      .filter((node) => node.id !== sourceId)
      .map((node) => ({ id: node.id, label: node.label })));
    const rows = (source.outcomes || []).reduce((all, outcome) => {
      const matches = (graph.edges || []).filter((item) => item.fromNodeId === sourceId
        && item.trigger.type === outcome.triggerType
        && item.trigger.outcomeCode === outcome.code);
      (matches.length ? matches : [null]).forEach((edge, branchIndex) => {
        // 具体玩法结果一旦已有 edge，就显示真实地点；只有未建立 edge 的结果才显示
        // “继续故事流”。否则目标恰好等于相邻节点时会被伪装成未配置，重进后看不出对应。
        const selectedId = edge ? edge.toNodeId : '';
        const selected = targetOptions.find((item) => item.id === selectedId) || targetOptions[0];
        all.push({
          mappingKey: edge ? 'edge:' + edge.id : 'outcome:' + outcome.triggerType + ':' + outcome.code,
          edgeId: edge ? edge.id : '',
          ruleConfig: JSON.parse(JSON.stringify({ conditions: edge ? edge.conditions : [], effects: edge ? edge.effects : [] })),
          code: outcome.code,
          triggerType: outcome.triggerType,
          label: outcome.label + (matches.length > 1 ? ' · 分支 ' + (branchIndex + 1) : ''),
          toNodeId: selected.id,
          targetLabel: selected.label,
          targetOptions,
        });
      });
      return all;
    }, []);
    this._routeRuleDrafts = Object.assign(Object.create(null), this._routeRuleDrafts || {});
    const pendingRules = this._routeRuleDrafts[sourceId] || {};
    this._routeRuleEpoch = (this._routeRuleEpoch || 0) + 1;
    rows.forEach(row => {
      row.formIdentity = sourceId + ':' + this._routeRuleEpoch + ':' + row.mappingKey;
      row.ruleBaseline = JSON.parse(JSON.stringify(row.ruleConfig));
      if (pendingRules[row.mappingKey]) row.ruleConfig = JSON.parse(JSON.stringify(pendingRules[row.mappingKey]));
    });
    this._routeRulesOwner = String(app.getUserID() || '');
    this._routeRulesScope = this.data.operationScope;
    this._routeMappingSourceNodeId = sourceId;
    this._routeMappingReturnToNodeEditor = returnToNodeEditor;
    this.setData({
      routeMappingShow: true,
      routeMappingSourceLabel: source.label,
      routeMappingTemplateLabel: (source.templateInfo && source.templateInfo.title)
        || source.templateName || '节点完成结果',
      routeMappingRows: rows,
      routeRuleNodes: nodes.map(node => ({ id: node.id, label: node.label })),
      routeRuleVariables: this._routeRuleState(graph.variables || {}, nodes), // 文档二 L3：名单与展开状态在 theme-route-names.js
      routeMappingFallbackLabel: nextNodeId ? '继续故事流' : '故事流结束',
      popChapterNodes: false,
    });
  },

  onRouteRulesChange(e) {
    if (!this.data.routeMappingShow || this._unloaded || this._hidden
        || this._routeRulesOwner !== String(app.getUserID() || '')
        || this._routeRulesScope !== this.data.operationScope) return;
    const detail = e.detail || {};
    const rows = (this.data.routeMappingRows || []).map(row => Object.assign({}, row));
    const row = rows.find(row => row.formIdentity === detail.identity);
    if (!row || !detail.value || !Array.isArray(detail.value.conditions) || !Array.isArray(detail.value.effects)) return;
    row.ruleConfig = JSON.parse(JSON.stringify(detail.value));
    this._routeRuleDrafts = Object.assign(Object.create(null), this._routeRuleDrafts || {});
    const draft = this._routeRuleDrafts[this._routeMappingSourceNodeId] = this._routeRuleDrafts[this._routeMappingSourceNodeId] || {};
    if (detail.discard) {
      delete draft[row.mappingKey];
      if (!Object.keys(draft).length) delete this._routeRuleDrafts[this._routeMappingSourceNodeId];
    } else draft[row.mappingKey] = row.ruleConfig;
    this.setData({ routeMappingRows: rows });
    this._setSaveState(this._persistDraftEnvelope() ? 'local' : 'localFailed');
  },

  onRouteMappingTargetPick(e) {
    const rowIndex = Number(e.currentTarget.dataset.rowindex);
    const rows = (this.data.routeMappingRows || []).map((row) => Object.assign({}, row));
    const row = rows[rowIndex];
    const selected = row && row.targetOptions[Number(e.currentTarget.dataset.optionindex ?? (e.detail || {}).value)]; // 就地勾选传 optionindex
    if (!selected) return;
    row.toNodeId = selected.id;
    row.targetLabel = selected.label;
    this.setData({ routeMappingRows: rows, routeMappingDestOpen: false });
  },

  closeNodeRouteMapping() {
    const returnToNodeEditor = this._routeMappingReturnToNodeEditor;
    this._routeMappingReturnToNodeEditor = false;
    this.setData({
      routeMappingShow: false, routeMappingOpen: '', routeMappingLayer: null,
      popChapterNodes: !!returnToNodeEditor,
    });
  },

  _rollbackNodeRouteEdit() {
    const snapshot = this._nodeRouteGraphBeforeEdit;
    this._nodeRouteGraphBeforeEdit = null;
    if (!snapshot) return;
    this._routeGraph = snapshot.routeGraph;
    this.setData({
      'formData.routeMode': snapshot.routeMode,
      'formData.routeGraphJson': snapshot.routeGraphJson,
    }, () => this.refreshRouteGraph());
  },

  _commitNodeRouteEdit() {
    this._nodeRouteGraphBeforeEdit = null;
  },

  finishNodeRouteMapping() {
    if (this._routeRulesOwner !== undefined && (this._unloaded || this._hidden
        || this._routeRulesOwner !== String(app.getUserID() || '')
        || this._routeRulesScope !== this.data.operationScope)) return;
    const sourceId = this._routeMappingSourceNodeId;
    const nodes = this._routeNodes();
    const source = nodes.find((node) => node.id === sourceId);
    if (!source) return this.closeNodeRouteMapping();
    const nextNodeId = this._nextStoryNodeId(sourceId, nodes);
    let graph = this.data.formData.routeMode === 'BRANCH_GRAPH'
      ? topicRouteGraph.normalizeGraph(this._routeGraph).graph
      : this._sequentialRouteGraph(nodes); this._declareRouteRuleVariables(graph); // 文档二 L3：用到的属性 / 进度钟补声明
    for (const row of this.data.routeMappingRows || []) {
      if (!row.ruleConfig) continue;
      const errors = storyRuleForm.errors(row.ruleConfig, nodes, graph.variables || {});
      if (errors.length) { cyToast(row.label + '：有一句还没选完'); return; }
      if (!row.toNodeId && !nextNodeId && (row.ruleConfig.conditions.length || row.ruleConfig.effects.length)) {
        cyToast(row.label + '：请先选择下一站，再保存条件与效果'); return;
      }
    }
    const preservedTerminalIds = new Set((graph.terminalNodeIds || []).map(String));
    const previous = graph.edges || [];
    graph.edges = previous.filter((edge) => edge.fromNodeId !== sourceId);
    (this.data.routeMappingRows || []).forEach((row, index) => {
      const targetId = row.toNodeId || nextNodeId;
      if (!targetId) return;
      const existing = previous.find((edge) => (!row.edgeId || edge.id === row.edgeId) && edge.fromNodeId === sourceId
        && edge.trigger.type === row.triggerType
        && edge.trigger.outcomeCode === row.code);
      const mappedEdge = Object.assign({
        id: 'mapping_' + sourceId + '_' + row.code,
        conditions: [], effects: [], priority: index, weight: 1,
        once: true, maxVisits: 1, allowLoop: false,
      }, existing || {}, {
        fromNodeId: sourceId,
        trigger: { type: row.triggerType, outcomeCode: row.code },
        toNodeId: targetId,
      }, row.ruleConfig ? JSON.parse(JSON.stringify(row.ruleConfig)) : {});
      if (existing && String(existing.id || '').indexOf('route_') === 0
          && row.toNodeId && targetId !== nextNodeId) {
        mappedEdge.id = 'mapping_' + sourceId + '_' + row.code;
      }
      graph.edges.push(mappedEdge);
    });
    graph.fallbacks = (graph.fallbacks || []).filter((item) => item.fromNodeId !== sourceId);
    if (nextNodeId) graph.fallbacks.push({ fromNodeId: sourceId, toNodeId: nextNodeId });
    if (graph.edges.some((edge) => edge.fromNodeId === sourceId)) preservedTerminalIds.delete(sourceId);

    // 没单独配置的节点仍按原故事流前进；起点和技术终点不暴露给创作者。
    nodes.slice(0, -1).forEach((node, index) => {
      if (preservedTerminalIds.has(node.id)
        || graph.edges.some((edge) => edge.fromNodeId === node.id)) return;
      graph.edges.push({
        id: 'route_' + (index + 1), fromNodeId: node.id,
        trigger: { type: 'CHOICE', outcomeCode: 'COMPLETED' },
        conditions: [], effects: [], toNodeId: nodes[index + 1].id,
        priority: 0, weight: 1, once: true, maxVisits: 1, allowLoop: false,
      });
    });
    graph.startNodeId = nodes.length ? nodes[0].id : '';
    const sources = new Set(graph.edges.map((edge) => edge.fromNodeId));
    graph.terminalNodeIds = nodes
      .filter((node) => preservedTerminalIds.has(node.id) || !sources.has(node.id))
      .map((node) => node.id);

    this._routeGraph = graph;
    if (this._routeRuleDrafts) delete this._routeRuleDrafts[sourceId];
    this.setData({ 'formData.routeMode': 'BRANCH_GRAPH' }, () => {
      this.refreshRouteGraph();
      this._onDraftEdited(this._persistDraftEnvelope());
      this.closeNodeRouteMapping();
    });
  },

  _routeSimulationReport() {
    return topicRouteGraph.simulate(this._routeGraph, this._routeNodes(), {
      runs: 1000,
      seed: 'release-check',
    });
  },

  validateRouteGraph(options) {
    if (this.data.formData.routeMode !== 'BRANCH_GRAPH') return true;
    const preview = this.refreshRouteGraph();
    if (options && options.draft) return true;
    if (!preview.valid) return false;
    return this._routeSimulationReport().releaseReady;
  },

  // 表单验证
  // 错误累积/顺序/首错文案走共享 publish-validator;字段集/文案/章节守卫/票务 price+mode 仍是本页口径。
  // errors 映射存字段内联短文案,toast 走"第N个票务"前缀文案(bag 第四参 toastMessage)。
  // 纯判定:产出 errorBag,不做 setData/toast/切Tab(供 validateForm + buildPublishCheck 共用,避免重复造规则)
  // 发布前校验 = 纯函数,见 utils/publish/publish-validation-bag.js。
  // 页面只负责把 data 切片和 4 个路线图私有方法的结果算好传进去。
  _buildValidationBag() {
    const branchGraph = !(Number(this.data.formData.productType) === 2)
      && this.data.formData.routeMode === 'BRANCH_GRAPH';
    return publishValidationBag.buildValidationBag({
      formData: this.data.formData,
      selectedCategoryIds: this.data.selectedCategoryIds,
      startDateTime: this.data.startDateTime,
      endDateTime: this.data.endDateTime,
      completionRuleMode: this.data.completionRuleMode,
      completionRequiredCount: this.data.completionRequiredCount,
      pendingMaterials: this.data.pendingMaterials,
      // 这四项只在分支图模式下才需要,非该模式不白算。
      routeGraph: branchGraph ? this._routeGraph : null,
      routeNodes: branchGraph ? this._routeNodes() : [],
      savedTicketOptions: branchGraph ? this._routeSavedTicketOptions() : [],
      // ⚠️ 传函数不传结果:模拟跑 1000 轮,原实现只在图校验通过后才跑。
      // 提前算好会让每次校验(输入时也触发)都白跑一遍。
      routeSimulationReport: () => this._routeSimulationReport(),
    });
  },

  refreshPrimaryActionState() {
    this.syncIdentityReady();
    const d = this.data;
    // 章节状态与「还差几项」由策略层统一算,页面不复制判据(见 pro-editor-policy 头注)
    const policy = proEditorPolicy.evaluateProfessionalDraft({
      formData: d.formData,
      clubId: d.formData && d.formData.clubId,
      pendingMaterials: d.pendingMaterials,
      categoryIds: d.selectedCategoryIds,
    });
    const missing = policy.blockingIssues.length;
    const openingIndex = (d.formData.chapters || []).findIndex(chapter => chapter.opening === true);
    let chapterNumber = 0;
    const summaryPatch = { openingIndex,
      // 序章与结局章都不占「第 N 章」的号:序章是开局,结局章不在路线里
      chapterNumbers: (d.formData.chapters || []).map(chapter => chapter.opening || chapter.ending ? 0 : ++chapterNumber) };
    // ⚠️ 派生态必须留在 formData 之外。曾试过挂 formData.chapters[i]._state,结果被
    // editScope=WHITELIST 的「锁定字段未改动」断言判成「章节被改过」,只改文案也保存不了。
    if (JSON.stringify(policy.chapterStates) !== JSON.stringify(d.chapterStates)) {
      summaryPatch.chapterStates = policy.chapterStates;
    }
    const chapterActionLabels = policy.chapterStates.map((state) => {
      return Number(d.formData.productType) === 1 && state.storyDone
        ? '把这段剧情落到地点' : '＋ 创建节点';
    });
    if (JSON.stringify(chapterActionLabels) !== JSON.stringify(d.chapterActionLabels)) {
      summaryPatch.chapterActionLabels = chapterActionLabels;
    }
    if (policy.defaultAnchor !== d.creationAnchor) summaryPatch.creationAnchor = policy.defaultAnchor;
    if (JSON.stringify(policy.starterAction) !== JSON.stringify(d.starterAction)) {
      summaryPatch.starterAction = policy.starterAction;
    }
    const pendingMaterialStates = {};
    (Array.isArray(d.pendingMaterials) ? d.pendingMaterials : []).forEach((material) => {
      pendingMaterialStates[material._localId] = proEditorPolicy.hasUsableCoords(material);
    });
    if (JSON.stringify(pendingMaterialStates) !== JSON.stringify(d.pendingMaterialStates)) {
      summaryPatch.pendingMaterialStates = pendingMaterialStates;
    }
    const summary = missing ? ('还有 ' + missing + ' 项未填') : '已填完';
    if (summary !== d.topicDetailSummary) summaryPatch.topicDetailSummary = summary;
    if (missing !== d.topicDetailMissing) summaryPatch.topicDetailMissing = missing;
    if (Object.keys(summaryPatch).length) this.setData(summaryPatch);

    const bag = this._buildValidationBag();
    const needsClub = Number(d.formData.productType) === 1 && d.myClubs.length > 0;
    const canPublish = bag.isValid() && (!needsClub || !!d.formData.clubId)
      && !templateBinding.issues(d.formData.chapters, d.formData.themeRuntimeConfig,
        this._templateDetailsById, d.formData.journeyRules, this._templateDetailRequested).length
      && (!d.editingTopicId || d.editLoaded);
    const patch = {};
    if (canPublish !== d.canPublish) patch.canPublish = canPublish;
    const ticketMissing = this._ticketMissing(bag);
    if (JSON.stringify(ticketMissing) !== JSON.stringify(d.ticketMissing)) patch.ticketMissing = ticketMissing;
    if (Object.keys(patch).length) this.setData(patch);
  },

  // P-4(V-25):票务页三个分区的缺项数 —— 档期 / 票务按校验袋的字段,实名按三格是否填了
  _ticketMissing(bag) {
    const errorKeys = Object.keys((bag || this._buildValidationBag()).errors || {});
    const id = this.identityForm();
    const missing = {
      dates: errorKeys.filter((key) => key === 'startDate' || key === 'endDate' || key === 'recruitDeadline').length,
      tickets: errorKeys.filter((key) => key.indexOf('ticket') === 0).length,
      identity: publisherIdentity.topicRequiresIdentity(this.data.formData) && !id.identityRegistered
        ? [!String(id.realName || '').trim(), !String(id.idCard || '').trim(), !id.consented].filter(Boolean).length : 0,
    };
    missing.total = missing.dates + missing.tickets + missing.identity;
    return missing;
  },

  validateForm() {
    let that = this
    const bag = that._buildValidationBag();

    that.setData({
      errors: bag.errors
    });

    // 九宫格开着但没排满就拦下来。不拦的话服务端会拒(TopicCompletionRulePolicy),
    // 商家看到的是一句从接口透出来的报错,而不是"第 3 格还没选站点"。
    const bingoError = advancedGameConfig.validateTopicBingo({
      enabled: that.data.bingoRows.some(row => !!(row.label.trim() || row.feedbackText || row.couponId)), cells: that._bingoCellsByPosition()
    });
    if (bingoError) {
      that.setData({ bingoError });
      cyToast(bingoError, { duration: 3000 });
      return false;
    }
    that.setData({ bingoError: '' });

    // 如果有错误，只显示第一个,避免信息过多
    if (!bag.isValid()) {
      that.focusValidationError(bag.errors);
      cyToast(bag.firstMessage(), { duration: 3000 });
    }

    return bag.isValid();
  },



  focusValidationError(errors) {
    const keys = Object.keys(errors || {});
    if (!keys.length) return;
    const key = keys[0];
    const anchor = this._anchorForErrorKey(key);
    // 平铺后所有区块都在同一屏,不用再跳步骤,滚到锚点即可
    const patch = {};
    if (key.indexOf('ticket') === 0) {
      patch['secCollapsed.ticket'] = false;
    }
    if (key.indexOf('ticket') === 0 && !this.data.showTicketEditor) {
      patch.showTicketEditor = true;
      const idx = key.match(/(\d+)/);
      const ticketIndex = idx ? parseInt(idx[1], 10) : 0;
      const tickets = this.data.formData.tickets || [];
      patch.editingTicket = Object.assign({}, tickets[ticketIndex] || this.createDefaultTicket(this.data.editingTicket.mode || 1), {
        editIndex: tickets.length ? ticketIndex : -1
      });
    }
    this.setData(patch, () => {
      this.refreshTicketSaveState();
      this._scrollSheetTo(anchor);
    });
  },

  // 发布前检查(M4·A):汇总 blocking(硬必填,阻断)+ advisory(软建议,不阻断),供发布弹层逐条定位
  // blocking 直接复用 validateForm 的判定(_buildValidationBag),按字段 key 映射到对应 Tab
  buildPublishCheck() {
    const bag = this._buildValidationBag();
    const errors = bag.errors || {};
    // 单字段错 → 所属 Tab:章节/节点类落「路线」Tab'0',其余落「基本信息」Tab'2'
    const tabOf = (key) => key.indexOf('chapterRecruit') === 0
      ? '2' : (key.indexOf('chapter') === 0 || key === 'chapters') ? '0' : '2';
    // CU-C-160:tab 是旧的粗分类(原三步向导),定位要靠字段名 —— 交给
    // locatePublishIssue → _anchorForErrorKey,别再按 tab 猜。
    const blocking = Object.keys(errors).map((key) => ({
      label: errors[key],
      field: key,
      tab: tabOf(key)
    }));
    templateBinding.issues(this.data.formData.chapters, this.data.formData.themeRuntimeConfig,
      this._templateDetailsById, this.data.formData.journeyRules, this._templateDetailRequested)
      .forEach(issue => blocking.push({ label: issue.label,
      field: 'templateBinding:' + issue.key, tab: '0' }));
    const runtime = publishIssueTarget.parse(this.data.formData.themeRuntimeConfig);
    (Array.isArray(runtime.chapters) ? runtime.chapters : []).forEach(row => {
      if (!Number.isInteger(row.index) || row.index < 0 || row.index >= (this.data.formData.chapters || []).length) return;
      const issue = this._chapterRuleIssue(row.index, row);
      if (issue) blocking.push({ label: '章节 index=' + row.index + '：' + issue,
        field: 'chapterRuntime:' + row.index, tab: '0' });
    });
    const structureIssue = this._structureIssue(); // structure
    if (structureIssue) blocking.push({ label: structureIssue.error, field: 'runtimeStructure:' + structureIssue.field, tab: '2' });
    blocking.push(...this._supportPublishIssues());
    const runtimeIssue = (this._structureIssue() || {}).error || this._validateRuntimeConfig(); // structure
    if (/挑战站点|备选挑战|zeroChallenge|归零挑战|客观题备选/.test(runtimeIssue))
      blocking.push({ label: runtimeIssue, field: 'themeState', tab: '2' });
    blocking.push(...this._nodePlanEndingIssues());
    // 已编译的章节终局由结构校验处理；这里只沿用旧结局目录的兼容检查。
    const legacyPlans = (runtime.gameplayRuntime?.plans || []).filter(plan => !plan.storyEndingId);
    const planIssue = publishIssueTarget.planIssue({ ...runtime, gameplayRuntime: { ...runtime.gameplayRuntime, plans: legacyPlans } });
    if (planIssue) {
      const target = publishIssueTarget.fromMessage(planIssue, this.data.formData.chapters, this._templateDetailsById);
      blocking.push(target || { label: planIssue, field: 'chapters', tab: '0' });
    }
    (this.data.formData.chapters || []).forEach(chapter => (chapter.nodes || []).forEach(node => {
      const detail = (this._templateDetailsById || {})[node.templateId] || node.templateInfo;
      const config = publishIssueTarget.parse(detail && detail.advancedConfigJson);
      const issue = publishIssueTarget.costIssue(config);
      if (issue) blocking.push({ label: issue, field: 'templateTool:' + templateBinding.nodeKey(node) + ':cost', tab: '0' });
    }));

    // advisory = computeCompleteness().missing 里的软项(站点级缺地点/描述/时长)+ 未配游戏站点;均落 Tab'0'
    // 软项都是站点级事实,没有单一字段:按章节区定位('chapters' → routeSection)。
    const advisory = [];
    const softSet = ['部分节点缺地点', '部分节点缺描述', '部分节点缺时长'];
    const missing = (this.computeCompleteness().missing) || [];
    missing.forEach((m) => {
      if (softSet.indexOf(m) !== -1) advisory.push({ label: m, field: 'chapters', tab: '0' });
    });
    // 未配游戏的站点(无 templateId 视为未配)
    let noGame = 0;
    ((this.data.formData && this.data.formData.chapters) || []).forEach((chapter) => {
      (chapter.nodes || []).forEach((node) => {
        if (node && !node.templateId) noGame++;
      });
    });
    if (noGame > 0) advisory.push({ label: `${noGame} 个节点未配置玩法`, field: 'chapters', tab: '0' });

    return { blocking, advisory };
  },

  // 发布确认页的「自动检查已通过 · N 项」。
  // ⚠️ 它不能拿 blocking/advisory 取反来凑 —— 那样只要没报错就算"通过",
  //    一条从来没跑过的检查也会被算成绿的。这里每一项都自己判一次现值。
  _buildPublishPassed(precheckOk) {
    const fd = this.data.formData || {};
    const chapters = fd.chapters || [];
    const nodeCount = publishStats.listedNodeCount(chapters);
    // CU-C-168:只数**填全了**的票 —— 数组里躺一张空票不等于配好了一种票。
    const readyTickets = (fd.tickets || []).filter((t) => !this._ticketBlockReason(t));
    const items = [
      { label: '主题名称已填写', ok: !!(fd.name && fd.name.trim()) },
      { label: '主题封面已上传', ok: !!fd.imgUrl },
      { label: '主题简介已填写', ok: !!(fd.description && fd.description.trim()) },
      { label: `已编排 ${chapters.length} 个章节`, ok: chapters.length > 0 },
      { label: `已放置 ${nodeCount} 个节点`, ok: nodeCount > 0 },
      { label: `已配置 ${readyTickets.length} 种票单`, ok: readyTickets.length > 0 },
      { label: '通关规则已确定', ok: !!this.data.completionRuleMode },
      { label: '内容安全预检通过', ok: !!precheckOk },
      // RUN-52:已登记才进这份清单(未登记时它出现在「必填项」那一侧,不能两头都算)
      { label: '发布者实名已登记', ok: publisherIdentity.topicRequiresIdentity(fd) && !!this.data.identityRegistered },
    ];
    return items.filter((item) => item.ok);
  },

  // 发布确认页的「玩家看到的样子」。
  // ⚠️ 数据源必须是编辑器里的**当前草稿**,不是 ?id= 拉服务端 —— 服务端那版是上一次保存的,
  //    拿它当预览就是 2026-09-05 删掉的那个假预览重新长回来。
  _buildPublishPreview() {
    const fd = this.data.formData || {};
    // 直接重算,不读 this.data.totalStats:那个字段全页零渲染(U4 存量债务 A1),
    // 读它只会把一条死字段升级成「内部状态」,而算一次的成本本来就可以忽略。
    const stats = this.calculateTotalStats() || {};
    return {
      cover: fd.imgUrl || '',
      name: fd.name || '未命名主题',
      subtitle: fd.subtitle || '',
      description: fd.description || '',
      stats: [
        { k: '章节', v: String((fd.chapters || []).length) },
        { k: '节点', v: String(stats.totalNodes == null ? 0 : stats.totalNodes) },
        { k: '时长', v: stats.totalDurationDisplay || '—' },
        { k: '里程', v: stats.totalDistance || 0 },
      ],
      // P-4 剧情预览:玩家看到的样子 —— 模式、票价、每章剧情与节点照片 / 玩法、通关勋章
      mode: Number(fd.productType) === 2 ? '自由探索' : '城市定向',
      ticket: ((fd.tickets || [])[0] && {
        name: fd.tickets[0].name || '未命名票种',
        price: Number(fd.tickets[0].price) > 0 ? '¥' + Number(fd.tickets[0].price) : '免费',
      }) || null,
      medal: fd.finishMedalName || (fd.name || '主题名') + ' · 通关',
      chapters: (fd.chapters || []).map((chapter, index) => ({
        index,
        // CU-C-87:与章节卡同一把尺子 —— 名字自带「第N章」时不再叠一次序号,
        // 否则确认页会出现「第1章 第1章 隔离书店站」。
        name: chapterCardTitle(index, chapter && chapter.name),
        story: (chapter && chapter.description) || '',
        nodes: (((chapter && chapter.nodes) || []).map((node, nodeIndex) => ({
          index: nodeIndex,
          name: (node && node.name) || '未命名节点',
          address: node && node._storyGame ? '无需到达地点' : ((node && node.address) || '未选地点'),
          img: String((node && node.imgUrl) || '').split(',').filter(Boolean)[0] || '',
          play: (node && node.templateName) || '',
        }))),
      })),
    };
  },

  _buildPublishSummary() {
    const formData = this.data.formData || {};
    const chapters = formData.chapters || [];
    const nodeCount = publishStats.listedNodeCount(chapters);
    // CU-C-168:同一张「发布前检查」里不能既说票没填完、又说配了 N 种 —— 没填全的不计数,
    // 但也不能不吭声,差额如实报出来。
    const allTickets = formData.tickets || [];
    const ticketCount = allTickets.filter((t) => !this._ticketBlockReason(t)).length;
    const unfinished = allTickets.length - ticketCount;
    const modeLabel = Number(formData.productType) === 2 ? '自由探索' : '城市定向';
    const completionLabel = this.data.completionRuleMode === 'AT_LEAST'
      ? `完成任意 ${Number(this.data.completionRequiredCount) || 0} 个节点即通关`
      : '完成全部节点后通关';
    return [
      `「${formData.name || '未命名主题'}」将按${modeLabel}发布，共 ${chapters.length} 个章节、${nodeCount} 个节点。`,
      `${completionLabel}；当前配置 ${ticketCount} 种票单${unfinished > 0 ? `，另有 ${unfinished} 种没填完` : ''}。`
    ].concat(d20Page.publishSummaryLine(formData.journeyRules) || []);
  },

  // 组装安全预检请求体(AiSafetyPrecheckReq):路线级文案 + 摊平后的全部节点
  // 节点上只有 name/description;task/hint1/hint2 的真源是所配玩法(node.templateInfo,来自 /api/template/my-list 整行,
  // 含 rule_instructions/question_name/hint1/hint2)。未配玩法的节点没有 task 可言,退化用节点自己的文字说明,
  // 这样至少内容安全那一半始终有料可检(硬传空 = 预检形同虚设)。
  _buildPrecheckReq() {
    const fd = this.data.formData || {};
    const nodes = [];
    (fd.chapters || []).forEach((chapter) => {
      ((chapter && chapter.nodes) || []).forEach((node) => {
        if (!node) return;
        const ti = node.templateInfo || {};
        const parsed = advancedGameConfig.parse(ti.advancedConfigJson);
        const gameplay = parsed.error ? [] : advancedGameConfig.enabledSections(parsed.value)
          .map(key => JSON.stringify({ [key]: parsed.value[key] }));
        nodes.push({
          name: node.name || '',
          task: [ti.ruleInstructions || ti.questionName || ti.description || node.description || '',
            ...gameplay].filter(Boolean).join('\n'),
          hint1: ti.hint1 || '',
          hint2: ti.hint2 || ''
        });
      });
    });
    return {
      title: fd.name || '',
      subtitle: fd.subtitle || '',
      description: fd.description || '',
      nodes
    };
  },

  // 预检不可用时的兜底文案(AI 不是发布的硬依赖,只降级成一条建议项)
  _precheckSkipLabel(msg) {
    if (String(msg || '').indexOf(AI_QUOTA_HINT) >= 0) {
      return 'AI 次数已用完，本次跳过安全预检(不影响发布)';
    }
    return '安全预检暂不可用，本次跳过(不影响发布)';
  },

  // 预检结果 → 并进 buildPublishCheck() 的本地判定,复用同一个弹层
  // level=error 才阻断;但 parse_error 是「AI 自己炸了」,不是用户内容有问题,降级为建议,否则 AI 一炸就没人能发布
  _showPublishCheck(aiIssues, skipLabel) {
    this.syncIdentityReady();
    const local = this.buildPublishCheck();
    const blocking = local.blocking.slice();
    const advisory = local.advisory.slice();
    // AI 预检只收到文案(_buildPrecheckReq 里没有封面字段),模型永远看不到封面 ⇒ 它报的
    // missing_cover 是必然误报,而 level=error 会把「确认发布」禁用,专业编辑器从此没人能发布。
    // 封面有无归 buildPublishCheck() 本地判定,这类「AI 看不见的字段」整条丢掉。
    const issues = (aiIssues || []).filter((issue) => issue && issue.message
      && AI_BLIND_ISSUE_TYPES.indexOf(issue.type) < 0);
    issues.forEach((issue) => {
      const item = { label: issue.message, tab: '2' };
      if (issue.level === 'error' && issue.type !== 'parse_error') blocking.push(item);
      else advisory.push(item);
    });
    if (skipLabel) advisory.push({ label: skipLabel, tab: '2' });

    const summary = this._buildPublishSummary();
    // 预检通过 = 没有 AI 报的问题、也没有降级跳过。跳过时它是一条 advisory,不能算通过。
    const precheckOk = !skipLabel && !issues.length;
    this._publishPrecheckOk = precheckOk;
    const passed = this._buildPublishPassed(precheckOk);
    this.setData({
      publishCheck: {
        show: true, blocking, advisory, summary, passed, preview: this._buildPublishPreview(),
      },
    });
    this.setEditorState('publishCheck');
  },

  // 2026-10-30 业主 V-24「删除发布确认」:预检刚跑完、没有必填缺项、实名也已登记(或不需要)时直接发布,
  // 不再弹发布确认;只有还缺东西(必填项 / 实名)时才出发布弹层,让人在原地补齐。
  // 只在预检回包里调:点灰钮那条没跑过预检,不能借这里绕过去。
  _afterPrecheck(aiIssues, skipLabel) {
    this.syncIdentityReady();
    const blockingAi = (aiIssues || []).some((issue) => issue && issue.message && issue.level === 'error'
      && issue.type !== 'parse_error' && AI_BLIND_ISSUE_TYPES.indexOf(issue.type) < 0);
    if (!blockingAi && !this.buildPublishCheck().blocking.length
        && (!this.data.identityRequired || this.data.identityRegistered)) {
      this._publishPrecheckOk = !skipLabel && !(aiIssues || []).length;
      this._publishAfterIdentity();
      return;
    }
    this._showPublishCheck(aiIssues, skipLabel);
  },

  _showBackendPublishIssue(issue) {
    const check = this.buildPublishCheck();
    this.setData({ publishCheck: { show: true, blocking: [issue].concat(check.blocking),
      advisory: check.advisory, summary: this._buildPublishSummary(),
      passed: this._buildPublishPassed(false), preview: this._buildPublishPreview() } });
    this.setEditorState('publishCheck');
  },

  // 发布前 AI 安全预检(入口A):submitForm 的前置闸门
  // silentError:code!=200 由本方法接管(request-client 会自动弹一次 toast,不关掉就是双弹)
  runPublishPrecheck() {
    if (this._prechecking) return; // 防连点重复烧 AI 次数(频控 20/天 是全端点共用一池)
    this._prechecking = true;
    const token = (this._precheckToken || 0) + 1;
    this._precheckToken = token;
    cyLoading.show('安全预检中');
    app.sendRequest({
      url: '/api/ai/safety/precheck',
      method: 'POST',
      silentError: true,
      data: JSON.stringify(this._buildPrecheckReq()),
      header: { 'Content-Type': 'application/json' },
      // ★ complete 是唯一always会跑的回调,重置只能放这:
      // request-client.js:90-98 在 HTTP statusCode != 200(如部署期 502)时只弹 toast 就 return,
      // success 和 fail 一个都不调。若把重置写在 success/fail 里,一次 502 就会让
      // _prechecking 永为 true + 带 mask 的 loading 永不消失 → 之后每次点发布都被
      // runPublishPrecheck 首行的 `if (this._prechecking) return` 挡掉 = 发布按钮永久变哑巴。
      // 这条路正是本仓库部署流程里明写会出现的「200→502→200」窗口。
      complete: () => {
        this._prechecking = false;
        cyLoading.hide(); // loading 是 app 全局的,不随页面跳转消失,漏 hide 会冻住用户落到的任何页
      },
      success: (res) => {
        if (token !== this._precheckToken) return; // 已被新一次提交取代 / 页面已销毁
        if ((res.code == '200' || res.code == 200) && res.data) {
          this._afterPrecheck(res.data.issues, '');
        } else {
          // 频控/身份门禁/AI 异常:降级放行(可用性红线,AI 挂了不能堵死发布路)
          this._afterPrecheck([], this._precheckSkipLabel(res && res.msg));
        }
      },
      fail: () => {
        if (token !== this._precheckToken) return;
        this._afterPrecheck([], this._precheckSkipLabel(''));
      }
    });
  },

  // 发布弹层「继续发布」:仅在无 blocking 时出现,置一次性放行标记后重走 submitForm
  confirmPublishCheck() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.confirmPublishCheck(), { owner: this, target: () => [this.data.editingTopicId, this.data.draftUuid, this.data.operationScope, this.data.formData.clubId, this.data.formData.productType] })) return; // 2026-10-24 游客写前登录
    if (this._identityRegistering) return;
    this.syncIdentityReady();
    if (this.data.publishCheck.blocking.length) return;
    // 发布者会收到招募状态与承接意向通知。订阅授权只能在点击里同步弹:「完成」这一下就请求;
    // 后面的实名登记与内容预检都是异步回调,到那时已经弹不出来。
    subscribe.request(['recruit', 'clubInterest']).then((result) => {
      this._publishSubscribeStatus = result.status;
    });
    // P-4:实名三格在票务页最后一个分区,预览里没有 —— 没填齐就送回那里
    if (!this._identityReady()) { this._backToIdentity(''); return; }
    if (!this.data.identityRequired || this.data.identityRegistered) { this._submitFromPreview(); return; }
    // 先落实名、再落主题:后端两道闸就是这个顺序(/api/publisher/identity 先于 /api/topic/create
    // 的实名校验),并发两发会被拦成「请先登记实名信息」。
    const requestEpoch = (this._identityRequestEpoch || 0) + 1;
    this._identityRequestEpoch = requestEpoch;
    const memberId = String((app.getUserID && app.getUserID()) || '');
    const precheckToken = this._precheckToken;
    const scope = this.data.operationScope;
    this._identityRegistering = true;
    this.setData({ identityError: '' });
    cyLoading.show('登记实名信息');
    publisherIdentity.registerIdentity(this.identityForm(), (r) => {
      if (requestEpoch !== this._identityRequestEpoch) return;
      this._identityRegistering = false;
      cyLoading.hide();
      if (memberId !== String((app.getUserID && app.getUserID()) || '')
          || precheckToken !== this._precheckToken || scope !== this.data.operationScope
          || !this.data.publishCheck.show) return;
      if (!r.ok) {
        // 字段在票务页:回到那里显示报错(软红整块,不给输入框描红边),改完再预览一次
        this._backToIdentity(r.message);
        return;
      }
      this.setData({ identityRegistered: true, identityError: '' });
      if (this.data.publishCheck.blocking.length) return;
      this._submitFromPreview();
    });
  },

  // P-4:剧情预览右上「完成」= 发布。内容安全预检在这一步才跑(内容到这一刻才定);
  // 预检没问题直接发布,有问题重新打开这一层、红行列在最上面(_afterPrecheck → _showPublishCheck)。
  _submitFromPreview() {
    this.setData({ 'publishCheck.show': false });
    this.setEditorState('idle');
    this.submitForm();
  },

  _backToIdentity(message) {
    this.closePublishCheck();
    this.setData({ editorPage: 2, identityError: message || '' }, () => this._scrollSheetTo('fieldIdentity'));
    if (!message) {
      const check = publisherIdentity.checkIdentityForm(this.identityForm());
      cyToast(check.ok ? '发布者实名还没登记' : check.message);
    }
  },

  // P-4(V-25):票务页底部「预览」—— 票务页还有没填的,滚到第一项并报还差几项;
  // 都填完就打开剧情预览(原发布确认那一层)。这一步不烧 AI 预检次数,预检在「完成」时跑。
  openStoryPreview() {
    this.syncIdentityReady();
    const bag = this._buildValidationBag();
    const errors = bag.errors || {};
    const missing = this._ticketMissing(bag);
    this.setData({ ticketMissing: missing });
    const first = Object.keys(errors).find((key) => ISSUE_PAGE2_ANCHORS.indexOf(this._anchorForErrorKey(key)) >= 0);
    if (first) {
      this.focusValidationError({ [first]: errors[first] });
      cyToast('还有 ' + missing.total + ' 项未填');
      return;
    }
    if (missing.identity) { this._backToIdentity(''); return; }
    // CU-C-168:这一步没跑内容安全预检,第二个参数照实说(预检在「完成」时才跑)
    this._showPublishCheck([], '内容安全预检还没跑');
  },

  // 票务页分区标题右侧红色「* 还有 N 项未填」:滚到这一区的第一项
  onReqLeftTap(e) {
    const anchor = (e && e.currentTarget && e.currentTarget.dataset.anchor) || '';
    if (anchor === 'ticketSection') {
      const errors = this._buildValidationBag().errors || {};
      const key = Object.keys(errors).find((k) => k.indexOf('ticket') === 0);
      if (key) { this.focusValidationError({ [key]: errors[key] }); return; }
    }
    this._scrollSheetTo(anchor);
  },

  // 剧情预览每一块右上的笔:回到配置里对应的位置(章节 → 章节设置,节点 → 节点编辑,票价 → 票务页)
  onPreviewEdit(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    this.closePublishCheck();
    if (ds.target === 'ticket') { this.setData({ editorPage: 2 }, () => this._scrollSheetTo('ticketSection')); return; }
    this.setData({ editorPage: 1 });
    const chapterindex = Number(ds.chapterindex);
    if (!(this.data.formData.chapters || [])[chapterindex]) return;
    if (ds.nodeindex !== undefined && ds.nodeindex !== '') {
      this.showEditNodes({ currentTarget: { dataset: { chapterindex, nodeindex: Number(ds.nodeindex) } } });
      return;
    }
    this.showEditChapter({ currentTarget: { dataset: { index: chapterindex } } });
  },

  _publishAfterIdentity() {
    this._precheckAcked = true;
    this.setData({ 'publishCheck.show': false });
    this.setEditorState('idle');
    this.submitForm();
  },

  // 发布弹层的「确认发布」被置灰时点它 —— 报第一条真缺项,不打空炮。
  onPublishCheckDisabledTap() {
    if (!this._identityReady()) {
      const check = publisherIdentity.checkIdentityForm(this.identityForm());
      cyToast(check.ok ? '发布者实名还没登记' : check.message);
      return;
    }
    const first = this.data.publishCheck.blocking[0];
    if (first) cyToast(first.label);
  },

  // ===== RUN-52 发布者实名(发布确认弹层内联) =====
  identityForm() {
    const d = this.data;
    return {
      realName: d.identityRealName,
      idCard: d.identityIdCard,
      consented: d.identityConsented,
      identityRegistered: d.identityRegistered,
      source: publisherIdentity.SOURCE_TOPIC_PUBLISH,
    };
  },

  // 要不要实名驱动票务页那一区显不显示;规则仍然只有 utils/publisher-identity.js 一份
  syncIdentityReady() {
    const required = publisherIdentity.topicRequiresIdentity(this.data.formData);
    if (required !== this.data.identityRequired) this.setData({ identityRequired: required });
  },

  // 三格齐不齐(或根本不需要实名):只在点「完成」/ 完成置灰时现算
  _identityReady() {
    return !publisherIdentity.topicRequiresIdentity(this.data.formData)
      || publisherIdentity.identitySatisfied(this.identityForm());
  },

  onIdentityFieldInput(e) {
    const field = e.currentTarget.dataset.field;
    this.setData({ [field]: e.detail.value, identityError: '' }, () => this.syncIdentityReady());
  },

  onIdentityConsent(e) {
    this.setData({
      identityConsented: !!(e.detail && e.detail.checked),
      identityError: '',
    }, () => this.syncIdentityReady());
  },

  // 发布弹层「去完善」:仅关闭
  closePublishCheck() {
    this._identityRequestEpoch = (this._identityRequestEpoch || 0) + 1;
    if (this._identityRegistering) cyLoading.hide();
    this._identityRegistering = false;
    this.setData({ 'publishCheck.show': false });
    this.setEditorState('idle');
  },



  // 一键定位:把这一项送到它字段真正所在的那一屏(切页 / 开主题详情弹窗 / 展开票务并打开对应票单),
  // 再滚到锚点。
  // ⚠️ CU-C-160:这里原先只关层 + 页面滚顶,七条必填共用同一个动作 —— 写「去填写」却把人
  //   放回创作总览,主题详情压根没打开、editorPage 也停在 1。那种"看起来响应了"的落点比不响应更糟。
  //   没有 field 的行(AI 预检建议)不猜位置,退回原来的关层行为。
  locatePublishIssue(e) {
    const ds = (e && e.currentTarget && e.currentTarget.dataset) || {};
    const field = ds.field || '';
    this.setData({ 'publishCheck.show': false });
    this.setEditorState('idle');
    if (field.indexOf('runtimeStructure:') === 0) { // structure
      this._locateStructureIssue(field.split(':')[1]);
      return;
    }
    if (field.indexOf('chapterRuntime:') === 0) {
      const index = Number(field.slice('chapterRuntime:'.length));
      if ((this.data.formData.chapters || [])[index]) {
        this.setData({ editorPage: 1 });
        this.showEditChapter({ currentTarget: { dataset: { index } } });
      }
      return;
    }
    if (field === 'themeState') {
      this._syncRuntimeStats();
      this.setData({ editorPage: 1, topicDetailShow: true, runtimeEditingStat: 'hp' },
        () => this._scrollSheetTo('fieldRuntimeState'));
      return;
    }
    if (field.indexOf('templateTool:') === 0 || field.indexOf('templateBinding:') === 0) {
      const tool = field.indexOf('templateTool:') === 0;
      const parts = field.slice((tool ? 'templateTool:' : 'templateBinding:').length).split(':');
      const key = parts[0] === 'client' ? parts.splice(0, 2).join(':') : parts.shift();
      const target = parts.shift() || '';
      (this.data.formData.chapters || []).some((chapter, chapterindex) =>
        (chapter.nodes || []).some((node, nodeindex) => {
          if (templateBinding.nodeKey(node) !== key) return false;
          this.setData({ editorPage: 1 });
          this.showEditNodes({ currentTarget: { dataset: { chapterindex, nodeindex } } });
          if (tool && target === 'vote' && (this.data.nodePlanEndingRows || []).length)
            this.setData({ nodePlanEndingError: ds.label || '请选择表决结果的结局章' });
          else if (tool) this.onEditBoundTemplate({ tool: target, planKey: parts.join(':'), message: ds.label });
          else if (target) this.setData({ nodeBindingErrorKey: (this.data.nodeBindingRows || []).some(row => row.key === target) ? target : '',
            nodeBindingError: ds.label || '资源未绑定' });
          return true;
        }));
      return;
    }
    if (!field) {
      setTimeout(() => {
        wx.pageScrollTo({ scrollTop: 0, duration: 250 });
      }, 60);
      return;
    }
    const anchor = this._anchorForErrorKey(field);
    // CU-C-160 负控:空锚点 = 这一条缺项在本页认不出对应字段(旧数据、AI 建议行、
    // 或字段改了名而这里没跟上)。此时什么都不切 —— 切屏 + 开层 + 滚一个猜出来的锚点
    // 就是「假装定位成功」,比不动更糟。确认层照关,页面照回顶部。
    if (!anchor) {
      setTimeout(() => {
        wx.pageScrollTo({ scrollTop: 0, duration: 250 });
      }, 60);
      return;
    }
    this.setData({
      editorPage: ISSUE_PAGE2_ANCHORS.indexOf(anchor) >= 0 ? 2 : 1,
      topicDetailShow: ISSUE_SHEET_ANCHORS.indexOf(anchor) >= 0 ? true : this.data.topicDetailShow
    }, () => {
      // 复用提交时那条定位路:票务项要展开折叠卡并打开对应票单编辑器。
      // 主题详情弹窗的锚点在弹窗自己的容器里,页面 scroll-view 滚不动它(空滚),
      // 但弹窗已经开在字段所在的那一层 —— 剩下的距离由用户自己滑,不再骗他说"到了"。
      this.focusValidationError({ [field]: '' });
    });
  },

  // 价格输入处理函数
  onTicketPriceChange(e) {
    let {
      field,
      index
    } = e.currentTarget.dataset;
    let value = e.detail.value;

    // 移除所有非数字和小数点的字符
    value = value.replace(/[^\d.]/g, '');

    // 确保最多只有一个小数点
    const dotCount = (value.match(/\./g) || []).length;
    if (dotCount > 1) {
      value = value.slice(0, -1);
    }

    // 限制小数点后最多两位
    const parts = value.split('.');
    if (parts.length > 1) {
      if (parts[1].length > 2) {
        value = `${parts[0]}.${parts[1].substring(0, 2)}`;
      }
    }

    // 转换为数字（允许0）
    let numericValue = value === '' ? 0 : Number(value);

    this.setData({
      [`formData.tickets[${index}].${field}`]: numericValue
    });
  },
  previewChapterOpening(e) {
    const index = Number(e.currentTarget.dataset.index);
    const chapters = this.data.formData.chapters || [];
    const chapter = chapters[index];
    if (!chapter) return;
    const regular = chapters.filter(item => !item.opening);
    this.setData({ reducedMotion: readReducedMotion(), chapterPreview: { show: true,
      number: chapter.opening ? 0 : regular.indexOf(chapter) + 1, total: regular.length,
      title: chapter.name || '未命名章节', subtitle: this.data.formData.name || '' } });
  },
  closeChapterPreview() { this.setData({ 'chapterPreview.show': false }); },
  // ===== 思维阁:念头清单(2026-09-22 与初始状态并排的第二个入口) =====
  // 念头的定义住在 journeyRules.thoughts,正文里的 thought 块只放引用 —— 同一条数据不要两个真源。
  // 配置只编辑文字和步数，存量的得到/应验条件原样保留。
  openThoughts() {
    try {
      const rules = storyInitialState.parse(this.data.formData.journeyRules);
      const list = Array.isArray(rules.thoughts) ? rules.thoughts.map((t) => Object.assign({}, t, {
        need: t.need == null ? '' : t.need,
        desc: t.desc || (t.name !== '伏笔' ? t.name : '') || '',
      })) : [];
      this.setData({ thoughtsEditor: { show: true, list, count: list.length, error: '' } });
      if (!list.length) this.addThought();
    } catch (error) { cyToast(error.message); }
  },
  closeThoughts() { this.setData({ 'thoughtsEditor.show': false }); },
  addThought() {
    const list = (this.data.thoughtsEditor.list || []).slice();
    if (list.length >= 1) return;
    // key 只做内部标识,作者不填 —— 填错了正文里的引用就对不上,不如自动给。
    // 旧协议需要 name，使用固定标签，不让作者再填写一个名称。
    list.push({ key: 't' + Date.now().toString(36), name: '伏笔', desc: '', need: 120 });
    this.setData({ 'thoughtsEditor.list': list, 'thoughtsEditor.error': '' });
  },
  onThoughtInput(e) {
    const index = Number(e.currentTarget.dataset.index);
    const field = e.currentTarget.dataset.field;
    if (!['desc', 'need'].includes(field)) return;
    const list = (this.data.thoughtsEditor.list || []).slice();
    if (!list[index]) return;
    list[index] = Object.assign({}, list[index], { [field]: e.detail.value });
    this.setData({ 'thoughtsEditor.list': list, 'thoughtsEditor.error': '' });
  },
  removeThought(e) {
    const index = Number(e.currentTarget.dataset.index);
    const list = (this.data.thoughtsEditor.list || []).slice();
    const target = list[index];
    if (!target) return;
    try {
      const rules = storyInitialState.parse(this.data.formData.journeyRules);
      const references = Object.assign({}, this.data.formData, {
        journeyRules: Object.assign({}, rules, { thoughts: list.filter((row, i) => i !== index) })
      });
      const referenced = (value) => {
        if (typeof value === 'string') {
          if (value === target.key) return true;
          try { const parsed = JSON.parse(value); if (parsed && typeof parsed === 'object') return referenced(parsed); } catch (ignore) {}
          return false;
        }
        if (Array.isArray(value)) return value.some(referenced);
        return !!value && typeof value === 'object' && Object.values(value).some(referenced);
      };
      if (target.key && referenced(references)) {
        this.setData({ 'thoughtsEditor.error': '正文或结局仍引用这条伏笔，请先移除对应引用再删除' });
        return;
      }
      list.splice(index, 1);
      this.setData({ 'thoughtsEditor.list': list, 'thoughtsEditor.count': list.length, 'thoughtsEditor.error': '' });
    } catch (error) { this.setData({ 'thoughtsEditor.error': error.message }); }
  },

  saveThoughts() {
    try {
      const rules = storyInitialState.parse(this.data.formData.journeyRules);
      const list = (this.data.thoughtsEditor.list || []).map((t) => {
        const desc = String(t.desc || '').trim();
        if (!desc) throw new Error('请输入伏笔文字');
        if (t.need == null || String(t.need).trim() === '') throw new Error('请输入步数');
        const row = { key: t.key, name: t.name || '伏笔', desc, need: t.need,
          when: t.when, doneWhen: t.doneWhen };
        return row;
      });
      // 走同一个 serialize:校验口径与服务端 JourneyRulesValidator 一致,不另写一套
      const raw = storyInitialState.serializeChanges(this.data.formData.journeyRules, Object.assign({}, rules, { thoughts: list }));
      // ⚠️ thoughtOptions 必须跟着走:它只在页面回填时取过一次,不在这里同步的话,
      //    作者在同一次编辑里配完念头、再去配结局或插念头块,下拉是空的 —— 得退出重进才有。
      const patch = thoughtPatchOf(raw);
      this.setData({ 'formData.journeyRules': raw, 'thoughtsEditor.show': false, 'thoughtsEditor.error': '',
        'thoughtsEditor.count': list.length, thoughtOptions: patch.thoughtOptions, thoughtNameOf: patch.thoughtNameOf });
    } catch (error) { this.setData({ 'thoughtsEditor.error': error.message }); }
  },

  // ===== 结局(journeyStory.ending.endings)=====
  /** 结局章的站点下拉:已保存过的站点(有 id)才能被「到过某站」引用 */
  _endingNodeOptions() {
    const out = [];
    (this.data.formData.chapters || []).forEach((chapter) => {
      (chapter.nodes || []).forEach((node) => {
        if (node && node.id) out.push({ id: node.id, name: node.name || ('节点 ' + node.id) });
      });
    });
    return out;
  },
  /** 本地 / 服务端草稿恢复时:还没有结局章、剧情里却有老结局清单 ⇒ 转成结局章(不然新编辑器里看不到,保存时还会被当空清单去掉) */
  _withEndingChapters(formData) {
    const chapters = (formData && formData.chapters) || [];
    if (chapters.some((c) => c && c.ending)) return formData;
    const attached = storyEnding.attachEndingsToChapters(chapters, formData.journeyStory);
    if (!attached.legacy.length && !attached.chapters.some((c) => c.ending)) return formData;
    return Object.assign({}, formData, {
      chapters: attached.chapters.concat(attached.legacy.map((item) => this._endingChapterFrom(item))),
    });
  },
  /** 老结局(没有 chapterId)→ 一章新的结局章:章名 = 标题,正文 = 那一句话 */
  _endingChapterFrom(item) {
    return {
      _localId: this._genLocalId(), name: item.title || '结局', description: '',
      blocks: item.summary ? [{ key: this._genLocalId(), type: 'text', content: item.summary }] : [],
      nodes: [], required: 1, schemaVersion: 1, recruitEnabled: 0,
      atmospherePreset: normalizeAtmosphere(''), ending: item.ending,
    };
  },
  /** 章节设置里的结局下拉:清单现取,下标按这一章已选的值算 */
  _syncChapterEndingOptions(ending) {
    const patch = thoughtPatchOf(this.data.formData.journeyRules);
    const tags = storyInitialState.optionTagsOf(this.data.formData.chapters);
    const nodes = this._endingNodeOptions();
    const kinds = this.data.chapterEndingKinds;
    const kind = ending ? (ending.fallback ? 'fallback' : ending.kind) : 'fallback';
    let pick = -1;
    if (ending && ending.kind === 'thought') pick = patch.thoughtOptions.findIndex((t) => t.key === ending.thoughtKey);
    if (ending && ending.kind === 'tag') pick = tags.findIndex((t) => t.tag === ending.tag);
    if (ending && ending.kind === 'node') pick = nodes.findIndex((n) => String(n.id) === String(ending.nodeId));
    // 回显名字:老结局转过来的只有键 / id,名字按清单补上(不然下拉那一行写「请选择」,作者以为没配)
    let named = ending || null;
    if (ending && pick >= 0) {
      if (ending.kind === 'thought') named = Object.assign({}, ending, { thoughtName: patch.thoughtOptions[pick].name });
      if (ending.kind === 'tag') named = Object.assign({}, ending, { tagLabel: tags[pick].label });
      if (ending.kind === 'node') named = Object.assign({}, ending, { nodeName: nodes[pick].name });
    }
    this.setData({
      thoughtOptions: patch.thoughtOptions, thoughtNameOf: patch.thoughtNameOf,
      endingTagOptions: tags, endingNodeOptions: nodes, 'chapterForm.ending': named,
      chapterEndingKindIndex: Math.max(0, kinds.findIndex((k) => k.key === kind)), chapterEndingPickIndex: pick,
    });
  },

  onChapterEndingToggle(e) {
    const on = !!e.detail.value;
    const form = this.data.chapterForm || {};
    if (on && (form.nodes || []).length) {
      cyToast('结局章里不能放节点或玩法');
      this.setData({ 'chapterForm.ending': null });
      return;
    }
    // 还没有兜底结局章时,第一章结局默认就是兜底;已有兜底就默认按伏笔
    const hasFallback = (this.data.formData.chapters || []).some((c, i) =>
      c && c.ending && c.ending.fallback && !(this.data.popChapterAction == 1 && i === this.data.popChapterIndex));
    const ending = !on ? null : (hasFallback
      ? { fallback: false, kind: 'thought', thoughtKey: '', thoughtName: '', tag: '', tagLabel: '', nodeId: null, nodeName: '' }
      : { fallback: true, kind: 'fallback' });
    this.setData({ 'chapterForm.ending': ending }, () => this._syncChapterEndingOptions(ending));
  },
  onChapterEndingKind(e) {
    const picked = (this.data.chapterEndingKinds || [])[Number(e.detail.value)];
    if (!picked) return;
    // 换触发方式就把另一种的选择清掉 —— 留着会让提交拿到一个对不上 kind 的值
    const ending = picked.key === 'fallback' ? { fallback: true, kind: 'fallback' }
      : { fallback: false, kind: picked.key, thoughtKey: '', thoughtName: '', tag: '', tagLabel: '', nodeId: null, nodeName: '' };
    this.setData({ 'chapterForm.ending': ending, chapterEndingKindIndex: Number(e.detail.value), chapterEndingPickIndex: -1 });
  },
  onChapterEndingPick(e) {
    const index = Number(e.detail.value);
    const ending = Object.assign({}, (this.data.chapterForm || {}).ending);
    if (ending.kind === 'thought') {
      const t = (this.data.thoughtOptions || [])[index];
      if (!t) return;
      Object.assign(ending, { thoughtKey: t.key, thoughtName: t.name });
    } else if (ending.kind === 'tag') {
      const t = (this.data.endingTagOptions || [])[index];
      if (!t) return;
      Object.assign(ending, { tag: t.tag, tagLabel: t.label });
    } else if (ending.kind === 'node') {
      const n = (this.data.endingNodeOptions || [])[index];
      if (!n) return;
      Object.assign(ending, { nodeId: n.id, nodeName: n.name });
    } else return;
    this.setData({ 'chapterForm.ending': ending, chapterEndingPickIndex: index });
  },

  // 同一主题锁内读取注册版本与完整定义；读取不会自动确认差异或写回规则。
  _readAttributeRegistrySnapshot(context, done) {
    app.sendRequest({
      url: '/api/topic/attribute-binding', hideLoading: true,
      method: 'GET', data: { topicId: context.topicId, scope: context.scope },
      success: res => {
        if (res && Number(res.code) === 200 && res.data
            && String(res.data.configVersion) === context.configVersion) {
          done({ ok: true, snapshot: res.data, supportsAtomicBinding: true });
        } else done({ ok: false, msg: res && Number(res.code) === 200
          ? '主题配置版本已变化，请重新读取主题后确认' : res && res.msg || '注册定义读取失败' });
      },
      fail: () => done({ ok: false, msg: '注册定义读取失败，请重试' })
    });
  },
  loadAttributeRegistry() {
    const context = { topicId: String(this.data.editingTopicId || ''), scope: this.data.operationScope,
      memberId: String(app.getUserID() || ''), configVersion: String(this.data.formData.configVersion || ''),
      authorization: typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined };
    this._attributeRegistryContext = context;
    this._attributeRegistryWriteReady = false;
    this.setData({ attributeRegistrySnapshot: null, attributeRegistryError: '' });
    if (!context.topicId || !context.memberId) {
      this.setData({ attributeRegistryError: '请先保存主题草稿，再读取注册属性' }); return;
    }
    let settled = false;
    const done = result => {
      if (settled || !this._attributeRegistryCurrent(context)) return;
      settled = true;
      try {
        if (!result || !result.ok) throw Error(result && result.msg || '注册定义读取失败，请重试');
        const snapshot = attributeBinding.snapshot(result.snapshot, context.topicId);
        this._attributeRegistryWriteReady = result.supportsAtomicBinding === true;
        this.setData({ attributeRegistrySnapshot: snapshot, attributeRegistryError: '' });
      } catch (error) { this.setData({ attributeRegistryError: error.message }); }
    };
    try { this._readAttributeRegistrySnapshot(context, done); } catch (error) { done({ ok: false, msg: '注册定义读取失败，请重试' }); }
  },
  _attributeRegistryCurrent(context) {
    return context && context === this._attributeRegistryContext && !this._hidden && !this._unloaded
      && context.topicId === String(this.data.editingTopicId || '') && context.scope === this.data.operationScope
      && context.memberId === String(app.getUserID() || '')
      && context.configVersion === String(this.data.formData.configVersion || '')
      && context.authorization === (typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined);
  },
  onAttributeBindingConfirm(e) {
    if (this.data.editScope === 'WHITELIST' || !this.data.initialStateEditor.show
        || !this._attributeRegistryCurrent(this._attributeRegistryContext)) return;
    try {
      const detail = e.detail || {};
      const raw = this.data.formData.journeyRules || '';
      const session = attributeBinding.session(raw, this.data.attributeRegistrySnapshot,
        { topicId: this.data.editingTopicId, configVersion: this.data.formData.configVersion });
      if (detail.fingerprint !== session.fingerprint || detail.sourceRules !== raw
          || detail.configVersion !== session.context.configVersion
          || detail.registryVersion !== session.registry.registryVersion) throw Error('配置或注册版本已变化，请重新查看并确认差异');
      const confirmed = attributeBinding.confirm(session, detail.enabledKeys, detail.acceptedKeys);
      const rules = JSON.parse(confirmed.rules);
      const editing = Object.assign({}, this.data.initialStateEditor.rules, { attributeBinding: rules.attributeBinding });
      editing.attributes = rules.attributes; delete editing.attributeDefinition; delete editing.attributeSnapshot;
      this.setData({ 'formData.journeyRules': confirmed.rules,
        'initialStateEditor.rules': editing, 'initialStateEditor.values': customInitialValues(editing.attributes), 'initialStateEditor.carry': this._carryRowsOf(editing.attributes), 'initialStateEditor.error': '' });
      this._onDraftEdited(this._persistDraftEnvelope());
    } catch (error) { this.setData({ 'initialStateEditor.error': error.message }); }
  },
  manageAttributeDefinitions() { this.closeInitialState(); this.openTopicDetail(); },

  openInitialState() {
    try {
      this._syncRuntimeStats();
      const rules = storyInitialState.parse(this.data.formData.journeyRules);
      const values = customInitialValues(rules.attributes);
      this.setData({ initialStateEditor: { show: true, rules, values, carry: this._carryRowsOf(rules.attributes), error: '' } });
      this.loadAttributeRegistry();
    } catch (error) { cyToast(error.message); }
  },
  closeInitialState() { this.setData({ 'initialStateEditor.show': false }); },
  onInitialStateField(e) {
    if (this.data.editScope === 'WHITELIST') return;
    const field = e.currentTarget.dataset.field;
    if (field === 'attribute.initial') {
      const index = Number(e.currentTarget.dataset.index);
      const rules = this.data.initialStateEditor.rules;
      if (rules.attributeBinding) { this.setData({ 'initialStateEditor.error': '初值由注册定义管理，请到主题配置调整' }); return; }
      if (!rules.attributes || !rules.attributes[index] || !/^counter\./.test(rules.attributes[index].key || '')) return;
      const attributes = rules.attributes.slice();
      attributes[index] = Object.assign({}, attributes[index], { initial: e.detail.value });
      this.setData({ 'initialStateEditor.rules.attributes': attributes,
        'initialStateEditor.values': customInitialValues(attributes),
        'initialStateEditor.error': '' });
      return;
    }
    // 上限归主题数值域设置；首章初始状态只改开局值
    if (!['hp.enabled', 'hp.init', 'luck.enabled', 'luck.init'].includes(field)) return;
    const type = field.split('.')[0];
    if (field.endsWith('.enabled') && !e.detail.value) {
      const saved = storyInitialState.parse(this.data.formData.journeyRules);
      this.setData({ ['initialStateEditor.rules.' + type]: Object.assign({}, saved[type], { enabled: false }), 'initialStateEditor.error': '' });
      return;
    }
    this.setData({ ['initialStateEditor.rules.' + field]: e.detail.value, 'initialStateEditor.error': '' });
  },
  addInitialState(e) {
    if (this.data.editScope === 'WHITELIST') return;
    const type = e.currentTarget.dataset.type;
    if (type !== 'hp' && type !== 'luck') return;
    this.setData({ ['initialStateEditor.rules.' + type + '.enabled']: true,
      'initialStateEditor.rules.stateEnabled': true, 'initialStateEditor.error': '' });
  },
  saveInitialState() {
    if (this.data.editScope === 'WHITELIST') { this.closeInitialState(); return; }
    try {
      const rules = this.data.initialStateEditor.rules;
      const enabled = rules.attributeBinding ? attributeBinding.binding(rules.attributeBinding).enabledKeys.length > 0
        : Array.isArray(rules.attributes) && rules.attributes.length > 0;
      let raw;
      if (rules.attributeBinding) {
        const local = Object.assign({}, rules, { stateEnabled: rules.hp.enabled || rules.luck.enabled || enabled });
        delete local.attributes; delete local.statLinks;
        const normalized = JSON.parse(storyInitialState.serialize(local, this._runtimeConfig()));
        if (rules.statLinks) normalized.statLinks = rules.statLinks;
        normalized.attributes = rules.attributes;
        raw = attributeBinding.requestRules(JSON.stringify(normalized), this._loadedAttributeRules || '', this.data.attributeRegistrySnapshot);
      } else raw = storyInitialState.serializeChanges(this.data.formData.journeyRules,
        Object.assign({}, rules, { stateEnabled: rules.hp.enabled || rules.luck.enabled || enabled }), this._runtimeConfig());
      this.setData({ 'formData.journeyRules': raw, 'initialStateEditor.show': false });
    } catch (error) { this.setData({ 'initialStateEditor.error': error.message }); }
  },

  // 提交表单
  submitForm() {
    if (app.ensureLoginFor && !app.ensureLoginFor('publish', () => this.submitForm(), { owner: this, target: () => [this.data.editingTopicId, this.data.draftUuid, this.data.operationScope, this.data.formData.clubId, this.data.formData.productType] })) return; // 2026-10-24 游客写前登录
    // 一次性消费放行标记:必须在任何 early return 之前取走并清掉,
    // 否则「确认发布 → 下面某条校验拦下」会把 true 留到下一次点击,让那一版内容静默绕过预检。
    const precheckAcked = this._precheckAcked;
    this._precheckAcked = false;

    if (this.data.submitting) return;
    if (this._serverDraftBusy()) { cyToast('正在保存草稿，保存完成后再发布'); return; }
    // 编辑态回填没到位就保存 = 拿空表单去重建,原主题的章节与票会被删光
    if (this.data.editingTopicId && !this.data.editLoaded) {
      cyToast('主题还在加载，稍等一下');
      return;
    }
    if (!this.validateForm()) {
      return;
    }

    // 经典定向团(含 mode==1 票种)且拥有俱乐部时,必须选归属俱乐部(D3/D12 一人多俱乐部)
    const isCityOrienteering = Number(this.data.formData.productType) === 1;
    if (isCityOrienteering && this.data.myClubs.length > 0 && !this.data.formData.clubId) {
      this._scrollSheetTo('fieldClub');
      cyToast('请选择路线归属的俱乐部');
      return;
    }

    // 招募状态与承接意向通知的订阅授权在剧情预览「完成」那一下请求(confirmPublishCheck),这里是异步链路,不再弹。

    // 发布前 AI 安全预检:每次提交跑一次(内容可能已改),用户在弹层点「继续发布」后一次性放行
    if (!precheckAcked) {
      this.runPublishPrecheck();
      return;
    }
    this._doSubmit();
  },

  // 真正组装 payload 并提交(与 submitForm 拆开:预检弹层确认后要能直接走到这)
  _storyPayloadChapter(chapter, chapterIndex) {
    const prepared = JSON.parse(JSON.stringify(chapter || {}));
    let sequence = 0;
    const temporaryKey = () => '_payload_' + chapterIndex + '_' + (++sequence);
    prepared._localId = prepared._localId || temporaryKey();
    prepared.nodes = Array.isArray(prepared.nodes) ? prepared.nodes : [];
    prepared.nodes.forEach((node) => {
      if (!node._localId) node._localId = temporaryKey();
      if (!node.clientNodeKey) node.clientNodeKey = node._localId;
    });
    return proEditorStory.toPayloadChapter(
      proEditorStory.materializeChapter(prepared, temporaryKey));
  },

  _nonStoryPayloadChapter(chapter) {
    const payload = JSON.parse(JSON.stringify(chapter || {}));
    delete payload.blocks;
    delete payload.schemaVersion;
    return payload;
  },

  _buildSubmitChapters(productType) {
    const endingIssue = storyEnding.endingChaptersIssue(this.data.formData.chapters);
    if (endingIssue) throw new Error(endingIssue);
    const needsStoryPayload = productType === 2 || this.data.formData.chapters.some(chapter => chapter.opening === true
      || chapter.ending || (!this.data.aiSimple && Array.isArray(chapter.blocks) && chapter.blocks.length > 0));
    return this.data.formData.chapters.map((chapter, chapterIndex) => {
      const sourceChapter = productType === 1 || needsStoryPayload
        ? this._storyPayloadChapter(chapter, chapterIndex) : this._nonStoryPayloadChapter(chapter);
      const nodes = (sourceChapter.nodes || []).map(node => this._stripLocalFields({
        ...node,
        // 首次创建尚无数据库 id；服务端用该稳定键把 routeGraphJson 重映射到新节点。
        clientNodeKey: node.clientNodeKey || node._localId || '',
        nodeTime: parseInt(node.nodeTime || 0, 10) // 转为数字，默认0
      }));
      return this._stripLocalFields({
        ...sourceChapter,
        nodes
      });
    });
  },

  // 组装主题 payload:发布与「保存草稿」共用这一份(两条路存进去的必须是同一个东西);组装不出来返回 null
  // options.draft:存草稿不过「章节与到达关系」闸 —— 删改站点后那道闸本身就要求先存草稿拿到新编号再重设,
  // 拦住存草稿会成死结;发布仍过这道闸,后端校验照旧。
  _buildSubmitPayload(options) {
    if (Object.keys(this._routeRuleDrafts || {}).length) {
      if (!(options && options.quiet)) cyToast('有条件与效果未完成，请先补全');
      return null;
    }
    const draft = !!(options && options.draft);
    const productType = Number(this.data.formData.productType) === 2 ? 2 : 1;
    let chapters, journeyRules;
    try {
      const currentRules = this.data.formData.journeyRules || '';
      if (this.data.editScope !== 'WHITELIST' && attributeBinding.parse(currentRules).attributeBinding && !this._attributeRegistryWriteReady) {
        throw new Error('请在顶部设置的初始状态中重新读取并确认属性；当前内容已保留在本机');
      }
      journeyRules = this.data.editScope === 'WHITELIST' ? currentRules
        : attributeBinding.requestRules(currentRules, this._loadedAttributeRules || '', this.data.attributeRegistrySnapshot);
      chapters = this._buildSubmitChapters(productType);
    } catch (error) {
      // 组装章节 payload 抛错(节点引用失效等)时点「确认发布」原来是静默无反应 —— 兜住给提示。
      // quiet:自动保存不弹提示(未完成的输入每 2 秒弹一次是骚扰),等用户明确保存/发布时再说
      if (!(options && options.quiet)) cyToast(app.getRequestErrorMessage(error, '故事流内容不完整，请检查后重试'));
      return null;
    }

    const formattedTickets = this.data.formData.tickets.map(ticket => ({
      ...ticket,
      mode: productType,
      // [P1-41] 归一化:sync 同步来的值可能已带时间/ISO 串,原样追加会拼出非法时间
      startTime: this._normalizeDateTime(ticket.syncWithTheme ? this.data.formData.startDate : ticket.startTime, '00:00:00'),
      endTime: this._normalizeDateTime(ticket.syncWithTheme ? this.data.formData.endDate : ticket.endTime, '23:59:59'),
      // [2-26] 售票时间是 date 选择器给的纯日期串,后端 DTO 的 @JsonFormat 只吃
      // "YYYY-MM-DD HH:mm:ss";不归一化会 400,归一化口径同 startTime/endTime。
      saleStartTime: this._normalizeDateTime(ticket.saleStartTime, '00:00:00'),
      saleEndTime: this._normalizeDateTime(ticket.saleEndTime, '23:59:59')
    }));

    // 准备API数据
    const runtimeIssue = draft ? '' : (this._structureIssue() || {}).error || this._validateRuntimeConfig(); // structure
    if (runtimeIssue) { cyToast(runtimeIssue); return null; }
    let apiData = {
      name: this.data.formData.name,
      subtitle: this.data.formData.subtitle,
      description: this.data.formData.description,
      // [P1-41] 写入点格式不一('HH:mm'/'HH:mm:ss'/纯日期),原 +':00' 对后两种拼出非法串
      startDate: this._normalizeDateTime(this.data.formData.startDate, '00:00:00'),
      endDate: this._normalizeDateTime(this.data.formData.endDate, '23:59:59'),
      imgUrl: this.data.formData.imgUrl,
      imgArr: this.data.formData.imgArr,
      categoryIds: this.data.selectedCategoryIds.join(','),
      chapters: chapters,
      collaboratorIds: this.data.formData.collaboratorIds,
      // [P0-02] 产品真源:主题级模式显式上送(= 入口的 entryMode)。后端据此覆盖每张票的 mode,
      // 票里的 mode 自此不再是真源。不传则后端从票 mode 推断,混票会被拒。
      productType,
      tickets: formattedTickets, // 使用格式化后的票价数据
      // 1=进商家池 0=自办。资格归一放在 _syncMerchantPoolEligibility(确证无资格即清 formData),
      // 这里不再二次判断 —— 否则 pro-editor-policy 读到的 formData 与上送值会分家。
      openMerchantPool: this.data.formData.openMerchantPool ? 1 : 0,
      // P0-4 开放范围:1=开放给俱乐部承接 0=自办。
      // spec §2.1「邀请俱乐部带队」只属于城市定向(spec §2.1:自由探索没有俱乐部带队)。
      // 2026-09-15 裁决 12B:专业版与简易版统一默认开(默认值在 formData 初值),主办可手动关。
      // 2026-09-15 补充裁决:归属俱乐部的主题(俱乐部自办团/自动挂靠)不开放公开承接池 ——
      // 开关不露面,存量回填的 1 也在这里归 0;服务端 resolveOpenClubPool/编辑落值同样压 0,两层一致。
      // 归一放在**上送这一处**而不是各个入口:模式切换(applyEntryMode)、草稿复制
      // (_buildCopiedDraft)、编辑回填三条路都可能把 formData 带到自由探索上,
      // 而自由探索上送 1 会撞服务端 assertClubLeadOnlyForCityOrienteering 直接抛。
      openClubPool: (productType === 1 && !this.data.formData.clubId && this.data.formData.openClubPool) ? 1 : 0,
      recruitDeadline: Number(this.data.formData.productType) === 2
        ? this._normalizeDateTime(this.data.formData.recruitDeadline, '23:59:59') : null,
      publishToCreative: this.data.publishToCreative ? 1 : 0, // 1=同步到创意广场 0=否(后端 ApiTopicController.createTopic 已处理:开关开则插 dataType=2 创意广场帖)
      clubId: this.data.formData.clubId || '', // 归属俱乐部(任何 mode 传了即挂靠;经典定向未传后端自动兜底)
      // M2 自玩票:仅「经典定向」生效(后端以 ticket mode=1 再次兜底,自由定向不开)
      selfPlay: this.data.formData.selfPlay ? 1 : 0,
      selfPlayPrice: this.data.formData.selfPlay ? this.data.formData.selfPlayPrice : 0,
      selfPlayQuota: this.data.formData.selfPlayQuota || 0,
      teamMode: Number(this.data.formData.teamMode) || 0,
      teamMaxMembers: Number(this.data.formData.teamMaxMembers) || 4,
      // P0.5 通关勋章(XP 自动分配无需上送)
      finishMedalName: this.data.formData.finishMedalName || '',
      finishMedalImg: this.data.formData.finishMedalImg || '',
      completeRewardCouponId: this.data.formData.completeRewardCouponId || 0,
      completeRuleJson: advancedGameConfig.mergeTopicBingo(
        advancedGameConfig.mergeTopicCompletion(
          this.data.formData.completeRuleJson,
          productType === 1 ? this.data.completionRuleMode : 'ALL',
          this.data.completionRequiredCount),
        { enabled: this.data.bingoRows.some(row => !!(row.label.trim() || row.feedbackText || row.couponId)), cells: this._bingoCellsByPosition() }),
      journeyRules: journeyRules || null,
      themeRuntimeConfig: this._runtimePayload(), look: this._lookPayload(),
      // 结局都在章节上(结局章):剧情里的结局清单交给服务端按章重建
      journeyStory: storyEnding.withoutEndingList(this.data.formData.journeyStory) || null,
      publishMode: this.data.aiSimple ? 'ai_simple' : 'pro',
      routeMode: productType === 1 && this.data.formData.routeMode === 'BRANCH_GRAPH'
        ? 'BRANCH_GRAPH' : 'LINEAR',
      routeGraphJson: productType === 1 && this.data.formData.routeMode === 'BRANCH_GRAPH'
        ? (this.data.editingTopicId
          ? topicRouteGraph.serialize(this._routeGraph)
          : topicRouteGraph.serializeWithNodeKeys(this._routeGraph)) : '',
      configVersion: this.data.formData.configVersion || '',
      scope: this.data.operationScope
    };

    // 已过审开卖的主题只许改文案与图:日期 / 章节站点结构 / 票价后端一律拒收(方案 §6 口径)。
    // 这里主动剔掉,而不是指望后端忽略 —— 整包上送会当场被拒,用户看到的是「保存失败」而不是「保存成功但没变」。
    if (this.data.editingTopicId && this.data.editScope === 'WHITELIST') {
      if (!this._assertLockedFieldsUntouched()) return null;
      apiData = {
        name: apiData.name,
        subtitle: apiData.subtitle,
        description: apiData.description,
        imgUrl: apiData.imgUrl,
        imgArr: apiData.imgArr,
        categoryIds: apiData.categoryIds,
        scope: this.data.operationScope
      };
    }

    return apiData;
  },

  _doSubmit() {
    // 预检弹层期间可能刚起了一次自动保存:三者串行,不并发写同一份草稿
    if (this.data.submitting) return;
    if (this._serverDraftBusy()) { cyToast('正在保存草稿，保存完成后再发布'); return; }
    this._cancelCloudDraftAutosave();
    const apiData = this._buildSubmitPayload();
    if (!apiData) return;
    // 服务端草稿:先把最新内容存回这份草稿并回读确认,再发布它;存失败就不发(不会发出旧版本)
    if (this.data.editingTopicId && this.data.editIsDraft) {
      this._publishServerDraft(apiData);
      return;
    }
    this.sendData(apiData);
  },
  // 主题写接口:专业版故事流走 v2,其余走旧接口;有主题号就 update,否则 create
  _topicEndpoint(payload, editingId) {
    const hasChapterBlocks = (payload.chapters || []).some(chapter =>
      Array.isArray(chapter.blocks) && chapter.blocks.length > 0);
    const professionalStory = (payload.publishMode === 'pro'
      && (Number(payload.productType) === 1 || (payload.chapters || []).some(chapter => chapter.opening
        || chapter.ending || (Array.isArray(chapter.blocks) && chapter.blocks.length > 0))))
      || (Number(payload.productType) === 2 && hasChapterBlocks);
    return professionalStory
      ? (editingId ? '/api/topic/v2/update' : '/api/topic/v2/create')
      : (editingId ? '/api/topic/update' : '/api/topic/create');
  },

  // 发布提交状态机(防重/失败回退/销毁守卫走共享 publish-workflow;url/analytics/toast/跳转保持本页)
  getPublishWorkflow() {
    if (!this._publishWorkflow) {
      this._publishWorkflow = createPublishWorkflow({
        networkFailureState: 'unknown',
        request: (payload, cb) => {
          // 编辑既有主题走 update(带 id),新建才走 create。
          // 在 request 里读而不是建 workflow 时读:workflow 是懒建且只建一次,建它那一刻回填可能还没到。
          const editingId = this.data.editingTopicId;
          const endpoint = this._topicEndpoint(payload, editingId);
          const owner = memberId(app);
          const draftMemberId = this.data.draftMemberId;
          const draftUuid = this.data.draftUuid;
          const editLoadToken = this._editLoadToken;
          const baseRevision = this.data.baseRevision;
          const scope = this.data.operationScope;
          const isCurrent = () => !this._unloaded
            && owner === memberId(app)
            && draftMemberId === this.data.draftMemberId
            && editingId === this.data.editingTopicId
            && draftUuid === this.data.draftUuid
            && editLoadToken === this._editLoadToken
            && baseRevision === this.data.baseRevision
            && scope === this.data.operationScope;
          sendUiStateRequest(app, endpoint, {
            data: JSON.stringify(editingId ? Object.assign({}, payload, { id: editingId }) : payload),
            method: "POST",
            header: {
              'Content-Type': 'application/json'
            },
            success: (res) => {
              if (!isCurrent()) return;
              if (res && res.code == "200") {
                const id = topicIdOf(res.data);
                const sameTopic = id > 0 && (!editingId || id === Number(editingId));
                const confirmed = sameTopic && (typeof res.data !== 'object'
                  || confirmedPublication(res.data, id));
                cb(confirmed ? { ok: true, data: res.data } : { ok: false, networkError: true });
              }
              else if (res && /^4\d\d$/.test(String(res.code))) cb({ ok: false, msg: res.msg, errorCode: res.errorCode || res.data && res.data.errorCode });
              else cb({ ok: false, networkError: true });
            },
            fail: () => { if (isCurrent()) cb({ ok: false, networkError: true }); }
          });
        }
      });
    }
    return this._publishWorkflow;
  },

  // 发送数据到API
  sendData(data) {
    let that = this;
    if (this._unloaded) return;
    if (this._draftScopeUnconfirmed) return;
    if (this._draftStorageUnavailable) {
      this._markDraftStorageUnavailable();
      return;
    }
    const memberId = String(app.getUserID() || '');
    if (!memberId || (this.data.draftMemberId && String(this.data.draftMemberId) !== memberId)) return;
    const workflow = that.getPublishWorkflow();
    if (workflow.getState() === 'submitting' || workflow.getState() === 'done') return;
    if (this._publishAttempt || workflow.getState() === 'unknown') {
      this._showPublishUnknown();
      return;
    }
    this._publishAttempt = { status: 'UNKNOWN', topicId: String(this.data.editingTopicId || '') };
    if (!this._persistDraftEnvelope()) {
      this._publishAttempt = null;
      return;
    }
    const draftRevision = this._draftContentRevision();
    const started = workflow.submit(data, {
      onSuccess: (resData) => {
        const tid = resData && typeof resData === 'object' ? resData.topicId : resData;
        const pendingReview = !!(resData && (resData.commercialAwaitingReview || !resData.published && ['PENDING', 'ESCALATED'].includes(resData.reviewState)));
        const reviewNextStep = resData && resData.commercialNextStep || '主题与引用的玩法将一起审核。';
        const localChanges = draftRevision !== that._draftContentRevision();
        that._publishAttempt = null;
        that._draftAutosaveReady = localChanges;
        if (localChanges && !that._persistDraftEnvelope()) {
          that._showUnstoredPublishedChanges(false);
          return;
        }
        if (!localChanges) that._clearDraftEnvelope();
        // 改已有主题走这条:不进发布成功那套全屏弹层(它的「去招商家 / 预览玩家视角」
        // 对已存在的主题没意义)。这里只报结果、然后回项目主页,没有导航分叉,
        // 正是 result-sheet 的形状。
        if (that.data.editingTopicId) {
          // ⚠️ 文案只说「已发布」:这条链路的 CTA 从头到尾是「检查并发布」,
          // 产品里没有「保存」这个概念(2026-09-05 用户当面纠正),别再写回「已保存」。
          // ⚠️ 不用 master 新引入的 cyToast:用户 2026-09-05/09-06 两次裁决要的是结果面板
          // (徽章 + 动效 + 自愈后跳转),toast 正是被它替代的那一档,不是并列选项。
          // 跳转挂在面板 close 上(落点写进 _resultSheetNext),不再另起 setTimeout ——
          // 面板自愈与硬等两个时长各算各的迟早会漂。
          that._resultSheetNext = '/pages/topic/merchantinfo/merchantinfo?topicId='
            + that.data.editingTopicId + (that.data.operationScope ? '&scope=MERCHANT' : '');
          that.setData({
            submitting: false, submitError: '',
            resultSheet: { show: true, kind: 'success', title: pendingReview ? '已提交审核' : '已发布',
              sub: localChanges ? '已发布提交时的版本；后续修改仍在本机，请继续编辑后另行保存。'
                : pendingReview ? reviewNextStep : '这份主题的改动已经发布。',
              meta: localChanges ? '草稿仍在本机' : '', why: '', duration: RESULT_SHEET_MS }
          });
          return;
        }
        analytics.track('topic_publish_success', {
          bizType: 'topic',
          bizId: tid || null,
          properties: {
            name: data.name
          }
        });
        that.setData({ submitting: false, submitError: '' });
        // 发完落到项目主页(主办视图),不是玩家买票页 —— 那页是给买票的人看的,
        // 发布者要的是「谁来接、谁来带团、卖了多少」。招商家和招俱乐部两个入口都在那儿。
        const scopeSuffix = that.data.operationScope ? '&scope=MERCHANT' : '';
        const projectListUrl = '/subpackageA/pages/myproject/index'
          + (that.data.operationScope ? '?scope=MERCHANT' : '');
        const projectUrl = tid ? ('/pages/topic/merchantinfo/merchantinfo?topicId=' + tid + scopeSuffix)
                               : projectListUrl;
        /* 2026-09-06 用户裁决「统一」:发布成功也不再放按钮,报一下就自己收、直接落到
           项目主页。原来两颗按钮是「去项目主页 / 预览玩家看到的」—— 两个都只是选去哪一页,
           不是「留在原地」那种真分叉。
           「预览玩家看到的」这条出口一度随之断掉:goTopicDetail 只在**承接视图**
           (project-join 的「我承接的」卡)上冒泡,而发完落到的是**主办视图**,那边没有入口。
           已在 project-host 标题下补了「预览玩家看到的」,路径接回来了。补在项目主页
           而不是把按钮加回本弹层 —— 发完就落到那一页,下一眼就能点到。
           ⚠️ 这一条推翻了旧交接稿 §4「S28 保留两颗按钮不自愈」。§4 的理由是「有导航分叉」,
           用户这次的裁决就是不要这个分叉。 */
        const chapterCount = (data.chapters || []).length;
        that._resultSheetNext = projectUrl;
        that.setData({
          resultSheet: {
            show: true, kind: 'success', title: pendingReview ? '已提交审核' : '城市路线已发布',
            sub: localChanges ? '已发布提交时的版本；后续修改仍在本机，请继续编辑后另行保存。'
              : pendingReview ? reviewNextStep : tid ? '接下来去招商家承接节点、找俱乐部带团。' : '路线已进入你的项目列表。',
            meta: localChanges ? '草稿仍在本机' : chapterCount ? (chapterCount + ' 个章节') : '',
            why: '', duration: RESULT_SHEET_MS,
          }
        });
      },
      onFail: (res) => {
        if (res && res.networkError) {
          that._showPublishUnknown();
          return;
        }
        that._publishAttempt = null;
        const persisted = that._persistDraftEnvelope();
        const message = (res && res.msg) || '发布失败，当前内容已保留，请重试';
        const reason = message + (persisted ? '' : '；本机存储暂时不可用，请检查后重试');
        const issue = publishIssueTarget.fromMessage(message,
          that.data.formData.chapters, that._templateDetailsById);
        if (issue) {
          that.setData({ submitting: false, submitError: reason });
          that._showBackendPublishIssue(issue);
          return;
        }
        that.setData({
          submitting: false,
          submitError: reason,
          // 面板只负责「说清楚发生了什么」,它自愈;重试出口是页内那条 cy-inline-error,
          // 它跟着 submitError 一直留在页上 —— 把恢复动作放进会自己消失的面板里是丢出口。
          // meta 带「草稿仍在本机」(稿 D):失败这一刻用户最想知道的是"我白写了吗",
          // 这句直接回答它,而且是真的 —— 本地草稿信封没被清(见 _clearDraftEnvelope
          // 只在成功路径调用)。
          resultSheet: { show: true, kind: 'fail', title: '这次还没有发布出去',
            sub: '修改后重试', meta: '草稿仍在本机',
            why: reason, duration: RESULT_SHEET_MS }
        });
      }
    });
    // submit 在途会返回 false(防重),仅真正发起时点亮 loading
    if (started && workflow.isBusy()) {
      that.setData({ submitting: true, submitError: '' });
    }
  },

  _serverDraftBusy() {
    return !!(this.data.draftSaving || (this._serverDraftSaver && this._serverDraftSaver.isBusy()));
  },

  // 云端草稿:保存(create/update 带 saveDraft=1)→ edit-detail 回读确认 →(发布时)publish-draft
  getServerDraftSaver() {
    const owner = String(this.data.draftMemberId || app.getUserID() || '');
    const scope = this.data.operationScope;
    const authorization = typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined;
    const current = () => !this._unloaded && !this._hidden && owner === String(app.getUserID() || '')
      && authorization === (typeof app.getAuthorization === 'function' ? app.getAuthorization() : undefined)
      && scope === this.data.operationScope;
    const context = JSON.stringify([owner, scope, this._draftIdentity(), this.data.draftUuid,
      this.data.baseRevision, this._editLoadToken, authorization]);
    if (this._serverDraftSaver && this._serverDraftContext !== context) {
      this._serverDraftSaver.destroy();
      this._serverDraftSaver = null;
    }
    if (!this._serverDraftSaver) {
      this._serverDraftContext = context;
      let publicationVersions = {};
      const reply = (cb, check) => ({
        success: (res) => {
          if (res && res.code == '200' && (!check || check(res.data))) cb({ ok: true, data: res.data });
          else if (res && /^4\d\d$/.test(String(res.code)) && Number(res.code) !== 408) cb({ ok: false, msg: res.msg, errorCode: res.errorCode || res.data && res.data.errorCode });
          else cb({ ok: false, networkError: true, msg: res && res.msg });
        },
        fail: (res, status) => cb(/^4\d\d$/.test(String(status)) && Number(status) !== 408
          ? { ok: false, msg: res && res.msg, errorCode: res && (res.errorCode || res.data && res.data.errorCode) } : { ok: false, networkError: true })
      });
      this._serverDraftSaver = createServerDraftSaver({ publishCheck: (topicId, cb) => this._topicPublishCheck(topicId, cb), // BL-185
        isCurrent: current,
        pendingPublication: () => this._publishAttempt,
        pendingPublish: () => this._publishAttempt && this._publishAttempt.kind === 'SERVER_DRAFT'
          ? this._publishAttempt.topicId : 0,
        beforePublish: (topicId) => {
          if (!current()) return false;
          this._serverDraftAttempt = null; // 保存内容与版本已经正向回读。
          this._publishAttempt = Object.assign({ status: 'UNKNOWN', kind: 'SERVER_DRAFT', topicId: String(topicId) }, publicationVersions);
          return this._persistDraftEnvelope();
        },
        save: (payload, cb) => {
          const editingId = this.data.editingTopicId;
          const handlers = reply(cb);
          // 首次建草稿带稳定意图键(按本机草稿,不随内容变):丢回包后的重试由服务端认出已建的那份,不再新建
          const body = editingId ? Object.assign({}, payload, { id: editingId })
            : Object.assign({}, payload, { requestId: payload.requestId || this._draftCreateIntentKey() });
          sendUiStateRequest(app, this._topicEndpoint(payload, editingId), {
            data: JSON.stringify(body),
            method: 'POST',
            header: { 'Content-Type': 'application/json' },
            success: handlers.success,
            fail: handlers.fail
          });
        },
        readback: (topicId, cb) => {
          const handlers = reply((result) => {
            publicationVersions = {};
            if (result.ok) {
              try {
                const topic = result.data.topic;
                if (topic.configVersion != null) publicationVersions.configVersion = attributeBinding.version(topic.configVersion);
                const rules = attributeBinding.parse(result.data.journeyRules || topic.journeyRulesJson);
                if (rules.attributeBinding) {
                  publicationVersions = { configVersion: attributeBinding.version(topic.configVersion),
                    registryVersion: attributeBinding.binding(rules.attributeBinding).registryVersion };
                }
              } catch (invalid) { cb({ ok: false, msg: invalid.message }); return; }
            }
            cb(result);
          }, isEditingTopicPayload);
          app.sendRequest({ url: '/api/topic/author-edit-detail', hideLoading: true,
            method: 'POST',
            data: { id: topicId, scope: this.data.operationScope },
            success: handlers.success,
            fail: handlers.fail
          });
        },
        publish: (topicId, cb) => {
          const handlers = reply(cb);
          sendUiStateRequest(app, '/api/topic/publish-draft', {
            method: 'POST',
            data: Object.assign({ id: topicId, scope: this.data.operationScope }, publicationVersions),
            success: handlers.success,
            fail: handlers.fail
          });
        }
      });
    }
    return this._serverDraftSaver;
  },

  // 复用广场发布的意图键(utils/publish/publish-intent):按「账号 + 本机草稿」隔离,payload 固定为草稿号,
  // 所以内容改了也沿用同一个键 —— 同一份本机草稿只对应一份服务端草稿;拿到主题号即结算。
  _draftCreateIntentScope() { return 'topic_draft_create:' + (this.data.draftUuid || 'none'); },
  _draftCreateIntentKey() {
    const memberId = this.data.draftMemberId || app.getUserID();
    return publishIntent.begin(wx, { scope: this._draftCreateIntentScope(), memberId, payload: [this.data.draftUuid || ''] }).key;
  },

  // 「保存草稿」:存进服务端但不发布、不送审、不占在架额度;存好并回读确认后留在编辑页
  // options.automatic:两秒防抖触发的自动保存 —— 不弹提示、不盖加载层、不弹结果面板,只更新标题旁状态
  saveServerDraft(options) {
    const automatic = !!(options && options.automatic === true); // bindtap 传进来的是事件对象
    if (this.data.submitting || this._serverDraftBusy()) return;
    // 已发布的主题不走草稿(编辑窗口与下架规则不变);回填没到位也不存,免得拿空表单覆盖
    if (this.data.editingTopicId && (!this.data.editLoaded || !this.data.editIsDraft)) return;
    // 原保存的恢复只使用冻结输入；后来未填完的标题/配置不能阻止查询。
    if (this._serverDraftAttempt && !automatic) {
      this._runServerDraft(null, false, false);
      return;
    }
    if (!String(this.data.formData.name || '').trim()) {
      if (!automatic) cyToast('先给主题起个名字，再保存草稿');
      return;
    }
    if (automatic && this._cloudAutosaveBlocked()) return;
    this._cancelCloudDraftAutosave();
    if (!automatic) this._cloudAutosavePaused = false; // 用户明确保存 = 重新放行自动保存
    const apiData = this._buildSubmitPayload(automatic ? { draft: true, quiet: true } : { draft: true });
    if (!apiData) return;
    if (automatic && this._cloudSavedKey && JSON.stringify(apiData) === this._cloudSavedKey) {
      this._cloudAutosaveQueued = false;
      this._setSaveState('saved');
      return;
    }
    this._runServerDraft(apiData, false, automatic);
  },

  _publishServerDraft(apiData) {
    this._runServerDraft(apiData, true);
  },

  _runServerDraft(apiData, publish, automatic) {
    if (this._draftStorageUnavailable || this._draftScopeUnconfirmed) return;
    if (this._unloaded || !app.getUserID() || (this.data.draftMemberId
        && String(this.data.draftMemberId) !== String(app.getUserID()))) return;
    const recoveringSave = !!this._serverDraftAttempt;
    if (recoveringSave) {
      if (!isRecord(this._serverDraftAttempt.payload)) {
        this._onServerDraftFailed({ stage: 'save', unknown: true, msg: '原保存输入无法读取，已保留待确认记录' },
          publish, this.data.draftMemberId || app.getUserID(), automatic);
        return;
      }
      apiData = JSON.parse(JSON.stringify(this._serverDraftAttempt.payload));
      publish = false; // 先确认原保存；后续发布需用已确认版本另行发起。
    } else if (!this._publishAttempt) {
      const frozen = JSON.parse(JSON.stringify(apiData));
      try { if (!this.data.editingTopicId) frozen.requestId = this._draftCreateIntentKey(); }
      catch (error) { this._markDraftStorageUnavailable(); return; }
      this._serverDraftAttempt = { status: 'UNKNOWN', topicId: String(this.data.editingTopicId || ''), payload: frozen };
      if (!this._persistDraftEnvelope()) { this._serverDraftAttempt = null; return; }
      apiData = frozen;
    }
    const previous = this._draftIdentity();
    const memberId = this.data.draftMemberId || app.getUserID();
    const scope = this.data.operationScope;
    const draftUuid = this.data.draftUuid;
    const baseRevision = this.data.baseRevision;
    const editLoadToken = this._editLoadToken;
    const draftRevision = this._draftContentRevision();
    let activeTopicId = String(this.data.editingTopicId || '');
    const current = () => !this._unloaded && String(memberId) === String(app.getUserID() || '')
      && String(memberId) === String(this.data.draftMemberId || app.getUserID() || '')
      && scope === this.data.operationScope && draftUuid === this.data.draftUuid
      && baseRevision === this.data.baseRevision && editLoadToken === this._editLoadToken
      && activeTopicId === String(this.data.editingTopicId || '');
    const authoredPayload = Object.assign({}, apiData);
    delete authoredPayload.requestId;
    const submittedKey = JSON.stringify(authoredPayload);
    const saver = this.getServerDraftSaver();
    this._cancelCloudDraftAutosave();
    this._cloudAutosaveQueued = false; // 这次请求已带上排队中的修改;之后的编辑会重新排队
    this.setData({ draftSaving: !publish, submitting: publish, submitError: '',
      themeSaveState: 'saving', themeSaveLabel: SAVE_STATE_LABELS.saving });
    if (!automatic) cyLoading.show(publish ? '正在保存并发布' : '正在保存草稿');
    const handlers = {
      isCurrent: current,
      recoverSave: recoveringSave,
      recoveryTopicId: recoveringSave && this._serverDraftAttempt.topicId,
      // 只用于保存草稿:回读确认时本机内容已和提交的不一样,就不换上回读版本
      hasChanges: publish ? undefined : () => this._cloudDraftKey() !== submittedKey
        || Object.keys(this._routeRuleDrafts || {}).length > 0,
      onSaved: (topicId) => {
        activeTopicId = String(topicId);
        if (this._serverDraftAttempt) this._serverDraftAttempt.topicId = activeTopicId;
        if (String(this.data.editingTopicId) === activeTopicId) return;
        const intent = publishIntent.pending(wx, this._draftCreateIntentScope(), memberId);
        if (intent) publishIntent.settle(wx, this._draftCreateIntentScope(), memberId, intent.key, 'success');
        // 服务端已经建好这份草稿:之后的保存都 update 同一个主题 —— 回读失败后重试也不会再建一份
        this.setData({ editingTopicId: String(topicId), editIsDraft: true, editLoaded: true });
        this._persistDraftEnvelope();
      },
      onSuccess: (result) => {
        this._serverDraftAttempt = null;
        if (!automatic) cyLoading.hide();
        // 发布,或上次发布结果不确定、这次查到其实已发布:都按已发布收口
        if (publish || result.published) {
          this._onServerDraftPublished(Object.assign({}, result, {
            localChanges: result.localChanges || draftRevision !== this._draftContentRevision()
          }), previous, memberId);
          return;
        }
        // 用回读到的版本替换编辑器:章节、站点拿到真实编号,章节规则才能引用它们
        this._adoptServerDraft = { previous, memberId };
        this._draftAutosaveReady = false;
        this.applyEditingTopic(result.detail);
        // 「已存云端」只在语义回读通过、且换上回读版本后才标;记下此刻内容,之后没改就不再重复上传
        this._cloudSavedKey = this._cloudDraftKey();
        this.setData({ draftSaving: false, submitError: '',
          themeSaveState: 'saved', themeSaveLabel: SAVE_STATE_LABELS.saved });
        if (automatic) return;
        this._resultSheetNext = '';
        this.setData({
          resultSheet: { show: true, kind: 'success', title: '草稿已保存到云端',
            sub: '已存草稿，可在「我的项目」继续编辑', meta: '',
            why: '', duration: RESULT_SHEET_MS }
        });
      },
      onFail: (res) => {
        if (!automatic) cyLoading.hide();
        if (res && (res.localChanges || res.stage === 'publish' && res.detail
            || res.stage === 'save' && !res.unknown && !res.networkError)) {
          this._serverDraftAttempt = null;
          this._persistDraftEnvelope();
        } else if (this._serverDraftAttempt && res && res.stage === 'readback') {
          res = Object.assign({}, res, { unknown: true });
        }
        if (res && res.localChanges) {
          this._onServerDraftSuperseded(res, previous, automatic);
          return;
        }
        this._onServerDraftFailed(Object.assign({}, res, {
          localChanges: draftRevision !== this._draftContentRevision()
        }), publish, memberId, automatic);
      }
    };
    const started = publish ? saver.saveAndPublish(apiData, handlers) : saver.save(apiData, handlers);
    if (!started) {
      if (!automatic) cyLoading.hide();
      this.setData({ draftSaving: false, submitting: false });
      this._setSaveState('local');
    }
  },

  // 保存在途时又改了内容:云端已确认的是上一版。不换回读版本(保住新编辑、弹窗内容与模板绑定),
  // 只接上服务端为这次写入推进的版本号,下一次自动保存才不会被自己的上一版当成 CAS 冲突。
  _onServerDraftSuperseded(res, previous, automatic) {
    const topic = (res.detail && res.detail.topic) || {};
    this.setData({ draftSaving: false, submitting: false, submitError: '',
      'formData.configVersion': topicRouteGraph.normalizeTopicRoute(topic).configVersion || this.data.formData.configVersion,
      baseRevision: topic.updateTime || topic.createTime || this.data.baseRevision });
    // 新内容已按主题号落本机后,首建时那份按 draftUuid 存的旧恢复稿才可以清
    if (this._persistDraftEnvelope() && previous && previous.draftUuid && this.data.editingTopicId) {
      try { proEditorDraft.removeDraft(wx, previous, { memberId: this.data.draftMemberId || app.getUserID(), operationScope: this.data.operationScope }); } catch (error) {}
    }
    if (!automatic) cyToast('已存上一版，后续修改会自动保存');
    this._cloudAutosaveQueued = true;
    this._setSaveState('local');
    this._queueCloudDraftAutosave();
  },

  _onServerDraftFailed(res, publish, memberId, automatic) {
    if (res.errorCode && /^(ATTRIBUTE_|STATE_KEY_)/.test(res.errorCode)) {
      this._attributeRegistryWriteReady = false;
      this.setData({ attributeRegistrySnapshot: null,
        attributeRegistryError: res.msg || '属性定义已变化，请重新读取并确认差异' });
    }
    const creating = !this.data.editingTopicId;
    const reason = res.unknown
      ? (res.stage === 'publish'
        ? '网络中断，暂时确认不了是否已发布；稍后重试会先查询真实状态'
        : creating ? '网络中断，暂时确认不了是否已保存；已保留原请求和内容，请勿另建草稿'
          : (res.msg ? res.msg + '；' : '') + '暂时确认不了原保存结果；重试只核对原主题，当前修改仍在本机')
      : res.networkError ? '网络暂时不可用，当前内容仍在本机，请重试'
        : (res.msg || '保存失败，当前内容已保留，请重试');
    // 存进去了、只是发布这一步没成:先换上回读到的版本(拿到真实编号),本机恢复稿此时才可以清
    if (res.stage === 'publish' && res.detail && res.unknown === false) {
      this._publishAttempt = null;
      if (res.localChanges) {
        // 发布被拒不撤销之前已成功的保存；只接上版本，不能换掉在途新编辑。
        const topic = res.detail.topic || {};
        this.setData({
          'formData.configVersion': topicRouteGraph.normalizeTopicRoute(topic).configVersion || this.data.formData.configVersion,
          baseRevision: topic.updateTime || topic.createTime || this.data.baseRevision
        });
        this._persistDraftEnvelope();
      } else {
        this._adoptServerDraft = { previous: null, memberId };
        this._draftAutosaveReady = false;
        this.applyEditingTopic(res.detail);
      }
    }
    // 发布阶段失败但草稿已确认存入(上面换上了回读版本)才算已存云端;结果不明确时标待确认
    const savedToCloud = res.stage === 'publish' && res.detail && res.unknown === false && !res.localChanges;
    const saveState = savedToCloud ? 'saved'
      : (res.unknown || res.stage === 'readback' && res.networkError) ? 'unknown' : 'failed';
    if (savedToCloud) this._cloudSavedKey = this._cloudDraftKey();
    if (automatic) {
      // 自动保存失败不弹面板、不占用「重试=发布」的页内错误条。确定失败(校验 / CAS / 回读不一致)
      // 暂停自动保存并只提示一次,等用户明确保存;网络失败或结果不明在下次编辑后用同一身份再存。
      this.setData({ draftSaving: false, submitting: false });
      this._setSaveState(saveState);
      if (!res.unknown && !res.networkError) {
        this._cloudAutosavePaused = true;
        cyToast('自动保存未成功：' + reason);
      }
      return;
    }
    const issue = res.stage === 'save' && !res.networkError && publishIssueTarget.fromMessage(reason,
      this.data.formData.chapters, this._templateDetailsById);
    this.setData({ draftSaving: false, submitting: false, submitError: reason,
      themeSaveState: saveState, themeSaveLabel: SAVE_STATE_LABELS[saveState] });
    if (issue) {
      this._showBackendPublishIssue(issue);
      return;
    }
    const sheet = res.stage === 'publish'
      ? (res.unknown
        ? { title: '发布结果待确认', sub: '重试只查询原主题的发布结果', meta: '本机内容已保留' }
        : res.localChanges
          ? { title: '本次发布未成功', sub: '提交后的修改仍在本机，请保存后重试', meta: '草稿仍在本机' }
          : { title: '草稿已保存，但还没有发布', sub: '草稿已保存，可重试', meta: '' })
      : res.stage === 'readback'
        ? { title: '保存结果待确认', sub: '原保存输入和当前修改均已保留，请再次核对', meta: '草稿仍在本机' }
        : res.unknown
          ? { title: '保存结果待确认', sub: '草稿已存本机，可重试', meta: '草稿仍在本机' }
          : publish
            ? { title: '这次还没有发布出去', sub: '草稿已存本机，可重试', meta: '草稿仍在本机' }
            : { title: '草稿还没有保存到云端', sub: '修改后重试', meta: '草稿仍在本机' };
    this.setData({
      resultSheet: Object.assign({ show: true, kind: 'fail', why: reason, duration: RESULT_SHEET_MS }, sheet)
    });
  },

  _onServerDraftPublished(result, previous, memberId) {
    this._publishAttempt = null;
    const pub = (result && result.published) || {};
    // 回读的拒审终态不能冒充待审，也不能自动重发发布。
    const rejected = pub.recovered && Number(pub.status) === 2;
    const pendingReview = pub.recovered ? Number(pub.status) === 0
      : !!(pub.commercialAwaitingReview || !pub.published && ['PENDING', 'ESCALATED'].includes(pub.reviewState));
    const reviewNextStep = pub.commercialNextStep || '主题与引用的玩法将一起审核。';
    const rejectionReason = rejected && result.detail && result.detail.topic.reason || '';
    const topicId = result.topicId;
    // 只清已确认的那版；在途新编辑或拒审后待修改的内容继续留在本机。
    const keepDraft = !!(result.localChanges || rejected);
    this._draftAutosaveReady = keepDraft;
    if (keepDraft && !this._persistDraftEnvelope()) {
      this._showUnstoredPublishedChanges(rejected);
      return;
    }
    if (!keepDraft) [previous, { topicId }].forEach((identity) => {
      if (!identity) return;
      try { proEditorDraft.removeDraft(wx, identity, { memberId, operationScope: this.data.operationScope }); } catch (error) {}
    });
    if (!rejected) analytics.track('topic_publish_success', {
      bizType: 'topic',
      bizId: topicId || null,
      properties: { name: this.data.formData.name }
    });
    this._resultSheetNext = rejected ? '' : '/pages/topic/merchantinfo/merchantinfo?topicId=' + topicId
      + (this.data.operationScope ? '&scope=MERCHANT' : '');
    if (!keepDraft) this._cloudSavedKey = '';
    this.setData({
      submitting: false, submitError: '', editIsDraft: false,
      themeSaveState: keepDraft ? 'local' : '', themeSaveLabel: keepDraft ? SAVE_STATE_LABELS.local : '',
      resultSheet: { show: true, kind: rejected ? 'fail' : 'success',
        title: rejected ? '审核未通过' : pendingReview ? '已提交审核' : '城市路线已发布',
        sub: rejected ? '内容仍保留，可以修改后重新提交。'
          : result.localChanges ? '上次保存的版本已发布；后续修改仍在本机，请继续编辑后另行保存。'
            : pendingReview ? reviewNextStep : '草稿已发布，接下来去招商家承接节点、找俱乐部带团。',
        meta: keepDraft ? '草稿仍在本机' : '',
        why: rejectionReason, duration: RESULT_SHEET_MS }
    });
  },

  _showPublishUnknown() {
    const reason = this.data.editingTopicId
      ? '发布结果暂时无法确认，请到项目列表确认原主题。采用已保存版本前不会再次发布，本地草稿已保留。'
      : '发布结果暂时无法确认，请到项目列表确认。为避免重复创建，当前草稿不会再次发布，内容仍在本机。';
    this.setData({ submitting: false, submitError: reason,
      resultSheet: { show: true, kind: 'fail', title: '发布结果待确认',
        sub: '请先确认项目列表', meta: '草稿仍在本机', why: reason, duration: RESULT_SHEET_MS } });
  },

  /* 面板收掉才跳走 —— 落点由各成功路径事先写进 _resultSheetNext。
     只有一处跳转、只有一个时长(面板的 duration),不再有「setTimeout 和面板各算各的」。
     失败与 loading 不设落点,收掉就只是关面板。 */
  closeResultSheet() {
    const next = this._resultSheetNext;
    this._resultSheetNext = '';
    this.setData({ 'resultSheet.show': false });
    if (next) wx.redirectTo({ url: next });
  },

  // 稿 S15/S16 的「为什么会这样」:出错时除了那一句结果,还得能展开看到人能照做的下一步。
  toggleSubmitErrorWhy() { this.setData({ submitErrorWhy: !this.data.submitErrorWhy }); },

  /**
   * 生命周期函数--监听页面显示
   */
  onShow() {
    this._hidden = false;
    this._cancelCloudDraftAutosave();
    if (this._readSessionChanged && this.data.editingTopicId) { this._readSessionChanged = false; this.loadEditingTopic(this.data.editingTopicId); return; }
    if (this._creatorOwnerChanged() || this.data.creatorLoginRequired) return;
    //this.updateStatistics();
    this.syncNodeGameSwitchWithTemplate();
    if (this._editingBoundTemplateId && this.data.popChapterNodes) {
      this._loadNodeTemplateDetail(this.data.nodesForm);
      this._editingBoundTemplateId = 0;
    }
    this._queueCloudDraftAutosave();
  },

  /**
   * CU-C-164:「配置模板」开关的唯一真源是 nodesForm.templateId(开关态不入库,见 onToggleNodeGame)。
   * 从玩法编辑器点「放弃未保存的玩法」返回时没人回传 ⇒ 回来的那一刻 templateId 还是 0,
   * 而 showTemplate 停在 true:开关绿着、下面只给「选择或创建玩法模板」,是个点了必落空的假已配置。
   * 返回本页时按同一判据重派生一次(只在节点抽屉开着时,别顺手改到没在看的表单项)。
   */
  syncNodeGameSwitchWithTemplate() {
    if (!this.data.popChapterNodes) return;
    const nodesForm = this.data.nodesForm || {};
    const on = !!Number(nodesForm.templateId);
    if (on !== !!nodesForm.showTemplate) this.setData({ 'nodesForm.showTemplate': on });
  },

  // 更新统计信息
  updateStatistics() {
    let chapters = this.data.formData.chapters;
    let totalNodes = 0;

    if (chapters) {
      chapters.forEach(chapter => {
        if (chapter.nodes) {
          totalNodes += chapter.nodes.length;
        }
      });
    }

    this.setData({
      totalNodes: totalNodes,
      totalDuration: this.calculateTotalDuration()
    });
  },
  /** 视图是 S 形序,存回去要按格位序 —— 存错序等于九格的奖全对错了格子 */
  _bingoCellsByPosition() {
    const cells = advancedGameConfig.BINGO_S_ORDER.map(() => ({ label: '', couponId: 0, feedbackText: '' }));
    (this.data.bingoRows || []).forEach((row) => {
      cells[row.pos] = { label: row.label, couponId: row.couponId, feedbackText: row.feedbackText };
    });
    return cells;
  },
  _setBingoRow(index, patch) {
    const rows = this.data.bingoRows.map((row, i) => (i === index ? Object.assign({}, row, patch) : row));
    this.setData({ bingoRows: rows, bingoError: '' });
  },
  onBingoLabelInput(e) {
    this._setBingoRow(Number(e.currentTarget.dataset.index), { label: e.detail.value });
  },
  onBingoCouponChange(e) {
    const detail = e.detail || {};
    this._setBingoRow(Number(e.currentTarget.dataset.index), {
      couponId: Number(detail.couponId || 0), couponName: detail.couponName || ''
    });
  },
  onBingoFeedbackInput(e) {
    this._setBingoRow(Number(e.currentTarget.dataset.index), { feedbackText: e.detail.value });
  },


  // 计算总时长
  calculateTotalDuration() {
    return publishStats.totalDurationText(this.data.formData.chapters);
  },

  // 弹框内切换分类选择
  toggleCategorySelect: function (e) {
    var that = this
    let categoryId = e.currentTarget.dataset.id;
    let categoryName = e.currentTarget.dataset.name;
    let selectedIds = this.data.selectedCategoryIds.slice();
    let selectedNames = this.data.selectedCategoryNames.slice();
    let index = selectedIds.indexOf(categoryId);
    let categoryList = that.data.categoryList

    if (index > -1) {
      // 取消选择
      selectedIds.splice(index, 1);
      selectedNames.splice(index, 1);

      for (let i = 0; i < categoryList.length; i++) {
        if (categoryId == categoryList[i]['id']) {
          categoryList[i]['isChecked'] = 0;
        }
      }
    } else {
      // 添加选择
      selectedIds.push(categoryId);
      selectedNames.push(categoryName);

      for (let i = 0; i < categoryList.length; i++) {
        if (categoryId == categoryList[i]['id']) {
          categoryList[i]['isChecked'] = 1;
        }
      }
    }

    this.setData({
      selectedCategoryIds: selectedIds,
      selectedCategoryNames: selectedNames,
      categoryList: categoryList,
    });
  },

  // 显示多选弹框
  showMultiSelectModal: function () {
    this.setData({
      showMultiSelectModal: true
    });
  },

  // 隐藏多选弹框
  hideMultiSelectModal: function () {
    this.setData({
      showMultiSelectModal: false
    });
  },

  // 确认选择
  confirmMultiSelect: function () {
    // 更新表单数据中的categoryIds
    this.setData({
      showMultiSelectModal: false,
      'formData.categoryIds': this.data.selectedCategoryIds
    });

  },

  // 搜索事件处理函数
  handleSearch: function (e) {
    const keyword = e.detail.value; // 获取输入框的值
    this.setData({
      searchKeyword: keyword
    });

    // 执行搜索逻辑
    this.getTempList(keyword);
  },

  retrySupportMatrix() { return this._loadSupportMatrix(true); },
  _loadSupportMatrix(retry = false) {
    if (this.data.supportMatrixLoading || (this._supportMatrixRequested && !retry)) return;
    this._supportMatrixRequested = true;
    this.setData({ supportMatrixLoading: true, supportMatrixLoadError: '' });
    return supportMatrix.load(app, retry).then(matrix => { this.setData({ supportMatrix: matrix, supportMatrixLoading: false,
      supportMatrixLoadError: matrix ? '' : '玩法支持信息未加载，请重试' });
      this._refreshSupportMatrix(); this._syncRuntimeStats(); });
  },
  _nodeSupportContext() { return supportMatrix.context(this.data.formData.themeRuntimeConfig, this.data.nodesForm); },
  _templateSupportReason(template) {
    if (!template || !Object.prototype.hasOwnProperty.call(template, 'advancedConfigJson')) return '';
    return supportMatrix.validate(this.data.supportMatrix, template, this._nodeSupportContext());
  },
  _refreshSupportMatrix() {
    const matrix = this.data.supportMatrix, ctx = this._nodeSupportContext();
    const options = matrix ? nodeGameCatalog.ALL.filter(item =>
      !ctx.settled && !ctx.challenge || ['profile', 'typeIn', 'photoCheck', 'note', 'd20'].includes(item.key)
      || matrix.types.some(row => row.key === item.key && (ctx.challenge
        ? ['SUPPORTED', 'CHALLENGE_ONLY'].includes(row.tools.zeroChallenge) : row.completionProof))) : this.data.storyGameOptions;
    const reasons = list => {
      const out = {};
      (list || []).forEach(item => {
        if (!Object.prototype.hasOwnProperty.call(item, 'advancedConfigJson')) return;
        out[item.id] = supportMatrix.validate(matrix, item, ctx);
      });
      return out;
    };
    const story = {};
    (options || []).forEach(item => {
      story[item.key] = supportMatrix.choice(matrix, item.key, {}, ctx);
    });
    this.setData({ supportNodeChallenge: ctx.challenge, supportNodeSettled: ctx.settled, storyGameOptions: options, storySupportReasons: story,
      publicSupportReasons: reasons(this.data.publicTempList), draftSupportReasons: reasons(this.data.storyDraftList),
      mineSupportReasons: reasons(this.data.tempList), nodeSupportReason: this._templateSupportReason(this.data.nodesForm.templateInfo) });
  },
  _supportPublishIssues() {
    const out = [];
    (this.data.formData.chapters || []).forEach(ch => (ch.nodes || []).forEach(node => {
      const template = (this._templateDetailsById || {})[node.templateId] || node.templateInfo;
      if (!template || !Object.prototype.hasOwnProperty.call(template, 'advancedConfigJson')) return;
      const reason = supportMatrix.validate(this.data.supportMatrix, template,
        supportMatrix.context(this.data.formData.themeRuntimeConfig, node));
      if (reason) out.push({ label: '节点 ' + (node.id || node._localId) + '（' + (node.name || '') + '）：' + reason,
        field: 'templateTool:' + templateBinding.nodeKey(node) + ':', tab: '0' });
    }));
    return out;
  },
  // 选择模板事件
  selectTemp: function (e) {
    const item = e.currentTarget.dataset.item;
    if (!isRecord(item) || !item.id) return;
    this._nodeTemplateLoadToken = (this._nodeTemplateLoadToken || 0) + 1;
    const reason = this._templateSupportReason(item);
    if (reason) { cyToast(reason); return; }
    const tempId = item.id;
    const source = e.currentTarget.dataset.source === 'public' ? 'public' : 'mine';

    if (this.data.selectedTempId === tempId && this.data.selectedTempSource === source) {
      this.setData({
        selectedTempId: null,
        selectedTempSource: '',
        selectedTempInfo: null
      });
    } else {
      this.setData({
        selectedTempId: tempId,
        selectedTempSource: source,
        selectedTempInfo: item
      });
    }
  },

  // 完成按钮事件
  confirmTemp: function () {
    if (!this.data.selectedTempInfo) {
      cyToast('请先选择一个模板');
      return;
    }
    const selectedTemplate = this.data.selectedTempInfo;
    const reason = this._templateSupportReason(selectedTemplate);
    if (reason) { cyToast(reason); return; }
    if (this._insertAlbumTemplate(selectedTemplate)) return;
    if (this.data.selectedTempSource === 'public') {
      this._adoptPublicTemplate(selectedTemplate);
      return;
    }
    this._bindNodeTemplate(selectedTemplate);
  },
  /**
   * 公共库 id ≠ 会员模板 id(复核 A11):直接写进节点,后端归属校验会拒,或两库 id 碰巧重号时绑到别人的模板。
   * 先走既有 /api/template/adopt-draft 采用成「我的草稿」,拿到会员模板 id 再绑。
   * 结果不确定(超时/断网)时不自动重发,避免重复建草稿;请作者去「我的玩法」查回。
   */
  // 复用原节点对象/身份与选择代次；编辑器导航可正常返回，换节点/主题/账号不能接旧结果。
  _nodeTemplateTargetCurrent(editorReturn = false) {
    const node = this.data.nodesForm, key = templateBinding.nodeKey(node);
    const owner = memberId(app), scope = this.data.operationScope, topicId = this.data.editingTopicId;
    const token = this._nodeTemplateLoadToken, epoch = this._loginContinuationEpoch || 0;
    const draftUuid = this.data.draftUuid, editToken = this._editLoadToken;
    const authorization = typeof app.getAuthorization === 'function' ? app.getAuthorization() : '';
    const session = typeof app.getSession === 'function' ? app.getSession() : null;
    const revision = session && typeof session.getRevision === 'function' ? session.getRevision() : null;
    // A child editor emits before navigateBack, while its opener is hidden. Only that explicitly
    // captured navigation may cross one hide; ordinary author reads still require the original epoch.
    return () => !this._unloaded && (editorReturn || !this._hidden)
      && ((this._loginContinuationEpoch || 0) === epoch
        || editorReturn && (this._loginContinuationEpoch || 0) === epoch + 1)
      && owner === memberId(app) && scope === this.data.operationScope
      && authorization === (typeof app.getAuthorization === 'function' ? app.getAuthorization() : '')
      && session === (typeof app.getSession === 'function' ? app.getSession() : null)
      && (revision === null || session.getRevision() === revision)
      && draftUuid === this.data.draftUuid && editToken === this._editLoadToken
      && topicId === this.data.editingTopicId && token === this._nodeTemplateLoadToken
      && node === this.data.nodesForm && key === templateBinding.nodeKey(this.data.nodesForm);
  },
  _adoptPublicTemplate(libraryTemplate) {
    if (this._adoptingPublicTemplate) return;
    const owner = String(app.getUserID() || '');
    const scope = this.data.operationScope || '';
    const sourceId = templateAdoption.id(libraryTemplate.id);
    const error = message => { this.setData({ templateAdoptionError: message }); cyToast(message); };
    if (!owner || !sourceId) { error('模板编号无法精确处理，尚未发起采用'); return; }
    const context = () => JSON.stringify([String(app.getUserID() || ''), this.data.operationScope || '',
      typeof app.getAuthorization === 'function' ? app.getAuthorization() : '',
      this.data.draftUuid, this.data.editingTopicId, this._editLoadToken,
      this.data.nodesForm, this.data.selectedTempSource, this.data.selectedTempId, this._loginContinuationEpoch]);
    const originalContext = context();
    const current = () => !this._hidden && !this._unloaded && context() === originalContext;
    let record;
    try { record = templateAdoption.read(wx, owner, scope, sourceId); }
    catch (invalid) { error(invalid.message); return; }
    if (record) {
      if (record.requestId) { this._recoverTemplateAdoption(record, current); return; }
      if (record.draftId) { this._readAdoptedTemplate(record, current); return; }
      error('旧采用结果待确认，原请求没有可查询的编号；请查看我的玩法，勿重复创建'); return;
    }
    this._adoptingPublicTemplate = true;
    app.sendRequest({ url: '/api/template/adoption-source', method: 'GET',
      data: { originalTemplateId: sourceId, scope },
      success: res => {
        this._adoptingPublicTemplate = false;
        if (!current()) return;
        const source = res && res.code == 200 && res.data;
        if (!source || source.originalTemplateId !== sourceId || !/^[a-f0-9]{64}$/.test(source.sourceVersion || '')) {
          error('来源版本未确认，尚未发起采用'); return;
        }
        const requestId = 'adopt_' + Date.now() + '_' + Math.random().toString(36).slice(2);
        record = { schemaVersion: 1, status: 'UNKNOWN', owner, scope, requestId,
          target: { draftUuid: this.data.draftUuid, topicId: this.data.editingTopicId,
            node: JSON.parse(JSON.stringify(this.data.nodesForm || {})) },
          sourceVersion: source.sourceVersion,
          payload: { originalTemplateId: sourceId, requestId, sourceVersion: source.sourceVersion, scope } };
        try { templateAdoption.write(wx, record); }
        catch (_) { error('本地恢复记录保存失败，尚未发起采用'); return; }
        this._submitTemplateAdoption(record, current);
      },
      fail: () => { this._adoptingPublicTemplate = false; if (current()) error('来源版本读取失败，尚未发起采用'); }
    });
  },
  _submitTemplateAdoption(record, current) {
    if (this._adoptingPublicTemplate || !current()) return;
    this._adoptingPublicTemplate = true;
    this.setData({ templateAdoptionError: '' });
    app.sendRequest({ url: '/api/template/adopt-draft', method: 'POST',
      header: { 'Content-Type': 'application/json' }, data: JSON.stringify(record.payload),
      success: res => {
        this._adoptingPublicTemplate = false;
        if (current()) this._acceptTemplateAdoptionReceipt(record, res, current);
      },
      fail: () => {
        this._adoptingPublicTemplate = false;
        if (current()) this.setData({ templateAdoptionError: '采用结果待确认；再次选择此玩法将查询原请求，不会另建请求' });
      }
    });
  },
  _recoverTemplateAdoption(record, current) {
    if (this._adoptingPublicTemplate || !current()) return;
    this._adoptingPublicTemplate = true;
    app.sendRequest({ url: '/api/template/adoption-receipt', method: 'GET',
      data: { requestId: record.requestId, scope: record.scope },
      success: res => {
        this._adoptingPublicTemplate = false;
        if (!current()) return;
        const facts = res && res.code == 200 && res.data;
        if (facts && facts.requestId === record.requestId && ['UNKNOWN', 'PENDING'].includes(facts.status)) {
          // Explicit retry resumes the SAME durable command. A negative query never creates a new intent.
          this._submitTemplateAdoption(record, current); return;
        }
        this._acceptTemplateAdoptionReceipt(record, res, current);
      },
      fail: () => { this._adoptingPublicTemplate = false; if (current()) this.setData({ templateAdoptionError: '原请求查询失败，恢复记录保留' }); }
    });
  },
  _acceptTemplateAdoptionReceipt(record, res, current) {
    const facts = res && res.code == 200 && res.data;
    if (!facts || facts.requestId !== record.requestId || facts.sourceVersion !== record.sourceVersion
        || facts.originalTemplateId !== String(record.payload.originalTemplateId)) {
      this.setData({ templateAdoptionError: '原请求结果未确认，原稿及恢复记录保留' }); return;
    }
    if (facts.status === 'REJECTED') {
      try { templateAdoption.clear(wx, record); } catch (_) { /* Re-read the same rejection. */ }
      this.setData({ templateAdoptionError: '原采用已拒绝：' + (facts.reason === 'SOURCE_VERSION_CONFLICT' ? '来源已改变或下架' : '来源内容、玩法能力或资产资格未通过校验') + '。再次选择会重新确认来源版本' }); return;
    }
    const draftId = templateAdoption.id(facts.draftId);
    if (facts.status !== 'COMPLETED' || !draftId) {
      this.setData({ templateAdoptionError: '采用尚未确认完成，恢复记录保留' }); return;
    }
    record.draftId = draftId;
    try { templateAdoption.write(wx, record); }
    catch (_) { this.setData({ templateAdoptionError: '结果未能保存，请保留页面后按原请求重查' }); return; }
    this._readAdoptedTemplate(record, current);
  },
  _readAdoptedTemplate(record, current) {
    if (this._adoptingPublicTemplate) return;
    this._adoptingPublicTemplate = true;
    const failed = message => this.setData({ templateAdoptionError: message });
    const handlers = { hideLoading: true,
      success: detail => {
        this._adoptingPublicTemplate = false;
        if (!current()) return;
        if (!detail || detail.code != '200' || !isRecord(detail.data)
            || templateAdoption.id(detail.data.id) !== record.draftId
            || !Object.prototype.hasOwnProperty.call(detail.data, 'advancedConfigJson')) {
          failed('草稿详情未确认，原稿未改变；再次选择此公共玩法只回读原草稿'); return;
        }
        const reason = this._templateSupportReason(detail.data);
        if (reason) { failed('已存为草稿；' + reason); return; }
        if (this._bindNodeTemplate(detail.data)) {
          try { templateAdoption.clear(wx, record); } catch (_) { /* Retrying reads the same known draft. */ }
          this.setData({ templateAdoptionError: '' });
          cyToast('已存为我的玩法草稿并绑定');
        }
      },
      fail: () => {
        this._adoptingPublicTemplate = false;
        if (current()) failed('草稿详情读取失败，原稿未改变；再次选择此公共玩法只回读原草稿');
      }
    };
    if (record.requestId) app.sendRequest({ ...handlers, url: '/api/template/adoption-detail', method: 'GET',
      data: { requestId: record.requestId, scope: record.scope } });
    else app.sendRequest({ ...handlers, url: '/api/template/author-myinfo', method: 'POST',
      data: { id: record.draftId, scope: record.scope } });
  },
  inspectAdoptedTemplates() {
    this.getStoryDraftList();
    this.setData({ nodeSheetView: 'games' });
  },
  _bindNodeTemplate(selectedTemplate) {
    if (!Object.prototype.hasOwnProperty.call(selectedTemplate, 'advancedConfigJson')) {
      if (this._loadingSelectedTemplate === templateAdoption.id(selectedTemplate.id)
          && this._loadingSelectedTemplateCurrent && this._loadingSelectedTemplateCurrent()) return;
      this._loadingSelectedTemplate = templateAdoption.id(selectedTemplate.id);
      const token = (this._nodeTemplateLoadToken || 0) + 1;
      this._nodeTemplateLoadToken = token;
      const targetCurrent = this._nodeTemplateTargetCurrent(), epoch = this._loginContinuationEpoch;
      const current = () => targetCurrent() && epoch === this._loginContinuationEpoch;
      this._loadingSelectedTemplateCurrent = current;
      const that = this;
      app.sendRequest({ url: '/api/template/author-myinfo', method: 'POST', hideLoading: true, silentError: true,
        data: { id: selectedTemplate.id, scope: this.data.operationScope },
        success(res) {
          if (token === that._nodeTemplateLoadToken) that._loadingSelectedTemplate = 0;
          if (!current()) return;
          if (res && res.code == '200' && isRecord(res.data) && templateAdoption.id(res.data.id) === templateAdoption.id(selectedTemplate.id))
            that._bindNodeTemplate(res.data);
          else cyToast('玩法详情未加载，请重新选择');
        },
        fail() { if (token === that._nodeTemplateLoadToken) that._loadingSelectedTemplate = 0;
          if (current()) cyToast('玩法详情未加载，请重新选择'); },
      });
      return;
    }
    const reason = this._templateSupportReason(selectedTemplate);
    if (reason) { cyToast(reason); return false; }
    let slots;
    try { slots = templateBinding.slotsOf(selectedTemplate); }
    catch (error) { cyToast('玩法资源声明无法读取'); return; }
    const saved = (templateBinding.parse(this.data.formData.themeRuntimeConfig).templateBindings || {})[
      templateBinding.nodeKey(this.data.nodesForm)];
    const previous = this._nodeBindingDraft || saved;
    const previousSlots = (this._nodeBindingSlots || []).length ? this._nodeBindingSlots
      : previous ? Object.keys(previous.values || {}).map(key => ({ key, kind: 'VALUE' }))
        .concat(Object.keys(previous.items || {}).map(key => ({ key, kind: 'ITEM' }))) : [];
    const binding = slots.length ? templateBinding.reconcile(slots, selectedTemplate.id, previous, previousSlots,
      this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig, selectedTemplate) : null;
    const currentNode = Object.assign({}, this.data.nodesForm, { templateId: selectedTemplate.id });
    this._nodeBindingSlots = slots;
    this._nodeBindingDraft = binding;
    this.setData({
      nodeSheetView: 'detail', nodeSupportReason: '',
      'nodesForm.templateId': selectedTemplate.id,
      'nodesForm.templateInfo': selectedTemplate,
      'nodesForm.showTemplate': true,
      nodeBindingRows: slots.length ? templateBinding.rows(slots, binding,
        this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig, selectedTemplate) : [],
      nodeBindingError: '', nodeBindingErrorKey: '',
      nodeSharedSites: templateBinding.sharedSites(this.data.formData.chapters, selectedTemplate.id, currentNode),
      nodeCopyUncertain: false, nodeCopyError: '',
      // 还没起名就用玩法名(2026-09-23 走查:选回草稿后名字为空,点「确定」被拦);作者起过的不覆盖
      'nodesForm.name': String(this.data.nodesForm.name || '').trim() || selectedTemplate.title || '',
    });
    this._openNodePlanEndings(selectedTemplate, previous);
    return true;
  },
  _openNodeBinding(node) {
    this.setData({ nodeSupportReason: supportMatrix.validate(this.data.supportMatrix, node.templateInfo || {},
      supportMatrix.context(this.data.formData.themeRuntimeConfig, node)) });
    let slots = [];
    try { slots = templateBinding.slotsOf(node.templateInfo); } catch (_) { /* 服务端详情稍后回读 */ }
    const runtime = templateBinding.parse(this.data.formData.themeRuntimeConfig);
    const old = (runtime.templateBindings || {})[templateBinding.nodeKey(node)];
    const binding = slots.length ? templateBinding.reconcile(slots, node.templateId, old, slots,
      this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig, node.templateInfo) : null;
    this._nodeBindingSlots = slots;
    this._nodeBindingDraft = binding;
    this.setData({ nodeSharedSites: templateBinding.sharedSites(this.data.formData.chapters, node.templateId, node),
      nodeBindingRows: slots.length ? templateBinding.rows(slots, binding,
        this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig, node.templateInfo) : [],
      nodeBindingError: '', nodeBindingErrorKey: '' });
    this._openNodePlanEndings(node.templateInfo || {}, old);
  },
  onEditBoundTemplate(focus) {
    const id = templateAdoption.id(this.data.nodesForm.templateId);
    if (!id) return;
    this._editingBoundTemplateId = id;
    const focusQuery = focus && focus.tool ? '&focusTool=' + encodeURIComponent(focus.tool)
      + '&focusPlan=' + encodeURIComponent(focus.planKey || '')
      + '&focusMessage=' + encodeURIComponent(focus.message || '') : '';
    wx.navigateTo({ url: '/pages/publish/temp/index?from=fabu&id=' + id
      + (this.data.operationScope === 'MERCHANT' ? '&scope=MERCHANT&templateScope=my' : '&scope=my')
      + '&runtimeEndings=' + encodeURIComponent(JSON.stringify((this._runtimeConfig().endings || []).map(row => ({ id: row.id, title: row.title })))) + checkContext.query(this.data.formData.journeyRules)
      + (this.data.supportMatrix ? '&supportContext=' + encodeURIComponent(JSON.stringify(this._nodeSupportContext())) : '') + focusQuery });
  },
  onCopyBoundTemplate() {
    const node = this.data.nodesForm;
    const sourceId = templateAdoption.id(node && node.templateId);
    if (this._copyingBoundTemplate || this.data.nodeCopyUncertain || !sourceId
      || (this.data.nodeSharedSites || []).length < 2) return;
    this._copyingBoundTemplate = true;
    this.setData({ nodeCopyBusy: true, nodeCopyError: '' });
    const nodeKey = templateBinding.nodeKey(node);
    const that = this;
    app.sendRequest({ url: '/api/template/author-myinfo', method: 'POST', hideLoading: true, silentError: true,
      data: { id: sourceId, scope: this.data.operationScope },
      success(detail) {
        const source = detail && detail.code == '200' && isRecord(detail.data)
          && templateAdoption.id(detail.data.id) === sourceId && typeof detail.data.title === 'string'
          && Object.prototype.hasOwnProperty.call(detail.data, 'advancedConfigJson') ? detail.data : null;
        if (!source) {
          that._copyingBoundTemplate = false;
          that.setData({ nodeCopyBusy: false, nodeCopyError: '玩法详情未加载，请重试' });
          return;
        }
        if (that.data.nodesForm !== node || templateAdoption.id(that.data.nodesForm.templateId) !== sourceId) {
          that._copyingBoundTemplate = false;
          that.setData({ nodeCopyBusy: false });
          return;
        }
        const reason = that._templateSupportReason(source);
        if (reason) {
          that._copyingBoundTemplate = false;
          that.setData({ nodeCopyBusy: false, nodeCopyError: reason });
          return;
        }
        const payload = templateAuthorFields.copyPayload(source, that.data.operationScope);
        app.sendRequest({ url: '/api/template/draft', method: 'POST', hideLoading: true, silentError: true,
          header: { 'Content-Type': 'application/json' }, data: JSON.stringify(payload),
          success(saved) {
            that._copyingBoundTemplate = false;
            const newId = saved && saved.code == '200' ? Number(saved.data) : 0;
            if (!Number.isSafeInteger(newId) || newId <= 0) {
              that.setData({ nodeCopyBusy: false,
                nodeCopyError: saved && saved.code == '200' ? '保存结果未确认，请到我的玩法确认' : app.getRequestErrorMessage(saved, '另存副本失败'),
                nodeCopyUncertain: !!(saved && saved.code == '200') });
              return;
            }
            if (that.data.nodesForm !== node || templateAdoption.id(that.data.nodesForm.templateId) !== sourceId
              || templateBinding.nodeKey(that.data.nodesForm) !== nodeKey) {
              that.setData({ nodeCopyBusy: false, nodeCopyUncertain: true,
                nodeCopyError: '副本已保存，请到我的玩法确认后重新绑定' });
              return;
            }
            const copy = Object.assign({}, source, { id: newId, status: 0 });
            that._templateDetailsById = Object.assign({}, that._templateDetailsById, { [newId]: copy });
            if (!that._bindNodeTemplate(copy)) {
              that.setData({ nodeCopyBusy: false, nodeCopyUncertain: true, nodeCopyError: '副本已保存，请到我的玩法确认；' + that._templateSupportReason(copy) });
            } else that.setData({ nodeCopyBusy: false });
            that.getStoryDraftList();
          },
          fail() {
            that._copyingBoundTemplate = false;
            that.setData({ nodeCopyBusy: false, nodeCopyUncertain: true,
              nodeCopyError: '保存结果未确认，请先到我的玩法确认，避免重复创建' });
          }
        });
      },
      fail() {
        that._copyingBoundTemplate = false;
        that.setData({ nodeCopyBusy: false, nodeCopyError: '玩法详情未加载，请重试' });
      }
    });
  },
  onNodeBindingPick(e) {
    const slot = (this._nodeBindingSlots || [])[Number(e.currentTarget.dataset.index)];
    const option = slot && templateBinding.optionsFor(slot.kind, this.data.formData.journeyRules,
      this.data.formData.themeRuntimeConfig, this.data.nodesForm.templateInfo, slot.key)[Number(e.detail.value)];
    if (!option) return;
    try {
      const binding = templateBinding.bind(this._nodeBindingDraft, this._nodeBindingSlots, slot.key,
        option.value, this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig,
        this.data.nodesForm.templateInfo);
      this._nodeBindingDraft = binding;
      this.setData({ nodeBindingRows: templateBinding.rows(this._nodeBindingSlots,
        binding, this.data.formData.journeyRules, this.data.formData.themeRuntimeConfig,
        this.data.nodesForm.templateInfo), nodeBindingError: '', nodeBindingErrorKey: '' });
    } catch (error) { this.setData({ nodeBindingError: error.message, nodeBindingErrorKey: slot.key }); }
  },
  _commitNodeTemplateBinding(node) {
    const runtime = this._runtimeConfig();
    const key = templateBinding.nodeKey(node);
    if (!key) return;
    const hasPlanEndings = Object.keys(this._nodeBindingDraft?.planEndings || {}).length > 0;
    if (!this._nodeBindingSlots.length && !hasPlanEndings && !(runtime.templateBindings && runtime.templateBindings[key])) return;
    const bindings = Object.assign({}, runtime.templateBindings || {});
    if ((this._nodeBindingSlots.length || hasPlanEndings) && this._nodeBindingDraft && templateAdoption.id(node.templateId))
      bindings[key] = this._nodeBindingDraft;
    else delete bindings[key];
    if (Object.keys(bindings).length) runtime.templateBindings = bindings;
    else delete runtime.templateBindings;
    this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(runtime) });
  },
  /* 删除站点时把它从章节规则(互斥组 / 额外站点)里明确移出:不留悬空编号让保存被拒,也不改指别的站点。
     返回被改动章节的原值供撤销恢复;同时给出一句「从哪些规则里移除了」的说明。 */
  _removeNodeRuleRefs(node) {
    const key = templateBinding.nodeKey(node);
    if (!key || !this.data.formData.themeRuntimeConfig) return null;
    const runtime = templateBinding.parse(this.data.formData.themeRuntimeConfig);
    const same = ref => String(ref) === key || (Number(ref) > 0 && String(Number(ref)) === key);
    const before = [], notes = [];
    (runtime.chapters || []).forEach(ch => {
      const groups = ch.exclusiveGroups || [], extras = ch.extraNodes || [];
      const inGroup = groups.some(group => group.some(same)), inExtra = extras.some(item => same(item.nodeId));
      if (!inGroup && !inExtra) return;
      before.push({ index: ch.index, exclusiveGroups: ch.exclusiveGroups, extraNodes: ch.extraNodes });
      if (inGroup) {
        ch.exclusiveGroups = groups.map(group => group.filter(ref => !same(ref))).filter(group => group.length >= 2);
        if (!ch.exclusiveGroups.length) delete ch.exclusiveGroups;
        notes.push('第' + (ch.index + 1) + '章互斥选项');
      }
      if (inExtra) {
        ch.extraNodes = extras.filter(item => !same(item.nodeId));
        if (!ch.extraNodes.length) delete ch.extraNodes;
        notes.push('第' + (ch.index + 1) + '章额外节点');
      }
    });
    // 运行结构里属于这一站的东西一并移出:任务(含幕的任务列表与前置引用)、玩法工具为它生成的设施与方案、
    // 以这一站为操作点的设施。否则保存时服务端会因引用已删站点拒收;撤销时整段运行结构原样放回。
    const game = runtime.gameplayRuntime;
    let gameBefore = null;
    if (game) {
      const nodeId = Number(node && node.id);
      const facilityHit = f => f.facilityKey === 'tpl_node_' + nodeId || same(f.operationNodeId);
      const toolRow = ((game.templateTools || {}).nodes || []).find(row => same(row.nodeId));
      if ((game.quests || []).some(q => same(q.nodeId)) || (game.facilities || []).some(facilityHit) || toolRow) {
        gameBefore = JSON.parse(JSON.stringify(game));
        const gone = new Set((game.quests || []).filter(q => same(q.nodeId)).map(q => q.questKey));
        const goneFacilities = new Set((game.facilities || []).filter(facilityHit).map(f => f.facilityKey));
        game.quests = (game.quests || []).filter(q => !gone.has(q.questKey)).map(q => {
          const next = q.prerequisites ? Object.assign({}, q, { prerequisites: q.prerequisites.filter(k => !gone.has(k)) }) : Object.assign({}, q);
          if (goneFacilities.has(next.facilityKey)) delete next.facilityKey;
          return next;
        });
        game.acts = (game.acts || []).map(act => Object.assign({}, act, { questKeys: (act.questKeys || []).filter(k => !gone.has(k)) }));
        if (game.facilities) game.facilities = game.facilities.filter(f => !goneFacilities.has(f.facilityKey));
        if (toolRow) {
          const plans = new Set(toolRow.planKeys || []);
          if (game.plans) game.plans = game.plans.filter(row => !plans.has(row.endingKey));
          game.templateTools.nodes = game.templateTools.nodes.filter(row => row !== toolRow);
          if (plans.size) notes.push('该节点玩法生成的表决方案');
        }
        if (goneFacilities.size) notes.push('该节点的设施');
      }
    }
    if (!before.length && !gameBefore) return null;
    this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(runtime) });
    return { before, gameBefore, note: notes.length ? '已从' + notes.join('、') + '中移除这个节点' : '' };
  },
  _removeNodeTemplateBinding(node) {
    const runtime = templateBinding.parse(this.data.formData.themeRuntimeConfig);
    const key = templateBinding.nodeKey(node);
    if (!key || !runtime.templateBindings || !runtime.templateBindings[key]) return null;
    const binding = runtime.templateBindings[key];
    delete runtime.templateBindings[key];
    if (!Object.keys(runtime.templateBindings).length) delete runtime.templateBindings;
    this.setData({ 'formData.themeRuntimeConfig': JSON.stringify(runtime) });
    return binding;
  },
  _loadNodeTemplateDetail(node) {
    const id = templateAdoption.id(node.templateId);
    if (!id) return;
    this._templateDetailRequested = Object.assign({}, this._templateDetailRequested, { [id]: true });
    const that = this;
    app.sendRequest({ url: '/api/template/author-myinfo', method: 'POST', hideLoading: true, silentError: true,
      data: { id, scope: this.data.operationScope },
      success(res) {
        if (!(res && res.code == '200' && isRecord(res.data) && templateAdoption.id(res.data.id) === id)) return;
        that._templateDetailsById = Object.assign({}, that._templateDetailsById, { [id]: res.data });
        const open = that.data.nodesForm;
        if (open && templateAdoption.id(open.templateId) === id && (!node.id && !node._localId
          || templateBinding.nodeKey(open) === templateBinding.nodeKey(node))) {
          that.setData({ 'nodesForm.templateInfo': res.data });
          that._openNodeBinding(Object.assign({}, open, { templateInfo: res.data }));
        }
        if (that.data.supportMatrix) that._syncRuntimeStats();
        that.refreshPrimaryActionState();
      },
      fail() { if (templateAdoption.id(that.data.nodesForm.templateId) === id) that.setData({ nodeBindingError: '玩法详情未加载，请重新打开节点' }); },
    });
  },
  /**
   * 本站玩法开关。关 = 纯到达打卡(走到即完成)。
   * 开关态不入库(NodeDTO 没有 showTemplate),开抽屉时由 templateId 派生,
   * 因此关掉时必须把 templateId/templateInfo/templateName 一并清空 ——
   * 否则重开抽屉会被派生回"开",看着像开关没保存。
   */
  onToggleNodeGame: function (e) {
    if (this.data.nodesForm._storyGame) return;
    const on = !!(e && e.detail && e.detail.value);
    if (on) {
      this.setData({ 'nodesForm.showTemplate': true });
      // 2026-10-12 用户定(推翻 2026-08-20 那条「直接进模板编辑器」):开「配置模板」打开本弹层里的
      // 玩法库视图 —— 底部横滑卡片选已保存的模板(公共库 / 我的草稿 / 我的模板),要新建再从那里进编辑器。
      if (!templateAdoption.id(this.data.nodesForm.templateId)) this.showTemp();
      return;
    }
    this._nodeBindingSlots = [];
    this._nodeBindingDraft = null;
    this.setData({
      'nodesForm.showTemplate': false,
      'nodesForm.templateId': 0,
      'nodesForm.templateInfo': {},
      'nodesForm.templateName': '',
      nodeBindingRows: [], nodeBindingError: '', nodeBindingErrorKey: '',
      nodePlanEndingRows: [], nodePlanEndingError: '',
      nodeSharedSites: [], nodeCopyBusy: false, nodeCopyUncertain: false, nodeCopyError: ''
    });
  },
  // 半屏 sheet 打开时吃掉背景滚动(catchtouchmove 需要一个真实存在的 handler)
  noop() {},
  // 切换到游戏选择视图
  showTemp: function () {
    this._refreshSupportMatrix();
    if (this.data.publicTempState === 'error') this.getPublicTempList();
    const info = this.data.nodesForm && this.data.nodesForm.templateInfo;
    this.getStoryDraftList();
    this.setData({
      nodeSheetView: 'games',
      selectedTempId: this.data.nodesForm && this.data.nodesForm.templateId ? this.data.nodesForm.templateId : null,
      selectedTempSource: this.data.nodesForm && this.data.nodesForm.templateId ? 'mine' : '',
      selectedTempInfo: info && info.title ? info : null
    });
  },
  // 从游戏选择视图返回详情视图（取消）
  cancelTemp: function () {
    this._nodeTemplateLoadToken = (this._nodeTemplateLoadToken || 0) + 1;
    this._loadingSelectedTemplate = 0;
    // 开关开了却没选模板就退回来:开关跟着关(开关态按 templateId 派生),别留「开着但没挂玩法」的假已配置
    this.setData({
      'nodesForm.showTemplate': !!Number((this.data.nodesForm || {}).templateId),
      nodeSheetView: 'detail',
      selectedTempId: null,
      selectedTempInfo: null
    });
  },
  // 返回按钮：回到节点详情视图
  backToNodeDetail: function () {
    this._nodeTemplateLoadToken = (this._nodeTemplateLoadToken || 0) + 1;
    this._loadingSelectedTemplate = 0;
    // 开关开了却没选模板就退回来:开关跟着关(开关态按 templateId 派生),别留「开着但没挂玩法」的假已配置
    this.setData({
      'nodesForm.showTemplate': !!Number((this.data.nodesForm || {}).templateId),
      nodeSheetView: 'detail',
      selectedTempId: null,
      selectedTempInfo: null
    });
  },
  // 新建玩法：跳转 temp 编辑器，回来经 EventChannel 绑定到当前节点
  onCreateGame: function (e) {
    const dataset = e && e.currentTarget && e.currentTarget.dataset || {};
    const game = nodeGameCatalog.findGame(dataset.key);
    const ctx = this._nodeSupportContext();
    const reason = game && supportMatrix.choice(this.data.supportMatrix, game.key, {}, ctx);
    if (reason && !dataset.preview) { cyToast(reason); return; }
    if (this._navigating) return;
    this._nodeTemplateLoadToken = (this._nodeTemplateLoadToken || 0) + 1;
    const that = this;
    const targetCurrent = this._nodeTemplateTargetCurrent(true);
    const themeName = this.data.formData.name;
    const nodeName = this.data.nodesForm.name || ('节点' + (this.data.editTargetNodeLid || ''));
    this._navigating = true; wx.navigateTo({ // 跳转中不响应连点,免得叠两层玩法编辑页
      url: '/pages/publish/temp/index?from=fabu&themeName=' + encodeURIComponent(themeName || '')
        + '&nodeName=' + encodeURIComponent(nodeName || '') + checkContext.query(this.data.formData.journeyRules)
        + (this.data.supportMatrix ? '&supportContext=' + encodeURIComponent(JSON.stringify(ctx)) : '')
        + (game ? '&game=' + encodeURIComponent(game.key) + (dataset.preview ? '&sample=1' : '') : '')
        + (this.data.operationScope ? '&scope=MERCHANT' : ''),
      events: {
        templateCreated(tpl) {
          if (!targetCurrent() || !tpl || !templateAdoption.id(tpl.id)) return;
          if (that._insertAlbumTemplate(tpl)) return;
          const templateInfo = Object.assign({}, tpl, {
            title: tpl.title || '',
            imgUrl: tpl.imgUrl || '',
            players: tpl.players,
            duration: tpl.duration
          });
          if (that._bindNodeTemplate(templateInfo)) cyToast.success('已绑定新玩法');
        }
      }, complete: () => { this._navigating = false; }
    });
  },

  // 公共玩法库完整分页：list 的 category_id 可省略，homeData 只是推荐切片，不能替代库。
  // ⚠️ 拉不到就留空并在界面上直说,不塞占位假模板:选中一个不存在的 templateId
  // 会让玩家端到了点位打不开玩法,而创作者这边显示"已配置"。
  // 公共玩法库三态分开:加载中 / 已加载(可能为空) / 请求失败(给重试),别把「库里没有」说成「拉不到」
  getPublicTempList: function () {
    const that = this, rows = [], seen = new Set(), pageSize = 100;
    const owner = memberId(app), scope = this.data.operationScope, epoch = this._loginContinuationEpoch;
    const token = this._publicTempListToken = (this._publicTempListToken || 0) + 1;
    const current = () => !that._unloaded && token === that._publicTempListToken
      && owner === memberId(app) && scope === that.data.operationScope && epoch === that._loginContinuationEpoch;
    const fail = () => { if (current()) that.setData({ publicTempList: [], publicTempState: 'error' }); };
    this.setData({ publicTempList: [], publicTempState: 'loading' });
    let received = 0;
    const load = pageNum => app.sendRequest({
      hideLoading: true,
      url: '/api/template/list',
      method: 'POST',
      data: { pageNum: pageNum, pageSize: pageSize },
      success: function (res) {
        if (!current()) return;
        const data = res && res.data, total = Number(data && data.total);
        if (!(res && res.code == '200' && isRecord(data) && isRecordList(data.rows)
          && Number.isSafeInteger(total) && total >= 0)) { fail(); return; }
        // 重复页/坏ID/总数前提前空页不能冒充完整库；失败只重读，不产生采用写入。
        if (new Set(data.rows.map(item => String(item.id))).size !== data.rows.length
          || data.rows.some(item => !templateAdoption.id(item.id)
          || seen.has(String(item.id))) || (!data.rows.length && received < total)) { fail(); return; }
        data.rows.forEach(item => { seen.add(String(item.id)); rows.push(item); });
        received += data.rows.length;
        if (received < total) { load(pageNum + 1); return; }
        that.setData({ publicTempList: rows, publicTempState: 'ready' });
        that._refreshSupportMatrix();
      },
      fail: fail,
    });
    load(1);
  },

  /* 我的故事玩法草稿(W-10):从发布页存下的故事玩法是 status=0 草稿,「我的模板」按 is_quote
     只列已发布,绑定一旦丢了(关了弹层、页面被重置)就再也选不回来,作者只能重配、库里留孤儿。
     每次打开面板都重拉 —— 刚存的那条要马上看得到。拉不到就不出这一段,不塞占位。 */
  // 草稿列表分页读取全部（接口默认每页 10 条；只取首页时第 11 个起的草稿在站点里选不到）
  getStoryDraftList: function () {
    const that = this, collected = [], pageSize = 100;
    const owner = memberId(app);
    const scope = this.data.operationScope;
    const token = this._storyDraftListToken = (this._storyDraftListToken || 0) + 1;
    // 换号 / 换经营身份 / 页面卸载 / 被更新一次读取取代后,旧响应一律作废;
    // 列表不属于当前账号+身份(或来历不明)时先清空,同一账号重读则保留旧列表直到新结果到
    const listKey = owner + '|' + (scope || '');
    const isCurrent = () => !that._unloaded && token === that._storyDraftListToken
      && owner === memberId(app) && scope === that.data.operationScope;
    if (this._storyDraftListKey !== listKey) {
      this._storyDraftListKey = '';
      this.setData({ storyDraftList: [] });
    }
    const settle = function () {
      that._storyDraftListKey = listKey;
      that.setData({ storyDraftList: collected });
    };
    const load = function (pageNum) {
      app.sendRequest({
        hideLoading: true,
        url: '/api/template/my-list',
        method: 'POST',
        data: { draft_status: 0, scope: scope, pageNum: pageNum, pageSize: pageSize },
        success: function (res) {
          if (!isCurrent()) return;
          const rows = res && res.code == '200' && res.data && isRecordList(res.data.rows) ? res.data.rows : [];
          rows.forEach(row => collected.push(row));
          const total = Number(res && res.data && res.data.total) || 0;
          if (rows.length === pageSize && collected.length < total && pageNum < 10) { load(pageNum + 1); return; }
          settle();
          that._refreshSupportMatrix();
        },
        fail: function () { if (isCurrent()) settle(); },
      });
    };
    load(1);
  },

  getTempList: function () {
    var that = this;
    const memberId = String(app.getUserID() || '');
    const scope = this.data.operationScope;
    const epoch = (this._tempListReadEpoch || 0) + 1;
    this._tempListReadEpoch = epoch;
    const isCurrent = () => !that._unloaded
      && epoch === that._tempListReadEpoch
      && memberId === String(app.getUserID() || '') && scope === that.data.operationScope;
    this.setData({ tempList: [] });
    app.sendRequest({
      hideLoading: true,
      url: '/api/template/my-list',
      method: "POST",
      data: {
        is_quote: 1,
        keyword: that.data.searchKeyword,
        scope: that.data.operationScope,
      },
      success: function (res) {
        if (!isCurrent()) return;
        if (res.code == "200" && res.data && isRecordList(res.data.rows)) {
          that.setData({
            tempList: res.data.rows,
          });
          that._refreshSupportMatrix();
        } else {
          // 无真实模板时显示空,避免把 mock 假模板插入节点
          that.setData({ tempList: [] })
        }
      },
      fail: function (res) {
        if (isCurrent()) that.setData({ tempList: [] });
      },
      complete: function () {

      }
    })
  },

  // 为节点选择地点
  choosePoiForNode() {
    const that = this;
    // wx.chooseLocation(经 pickLocation)返回坐标系为 gcj02,与 <map> 一致,无需转换
    pickLocation({
      onPick(poi) {
        // 更新节点表单数据(保留 nodesForm 其它字段)
        that.setData({
          nodesForm: {
            ...that.data.nodesForm,
            name: that.data.nodesForm.name || poi.name,
            address: poi.address,
            longitude: poi.longitude,
            latitude: poi.latitude
          },
          mapCenter: { longitude: poi.longitude, latitude: poi.latitude },
          mapScale: Math.max(that.data.mapScale, 16)
        }, function () {
          that.buildRouteMap();
          cyToast('地点已定位到地图');
        });
      }
    });
  },
  openTimePopup: function () {
    this.setData({
      'timePicker.show': true
    });
  },

  // 2026-09-05 用户裁决:删掉「合作者」选择器。它只是署名,没有任何协作权限
  // (编辑/删除的归属校验只认 cms_topic.member_id,cms_collaborators 从不参与鉴权),
  // 而且选择器拉的是全平台用户、搜索框还没接过滤 —— 名字叫「合作者」却做不了协作。
  // ⚠️ 保留 formData.collaboratorIds:它被 getUserData 自动种上当前用户(isOwner=1),
  // 玩家端主题页的「发起人」署名和后端「整体替换」防误清空闸都依赖它;清空会连署名一起没。
  // 显示编辑弹框
  // 编辑输入框变化
  // 取消编辑
  // 确认编辑
  // 计算单个章节的统计信息
  calculateChapterStats(chapter) {
    return publishStats.chapterStats(chapter);
  },

  // 计算所有章节的总统计信息
  calculateTotalStats() {
    return publishStats.totalStats(this.data.formData.chapters || [], this.calculateTotalDistance());
  },

  // 计算总里程（优化版：增加经纬度有效性校验）
  calculateTotalDistance() {
    return publishStats.totalDistanceKm(this.data.formData.chapters || []);
  },
  // 更新所有统计信息
  updateAllStatistics() {
    const chapters = this.data.formData.chapters || [];
    const chapterStats = [];

    // 计算每个章节的统计信息
    chapters.forEach(chapter => {
      chapterStats.push(this.calculateChapterStats(chapter));
    });

    // 计算总统计信息
    const totalStats = this.calculateTotalStats();

    this.setData({
      chapterStats: chapterStats,
      totalStats: totalStats,
      completeness: this.computeCompleteness()
    }, () => this.refreshPrimaryActionState());

    // 路线变更 → 合并重绘地图 Hero(debounce,批量变更只重绘一次)
    this._scheduleRouteMap();
    if (this.data.formData.routeMode === 'BRANCH_GRAPH') this.refreshRouteGraph();
  },

  // setData 限频:仅 updateAllStatistics 路径走此 ~120ms debounce,
  // 连续/批量路线变更合并成一次 buildRouteMap 重绘;
  // selectNode / onMarkerTap 的即时高亮仍直调 buildRouteMap,不走这里。
  _scheduleRouteMap() {
    if (this._routeMapTimer) clearTimeout(this._routeMapTimer);
    this._routeMapTimer = setTimeout(() => {
      this._routeMapTimer = null;
      this.buildRouteMap();
    }, 120);
  },

  // —— 路线地图 Hero(Strava 化)——
  // 将 chapters[].nodes[] 按章节顺序展平,产出 markers / polyline / includePoints
  // 路线地图视图 = 纯函数,见 utils/publish/route-map-view.js。
  // 这里只做三件页面才能做的事:取 data 切片、存 markerMap 反查表、setData。
  // 配色/简化阈值/折线规则那 178 行搬去模块后有了单测覆盖 —— 埋在页面里时一行都没有。
  // P-45:离屏画玩家地图同款圆点图钉(utils/play-marker-draw.js),只画一次;画完重绘一遍地图。
  // 画失败就留空,route-map-view 回落旧图钉,不影响编辑。
  _ensureRouteIcons() {
    if (this._routeIcons || this._routeIconsPending || !wx.canvasToTempFilePath || !this.createSelectorQuery) return;
    this._routeIconsPending = true;
    const done = (icons) => {
      this._routeIconsPending = false;
      this._routeIcons = icons;
      if (!this._unloaded && icons.actionable) this.buildRouteMap();
    };
    this.createSelectorQuery().select('#routeIconCv').fields({ node: true }).exec((res) => {
      const cv = res && res[0] && res[0].node;
      // 地图层还没渲染出来(画布跟着地图 wx:if):不记结果,下次重绘地图再试
      if (!cv) { this._routeIconsPending = false; return; }
      const S = 108; cv.width = S; cv.height = S;
      const g = cv.getContext('2d'), icons = {};
      const keys = ['actionable', 'candidate', 'target'];
      const next = (i) => {
        if (i >= keys.length) { done(icons); return; }
        drawPlayStateMarker(g, S, keys[i]);
        wx.canvasToTempFilePath({ canvas: cv, fileType: 'png',
          success: (r) => { icons[keys[i]] = r.tempFilePath; next(i + 1); },
          fail: () => done({}) });
      };
      next(0);
    });
  },

  buildRouteMap() {
    this._ensureRouteIcons();
    const view = routeMapView.buildRouteMapView({
      icons: this._routeIcons,
      chapters: (this.data.formData && this.data.formData.chapters) || [],
      selectedNodeLid: this.data.selectedNodeLid,
      pendingMaterials: this.data.pendingMaterials,
      popChapterNodes: this.data.popChapterNodes,
      nodesForm: this.data.nodesForm,
      popChapterNodesAction: this.data.popChapterNodesAction,
    });
    this._markerMap = view.markerMap; // markerId → {chapterLid, nodeLid} 反查表(供 M1.2 bindmarkertap)
    this.setData(view.patch);
  },

  // 完整度 pill 展开/收起缺失项
  toggleCompletenessDetail() {
    this.setData({ _showCompletenessDetail: !this.data._showCompletenessDetail });
  },

  // 路线完整度评分:口径对齐 validateForm(每个节点都必须有可用坐标)
  // 返回 {percent, missing:[...]};在 updateAllStatistics 末尾调用
  computeCompleteness() {
    return publishStats.computeCompleteness(this.data.formData || {}, {
      startDateTime: this.data.startDateTime,
      endDateTime: this.data.endDateTime,
      selectedCategoryIds: this.data.selectedCategoryIds,
    });
  },

  // BL-185:存好并回读确认后、publish-draft 之前,对已存的这一版跑一次只读试检查。
  // error 不发布；warning 等作者确认，HTTP complete不代表作者已选择。
  _topicPublishCheck(topicId, cb) {
    let settled = false;
    let awaitingDecision = false;
    const owner = String(app.getUserID() || '');
    const scope = this.data.operationScope;
    const epoch = this._precheckToken || 0;
    const current = () => !this._hidden && !this._unloaded && epoch === (this._precheckToken || 0)
      && owner === String(app.getUserID() || '') && scope === this.data.operationScope;
    const done = (result) => { if (!settled && current()) { settled = true; cb(result); } };
    const unavailable = () => done({ ok: false, msg: '发布检查暂未完成，草稿已保存，请重试' });
    app.sendRequest({
      url: '/api/topic/publish-check',
      method: 'GET',
      hideLoading: true,
      data: { topicId, scope: this.data.operationScope },
      success: (res) => {
        if (!current() || settled || awaitingDecision) return;
        if (!res || res.code != '200' || !Array.isArray(res.data)
            || res.data.some(issue => !issue || typeof issue.message !== 'string' || !issue.message
              || !['error', 'warn'].includes(issue.level))) { unavailable(); return; }
        const issues = res.data;
        const errors = issues.filter((issue) => issue.level === 'error');
        const warns = issues.filter((issue) => issue.level !== 'error');
        if (errors.length) {
          done({ ok: false, msg: '发布前检查未通过：' + errors.map((issue) => issue.message).join('；') });
          return;
        }
        if (!warns.length) { done({ ok: true }); return; }
        awaitingDecision = true;
        cyLoading.hide();
        modal.show({
          title: '发布前提示',
          content: warns.map((issue) => issue.message).join('\n'),
          confirmText: '继续发布',
          cancelText: '再改改',
          success: (r) => {
            if (!current() || settled) return;
            if (r && r.confirm) cyLoading.show('正在保存并发布');
            done(r && r.confirm ? { ok: true } : { ok: false, msg: '已暂停发布，草稿已保存' });
          },
          fail: () => done({ ok: false, msg: '已暂停发布，草稿已保存' })
        });
      },
      fail: unavailable,
      complete: () => { if (!awaitingDecision) unavailable(); }
    });
  },
});
