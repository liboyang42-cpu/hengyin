// r2-create 故事编辑器取证驱动(只在 rig 里跑,不进产品代码)。
// 用法: RIG_SHOTS=<dir> node editor-shots.mjs <tag> [drag]
//   tag = before | after,决定截图名后缀;drag 额外录一组拖拽帧序列。
// 走真实页面:商家 9002 → fabu → 「＋ 创建章节」→ 故事流编辑器。正文用页面自己的
// _storyCommand 写入(rig 没有软键盘,逐字敲 textarea 没有意义),排版同理走页面方法。
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');

const tag = process.argv[2] || 'after';
const wantDrag = process.argv.includes('drag');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const w = await launch({ path: 'pages/publish/fabu/index', identity: 'e2e-openid-9002', agree: true });
await sleep(2500);
await w.tap('＋ 创建章节');
await sleep(1500);
await w.shot(`a5d-empty-${tag}`);

// 写一章:四段文字 + 分割线 + 一张图 + 一段音频占位
await w.eval(`(() => {
  const p = getCurrentPages().slice(-1)[0];
  const ci = p.data.storyEditor.chapterIndex;
  p.setData({ ['formData.chapters[' + ci + '].name']: '雨后的咖啡馆' });
  const k = () => p._genLocalId();
  const paras = ['雨停了，石板路上还有积水。', '她推开咖啡馆的门，铃铛响了一声。', '吧台后面没有人，只有一杯还冒着热气的咖啡。', '杯底压着一张纸条。'];
  let at = 0;
  for (const text of paras) {
    const key = k();
    p._storyCommand({ type: 'insertTextAt', index: at, blockKey: key });
    p._storyCommand({ type: 'editText', blockKey: key, content: text });
    at++;
  }
  p._storyCommand({ type: 'insertMediaAt', mediaType: 'image', index: 2, blockKey: k(), url: '/images/home-route-city-placeholder.jpg' });
  p._storyCommand({ type: 'insertMediaAt', mediaType: 'divider', index: 4, blockKey: k() });
  p._storyCommand({ type: 'insertMediaAt', mediaType: 'audio', index: 6, blockKey: k(), url: '' });
  p.setData({ 'storyEditor.focusBlockKey': '' });
})()`);
await sleep(1200);
await w.shot(`a5d-written-${tag}`);

if (tag === 'after') {
  // 排版:第 2 段「铃铛响了一声」加粗、第 3 段斜体一截、第 4 段引用、开头插一个副标题;封面
  await w.eval(`(() => {
    const p = getCurrentPages().slice(-1)[0];
    const blocks = p.data.formData.chapters[p.data.storyEditor.chapterIndex].blocks;
    const texts = blocks.filter((b) => b.type === 'text');
    p._storyCommand({ type: 'formatText', blockKey: texts[1].key, from: 9, to: 15, mark: 'bold' });
    p._storyCommand({ type: 'formatText', blockKey: texts[2].key, from: 8, to: 20, mark: 'italic' });
    p._storyCommand({ type: 'setParagraphStyle', blockKey: texts[3].key, style: 'quote' });
    const h = p._genLocalId();
    p._storyCommand({ type: 'insertTextAt', index: 0, blockKey: h });
    p._storyCommand({ type: 'editText', blockKey: h, content: '一 · 雨停之后' });
    p._storyCommand({ type: 'setParagraphStyle', blockKey: h, style: 'h2' });
    const chapters = JSON.parse(JSON.stringify(p.data.formData.chapters));
    chapters[p.data.storyEditor.chapterIndex].imgArr = '/images/home-route-free-placeholder.jpg';
    p.setData({ 'formData.chapters': chapters, storyCover: '/images/home-route-free-placeholder.jpg', 'storyEditor.focusBlockKey': '' });
  })()`);
  await sleep(1000);
  await w.shot('A5D-format-after');
  // 预览态
  await w.eval(`getCurrentPages().slice(-1)[0].toggleStoryPreview()`);
  await sleep(900);
  await w.shot('A5D-preview-after');
  await w.eval(`getCurrentPages().slice(-1)[0].toggleStoryPreview()`);
  await sleep(600);
  // 插入菜单 / 段落样式 / 本章信息
  await w.tap('插入', { exact: true });
  await sleep(700);
  await w.shot('A5D-insert-menu-after');
  await w.eval(`getCurrentPages().slice(-1)[0].closeStorySheet()`);
  await sleep(400);
  await w.eval(`getCurrentPages().slice(-1)[0].openStoryInfo()`);
  await sleep(600);
  await w.shot('A5D-info-after');
  await w.eval(`getCurrentPages().slice(-1)[0].closeStorySheet()`);
  await sleep(400);
  await w.eval(`getCurrentPages().slice(-1)[0].openStoryEmoji()`);
  await sleep(600);
  await w.shot('A5D-emoji-after');
  await w.eval(`getCurrentPages().slice(-1)[0].closeStorySheet()`);
  await sleep(400);
}

if (wantDrag) {
  // 拖拽帧序列:长按第 4 块(图片)的把手,一路拖到第 1 段上方,再拖回去落在第 2 段后
  const page = w.page;
  const grips = await page.$$('pierce/.story-grip');
  const blocks = await page.$$('pierce/.story-block');
  // 拖第 4 块:after 里是那张图(前面多了一个副标题),before 里同样拖第 3 块(那张图)
  const pick = tag === 'after' ? 3 : 2;
  const handle = grips.length ? grips[pick] : blocks[pick];
  const box = await handle.boundingBox();
  const x = box.x + Math.min(30, box.width / 2);
  const y0 = box.y + Math.min(40, box.height / 2);
  await page.mouse.move(x, y0);
  await page.mouse.down();
  await sleep(600); // 长按 350ms 起拖
  await w.shot(`a5d-drag-f0-lift-${tag}`);
  const path = [40, 90, 140, 190];
  for (let i = 0; i < path.length; i++) {
    await page.mouse.move(x, y0 + path[i], { steps: 4 });
    await sleep(160);
    await w.shot(`a5d-drag-f${i + 1}-${tag}`);
  }
  // 贴到底边:自动滚屏
  await page.mouse.move(x, 815, { steps: 6 });
  await sleep(250);
  await w.shot(`a5d-drag-f5-edge-${tag}`);
  await sleep(450);
  await w.shot(`a5d-drag-f6-edge-${tag}`);
  await page.mouse.move(x, 700, { steps: 4 });
  await sleep(200);
  await w.shot(`a5d-drag-f7-${tag}`);
  await page.mouse.up();
  await sleep(500);
  await w.shot(`a5d-drag-f9-drop-${tag}`);
  const order = await w.eval(`getCurrentPages().slice(-1)[0].data.formData.chapters[0].blocks.map(b => b.type + ':' + (b.content || b.url || '').slice(0, 6))`);
  console.log('ORDER', JSON.stringify(order));
}

console.log('errors', JSON.stringify(w.errors()));
console.log('log', JSON.stringify((await w.log()).slice(-20)));
await w.browser.close();
