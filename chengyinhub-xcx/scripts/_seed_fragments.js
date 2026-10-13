// 自由探索(mode2)随机种子文案:词库拼出 7 组 NPC 钩子 + 碎片文案,注入 play 页,产出一条可玩的
// mode2 测试路线。纯前端注入,不落库,不依赖后端 /api/play/nodes。
//
// 字段名说明(与 task-11-brief.md 词库示例的差异,已核对 pages/play/index.js 源码后做的修正):
// brief 里的 `cardHookLong`/`validationMethod` 是后端原始字段名(过 normNode() 前);而 rebuild()/
// openSheet()/buildJournal() 实际消费的是 normNode() 转换后的字段 `cardHook`/`vm`。这里选择直接
// setData 进 Page.data.nodes(已归一化形状),跳过 normNode 转换直接驱动渲染链——所以用 `cardHook`/
// `vm`,而不是字面照抄 brief 的 `cardHookLong`/`validationMethod`(照抄会导致地点大卡钩子读到
// undefined,断言假绿)。词库内容(HOOKS/FRAGS/人称文案)与 brief 一致,未改动。
//
// 单独运行:node _seed_fragments.js → 打开/复用 DevTools,注入种子,截图确认地图态(不做断言;
// 完整端到端自证见同目录 _verify_freeexplore.js,它 require 本文件复用同一词库,避免两处漂移)。
const automator = require('miniprogram-automator');

const HOOKS = [
  '她最后一次出现在这里', '潮水退了才看得见的东西', '老板娘再没让人坐过靠窗那桌',
  '整点还会响的那座钟', '按气味分类的二手书', '巷口第三家的手冲', '被高楼夹住的一小片绿'
];
const NAMES = ['老茶馆', '老码头', '临窗座', '钟楼', '旧书店', '手冲馆', '口袋公园'];
const FRAGS = HOOKS.map((h, i) =>
  `碎片#${i + 1}:${h}。你走到这里的时候,天正好${i % 2 ? '暗下来' : '亮着'},城市在这一处对你说了半句话。`
);

// 构造 7 个种子节点:lat/lng 递增出一段真实可走的路线,done 均为 false(供逐个 onComplete 推进)。
// 直接落 Page.data.nodes 已归一化形状(见上方字段名说明),setData 后只需手动 callMethod('rebuild')
// 即可驱动 fmNodes/journalCards 整链(loadData() 的 normNode 转换步骤在此被跳过)。
function buildSeedNodes() {
  return HOOKS.map((h, i) => ({
    nodeId: i + 1,
    num: i + 1,
    sortId: i + 1,
    name: NAMES[i],
    address: '',
    description: h,          // 手记正文暂无独立字段,复用钩子文案,保证 buildJournal 的 paras 非空
    businessTime: '',
    lat: 31.23 + 0.002 * i,
    lng: 121.47 + 0.002 * i,
    done: false,
    doneAt: 0,
    cardHook: h,              // 地点大卡钩子(openSheet 消费字段)= 词库 HOOKS
    fragmentText: FRAGS[i],   // 完成翻卡文案(onComplete 消费字段)= 词库 FRAGS
    vm: 0,                    // 对应 brief 词库示例的 validationMethod:0(到点即完成,不开节点任务)
    hasTemplate: false,
    imgUrl: '', points: 12,
    medalName: '', medalImg: '', couponId: 0
  }));
}

module.exports = { HOOKS, NAMES, FRAGS, buildSeedNodes };

// 独立运行:注入种子到 play 页并截图确认(不断言,详细端到端自证见 _verify_freeexplore.js)。
if (require.main === module) {
  const OUT = '/Users/arlan/Downloads/chengyin/小程序截图';
  (async () => {
    const mp = await automator.launch({
      projectPath: '/Users/arlan/Downloads/chengyin/chengyinhub-xcx',
      cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli'
    });
    const page = await mp.reLaunch('/pages/play/index?activityId=');
    await page.waitFor(1800);
    const nodes = buildSeedNodes();
    await page.setData({
      emptyTip: '', emptyKind: '', loading: false, screen: 'map',
      mode: 2, serverMode: 2, modeLabel: '自由探索',
      nodes, total: nodes.length, doneCount: 0, allDone: false
    });
    await page.callMethod('rebuild');
    await page.waitFor(700);
    await mp.screenshot({ path: OUT + '/seed_freeexplore_map.png' });
    console.log('SEEDED 7 nodes, mode=2 · screenshot -> seed_freeexplore_map.png');
    await mp.close();
  })().catch((e) => { console.error('ERR', e && e.message || e); process.exit(1); });
}
