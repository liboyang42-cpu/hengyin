// 自由探索(mode2)端到端自证:照九波风格(wechat-miniprogram-verify)——callMethod 拿真实返回值断言,
// 不是像素验证。种子路线复用 _seed_fragments.js 的词库(HOOKS/FRAGS/NAMES),避免两处漂移。
//
// 断言链(对应 task-11-brief.md Step 2):
//   1) 注入 7 种子节点 + mode:2 后 rebuild() → fmNodes 恰好 1 个 recommended。
//   2) 按「非顺序」的自由探索顺序(nodeId 3→6→1)逐个 callMethod onComplete(nodeId):
//      每次 frag.text 非空;全部完成后 buildJournal() 的 journalCards 前 3 张 label = 我的第 1/2/3 步。
//   3) callMethod openFinish():mock /api/play/ending(mockWxMethod('request', ...),后端未部署,
//      前端插桩验证 openFinish() 的取数/落 data 逻辑本身)返回随机 opener + 按完成序排列的 fragments,
//      断言 ending.fragments 顺序与内容 = 完成序(3,6,1)。
//   4) 5 屏截图:主屏真地图 / 地点大卡钩子 / 碎片翻卡 / 结局信 / 足迹卡。
//   5) CONSOLE_ERRORS 断言必须为 0。
const automator = require('miniprogram-automator');
const { buildSeedNodes } = require('./_seed_fragments.js');

const OUT = '/Users/arlan/Downloads/chengyin/小程序截图';
let pass = 0, fail = 0;
const eq = (name, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log((ok ? '✅' : '❌') + ' ' + name + '  got=' + JSON.stringify(got) + (ok ? '' : ' want=' + JSON.stringify(want)));
  ok ? pass++ : fail++;
};
const ok = (name, cond) => {
  console.log((cond ? '✅' : '❌') + ' ' + name);
  cond ? pass++ : fail++;
};

