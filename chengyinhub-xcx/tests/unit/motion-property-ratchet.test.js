'use strict'

/* 动效属性棘轮(2026-08-26)
 *
 * 为什么要有这条:8-25 那份 UI/UX 清单把「进度条改 scaleX」「keyframes 分类清理」写进了
 * P1,但一个月后复测,两个数字**都在涨**(布局属性动画 11→16、keyframes 97→114)。
 * 写了没门禁的项不但没做,还在恶化 —— 所以先把当前值钉死,再慢慢往下修。
 *
 * 两条都是**只减不增**的棘轮:修好一处就把额度调小一格,调不小说明没真修。
 *
 * 判据来源(8-25 从 emilkowalski 抄来的那套尺子):
 *   · 只动 transform / opacity —— 布局属性每帧触发 layout+paint,合成层动画不会;
 *   · keyframes 从 0 重启、transition 可中途改向 —— 会被快速重触发的元素用 keyframes 就是抖。
 */

const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const SKIP = new Set(['node_modules', 'miniprogram_npm', 'dist'])

/* 会触发 layout 的属性。transform / opacity / color / background / border-color / box-shadow
   等只走合成或重绘,不在此列。`all` 也算 —— 它把未来新增的任何属性一并卷入。 */
const LAYOUT_PROPS = new Set([
  'width', 'height', 'max-width', 'max-height', 'min-width', 'min-height',
  'padding', 'padding-top', 'padding-bottom', 'padding-left', 'padding-right',
  'margin', 'margin-top', 'margin-bottom', 'margin-left', 'margin-right',
  'top', 'left', 'right', 'bottom',
  'flex', 'flex-basis', 'font-size', 'line-height', 'gap', 'all',
])

/* 当前值。**只准调小**。
   改小的同时请在下面 CHANGELOG 记一笔,免得下一个人以为是随手改的。 */
/* 2026-09-10 14 → 13:集邮册照原型重做成三列方格,「收藏夹」那套展开/合上的
   transform 动画随皮肤一起退场。棘轮只减不增,修好了就收窄。
   同日 13 → 12:旅程手记照原型重做成半屏 .kv 列表,滚动沉降那套(逐段 scale/blur/opacity)
   整套退场 —— 它是本仓布局属性动画最大的一处。 */
/* 2026-10-22 12 → 13:主题详情「故事」标签的章节卡点开展开(业主口述 A10)。展开要把下面的卡往下推,
   只能过渡 height —— transform 不占位,会叠到下一张卡上。同批的进度条宽度、卡内标题上移已改成 transform。 */
const LAYOUT_ANIMATION_CEILING = 12 // 2026-10-22 v13 票夹换成卡堆,旧 swiper 的 height 过渡随死样式一起删,13 → 12
// 2026-09-06 原生弹层全删:新组件 cy-loading-mask 的转圈是唯一新增的一组 @keyframes(替代 39 处 wx.showLoading);
// cy-toast 进出场按棘轮要求改用 transition,不占 keyframes。129→130。
/* 2026-09-10 130 → 129:同上,集邮册的 album-page-in 那一组随「收藏夹」退场。
   同批新加的半屏进出场没有占 keyframes —— 按棘轮要求用的是 transition。 */
/* 2026-09-12 并 #1045 后上限 125；本 PR 十九个玩法壳补齐接线后实测 147。 */
/* 2026-09-16 147 → 149,两组都是**照原型逐值补的动效**,且都不是 transition 做得到的:
   · `ci-pop`(play-countin):3-2-1 的数字弹跳,原型 @keyframes cinpop
     —— scale 1.5→1 且 60% 处才到不透明,是三段式,transition 表达不了中间那一帧。
     它原先用 wx.createAnimation,而 createAnimation 的 timingFunction 收不了
     cubic-bezier,所以怎么调都对不上原型那条曲线。每 620ms 重触发一次,但每次是
     **换一个类名重新起跑**(a / b 轮着挂),不是同一条动画被打断,不会抖。
   · `dk-chflow`(playkit-random):抽卡两颗按钮上三个尖角依次亮起(原型 chflow,
     1.35s ease-in-out **infinite**)。无限循环只有 keyframes 能做,transition 没有循环。
   两组都**不在**门禁点名的那几类元素上(toast / toggle / chip / tab / 列表项进入)。 */
