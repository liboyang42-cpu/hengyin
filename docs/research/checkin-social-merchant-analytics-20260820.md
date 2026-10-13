# 到店打卡/本地商户产品的商家端「店铺数据」调研

> 2026-08-20 · 为城瘾商家端「AI 店铺参谋」页(营销 tab)做设计参照。
> 调研范围:Foursquare/Swarm、Yelp for Business、Google Business Profile、抖音来客、美团经营宝(原开店宝)/点评管家、小红书商家侧、Square Loyalty、Fivestars→SumUp Connect、Blackbird,附 Instagram/Questo 负结果。
> 来源纪律:只依据一手来源(官方帮助中心/商家学院/产品页/官方博客/API 文档/应用商店官方页);未见官方截图的按官方文档描述还原,标「未见截图,据文档」;官方源拿不到的直接标「未覆盖」,不用第三方转述冒充结论。

## 0. 城瘾参谋页现状(基线)

`chengyinhub-xcx/pages/merchant/marketing/ai-insight/`,商家浅色黑白系主题:

- 三张大指标卡:icon 徽章 + 名称 + 右上比率胶囊 + 大数字/分母 + 10 段胶囊进度条(到店打卡数+核销率 / 到店顾客数+复购率 / 合作席位+席位使用率)
- 打卡时段分布:24 列细柱图(复用工作台 revenue7d 柱图做法)
- 客群构成:性别一行文本 + 兴趣标签 chips
- AI 经营解读(纯文本段落)+ 适合人群 chips + 建议活动卡(标题/时段/面向/理由/「一键去办」CTA)
- 数据区与 AI 区分层:AI 挂了数据半屏照常

用户反馈:**不好看、单调**。平台数据量小(几十单),商家端是微信小程序浅色 DS。

## 1. 概览对比表

| 产品 | 核心数据模块 | 首屏主角 | 图表形态 | 自然语言诊断/建议 | 官方源覆盖度 |
|---|---|---|---|---|---|
| Foursquare 商家侧(历史) | check-in 总数、独立访客、新客vs回头客、性别、时段分解 | check-in 时间序列 | 逐日折线 + 人口属性分块 | 无 | 历史形态据官方博客/API;现状=面板已不存在 |
| Yelp for Business | Page Visits、Audience(曝光/移动占比)、Customer Leads(7 类行动)、竞对对比 | Performance Summary 三区块 | 大数字 + 预测图 + 明细列表;30天/12月/24月三档 | 无 AI 卡;用「漏斗解读文案」代替 | 高(官方文章) |
| Google Business Profile | Interactions 总览、Views、搜索词、Calls/Directions/网站点击/消息/预订 | 互动汇总 | 日粒度时间序列 + 搜索词列表 | 无 | 高(帮助中心+API 文档) |
| 抖音来客 | 直播/短视频/带货/交易流水/用户数据五类;另有官方数据平台「生意经」 | 「一目了然引导经营」的汇总看板 | 未覆盖 | 官方口径含「智能诊断+策略建议」,形态未覆盖 | 中(App Store 官方页) |
| 美团经营宝/点评管家 | 交易、流量、门店客流构成、评价管理、「全新诊断」;点评管家有「经营参谋」模块 | 工作台=经营聚合门户 | 未覆盖 | **官方文案明确打「诊断/参谋」概念**,形态未覆盖 | 中(App Store 官方页) |
| 小红书本地商家版 | 官方叙事只有收款/核销工具,**无数据面板**(数据能力在专业号/千帆侧) | — | — | — | 中(负发现,官方页) |
| Square Loyalty | Visits、Sales(均分 loyalty vs non-loyalty 对比)、Top Customers、等级报表 | 三个可切换报表 | 对比数字 + 列表 | 无 | 高(官方支持文档) |
| Fivestars→SumUp Connect | 新注册会员、带回的回访数、campaign 表现;AutoPilot 自动营销 | 「证明平台每天带来的价值」 | 未覆盖 | 无 AI 卡;AutoPilot 是自动执行不是建议 | 中(官方产品页+帮助中心目录) |
| Blackbird | 单客档案:Guest Value Score、本店到访史、VIP 状态,开单实时推送 | 不是聚合报表,是**单客卡片** | 单客画像卡 | 无 | 中(官方新闻稿) |

