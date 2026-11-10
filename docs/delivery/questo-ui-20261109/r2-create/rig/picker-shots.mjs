// r2-create 玩法模板选择(a5-v3-46/47)、节点「修改原模板」(a5-v3-31)、预设入口(b4-21)取证(rig only)。
// 用法: RIG_SHOTS=<dir> node picker-shots.mjs <before|after>
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// b4-21:玩法引导页(圆形旋转开场动画 + 开始创建),改版前多一颗「从预设开始」
{
  const w = await launch({ path: 'pages/publish/template-intro/index', identity: 'e2e-openid-9002', agree: true });
  await sleep(3500);
  await w.shot(`b4-21-${tag}`);
  await w.browser.close();
}

const w = await launch({ path: 'pages/publish/fabu/index', identity: 'e2e-openid-9002', agree: true });
await sleep(2500);
await w.tap('＋ 创建章节');
await sleep(1500);
// 在故事流里插一个「玩法」→ 节点弹层 → 玩法模板
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; const k = p._genLocalId();
  p._storyCommand({ type: 'insertTextAt', index: 0, blockKey: k }); p._storyCommand({ type: 'editText', blockKey: k, content: '雨停了，石板路上还有积水。' });
  p.setData({ 'storyEditor.focusBlockKey': '' });
  p.onStoryToolbarInsert({ currentTarget: { dataset: { kind: 'game' } } }); })()`);
await sleep(2000);
await w.shot(`a5-v3-31-sheet-${tag}`);
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ nodeSheetView: 'games' }); if (p.getPublicTempList) p.getPublicTempList(); })()`);
await sleep(2500);
await w.shot(`a5-v3-46-${tag}`);
// 选第一张公共玩法(若有)
try {
  await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; const it = (p.data.publicTempList || [])[0] || (p.data.tempList || [])[0]; if (it) p.selectTemp({ currentTarget: { dataset: { item: it, source: (p.data.publicTempList || [])[0] ? 'public' : 'mine' } } }); })()`);
  await sleep(800);
  await w.shot(`a5-v3-47-${tag}`);
  await w.tap('确定', { exact: true });
  await sleep(3500);
  await w.shot(`a5-v3-31-${tag}`);
} catch (e) { console.log('pick step', e.message); }
console.log('text', (await w.text()).slice(0, 700));
console.log('errors', JSON.stringify(w.errors()));
await w.browser.close();