/* 合并 master 后重数:master 移除 ps-up(-1),本支补 ci-pop / dk-chflow(+2)。
   2026-09-16 147 → 149 的理由仍然成立——两组都是照原型逐值补、transition 做不到的动效
   (ci-pop 三段式弹跳;dk-chflow 无限循环尖角),且都不在门禁点名的元素上。 */
/* 2026-09-19 批复「全部修好」#7:playkit-woodfish 整件孤儿删除,带走它唯一的一组
   @keyframes wf-merit —— 149 → 148。按本门禁自己的话:清理掉了就把上限调小。 */
/* 2026-09-20 合批:master(d37e89003)实测=顶 148(其 149→148 那格是走查批删 ps-up),
   本支收口批删 playkit-woodfish 是**另一格**(-wf-merit)—— 两笔 -1 不相干,并集后实测 147。
   按门禁自己的规矩压到实测值,不放回、不新增。 */
/* 2026-09-22 147 → 149:《预制人生》那批新玩法屏(D20 检定 / 角色建档 / 留言 /
   限时打字 / 拍照审核)此前**一条动效都没有**,原版十九个每屏都有。补两条,只两条:
   · `pk-in`(pages/play/style/play-surface.wxss)—— **五屏共用**的入场,
     逐值照原型「十九个玩法屏」的 rise:.34s cubic-bezier(.2,.9,.3,1)、translateY 18px + 淡入。
   · `jc-die`(playkit-journey-check)—— D20 骰子逐颗落定,带回弹。
     这一条不共用:掷骰的张力全在「掷→停→读数」,共用入场表达不出「一颗一颗停下」。
   ⚠️ 为什么不能用 transition:这几块都由 wx:if 新建,新节点没有「前一个值」,
   transition 根本不播 —— 实测写过一版,是死的,已撤。入场只能靠关键帧。
   打错抖台面没有占额度:调的是 cy-play-stage 现成的 nudge()。 */
// 2026-10-22 148 → 146:创建引导页三张扇形卡的三条入场关键帧随卡片撤掉,换成整屏画布一条淡入。
// 2026-10-22 146 → 147:owner「卡片每次打开都飞进来」—— 全站共用一条 style/motion-entry.wxss 的 cy-fly-in
//   (模板页 / 统一搜索 / 合作中心广场卡 / 我的订单卡)。理由同上面那段:卡片由 wx:for 新挂,新节点没有
//   「前一个值」,transition 不播;它每次打开页面最多播一次,不属于会被快速重触发的元素。
// 2026-10-22 147 → 148:owner「简单必要的动效」—— 帖文卡点亮时心形弹一下(post-card 是隔离样式组件,
//   全站关键帧进不来,只能本地一条 pc-like-pop)。只在「未赞 → 赞」那一下播,进页已赞的不播。
// 2026-10-23 148 → 146:起名页 templateadd 整页删除带走 1 条(#565);弹层动效统一,cy-scene-sheet 的 ss-up 换成 transition(_in 开关)、顺带补上退场,再减 1。
// 2026-10-28 146 → 145:模板页改为分包货架(业主定稿原型 v21),头牌扫光 tpl-glow-spin 随旧列表退役
const KEYFRAMES_CEILING = 145

/* 逐条豁免。**不是预算额度** —— 想加一组就得在这里写清楚为什么它不属于本门禁要防的场景,
   由 review 逐条看,而不是把上限往上抬一格了事。抬上限会让棘轮退化成台账:
   任何人只要写段理由就能加,门禁再也拦不住下一次回退。
   key = @keyframes 的名字,value = 豁免理由。 */
