// 自由探索游玩页三屏实拍(2026-09-07):卡片详情五段 / 章节剧情故事流 / 店铺分身对话。
//
// ⚠️ 这是 fixture 驱动的**布局**验收,不是「后端真的会下发这些字段」的证据。
//    hookText / npc / perk 三项由后端本轮新加,那一半的证据是 ApiPlayProgressController 的
//    输出 map 加了对应 put 且整模块编译通过 —— 别拿这里的截图当生产事实。
//
// 用法:NODE_PATH=<repo>/chengyinhub-xcx/node_modules WX_AUTO_PORT=9562 node scripts/_shot_free_explore.js

// 有效期写死会变成定时炸弹:过了那天再跑,拍出来的全是「已过期」态,而且不报错。
const inDays = (n) => { const d = new Date(Date.now() + n * 864e5);
  const p = (v) => String(v).padStart(2, '0');
  // ⚠️ 不能用 toISOString():那是 UTC,东八区会整体退回前一天,
  //    「有效期按中国时区格式化」那条断言当场判红。按本地时间逐位拼。
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' 23:59:59'; };
const { spawn } = require('child_process');
const net = require('net');
const fs = require('fs');
const automator = require('miniprogram-automator');
require('miniprogram-automator/out/MiniProgram').default.prototype.checkVersion = async () => {};
const PROJECT = require('path').resolve(__dirname, '..');
const OUT = '/Users/arlan/Desktop/自由探索游玩页实拍';

const isPortFree = (port) => new Promise((r) => { const s = net.createServer(); s.once('error', () => r(false)); s.once('listening', () => s.close(() => r(true))); s.listen(port, '127.0.0.1'); });
async function launchFixed({ projectPath, cliPath = '/Applications/wechatwebdevtools.app/Contents/MacOS/cli', port = 9420, timeout = 60000 }) {
  spawn(cliPath, ['auto', '--project', projectPath, '--auto-port', String(port), '--trust-project'], { stdio: 'ignore', detached: true }).unref();
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline && (await isPortFree(port))) await new Promise((r) => setTimeout(r, 500));
  const mp = await automator.connect({ wsEndpoint: `ws://127.0.0.1:${port}` });
  const alive = Date.now() + timeout;
  while (Date.now() < alive) { try { await mp.pageStack(); return mp; } catch (e) { await new Promise((r) => setTimeout(r, 1000)); } }
  throw new Error('DevTools 连上了但逻辑层一直没响应');
}

const names = ['晨间烘焙', '旧书与唱片', '午后小酌', '手作时间'];
const shops = ['Bakehouse 巨鹿路店', '长乐路旧物店', '安福路小酒馆', '陶所 · 五原路'];
// 长短各一:第 2 章是三段(验「多则分段」),第 3 章只有一句(验「少则一句」)
const chapDesc = [
  '一天从一炉可颂开始。这一章讲的是安福路清晨六点半的样子:烤箱预热、面团回温、第一批客人还没来。',
  '午后两点,光从长乐路的梧桐叶间漏进来。\n\n这一章是关于一家开了十一年的旧物店,和一本 1998 年的书。\n\n老板不会主动搭话,你得先说出一个年份。\n\n店里没有分区,书是按他记得的顺序摆的。\n\n唱片在最里面那排,可以点播,一次一首。\n\n找到那本书,翻到扉页,上面有一句手写的话。',
  '四点半以后的安福路属于露台。',
  '黄昏收尾在五原路的一间陶艺工作室。这一章不着急,拉一只杯子,烧好要等一周。',
];