;(async () => {
  const mp = await automator.launch({
    projectPath: '/Users/arlan/Downloads/chengyin/chengyinhub-xcx',
    cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli'
  });
  const errors = [];
  mp.on('console', (m) => { if (m.type === 'error') errors.push(String(m.args && m.args[0]).slice(0, 160)); });
  mp.on('exception', (e) => errors.push('EXC:' + String(e && e.message).slice(0, 160)));

  // ---- 完成序(有意打乱,不按站序,验证「自由探索真自由」)+ 对应的 mock /api/play/ending 返回 ----
  const seedNodes = buildSeedNodes();
  const completeOrder = [3, 6, 1]; // nodeId,乱序完成
  const ENDING_OPENER = '如果把这一天摊开来看,你其实拆开了很多把锁。';
  const endingFragments = completeOrder.map((nid, i) => {
    const n = seedNodes.find((x) => x.nodeId === nid);
    return { step: i + 1, nodeId: n.nodeId, name: n.name, text: n.fragmentText };
  });
  // functionDeclaration 会被序列化后送进小程序运行时执行,不能闭包引用外部 Node 变量,
  // 所以把动态内容(opener/fragments)以 JSON 字面量的形式内联进函数体字符串里。
  const mockRequestFn = `function (opts) {
    var url = (opts && opts.url) || '';
    if (url.indexOf('/api/play/ending') >= 0) {
      opts.success({ statusCode: 200, data: { code: 200, data: {
        opener: ${JSON.stringify(ENDING_OPENER)},
        fragments: ${JSON.stringify(endingFragments)}
      } } });
      return;
    }
    if (opts && typeof opts.success === 'function') {
      opts.success({ statusCode: 200, data: { code: 200, data: {} } });
    }
  }`;
  await mp.mockWxMethod('request', mockRequestFn);

  // ---- 进 play 页(activityId= 空 → 同步空态,不打真网络),清守卫弹窗,注入种子 + mode:2 ----
  const page = await mp.reLaunch('/pages/play/index?activityId=');
  await page.waitFor(1800);
  await page.setData({
    emptyTip: '', emptyKind: '', loading: false, screen: 'map',
    mode: 2, serverMode: 2, modeLabel: '自由探索',
    nodes: seedNodes, total: seedNodes.length, doneCount: 0, allDone: false,
    sheet: { show: false, node: null, continueText: '前往下一站 →' },
    frag: { show: false, text: '', step: '' },
    ending: { show: false, opener: '', fragments: [] },
    showFinish: false
  });

  // ---- 断言 1:rebuild 后 fmNodes 恰好 1 个 recommended ----
  await page.callMethod('rebuild');
  await page.waitFor(300);

  // ---- 负控:确认校验器能判 FAIL(非橡皮图章) ----
  const failBefore = fail;
  eq('负控-已知不等', 1, 2);
  if (fail === failBefore) throw new Error('负控失败:eq() 没有在 1!==2 时判 FAIL,校验器可能是空转的');
  console.log('✓ 负控通过:校验器能判 FAIL');

  const fmNodes1 = await page.data('fmNodes');
  ok('7 个 fmNodes 已注入', Array.isArray(fmNodes1) && fmNodes1.length === 7);
  eq('rebuild 后恰好 1 个 recommended', fmNodes1.filter((n) => n.state === 'recommended').length, 1);

  // ---- 截图①:主屏真地图 ----
  await page.waitFor(400);
  await mp.screenshot({ path: OUT + '/verify_freeexplore_1_map.png' });
  console.log('SHOT 1/5 map');

  // ---- 截图②:地点大卡钩子(mode2 · sheet.node.cardHook) ----
  await page.callMethod('onPin', { detail: { nodeId: 2 } });
  await page.waitFor(500);
  const sheetNode = await page.data('sheet.node');
  ok('地点大卡钩子非空(place-hook)', !!(sheetNode && sheetNode.cardHook));
  await mp.screenshot({ path: OUT + '/verify_freeexplore_2_placehook.png' });
  console.log('SHOT 2/5 placehook');
  await page.callMethod('closeSheet');
  await page.waitFor(200);

  // ---- 断言 2:按乱序 3→6→1 逐个 onComplete,frag.text 每次非空 ----
  let firstFragShotDone = false;
  for (const nid of completeOrder) {
    await page.callMethod('onComplete', nid);
    await page.waitFor(400);
    const frag = await page.data('frag');
    ok('onComplete(' + nid + ') 后 frag.text 非空', !!(frag && frag.show && frag.text && frag.text.length > 0));
    if (!firstFragShotDone) {
      // ---- 截图③:碎片翻卡(取第一次完成) ----
      await mp.screenshot({ path: OUT + '/verify_freeexplore_3_fragment.png' });
      console.log('SHOT 3/5 fragment');
      firstFragShotDone = true;
    }
    await page.callMethod('closeFragment');
    await page.waitFor(200);
  }

  // ---- 断言 2b:buildJournal 后 journalCards 前 3 张 label = 我的第 1/2/3 步 ----
  await page.callMethod('buildJournal');
  await page.waitFor(300);
  const cards = await page.data('journalCards');
  const wantLabels = completeOrder.map((nid, i) => {
    const n = seedNodes.find((x) => x.nodeId === nid);
    return '我的第 ' + (i + 1) + ' 步 · ' + n.name;
  });
  eq('journalCards 前 3 张 label(完成序 3→6→1)', cards.slice(0, 3).map((c) => c.label), wantLabels);

  // ---- 断言 3:openFinish → mock /api/play/ending → ending.fragments 顺序 = 完成序 ----
  await page.callMethod('openFinish');
  await page.waitFor(600); // 等 mock 的 wx.request 异步回调落 setData
  const ending = await page.data('ending');
  ok('ending.show=true', ending && ending.show === true);
  eq('ending.opener = mock 随机开场白', ending && ending.opener, ENDING_OPENER);
  eq('ending.fragments 顺序/内容 = 完成序(3,6,1)', ending && ending.fragments, endingFragments);

  // ---- 截图④:结局信(先隐 showFinish,避免与足迹卡叠加)----
  await page.setData({ showFinish: false });
  await page.waitFor(400);
  await mp.screenshot({ path: OUT + '/verify_freeexplore_4_ending.png' });
  console.log('SHOT 4/5 ending');

  // ---- 截图⑤:足迹卡(切回 showFinish,隐 ending,单独看通关卡)----
  await page.setData({ showFinish: true, 'ending.show': false });
  await page.waitFor(500);
  await mp.screenshot({ path: OUT + '/verify_freeexplore_5_finish.png' });
  console.log('SHOT 5/5 finish');

  // ---- 断言 4:控制台零错误 ----
  eq('CONSOLE_ERRORS=0', errors.length, 0);
  if (errors.length) errors.slice(0, 8).forEach((e) => console.log('  - ' + e));

  console.log('\n== free-explore e2e: ' + pass + ' pass / ' + fail + ' fail ==');
  await mp.restoreWxMethod('request');
  await mp.close();
  if (fail) process.exit(1);
})().catch((e) => { console.error('ERR', e && e.message || e); process.exit(1); });