const KEYFRAMES_EXEMPT = new Map([
  /* 2026-10-22 业主「和 HTML 一模一样」(v13):以下四组都是长驻元素上的无限循环,从不被快速重触发 */
  ['md-rot', '通关勋章页背后的放射光常驻慢转(40s 一圈),页面在就一直转，没有重触发；减少动态效果时整组停掉。'],
  ['md-tw', '通关勋章页的闪点常驻明灭，无限循环，没有重触发；减少动态效果时整组停掉。'],
  ['md-shine', '六边形勋章表面的高光常驻扫过，无限循环，没有重触发；减少动态效果时整组停掉。'],
  ['holo-pan', '购买半屏票面的镭射彩光常驻缓慢平移(alternate 无限循环),半屏开着就一直动，没有重触发；减少动态效果时停掉。'],
  ['fxCardFloat',
    '自由探索卡片详情的 3D 卡盒常驻呼吸浮动。本门禁防的是「会被快速重触发的元素」' +
    '(toast/toggle/chip/tab)从 0 重启导致的抖；这是长驻元素上的无限循环，没有重触发。' +
    '且它只写 translateY + rotateX、不碰 rotateY —— 旋转由 --ry 单独管,' +
    '两者挤进同一个 transform 才是真会打架的写法。'],
  ['paysuc-spin',
    '结算页「正在支付」那一档的 48px 加载环(稿 576:1255)。无限匀速自转,' +
    '没有终点态可补间 —— transition 做不出来。它只在「微信收银台已收款、服务端还在确认」' +
    '这个窗口内存在，层一关节点就卸载，不存在快速重触发从 0 重启的抖。' +
    '且 .cy-motion-reduced / .paysuc-layer--reduced 下已显式停转。'],
  ['npcRingSpin',
    '门店分身脚下那圈地环的匀速自转(样机 .npc__ring.r2:14s linear infinite)。' +
    '无限循环、常驻、匀速，没有终点态可以补间 —— transition 只能做一次 A→B,做不出来。' +
    '挂在对话层上，层一关整个节点就卸载，不存在「快速重触发从 0 重启导致抖」。'],
  ['rs-pop',
    '结果面板 cy-result-sheet 徽章弹入。挂在刚打开、终态 2s 自收的面板上，不是会被连点的元素。' +
    '淡入→过冲→淡出的三段,transition 只能做一次 A→B。形制对应 creation-success 的 cs-mark。'],
  ['rs-ring',
    '结果面板涟漪。同上，一次性终态，三段扩开淡出,transition 做不出中间态。'],
  ['rs-spark',
    '结果面板八颗粒子外飘。同上，一次性终态，三段,transition 做不出。'],
  ['rs-draw',
    '结果面板勾按笔顺画出来。两根条错峰各画一段，起点还要靠 JS 翻 class,transition 做不到。'],
  ['rs-spin',
    '结果面板 loading 转圈。无限循环没有终点态,transition 只能做一次 A→B。'],
  ['hero-drop',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '点「查看详情与报名」后第二屏头图回落一次。元素在 wx:if/class 翻上的同一帧才出现,transition 没有起点；一次性，不会被连点。.topic--reduced 下关闭。'],
  ['hero-up',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '同上:标题、按钮、数据条逐行上浮，以及故事扇收进顶栏后那叠小卡出现。节点挂载即播,transition 没有起点。.topic--reduced 下关闭。'],
  ['tb-sweep',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '主题徽章金属面上常驻的反光带(无陀螺仪数据时)。无限循环没有终点态,transition 做不出。'],
  ['tb-fade',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '徽章放大层 wx:if 挂载时的压暗淡入。挂载帧没有起点,transition 不触发。只改 opacity。'],
  ['tb-pop',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '徽章放大层里大徽章弹出:缩小→过冲→回位三段,transition 只能 A→B。.tb-layer--reduced 下关闭。'],
  ['rp-pop',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '路线进度条章节点点亮时弹一下(三段过冲),每个点只在进度条那一遍里亮一次，不会被连点。.rp--reduced 下关闭。'],
  ['rp-plus',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '点亮时冒出的「+第 N 章」上飘淡出:淡入→上移→淡出三段。.rp--reduced 下关闭。'],
  ['rp-bump',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '全部完成时宝箱弹一下(三段)。只有通关才播一次。.rp--reduced 下关闭。'],
  ['rp-conf',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '宝箱打开时的彩屑下落。wx:if 带 !reducedMotion,挂载即播，一次性。'],
  ['pr-run-t',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '通行证到账:屏幕上边那段琥珀光上的高光顺时针跑(另三条边各一组)。无限循环、沿边位移，没有终点态。整层是付款成功后才出现的一次性全屏层。.pr--reduced 下整层直接给落定终态。'],
  ['pr-run-r',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '同 pr-run-t,右边。'],
  ['pr-run-b',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '同 pr-run-t,下边。'],
  ['pr-run-l',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '同 pr-run-t,左边。'],
  ['pr-spk',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '到账层边光附近的闪点:淡入上浮淡出三段、无限循环。.pr--reduced 下关闭。'],
  ['pr-ch',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '「通行证已到账」逐字由糊变清。每个字是 wx:for 挂载即播，带逐字延迟,transition 没有起点。.pr--reduced 下直接显示。'],
  ['pr-orb-in',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '白色光球长出。与常驻的 pr-breathe 同挂在一个元素上 —— 若改 transition,呼吸动画的 transform 会把它整段盖掉，只能两段都用 keyframes。'],
  ['pr-breathe',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '光球呼吸:无限往返，没有终点态。'],
  ['pr-orb-out',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '光球散开让位给通行证。与 pr-orb-in 同理，前一段 animation 仍挂着,transition 接不上。'],
  ['pr-spin',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '通行证长出后带透视转两圈落定:多关键帧(位移+缩放+rotateY 720°),transition 只能 A→B 走最短路径，转不出两圈。'],
  ['pr-fly',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '「放进票夹」:卡片先鼓起再飞走(两段),随后整页交给票夹。点一次即卸载，不会重触发。.pr--reduced 下不飞。'],
  ['pr-holo',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '通行证镭射面彩光缓慢流动，无限往返，没有终点态。'],
  ['pr-metal',
    '2026-10-22 业主口述的主题详情动效(docs/design/动效界面落地_20261022)。' +
    '通行证背面黑金属反光扫过，无限循环，没有终点态。'],
])