function seed() {
  return {
    mode: 2, topicId: 'T-fx', topicName: '安福路慢半拍的下午', selfPlay: true, expiresAt: inDays(7),
    // 通行证首屏的简介是**主题**的,不是第一章的
    topicDesc: '一个慢下来的下午,从巨鹿路的第一炉可颂走到五原路的陶艺工作室。四家店各出一份权益,30 天内去哪家、先去哪家都由你,只去一家也算。',
    chapters: names.map((n, i) => ({
      chapterId: 100 + i, idxLabel: '第 ' + (i + 1) + ' 章', name: n,
      imgArr: '/scripts/_fixtures/shop' + (i + 1) + '.jpg', description: chapDesc[i],
    })),
    nodes: names.map((_, i) => ({
      nodeId: i + 1, chapterId: 100 + i, name: shops[i], done: i === 0,
      imgUrl: '/scripts/_fixtures/shop' + (i + 1) + '.jpg',   // 与样机同一组真实照片(gitignore 覆盖,不进仓)
      address: ['巨鹿路 758 号', '长乐路 139 号', '安福路 322 号', '五原路 288 号'][i],
      businessTime: '12:00-21:00', lat: 31.21 + i * 0.004, lng: 121.44 + i * 0.004,
      hasGame: true, validationMethod: [2, 3, 3, 1][i],
      gameTitle: ['拉花挑战', '找一本 1998', '盲品三杯', '拉坯 30 分钟'][i],
      duration: 15, difficulty: '轻松', players: '1–2 人',
      ruleInstructions: '进店对老板说出一个年份:1998\n在他指的那排书架里找封面是唱片的书\n翻到扉页,回答上面手写的问题',
      requiredMaterials: '一张纸巾',
      // 本轮新增的三项
      hookText: '老板不会主动搭话,你得先说出一个年份。',
      npc: { name: '阿旧', avatar: '', greeting: '' },
      perk: { name: '到店赠明信片', redeemRule: '到店出示核销', validEnd: inDays(7) },
      arrived: i === 0, gameDone: i === 0,
      audioUrl: i === 1 ? 'https://example.invalid/ch2.mp3' : '', audioDuration: i === 1 ? 46 : 0,
    })),
  };
}

