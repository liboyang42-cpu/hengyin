// node gen.js <xcxDir> <kind>  → prints previewData JSON for pages/play preview (l-play; copied from r2-play/rig/gen.js, + branch / 2 chapters / look)
const path = require('path');
const xcx = process.argv[2]; const kind = process.argv[3] || 'coinFlip';
const { buildPreviewKit } = require(path.join(xcx, 'pages/publish/utils/publish/advanced-game-preview.js'));
const imgs = ['/pages/play/images/d_topic1.png', '/pages/play/images/d_topic2.png', '/pages/play/images/d_topic4.png', '/pages/play/images/d_topic5.png'];
const CFG = {
  coinFlip: { coinFlip: { enabled: true, kicker: '抛一次，认结果', heads: { label: '正面', action: '向左走进弄堂' }, tails: { label: '反面', action: '沿街直走' } } },
  qa: { qa: { enabled: true, mode: 'PICK', title: '信封里响的是什么？', options: [{ id: 'a', label: '一把黄铜钥匙' }, { id: 'b', label: '一枚铜板' }, { id: 'c', label: '一串念珠' }] } },
  ballShake: { ballShake: { enabled: true, kicker: '撞够 30 次', goal: 30 } },
  branch: { branch: { enabled: true, title: '岔路', steps: [{ title: '岔路', body: '钟声停了。你手里只有一把钥匙的时间。', options: [{ label: '直接去钟楼', feedback: '体力 −1，十一点前能到。' }, { label: '回头找报童问路', feedback: '多花十分钟，但他会给你一条线索。' }, { label: '先回邮局翻账本', feedback: '+15 分，钟楼那边的事就只能听别人说了。' }] }] } },
};
CFG.diceRoll = { diceRoll: { enabled: true, kicker: '掷到几就做第几件事', faces: ['向左走进弄堂', '沿街直走', '回头找报童', '在原地等', '翻墙', '去钟楼'], diceCount: 1 } };
CFG.qaType = { qa: { enabled: true, mode: 'TYPE', title: '钟面停在几点？', lead: '信封上画着一座钟楼。' } };
CFG.random = { random: { enabled: true, deckName: '钟楼签', items: ['黄铜钥匙', '一枚铜板', '褪色邮戳', '半张车票'].map((t, i) => ({ id: 'c' + i, label: t, content: '签文 ' + (i + 1) + '：' + t + ' 在旧邮局的第三个抽屉里。' })) } };
CFG.compare = { coinFlip: { enabled: true, heads: { label: '正面', action: 'a' }, tails: { label: '反面', action: 'b' } } };
const cfg = CFG[kind] || CFG.coinFlip;
const para = (t) => ({ type: 'text', content: t });
const nodes = [
  { nodeId: 1, chapterId: 1, sortId: 1, name: '谁在钟楼留下了钥匙', validationMethod: 0, imgUrl: '/pages/play/images/d_topic1.png',
    advancedConfigJson: JSON.stringify(cfg), previewKit: buildPreviewKit(cfg, { title: '谁在钟楼留下了钥匙' }), done: false },
  { nodeId: 2, chapterId: 1, sortId: 2, name: '邮政大楼', address: '北苏州路 250 号', description: '1924 年落成，楼顶的钟一度是全城对时的地方。侧门进去是老营业厅，柜台还是当年的样子。', validationMethod: 0, imgUrl: '/pages/play/images/d_topic2.png', latitude: 31.2445, longitude: 121.4860, done: false },
];
const chapters = [{ id: 1, chapterId: 1, idxLabel: '第 1 章', name: '邮局旧址', cover: '/pages/play/images/d_topic1.png', blocks: [
  para('信是昨天到的。邮戳上的日期，是四十年前。'),
  para('寄信人没有留名字，只画了一座钟楼，钟面停在十一点。'),
  para('柜台后的老人把第二封信推过来。信封比第一封厚，里面有东西在响。'),
  { type: 'node', nodeId: 1, nodeIndex: 0, locationRequired: false },
  para('钟楼的方向传来一声钟响。十一点整。'),
] }];
if (kind === 'route') {
  nodes.length = 0; chapters[0].blocks = chapters[0].blocks.filter(b => b.type === 'text').slice(0, 2);
  nodes.push({ nodeId: 11, chapterId: 1, sortId: 1, name: '邮政大楼', address: '北苏州路 250 号 · 钟楼下的侧门', description: '1924 年落成，楼顶的钟一度是全城对时的地方。侧门进去是老营业厅，柜台还是当年的样子。', imgUrl: '/pages/play/images/d_topic2.png', validationMethod: 0, latitude: 31.2445, longitude: 121.4860, businessTime: '09:00-17:00', done: false },
             { nodeId: 12, chapterId: 1, sortId: 2, name: '老书店', address: '福州路 424 号', validationMethod: 0, latitude: 31.2045, longitude: 121.4380, done: false });
  chapters[0].blocks.push({ type: 'node', nodeId: 11, nodeIndex: 0 }, { type: 'node', nodeId: 12, nodeIndex: 1 });
}
if (kind === 'two') {   // 两章:第 1 章一站已完成,用来出「第 1 章完成」整屏
  nodes.length = 0; chapters[0].blocks = chapters[0].blocks.filter(b => b.type === 'text').slice(0, 2);
  nodes.push({ nodeId: 11, chapterId: 1, sortId: 1, name: '邮政大楼', address: '北苏州路 250 号', imgUrl: '/pages/play/images/d_topic2.png', validationMethod: 0, latitude: 31.2445, longitude: 121.4860, done: true, doneAt: 1, puzzleScore: 80 },
             { nodeId: 21, chapterId: 2, sortId: 2, name: '老书店', address: '福州路 424 号', imgUrl: '/pages/play/images/d_topic4.png', validationMethod: 0, latitude: 31.2045, longitude: 121.4380, done: false },
             { nodeId: 22, chapterId: 2, sortId: 3, name: '灯笼铺', validationMethod: 0, latitude: 31.2055, longitude: 121.4390, done: false });
  chapters[0].blocks.push({ type: 'node', nodeId: 11, nodeIndex: 0 });
  chapters.push({ id: 2, chapterId: 2, idxLabel: '第 2 章', name: '老书店', cover: '/pages/play/images/d_topic4.png', mileage: '0.5km', timeText: '20 分钟', blocks: [para('店门虚掩着，柜台后面没有人。'), { type: 'node', nodeId: 21, nodeIndex: 1 }, { type: 'node', nodeId: 22, nodeIndex: 2 }] });
}
if (kind === 'tasks') {
  nodes.length = 0; delete chapters[0].blocks; chapters[0].description = '信是昨天到的。邮戳上的日期，是四十年前。\n寄信人没有留名字，只画了一座钟楼。';
  ['地铁站的第三根柱子', '弄堂口的洗衣店', '老街的灯笼铺'].forEach((nm, i) => nodes.push({ nodeId: 21 + i, chapterId: 1, sortId: 101 + i, name: nm, address: ['人民广场站 7 号口', '山西南路 98 号', ''][i], description: ['找到柱子上 1930 年代的瓷砖编号，拍一张。', '老板娘手里有第三封信的复印件，换一句暗号。', '数清门楣上的灯笼，数字就是密码的一半。'][i], imgUrl: i < 2 ? imgs[i] : '', validationMethod: 0, done: false }));
}
if (kind === 'compare') nodes[0].previewKit = { type: 'compare', prompt: '把两边对不上的时间标出来', attempts: 0, passed: false, finished: false, lastCorrect: false, lastMarked: [],
  left: { label: '信上的日期', items: [{ id: 'l1', time: '1924', text: '邮政大楼落成' }, { id: 'l2', time: '1931', text: '钟楼加装报时' }, { id: 'l3', time: '1945', text: '第一封信寄出' }] },
  right: { label: '邮戳的日期', items: [{ id: 'r1', time: '1924', text: '邮政大楼落成' }, { id: 'r2', time: '1936', text: '钟楼加装报时' }, { id: 'r3', time: '1945', text: '第一封信寄出' }] } };
if (kind === 'album') chapters[0].blocks.splice(1, 0, { type: 'dream', title: '旧照片', images: imgs.map((u, i) => ({ url: u, line: '第 ' + (i + 1) + ' 张' })) });
const look = process.env.LOOK === 'none' ? '' : JSON.stringify({ style: process.env.LOOK || 'STARDUST', palette: process.env.PAL || 'DEFAULT' });
process.stdout.write(JSON.stringify({ topicId: '', topicName: '老城三封信', topicCover: '/pages/play/images/d_topic1.png', mode: 1, chapters, nodes, startStory: true, thoughts: [], endings: [], startState: [], look }));