/* CHANGELOG
 * 2026-08-26 立此门禁。布局属性动画 14 / keyframes 117 是当天实测值。
 *   ⚠️ 这两个数字不是目标,是**上限**。它们此前一直在涨,先钉住再往下修。
 * 2026-08-27 keyframes 117 → 119。#863 立门禁用的是当天实测值,#862「点赞爆一下」
 *   同期在做、后合入,在 square/detail 加了 cy-like-pop / cy-like-ring 两组,
 *   master 的 tip 因此自己判红、挡住了所有 PR。这是两个 PR 撞车的时序问题,不是
 *   有人偷偷加动画。
 *   ⚠️ 显式调大而不是压回去,理由是这两组不属于本门禁要防的场景:is-bursting 是
 *   一次性 class,js 播完就摘,取消赞不挂 —— 不存在「快速重触发从 0 重启导致抖」。
 *   而 cy-like-pop 是 0.8 → 1.18 → 1 的三段弹跳,transition 做不出中间态。
 *   ★ 这是**放松**门禁,按棘轮规矩必须留痕:下一个想加 keyframes 的人仍然要先
 *   问「能不能用 transition」,不要把 119 当新的预算额度。
 * 2026-08-27 keyframes 119 → 120。危险动作三段式确认(T2)在 cy-modal 里加了一组
 *   mo-spin —— 确认键内的执行中转圈(照 Locals 64:不换按钮尺寸,文字位置换成转圈)。
 *   ⚠️ 先问过「能不能用 transition」:不能。这是无限循环的匀速旋转,没有终点态可以补间,
 *   transition 只能做一次 A→B。而且它挂在 loading 期间,按钮此时已被锁死不可重触发,
 *   不存在本门禁要防的「快速重触发从 0 重启导致抖」。
 *   ★ 同样是**放松**门禁,留痕在此:120 不是新预算额度。
 * 2026-08-31 keyframes 120 → 130。漫游 GO 弧线切场是一次性 cinematic（定位成功才播一次，
 *   播完卸 opening 层），不是 toast/toggle 那种快速重触发。transition 做不出紫弧扫过再切
 *   黑底品牌页的多拍时序，故显式放宽并留痕：130 不是新预算额度。
 * 2026-08-31 keyframes 130 → 129。开场遮罩不再半途淡出，删除 r-openingAway。
 * 2026-09-02 keyframes 120 → 121。俱乐部管理页两处折叠面板的内容进场(cy-fold-in)——
 *   Figma 40:3「行内展开」+ 42:168「内容自 -4px 归位」的落地。
 *   ⚠️ 先问过「能不能用 transition」:不能。两处面板都是 `wx:if` 挂载,挂载那一帧
 *   没有可补间的起始态,transition 不会播;要用 transition 就得把 wx:if 改成常驻渲染,
 *   那等于把所有折叠面板的列表内容永远渲染着,代价比一组 keyframes 大得多。
 *   折叠行点一下翻转、再点是**卸载**而不是重触发,不存在本门禁要防的
 *   「快速重触发从 0 重启导致抖」。
 *   ★ 又一次**放松**门禁,留痕在此:121 不是新预算额度;两处折叠面板共用这一组,
 *   下一个折叠点请直接复用 cy-fold-in,别再抄第二组。
 * 2026-09-02 keyframes 129 → 130。**棘轮撞车的并集**:master 上「漫游 GO 弧线切场」
 *   已把上限抬到 129,而本 PR 是在 120 的基线上 +1 组(cy-fold-in)。
 *   两边都是显式放宽且各自留了痕,所以取并集 129 + 1 = 130,而不是二选一 ——
 *   选任何一边都会把另一边的那组动画变成「超预算」而判红。
 *   ⚠️ 130 仍然不是新预算额度。
 * 2026-09-06 keyframes 130 → 131(净 +1,后改走豁免)。结果面板 cy-result-sheet 带来 5 组:
 *   rs-pop(徽章弹入)/ rs-ring(涟漪)/ rs-spark(八颗粒子)/ rs-draw(勾按笔顺画)/ rs-spin(转圈);
 *   同时报名页自绘的 .paysuc-* 整段随之删除,带走 4 组 ⇒ 净 +1。
 *   ⚠️ 先问过「能不能用 transition」:五组都不能。rs-spin 是无限循环没有终点态;
 *   rs-pop/.ring/.spark 都是「淡入→过冲→淡出」的三段,transition 只能做一次 A→B;
 *   rs-draw 是两根条错峰各画一段,起点还要靠 JS 翻 class 才有。
 *   前四组形制一一对应 creation-success 已有的 cs-mark / cs-ring / cs-confetti-float,
 *   是照抄仓库既有做法,不是另发明一套。
 *   ⚠️ 它们挂在**刚打开、终态 2s 自收**的结果面板上,不是会被连点的元素,
 *   不存在本门禁要防的「快速重触发从 0 重启导致抖」。
 *   2026-09-11 并 master 后上限不再抬:这 5 组改登记进 KEYFRAMES_EXEMPT。报名页 .paysuc-* 4 组已删,豁免后实测 125,上限 129 → 125。
 * 2026-09-08 **上限不再往上抬,改为逐条豁免**(KEYFRAMES_EXEMPT)。自由探索的 fxCardFloat
 *   本来是按老办法「写段理由把 129 抬到 130」加进来的,合 master 时正好撞见上面那两条
 *   同样是「写段理由抬一格」—— 三次放宽都各自有理,而这恰恰说明问题:有理由就能抬,
 *   棘轮从此只是台账,拦不住下一次回退。所以上限**留在 master 的 130**(那一格是
 *   cy-loading-mask 的),fxCardFloat 走点名豁免:名单里要留下名字和理由,而且名单项
 *   必须对应仓库里真实存在的那一组(见「豁免名单」用例),不能留空位当额度。
 *   ⚠️ 下一个想加 keyframes 的人:先问「能不能用 transition」,再来这份名单写理由,
 *   不要再抬上限。
 * 2026-09-08 名单 +npcRingSpin(分身脚下地环慢转,对齐样机)。上限仍是 130,没动。
 *   —— 这正是逐条豁免相对「抬上限」的价值:第二次新增也不用把预算再放宽一格。
 */

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name) || e.name.startsWith('.')) continue
    const full = path.join(dir, e.name)
    if (e.isDirectory()) walk(full, acc)
    else if (e.name.endsWith('.wxss')) acc.push(full)
  }
  return acc
}