(async () => {
  if (!fs.existsSync(OUT)) fs.mkdirSync(OUT, { recursive: true });
  const mp = await launchFixed({ projectPath: PROJECT, port: Number(process.env.WX_AUTO_PORT) || 9562 });
  const errors = [];
  mp.on('console', (m) => { if (m.type === 'error') errors.push(String(m.args && m.args[0]).slice(0, 200)); });
  const shot = async (name) => { await mp.screenshot({ path: `${OUT}/${name}.png` }); console.log('📸', name); };
  const asserts = [];
  const ok = (cond, label) => { asserts.push((cond ? '✅ ' : '❌ ') + label); };

  const open = async () => {
    const page = await mp.reLaunch('/pages/play/index?preview=1'); await page.waitFor(1400);
    await page.callMethod('applyPreviewData', seed()); await page.waitFor(1000);
    // 卡包动画跳过:直接置 packOpen,否则量瓦片量到的是半空中的矩形
    await page.setData({ packOpen: true, packDim: false });
    await page.waitFor(900);
    return page;
  };

  // ── ① 通行证首屏(卡片样式对照) ──
  const page = await open();
  await shot('00-通行证首屏(六宫格卡片)');
  const td = await page.data('topicDesc');
  ok(td && td.indexOf('四家店各出一份权益') >= 0, '首屏简介取的是主题简介,不是第一章的描述');
  // 瓦片高度是 JS 按剩余屏高等分算的(tileH 内联在 style 上),和样机 .grid{flex:1} 同一个做法。
  // ⚠️ 别拿缩略图目测它「比样机高」——两张图的手机框高度不同,那是假阳性。
  const tileEl = await page.$('.fx-tile');
  const tileR = tileEl ? await tileEl.size() : { height: 0 };
  ok(tileR.height > 150 && tileR.height < 420, '瓦片高度落在按屏高等分的合理区间,实测 ' + tileR.height.toFixed(0));
  // 卡片尺寸恒定:高 = 宽 × 1.38,与章数无关(3 张右边空一格,5/6 张往下滚)
  const t4 = await page.data('seatTiles');
  ok(t4.every((t) => !t.wide), '没有通栏卡:任何章数都是恒定两列');
  ok(Math.abs(tileR.height / tileR.width - 1.38) < 0.06,
    '卡片比例恒定在 1.38,实测 ' + (tileR.height / tileR.width).toFixed(2));
  const tiles0 = await page.data('seatTiles');
  ok(tiles0 && tiles0[0].done && !tiles0[0].statusText, '已核销的卡不挂标签,只把整张阴掉');

  // ── ② 卡片详情五段 ──
  await page.callMethod('onSeatTap', { currentTarget: { dataset: { id: 2, idx: 1 } } });
  await page.waitFor(1200);
  await shot('01-卡片详情-顶部(章节+店铺卡)');
  // 卡片压在正文之上后,横向拖仍能转、竖向拖要能代滚正文
  await page.callMethod('onCardTouchStart', { touches: [{ pageX: 200, pageY: 400 }] });
  await page.callMethod('onCardTouchMove', { touches: [{ pageX: 320, pageY: 404 }] });
  await page.callMethod('onCardTouchEnd');
  const hx = await page.data('hero');
  ok(Math.abs(hx.ry) > 10 || hx.ry === 0, '横向拖改变转角(松手吸附到最近一面),实测 ry=' + hx.ry);
  await page.callMethod('onCardTouchStart', { touches: [{ pageX: 200, pageY: 400 }] });
  await page.callMethod('onCardTouchMove', { touches: [{ pageX: 202, pageY: 260 }] });
  const hy = await page.data('hero');
  await page.callMethod('onCardTouchEnd');
  ok(hy.scrollTop > 100, '竖向拖代滚正文(手指落在卡片上也能滚页面),实测 ' + hy.scrollTop);

  // 拍卡面前把正文滚回顶部:上面那次竖向拖已经把正文滚下去了,卡片会跟着缩小
  await page.setData({ 'hero.scrollTop': 0, 'hero.ss': '1', 'hero.collapsed': false, 'hero.faded': false });
  await page.waitFor(500);
  // 3D 卡盒:转到 180° 看背面、转到 60° 看侧脊
  await page.setData({ 'hero.ry': 180, 'hero.spun': true }); await page.waitFor(700);
  await shot('01b-卡片背面(只写章节介绍)');
  // 60° 而不是 92°:92° 是刀锋角,只剩一条厚度什么也看不出来
  await page.setData({ 'hero.ry': 60, 'hero.turned': true }); await page.waitFor(700);
  const h60 = await page.data('hero');
  const el = await page.$('.fx-card3d'); const r60 = el ? await el.size() : null;
  console.log('  60° 时 hero.ry=' + h60.ry + ' turned=' + h60.turned
    + ' 卡盒盒宽=' + (r60 ? r60.width.toFixed(1) : 'n/a'));
  await shot('01c-卡片侧脊(卡片是有厚度的)');
  await page.setData({ 'hero.ry': 0, 'hero.turned': false }); await page.waitFor(500);
  const hz = await page.data('hero');
  ok(hz && hz.spun === true, '自转一圈后停在正面并接上呼吸浮动');
  const hv = await page.data('hero');
  ok(hv && hv.show, 'hero 层已打开');
  ok(hv && hv.node && hv.node.chapName === '旧书与唱片', '章节名来自 chapterCards join,不是店名');
  ok(hv && hv.node && hv.node.hook === '老板不会主动搭话,你得先说出一个年份。', 'hookText 落到「小瘾说」而不是回落 description');
  ok(hv && hv.node && hv.node.npcName === '阿旧', '门店分身名进了店铺卡');
  ok(hv && hv.node && hv.node.perkName === '到店赠明信片', '本店权益取到 CoopPerk.name');
  // 期望值也从同一个 inDays 推,别再写死一个日期 —— 写死的话它自己就是下一颗定时炸弹
  const vd = inDays(7).slice(0, 10).split('-');
  const wantValid = '有效期至 ' + Number(vd[1]) + '/' + Number(vd[2]);
  ok(hv && hv.node && hv.node.perkValid === wantValid,
    '有效期按中国时区格式化,期望「' + wantValid + '」实测「' + (hv && hv.node && hv.node.perkValid) + '」');
  ok(hv && hv.node && hv.node.howto.length === 3, '「怎么玩」按换行拆成 3 条');
  // 章节全文归故事流,详情页不再重复铺一遍
  const wx0 = require('fs').readFileSync(require('path').resolve(__dirname, '../pages/play/index.wxml'), 'utf8');
  ok(wx0.indexOf('hero.node.chapParas') < 0, '详情页不再铺章节正文(全文在故事流里)');
  ok(wx0.indexOf('fx-card3d__ttl') < 0 && wx0.indexOf('fx-card3d__sp') < 0,
    '卡面与侧脊不写字;章节名与介绍只在背面');
  ok(wx0.indexOf('fx-card3d__bh') > 0 && wx0.indexOf('fx-card3d__bp') > 0, '背面保留章节名与章节介绍');
  ok(hv && hv.node && hv.node.gameMeta.length === 3, '游戏 meta 三条(时长/人数/奖励)');

  // 正文滚到底:到店三步 + 本店权益在按钮上方
  // 不改 bodyTop:那会让正文压到卡片上,是驱动造出来的假象不是页面问题
  const body = await page.$('.fx-hero__body');
  if (body) { try { await body.scrollTo(0, 2400); } catch (e) {} }
  await page.waitFor(900);
  const hs = await page.data('hero');
  console.log('  滚到底 卡片缩放 ss=' + hs.ss + ' 顶栏接管=' + hs.collapsed + ' 卡片淡出=' + hs.faded);
  ok(Number(hs.ss) < 0.3, '正文滚上去后卡片缩到很小,实测 ' + hs.ss);
  ok(hs.collapsed === true, '顶栏换成缩略图那条紧凑条');
  await shot('02-卡片详情-下半(游戏+到店三步+本店权益)');
  // 滚到底之后「本店权益」必须整块露出来,不能被底栏压掉
  const perkEl = await page.$('.fx-perk');
  if (perkEl) {
    const bodyEl = await page.$('.fx-hero__body');
    const pr = await perkEl.offset(), sr = bodyEl ? await bodyEl.offset() : { top: 0 };
    console.log('  权益块 offsetTop=' + pr.top.toFixed(0) + ' 正文容器 top=' + sr.top.toFixed(0));
  }
  ok(!!perkEl, '滚到底能看到本店权益');

  // 已核销那一家:到店三步应当整排点亮(第 1 家 arrived/gameDone/done 都为真)
  await page.callMethod('closeHero'); await page.waitFor(700);
  await page.callMethod('onSeatTap', { currentTarget: { dataset: { id: 1, idx: 0 } } }); await page.waitFor(1300);
  const hd = await page.data('hero');
  ok(hd.node.stepArrive && hd.node.stepProof && hd.node.stepRedeem, '已核销的店三步全亮');
  ok(hd.node.done === true, '已核销的卡:底部按钮是禁用态「已核销」,点不动');
  const bd = await page.$('.fx-hero__body');
  if (bd) { try { await bd.scrollTo(0, 1500); } catch (e) {} }
  await page.waitFor(900);
  await shot('11-到店三步全亮(绿)与本店权益(橙)');
  await page.callMethod('closeHero'); await page.waitFor(700);
  await page.callMethod('onSeatTap', { currentTarget: { dataset: { id: 2, idx: 1 } } }); await page.waitFor(1300);

  // ── ③ 章节剧情故事流(第 2 章三段) ──
  await page.callMethod('openChapStory'); await page.waitFor(1400);
  await shot('03-故事流-进入(首屏已显形)');
  const st = await page.data('chapStory');
  console.log('  story =', JSON.stringify(st).slice(0, 300));
  ok(st && st.show, '故事流已打开');
  const paras = (st.lines || []).filter((l) => l.text);   // 图片块也在 lines 里,只数文字块
  ok(paras.length === 6, '第 2 章按空行拆成 6 段(多则分段),实测 ' + paras.length);
  ok((st.lines || []).some((l) => l.img), '章节封面作为图片块混排进故事流');
  ok(st && (st.lines || []).some((l) => l.in), '停下来那一刻已有行显形');
  // 粒子是不是真的在动:静态截图看不出,读 rAF 帧计数
  const fr1 = (await page.data('chapStory')).frames;
  await page.waitFor(1600);
  const fr2 = (await page.data('chapStory')).frames;
  console.log('  粒子 rAF 帧数 1.6s 内: ' + fr1 + ' -> ' + fr2);
  ok(fr2 > fr1, '漂浮粒子在持续重绘(rAF 帧数在涨),实测 ' + fr1 + ' -> ' + fr2);
  const stA = await page.data('chapStory');
  ok(stA && stA.audio === 'https://example.invalid/ch2.mp3', '这一章的音频进了故事流');
  ok(stA && stA.audioDur === '46″', '时长带单位');
  // ── 滚动显形动效:真的滚一段,量「滚动中没有新显形」与「停下来之后才显形」 ──
  const sv = await page.$('.st-scroll');
  const inCount = async () => ((await page.data('chapStory')).lines || []).filter((l) => l.in).length;
  const before = await inCount();
  if (sv) { try { await sv.scrollTo(0, 1500); } catch (e) {} }
  await page.waitFor(40);                       // 还在防抖窗口内(70ms)
  const during = await inCount();
  const barMid = (await page.data('chapStory')).barAway;
  ok(barMid === true, '滑动中底栏收下去了');
  await shot('10-故事流-滚动中(底栏收起、新进屏的行还没显形)');
  await page.waitFor(1200);                     // 停下来 + 过渡跑完
  const after = await inCount();
  const ds = ((await page.data('chapStory')).lines || []).filter((l) => l.in).map((l) => l.d);
  const barRest = (await page.data('chapStory')).barAway;
  ok(barRest === false, '停下来底栏弹回来');
  console.log('  显形数 滚前=' + before + ' 滚动中=' + during + ' 停下后=' + after + ' 错峰=' + JSON.stringify(ds));
  ok(new Set(ds).size > 1, '同一批显形的行错峰入场,不是整块一起亮');
  ok(after > during, '停下来那一刻才补显形(滚动中 ' + during + ' → 停下后 ' + after + ')');
  await shot('07-故事流-图片块与音频块');
  // 再滚回去:出屏复位过的行要能重演,而不是「向下滚完再向上就没效果了」
  if (sv) { try { await sv.scrollTo(0, 0); } catch (e) {} }
  await page.waitFor(1200);
  const back = await inCount();
  ok(back > 0, '滚回顶部后仍有行处于显形态(复位后能重演),实测 ' + back);
  await page.callMethod('closeChapStory'); await page.waitFor(700);

  // 短章节:只有一句
  await page.callMethod('closeHero'); await page.waitFor(700);
  await page.callMethod('onSeatTap', { currentTarget: { dataset: { id: 3, idx: 2 } } }); await page.waitFor(1100);
  await page.callMethod('openChapStory'); await page.waitFor(1300);
  const st2 = await page.data('chapStory');
  console.log('  story2 =', JSON.stringify(st2).slice(0, 300));
  ok((st2.lines || []).filter((l) => l.text).length === 1, '第 3 章只有一句(少则一句,不硬凑段)');
  await shot('04-故事流-短章节只有一句');
  const stB = await page.data('chapStory');
  ok(stB && !stB.audio, '没配音频的章节不渲染那颗按钮(不留点了没反应的壳)');
  await page.callMethod('closeChapStory'); await page.waitFor(700);

  // ── ④ 店铺分身对话 ──
  await page.callMethod('openShopNpc'); await page.waitFor(1200);
  await shot('05-店铺分身-开场问候(居中大字)');
  const np = await page.data('shopNpc');
  ok(np && np.show, '对话层已打开');
  ok(np && /好，我是阿旧，欢迎来到/.test(np.greeting), '问候语按时段拼且带分身名');
  ok(np && np.started === false, '还没开口时问候语占着中间');
  await page.callMethod('onShopNpcFocus'); await page.waitFor(600);
  const np2 = await page.data('shopNpc');
  ok(np2 && np2.started === true, '点输入框后问候语让位');
  await page.setData({ 'shopNpc.msgs': [
    { id: 1, role: 'me', text: '几点关门' },
    { id: 2, role: 'ai', text: '我们今天 12:00–21:00。你现在过来正好,人不多。' },
  ], 'shopNpc.thinking': true });
  await page.waitFor(700);
  await shot('06-店铺分身-消息流与加载态(第一拍:正在输入)');
  // 等待名条第二拍:一句话不动地挂着像卡住,超过一秒换一拍说明「还在,只是慢」
  await page.setData({ 'shopNpc.thinkPhase': 1 });
  await page.waitFor(500);
  await shot('06b-店铺分身-加载第二拍(正在回答)+回答下的复制重答');
  // 重答:把这条回答之前最近的那句我方提问再问一次(不动输入框里正在打的字)
  await page.setData({ 'shopNpc.thinking': false, 'shopNpc.input': '正在打的半句' });
  await page.callMethod('retryNpcMsg', { currentTarget: { dataset: { idx: 1 } } });
  await page.waitFor(400);
  const rt = await page.data('shopNpc');
  ok(rt && rt.input === '正在打的半句', '重答不许改玩家正在打的字,实测「' + (rt && rt.input) + '」');
  ok(rt && rt.msgs.length === 2, '重答改写原来那一条,不追加新回答,实测 ' + (rt && rt.msgs.length));
  ok(rt && rt.msgs[1] && rt.msgs[1].id === 2, '被改写那条的 id 不变(换 id 会让整条重新入场闪一下)');
  // 预览态没有后端,这一次重答必然失败 —— 正好用来验「失败不许覆盖原文」
  ok(rt && rt.msgs[1] && /12:00/.test(rt.msgs[1].text),
    '重答失败必须保住原来那条答案,实测「' + (rt && rt.msgs[1] && rt.msgs[1].text) + '」');

  // 形象跟手转:±38° 夹紧
  await page.callMethod('onNpcTouchStart', { touches: [{ pageX: 200 }] });
  await page.callMethod('onNpcTouchMove', { touches: [{ pageX: 460 }] });
  await page.callMethod('onNpcTouchEnd');
  const npr = await page.data('shopNpc');
  ok(npr && npr.ry === 38, '右拖到头夹在 +38°,实测 ' + (npr && npr.ry));
  await page.waitFor(400); await shot('08-店铺分身-形象跟手右转到头');
  await page.callMethod('onNpcTouchStart', { touches: [{ pageX: 200 }] });
  await page.callMethod('onNpcTouchMove', { touches: [{ pageX: -400 }] });
  await page.callMethod('onNpcTouchEnd');
  const npl = await page.data('shopNpc');
  ok(npl && npl.ry === -38, '左拖到头夹在 -38°,实测 ' + (npl && npl.ry));

  // 按住说话:录音态下输入框仍在(「点了语音回不了打字」结构上不成立)
  await page.setData({ 'shopNpc.recording': true }); await page.waitFor(1200);
  const inp = await page.$('.fx-npc__input');
  ok(!!inp, '录音态下输入框仍然在,没有被全屏通话态挤掉');
  await shot('09-店铺分身-按住说话(录音态)');
  await page.setData({ 'shopNpc.recording': false });

  console.log('\n—— 断言 ——');
  asserts.forEach((a) => console.log(' ', a));
  console.log('\nCONSOLE_ERRORS:', errors.length);
  errors.slice(0, 6).forEach((e) => console.log('  !', e));
  console.log('通过', asserts.filter((a) => a.startsWith('✅')).length, '/', asserts.length);
  await mp.close();
})().catch((e) => { console.error('FATAL', e && e.message); process.exit(1); });