## 2. 逐产品详述

### 2.1 Foursquare / Swarm 商家侧 —— check-in 数据鼻祖

**2026 年现状**:City Guide App 已于 2024-12-15 关停、网页版 2025-04-28 关停,官方对商家的承诺只剩一句 "Your business listing will remain discoverable on our Swarm app";商家可用工具收敛为 **Placemaker**(编辑场地信息)+ 企业级 Places/API 数据产品。**面向单店商家的自助 check-in 分析面板已不存在。**(来源:https://foursquare.com/city-guide-sunset/ )

**历史形态**(未见截图,据官方文档/博客):早期 Merchant Dashboard 提供 **total check-ins、unique visitors、性别对比、按时段(time-of-day)分解**;官方 Medium 博客《Moar stats in your Merchant Platform API》确认面板数据经 `venues/stats` 端点全量开放("get data behind charts and graphs for any venue managed over any time range"),`venues/timeseries` 提供**逐日 check-in 数与新访客数**——即历史面板形态是「时间序列折线 + 人口属性分块」,且「新客 vs 回头客」是 API 一级字段。(来源:https://medium.com/foursquare-direct/moar-stats-in-your-merchant-platform-api-e98f23a372b7 ,直抓 403,结论取自该官方文检索摘要)2017 年起的企业级 "Foursquare Analytics" foot-traffic 面板(性别/年龄/新老客维度,H&M、Taco Bell 等 beta)官方产品页**未覆盖**,以上仅 TechCrunch 等报道佐证为线索(https://techcrunch.com/2017/03/20/foursquare-is-launching-an-analytics-platform-to-help-retailers-understand-foot-traffic/ )。历史面板的 top visitors 模块未从官方源坐实,不作结论。

**对城瘾**:值得抄——「新客 vs 回头客」作为一级指标、按时段分解;鼻祖的教训是纯 check-in 数据面撑不起商业续费,后来全部转向「带来了多少客流」的归因叙事。

### 2.2 Yelp for Business —— 漏斗叙事最完整的样本

**数据模块**(来源:https://business.yelp.com/resources/articles/understanding-your-business-metrics-on-yelp/?domain=local-business ):

- **Page Visits**(=User Views):搜索后点进商家页的总次数,全设备合计;附**自动生成的预测图**(投放广告可能获得的额外访问)。
- **About Your Audience**:移动端占比 + **商家在搜索结果中出现的次数**。官方原文给出漏斗解读文案:如果你有 10 次页面访问但出现在 1,000 次搜索中,你只被发现了 1%——曝光→点击的转化被翻译成一句人话。
- **Customer Leads**:页面访问后发生的任何可追踪行动,官方枚举 7 类:**mobile check-ins、查看地图/导航、移动来电、网站点击、用户上传照片、收藏、发消息**。去重:同一用户同类型动作 1 小时内只计 1 条(来源:https://www.yelp-support.com/article/How-do-you-measure-User-Views-and-Customer-Leads-on-Yelp-for-Business ,证书异常未直抓,取自官方文检索摘要)。
- **竞对对比**:新版 page views dashboard 可与最多 3 个自选竞对比较页面浏览量(来源:https://business.yelp.com/products/new-yelp-for-business-products-features/ )。

**页面形态**(未见截图,据文档):登录落在 Home tab,首屏是 **Performance Summary** 区块("your home base for all business analytics"),三个子区依次 Page Visits / About Your Audience / Customer Leads;时间范围三档:**过去 30 天 / 12 个月 / 24 个月**;右上角铃铛=Activity 通知流(谁访问了页面、做了什么)。无 AI 洞察卡——「建议」体现为漏斗解读文案和广告引导。

**对城瘾**:值得抄——①「曝光→访问→行动」三段漏斗做首屏主线;②把转化率翻译成一句人话的文案手法(这正是 AI 解读卡的正确用法);③ Activity 通知流=「谁刚刚做了什么」的单事件叙事,小数据量下比聚合曲线耐看。不适合——预测图(诱导买广告的形态,城瘾无此动机)。

### 2.3 Google Business Profile — Performance 面板

**指标清单**(来源:https://support.google.com/business/answer/9918094 ;API:https://developers.google.com/my-business/reference/performance/rest/v1/DailyMetric ):**Interactions 总览**、**Views**(Search/Maps 看到 Profile 的人数,同一用户同日多次只计 1)、**Searches**(用户找到你所用的搜索词列表,月更、延迟约 5 天)、**Calls / Directions(有防刷去重)/ Website clicks / Messages / Bookings / Food orders / Menu·Products·Offers 浏览点击**。

**粒度与形态**(未见截图,据文档):API 全部指标为**日粒度**;面板不是独立后台,**嵌在 Google Search 搜自家商名的管理界面和 Maps App 里**;自选日期范围、可下载报表。无自然语言洞察模块。可据 API 推断形态=「大数字互动汇总 + 按日时间序列 + 搜索词列表」。

**对城瘾**:值得抄——「搜索词/发现方式列表」这种**列表即图表**的形态,对应城瘾「玩家从哪个主题/章节进来」的来源列表;所有计数都写明去重规则(指标可信感)。不适合——月更延迟、纯自助无解读(它有海量数据撑得起冷面板,城瘾撑不起)。

### 2.4 抖音来客(抖音生活服务商家端)

**数据模块**(官方一手源=App Store 官方页,来源:https://apps.apple.com/cn/app/%E6%8A%96%E9%9F%B3%E6%9D%A5%E5%AE%A2/id1603172928 ):四大板块=门店管理/营销推广/**数据分析**/交易提现;数据分析官方原文:"直播、短视频观看、带货数据、订单交易流水、用户数据分析等等,一目了然引导经营"——至少五类:直播、短视频/内容、带货、交易流水、用户(客群)。官方生态另有独立数据洞察平台**「抖音生活服务生意经」**(https://www.life-data.cn/ ,官方站,页面 JS 渲染抓不到正文,模块清单未覆盖)。官方数据产品叙事含「智能诊断与策略建议」,覆盖货架经营/内容经营/服务体验三场景(据 school.douyin.com 官方站检索摘要)。

**「经营参谋/数据罗盘/经营诊断」的具体形态:未覆盖**——school.douyin.com、school.oceanengine.com 的官方手册需登录/JS 渲染,公开抓取失败;多轮定向搜索未命中可公开访问的官方页。⚠️ 搜索会命中「抖音电商罗盘」(compass.jinritemai.com),那是**电商侧**官方数据平台,不是来客/本地生活侧,不可混用。

**页面形态**:未见截图,据文档只能判断定位是"一目了然引导经营"的汇总式看板;诊断建议的呈现形式(打分/自然语言/任务卡)官方源未覆盖。

**对城瘾**:方向性参照——国内本地生活商家端把「诊断+策略建议」当官方卖点,证明 AI 参谋这个方向本身对路;但形态细节拿不到官方源,不能照抄坊间截图。

### 2.5 美团经营宝(原开店宝)/ 点评管家

**数据模块**(官方一手源=App Store 官方页):

- **美团经营宝**官方原文:"科学经营,解析客流,线上数据更全面。**全新诊断,洞察门店经营机会和问题;门店客流,深度解析客流组成**";三大板块:工作台(经营聚合门户)/数据分析/顾客评价管理。(来源:https://apps.apple.com/cn/app/%E7%BE%8E%E5%9B%A2%E7%BB%8F%E8%90%A5%E5%AE%9D-%E5%8E%9F%E7%BE%8E%E5%9B%A2%E5%BC%80%E5%BA%97%E5%AE%9D/id592673661 )
- **点评管家**(到店综合商家后台)官方页含**「经营参谋」模块:"门店经营数据一目了然,轻松制定经营策略"**,另含团购发布、验券、评价管理。(来源:https://apps.apple.com/us/app/%E7%82%B9%E8%AF%84%E7%AE%A1%E5%AE%B6-%E7%BE%8E%E5%9B%A2%E7%82%B9%E8%AF%84%E5%95%86%E5%AE%B6%E5%90%8E%E5%8F%B0/id793755894 ,检索摘要转述官方页文案)

e.dianping.com/e.meituan.com 帮助中心的「曝光→访问→转化」漏斗、客群画像(性别/年龄/消费力)、商圈对比等细节文档需商家登录,**未覆盖**;页面形态与诊断呈现(打分?任务卡?)**未覆盖**(未见截图,据文档)。

**对城瘾**:美团系是国内唯一在官方文案里明确打出「诊断/经营参谋」概念的——「洞察机会和问题」这个措辞值得抄进 AI 解读卡的定位:不是复述数据,是指出**机会**和**问题**各一条。

### 2.6 小红书商家侧 —— 一个有价值的负发现

- **小红书本地商家版(门店助手)**官方页只列**小红卡收款、套餐核销**等经营工具,**未列出任何数据分析模块**——本地商家端 App 的官方叙事重心不在数据面板。(来源:https://apps.apple.com/cn/app/%E5%B0%8F%E7%BA%A2%E4%B9%A6%E6%9C%AC%E5%9C%B0%E5%95%86%E5%AE%B6%E7%89%88/id6470608161 )
- **小红书千帆(电商商家版)**官方页含数据看板/订单/商品/客服。(来源:https://apps.apple.com/cn/app/%E5%B0%8F%E7%BA%A2%E4%B9%A6%E5%95%86%E5%AE%B6%E7%89%88/id1508097648 )
- 专业号数据中心(pro.xiaohongshu.com)官方功能说明需登录,**未覆盖**。第三方转述口径(笔记曝光/阅读/互动/流量来源分布,粉丝性别/年龄/城市/兴趣画像)仅作线索不作结论。

**对城瘾**:负发现本身是结论——到店打卡语境下,小红书把数据能力放在**内容侧**(笔记数据)而非门店侧;城瘾商家的「内容」=承接的主题/章节,对应模块是「各主题带来的打卡数」而不是笔记曝光。

### 2.7 Square Loyalty —— 「对比」形态的最佳官方样本

**数据模块**(来源:https://squareup.com/help/us/en/article/6467-view-your-square-loyalty-metrics ,官方支持文档,本轮核实最充分):入口 Square Dashboard → Customers → Loyalty,报表分:

- **Visits**:loyalty 总到访数;**loyalty vs non-loyalty 客户平均到访对比**;loyalty 客户到访频次;选定时段新到访数;
- **Sales**:**loyalty vs non-loyalty 销售额对比**;loyalty 促销带来的销售;
- **Top Customers**:按到访次数与忠诚度等级列出头部客户(列表形态);
- **Tier Members**:各等级成员数、升/降级人数、各等级到访与消费;
- 筛选:日期、门店。

**页面形态**(未见截图,据文档):三个可切换报表 + 等级报表;Top Customers 为列表;无自然语言洞察模块。**「参加计划的客人 vs 没参加的」对比是官方文档明写的核心卖点指标**——数字的意义全靠对比给出。

**对城瘾**:最值得抄的一个形态——「城瘾玩家 vs 普通顾客」没法比(我们看不到商家自然客流),但**「打卡客的复购 vs 首次」「本主题 vs 上一主题」**这类内部对比同样把孤立数字变成结论;Top Customers 列表=城瘾「常客玩家」列表,几十单数据量恰好够列 5-10 行。

### 2.8 Fivestars → SumUp Connect —— 商家面板=价值证明书

**数据模块**(来源:https://www.sumup.com/en-us/loyalty-program/loyalty-rewards/ ;https://help.sumup.com/en-US/sections/RqPe3XHxJvRKsH8POwjI0-sumup-connect ):官方 loyalty 产品页明确商家可看实时数据——**new signups(新注册会员)、return visits generated(带回的回访次数)、进行中与历史 campaign 表现**;帮助中心分区含 **Merchant Dashboard**(官方描述:"shows critical information that proves the value we are providing businesses on a daily basis"——定位就是**证明平台每天带来的价值**)、**AutoPilot**(自动化定向促销,提升 engagement 与 retention)、发促销、Text-to-Join、积分调整等。

**页面形态**:dashboard 详情文章 404(JS 帮助中心),首屏指标排布与图表类型**未覆盖**;但信息架构主线可判定为「归因给平台的价值」(新客数/回访数/活动 ROI),AutoPilot 是独立自动营销模块而非报表。(未见截图,据官方产品页与帮助中心目录)

**对城瘾**:抄它的定位语——商家数据面第一职责是回答「**这个平台给我带来了什么**」;「return visits generated」这种归因措辞(城瘾版:「城瘾为你带来 N 位顾客、其中 M 位回头」)比中性的「到店顾客数」更有说服力。

### 2.9 Blackbird —— 单客卡片,不是聚合报表

**数据模块**(来源:https://www.prnewswire.com/news-releases/blackbird-launches-new-restaurant-payments-and-rewards-platform-to-improve-merchant-economics-and-transform-the-guest-experience-302209429.html ,官方新闻稿;官网 JS 渲染抓不到正文):

- **实时到店通知**:客人开单那一刻,餐厅被告知「这位是 Blackbird 会员」;
- 餐厅可见的**单客档案**:专有 **Guest Value Score**、该客在本店的**到访历史**、**VIP 状态**;
- 商家可配置 perks/benefits/points/access 精细化款待;Blackbird Pay 约 2% 费率。

**页面形态**:官方源**没有任何聚合 analytics dashboard 的描述**——产品重心是「开单那一刻推给餐厅的单客画像卡」。regulars vs new 聚合视图、$FLY 商家侧视图均未见官方描述。第三方(gen.xyz 博客)提到按到访次数分层(1 次 Neighbors/10 次 Friends/15 次 Fam),仅线索。(未见截图,据官方新闻稿)

**对城瘾**:小数据量的解法范本——**当聚合曲线没内容时,讲「一位客人」的故事**:某玩家第 3 次到店、玩过哪两个主题。城瘾打卡记录天然有 member + 主题上下文,做「最近到店玩家」单客行成本极低。

### 2.10 附:Instagram Insights / Questo(负结果)

- **Instagram**:help.instagram.com JS 渲染,官方正文未抓到,**官方源未覆盖**;第三方线索(Reached/Engaged/Followers 三大块+画像)不作结论。
- **Questo**:官方创作者页(https://questoapp.com/creators ;https://questoapp.com/creator-room )只确认分成至高 75%、按月结算、免费 quest builder;**未找到任何创作者数据面板的官方描述**——无证据表明存在成型的 creator analytics。城市探索赛道里「给供给侧看数据」这件事基本没人做好,城瘾做了就是差异点。

## 3. 共性规律

1. **面板第一职责是归因,不是统计**。Fivestars 官方直说 dashboard 用来"证明我们每天带来的价值";Yelp 的 Leads、GBP 的 Interactions 全是「平台带来的行动」。商家数据面的主语是「平台为你带来了__」,不是「你的店发生了__」。
2. **漏斗是通用叙事骨架**:曝光→访问/到店→行动(Yelp 三区块、GBP Views→Interactions、美团官方文案的流量→转化)。首屏先回答「多少人看见我」,再谈转化。
3. **孤立数字没有意义,对比才有**:Square 的 loyalty vs non-loyalty、Yelp 的 3 竞对对比、Foursquare 的新客 vs 回头客。数据量小的时候,「对比」是把数字变成结论的最便宜手段。
4. **自然语言诊断是国内商家端标配概念、海外反而少**:美团「全新诊断,洞察机会和问题」、点评「经营参谋」、抖音「智能诊断+策略建议」;海外(Yelp/GBP/Square)没有 AI 卡,Yelp 用「你只被发现了 1%」这类**漏斗解读文案**承担同一职能。两条路线殊途同归:把转化率翻译成一句人话。
5. **列表是被低估的图表**:GBP 搜索词列表、Square Top Customers、Yelp Activity 通知流。数据稀疏时列表比曲线诚实且信息密度更高。
6. **单客叙事是小数据的正解**:Blackbird 整个商家侧就是一张单客卡(到访史+分层+分值),没有聚合报表也成立。
7. **时间序列底座都是日粒度**(Foursquare venues/timeseries、GBP DailyMetric),但展示档位是大跨度(Yelp 30天/12月/24月)——采集细、展示粗。

## 4. 给城瘾参谋页的落地建议

前提:几十单数据量、微信小程序浅色黑白系 DS、现有组件(cy-card/cy-icon/chips/分段胶囊条/工作台柱图)。原则:**每一屏都要在小数据下成立**——不出现空稀图表,不出现 0.0% 的尴尬比率。

### ① 信息架构(首屏什么最重要)

- **第 1 屏 = 归因价值卡(新增,页面主角)**:一张大卡回答「城瘾为你带来了什么」——本期(近 30 天)`到店 N 位玩家 · 打卡 M 次 · 核销 K 单`,副行环比(「比上期 +3 位」,期内无数据则不显示环比而非显示 0%)。这是 Fivestars 的"价值证明"定位 + Yelp Performance Summary 的位置。现有三张指标卡从「页面开场」降级为第 2 屏。
- **第 1.5 屏 = 迷你漏斗条**:`主题曝光 → 到店打卡 → 核销` 三段横条(数据来自现有指标的分子分母重排),对齐 Yelp/GBP/美团的漏斗骨架。曝光取不到就两段(打卡→核销),别硬造。
- **第 2 屏 = 三张指标卡改横滑或 2+1 网格** + 一条**新客 vs 回头客**双色对比条(Foursquare 一级字段、Square 核心卖点;复购率已有,拆成人数对比更直观)。
- **第 3 屏 = 最近到店玩家(新增,Blackbird 式单客行)**:头像 + 昵称脱敏 + 「第 3 次来 · 来自〈主题名〉 · 昨天 19:40」,列 5 行。**这是小数据量下信息密度最高、最不像空壳的模块**,数据几十单恰好够。
- **第 4 屏 = 时段 + 客群**(现有模块合并收紧)。
- **第 5 屏 = AI 经营解读 + 建议活动卡**(现有,保底部位置不变——先给事实再给解读的顺序是对的)。

### ② 图表形态选型

- **24 列时段柱图 → 收成 4 段**(早/午/晚/夜)粗胶囊条,或 7 天 × 4 时段热力块(周视角)。几十次打卡摊到 24 列必然稀稀拉拉,这是「单调」观感的主要来源之一;4 段在 30 次样本下也能形成明暗差。
- **对比一律用双段横条**(新客/回头客、男/女),不用环形——环形在 n<50 时显得煞有介事,横条 + 两个数字更诚实,也贴现有分段胶囊语言。
- **来源列表替代来源图**:「打卡来自哪个主题」做 GBP 搜索词式列表(主题名 + 次数 + 细进度条),不做饼图。
- **趋势线暂不做**:日粒度折线在几十单下是心电图停跳,等单量上百再加 7 日折线(对齐工作台 revenue7d)。

### ③ AI 建议模块怎么呈现不尴尬

- **学 Yelp 的漏斗解读文案,不学「报告腔」**:AI 解读第一句固定为一条翻译过的转化事实(「30 天里有 18 位玩家打卡,其中 12 位核销——每 3 个到店有 2 个坐下消费」),而非「本店经营状况良好」。数据不足时退化为单客事实(「本周有位玩家第 3 次到店」),Blackbird 式叙事永远有料。
- **学美团的措辞框架**:解读固定「1 个机会 + 1 个问题」两条,各一行,带 icon 区分;不凑长段落。
- **建议卡保留「一键去办」**(这已是同类里的先进形态,抖音/美团的诊断也是导向动作),但每卡加一行「依据:」引用具体数字,让建议看起来是算出来的不是编的。
- **样本量声明**:数据 <10 条时在 AI 卡顶部加小字「数据还少,以下解读仅供参考」——比硬算百分比可信。
- 空态维持现有「先去合作中心承接主题」引导,但把空图表整屏隐藏,只留引导卡。

### ④ 视觉上怎么不单调(小程序浅色 DS 下)

- **每屏保证一个非数字元素**:第 1 屏大卡的环比箭头(涨绿跌灰,黑白系下用现有 tone 语义色)、第 3 屏头像行、第 4 屏热力块明暗、第 5 屏 chips——全页不再是「数字卡连发」。
- **归因价值卡用现有 `--cy-bg-accent-weak` 弱底 + 大号数字**,与后面白卡拉开一档层次;其余卡维持白底,靠 icon 徽章 tone 区分(现有 metric--tone 机制直接复用)。
- **单客行是唯一带头像的模块**,天然成为页面视觉锚点,零新组件(复用 member 头像 + cy-card)。
- 热力块/对比条深浅只用中性灰阶 + 一个品牌色,遵守商家侧黑白系;不引入新色板。

---

**本轮硬缺口(后续可补)**:抖音来客「经营参谋/生意经」与美团经营宝数据页的真实界面形态(官方文档登录墙)——若要形态级参照,唯一干净路径是注册测试商家号实拍,而非引用坊间截图。