/** 注释里的 transition 是说明不是声明 —— 抹掉但保留换行,行号不受影响 */
function stripComments(source) {
  return source.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
}

/* ⚠️ 扫描逻辑参数化,负控喂内存 —— **测试不能改仓库文件**。
   本仓单测并发跑,别的扫描器同时在读同一批文件,临时写进去的探针会被它们看见
   (2026-08-26 在另一条门禁上实测踩过:临时探针把一条无关的台账用例带红)。 */
function scanSources(sources) {
  const hits = []
  for (const item of sources) {
    const file = item.name
    const code = stripComments(item.source)
    const re = /transition\s*:\s*([^;}]+)/g
    let m
    while ((m = re.exec(code))) {
      const line = code.slice(0, m.index).split('\n').length
      m[1].split(',').forEach((seg) => {
        // 每一段的第一个 token 就是属性名:`width .3s ease` → width
        const prop = seg.trim().split(/\s+/)[0]
        if (LAYOUT_PROPS.has(prop)) {
          hits.push(file + ':' + line + ' → ' + prop)
        }
      })
    }
  }
  return hits
}

function countKeyframesIn(sources) {
  let n = 0
  for (const item of sources) {
    const re = /@keyframes\s+([A-Za-z_][\w-]*)/g
    let m
    while ((m = re.exec(stripComments(item.source)))) {
      if (!KEYFRAMES_EXEMPT.has(m[1])) n += 1
    }
  }
  return n
}

