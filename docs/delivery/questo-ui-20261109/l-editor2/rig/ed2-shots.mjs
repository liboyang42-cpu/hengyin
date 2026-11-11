// l-editor2 取证驱动(只在 rig 跑):RIG_SHOTS=<dir> node ed2-shots.mjs before|after
const { launch } = await import((process.env.RIG_DIR || '/root/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/publish/fabu/index', identity: 'e2e-openid-9002', agree: true });
await sleep(2500);
await w.tap('＋ 创建章节');
await sleep(1500);
await w.eval(`(() => {
  const p = getCurrentPages().slice(-1)[0];
  const ci = p.data.storyEditor.chapterIndex;
  p.setData({ ['formData.chapters[' + ci + '].name']: '雨后的咖啡馆' });
  const k = () => p._genLocalId();
  const paras = ['雨停了，石板路上还有积水。', '她推开咖啡馆的门，铃铛响了一声。吧台后面没有人，只有一杯还冒着热气的咖啡。杯子旁边压着一张折起来的纸条，纸角被咖啡洇湿了一小块。', '杯底压着一张纸条。'];
  let at = 0;
  for (const text of paras) { const key = k(); p._storyCommand({ type: 'insertTextAt', index: at, blockKey: key }); p._storyCommand({ type: 'editText', blockKey: key, content: text }); at++; }
  p._insertStoryMedia('gallery', 1, { layout: 2 });
  const g = p.data.formData.chapters[ci].blocks.find((b) => b.type === 'gallery');
  p._storyCommand({ type: 'fillGalleryCell', blockKey: g.key, index: 0, url: '/images/home-route-city-placeholder.jpg' });
  p._storyCommand({ type: 'fillGalleryCell', blockKey: g.key, index: 1, url: '/images/home-route-free-placeholder.jpg' });
  p.setData({ 'storyEditor.focusBlockKey': '', 'storyEditor.selectedBlockKey': '' });
})()`);
await sleep(1200);
await w.shot(`ED2-editor-${tag}`);
const page = w.page;
const order = () => w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; return p.data.formData.chapters[p.data.storyEditor.chapterIndex].blocks.map(b => b.type + (b.content ? ':' + b.content.slice(0, 6) : '')); })()`);
const state = () => w.eval(`(() => { const d = getCurrentPages().slice(-1)[0].data; return { drag: d.storyDrag.key, selected: d.storyEditor.selectedBlockKey }; })()`);
console.log('ORDER0', JSON.stringify(await order()));

// 1. 长按长正文段(第 3 块)并往下拖
let types = await order();
let blocks = await page.$$('pierce/.story-block');
const longIdx0 = types.findIndex((t) => t.startsWith('text:她推开咖啡馆'));
let box = await blocks[longIdx0].boundingBox();
let x = box.x + box.width / 2, y = box.y + box.height / 2;
await page.mouse.move(x, y); await page.mouse.down(); await sleep(700);
await w.shot(`ED2-text-longpress-${tag}`);
console.log('TEXT-HOLD', JSON.stringify(await state()));
await page.mouse.move(x, y + 120, { steps: 5 }); await sleep(250);
await w.shot(`ED2-text-drag-${tag}`);
await page.mouse.up(); await sleep(600);
await w.shot(`ED2-text-release-${tag}`);
console.log('TEXT-AFTER', JSON.stringify(await state()), JSON.stringify(await order()));
// 正文被挪动了(底座)就撤销回原顺序,让两边的图片拖动从同一状态开始
if ((await order())[2] !== types[longIdx0]) { await w.eval(`getCurrentPages().slice(-1)[0].undoStoryStep()`); await sleep(400); console.log('UNDONE', JSON.stringify(await order())); }
await w.eval(`getCurrentPages().slice(-1)[0].setData({ 'storyEditor.selectedBlockKey': '', 'storyEditor.focusBlockKey': '', storyScrollTop: 1 })`);
await sleep(300);
await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p._storyScrollNow = 0; p.setData({ storyScrollTop: 0 }); })()`);
await sleep(700);

// 2. 长按图片组拖进长正文段中间(句子之间),放下
types = await order();
blocks = await page.$$('pierce/.story-block');
const gi = types.findIndex((t) => t === 'gallery');
const longIdx = types.findIndex((t) => t.startsWith('text:她推开咖啡馆'));
box = await blocks[gi].boundingBox();
x = box.x + box.width / 2; y = box.y + box.height / 2;
console.log('GBOX', JSON.stringify(box));
const tb = await blocks[longIdx].boundingBox();
await page.mouse.move(x, y); await page.mouse.down(); await sleep(700);
await w.shot(`ED2-image-lift-${tag}`);
console.log('IMG-HOLD', JSON.stringify(await state()));
const ty = tb.y + tb.height * 0.5;
await page.mouse.move(x, ty, { steps: 8 }); await sleep(500);
await w.shot(`ED2-image-split-${tag}`);
await page.mouse.up(); await sleep(800);
await w.shot(`ED2-image-drop-${tag}`);
console.log('IMG-DROP', JSON.stringify(await order()));
console.log('errors', JSON.stringify(w.errors()));
await w.browser.close();