/** 真实仓库(只读) */
function repoSources() {
  return FILES.map((f) => ({
    name: path.relative(ROOT, f),
    source: fs.readFileSync(f, 'utf8'),
  }))
}
const scanLayoutAnimations = () => scanSources(repoSources())
const countKeyframes = () => countKeyframesIn(repoSources())

const FILES = walk(ROOT)

test('扫描分母正常:全仓 wxss 必须都被看到', () => {
  assert.ok(FILES.length > 100, '只扫到 ' + FILES.length + ' 个 wxss,扫描根多半错了')
})

test('布局属性动画只减不增 —— 它们每帧触发 layout+paint,合成层动画不会', () => {
  const hits = scanLayoutAnimations()
  assert.ok(
    hits.length <= LAYOUT_ANIMATION_CEILING,
    '布局属性动画 ' + hits.length + ' 处 > 上限 ' + LAYOUT_ANIMATION_CEILING +
      '。新增的请改成 transform,确实改不动就连同理由一起调高上限并说明:\n  ' +
      hits.join('\n  ')
  )
  assert.equal(
    hits.length, LAYOUT_ANIMATION_CEILING,
    '实测 ' + hits.length + ' < 上限 ' + LAYOUT_ANIMATION_CEILING +
      ':修好了就把上限调小，否则棘轮会空出一格给下一次回退'
  )
})

test('keyframes 只减不增 —— 它从 0 重启,快速重触发的元素用它就是抖', () => {
  const n = countKeyframes()
  assert.ok(n <= KEYFRAMES_CEILING,
    '@keyframes ' + n + ' 组 > 上限 ' + KEYFRAMES_CEILING +
    '。会被快速重触发的元素(toast / toggle / chip / tab / 列表项进入)请改用 transition')
  assert.equal(n, KEYFRAMES_CEILING,
    '实测 ' + n + ' < 上限 ' + KEYFRAMES_CEILING + ':清理掉了就把上限调小')
})

test('transition: all 必须保持零 —— 它把未来新增的任何属性一并卷入', () => {
  const hits = scanLayoutAnimations().filter((h) => h.endsWith('→ all'))
  assert.deepEqual(hits, [], 'transition: all 已于 2026-08-25 清零,不许回退')
})

test('负控:注入一处 transition: width 必须判红', () => {
  const probe = [{ name: 'probe.wxss', source: '.p { transition: width .3s ease; }' }]
  assert.deepEqual(scanSources(probe), ['probe.wxss:1 → width'], '新增的布局属性动画必须被数到')
  assert.ok(scanLayoutAnimations().length + 1 > LAYOUT_ANIMATION_CEILING, '多一处就超上限，必须判红')
})

test('负控:注释里的 transition 不算数,别把说明文字当成声明', () => {
  const probe = [{ name: 'probe.wxss',
    source: '/* 历史写法:transition: width .3s —— 已改成 transform */\n.p { color: red; }' }]
  assert.deepEqual(scanSources(probe), [],
    '注释里的 transition 被当成了真声明 —— 误报会拿不存在的问题挡住别人的 PR')
})

test('豁免名单里的每一条都必须真的还在仓库里 —— 修掉了就要从名单里删,不许留空位', () => {
  const all = repoSources().map((s) => stripComments(s.source)).join('\n')
  for (const name of KEYFRAMES_EXEMPT.keys()) {
    assert.ok(new RegExp('@keyframes\\s+' + name + '\\b').test(all),
      '豁免了 ' + name + ' 但仓库里已经没有这组 keyframes —— 空豁免会变成下一个人的免费额度')
  }
})

test('负控:豁免只对点名的那一组生效,别的同样要被数到', () => {
  const exempt = KEYFRAMES_EXEMPT.keys().next().value
  assert.equal(countKeyframesIn([{ name: 'p.wxss', source: '@keyframes ' + exempt + ' { to { opacity: 1 } }' }]), 0)
  assert.equal(countKeyframesIn([{ name: 'p.wxss', source: '@keyframes ' + exempt + 'X { to { opacity: 1 } }' }]), 1,
    '前缀相同的别名不该跟着一起被豁免')
})

test('负控:注入一组 @keyframes 必须被数到', () => {
  const probe = [{ name: 'probe.wxss', source: '@keyframes p { from { opacity: 0 } to { opacity: 1 } }' }]
  assert.equal(countKeyframesIn(probe), 1)
  assert.equal(countKeyframesIn([{ name: 'probe.wxss', source: '/* @keyframes p {} */' }]), 0,
    '注释里的 @keyframes 也不该算')
})
