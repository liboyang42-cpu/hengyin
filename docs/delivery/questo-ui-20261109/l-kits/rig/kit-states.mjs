// l-kits 截图状态:服务端形状的会话视图(字段名照 AdvancedGameRuntimeServiceImpl / HangmanGameplay.publicProjection / accusationView / GameSessionRuntimeServiceImpl.clueView)。
// 图片用小程序包内站内路径(rig 能出图;线上是 OSS https 地址,同一条 displayImage 口径)。改前 / 改后两个包各跑一次同一份视图。
const IMG = { a: '/subpackageP3/images/memoji-1.png', b: '/subpackageP3/images/memoji-2.png', c: '/subpackageP3/images/memoji-3.png', d: '/images/home-banner-club.jpg', board: '/images/home-banner-2.jpg' };
const hangSeg = (over) => Object.assign({ length: 7, blankCount: 5, caseSensitive: false, solved: false, display: 'E_____S',
  slots: [{ i: 0, ch: 'E', kind: 'PREFILLED' }, { i: 1, kind: 'BLANK' }, { i: 2, kind: 'BLANK' }, { i: 3, kind: 'BLANK' },
    { i: 4, kind: 'BLANK' }, { i: 5, kind: 'BLANK' }, { i: 6, ch: 'S', kind: 'PREFILLED' }], title: '钟楼的看守人，在信里被叫作什么？' }, over);
const suspects = [
  { id: 'A', name: '古董店老板', identity: '钟楼对面', avatar: IMG.a, testimony: [{ key: 'clue.a', text: '那晚十点我在店里盘点，账本可以作证。' }] },
  { id: 'B', name: '守夜人', identity: '钟楼值班', avatar: IMG.b, testimony: [{ key: 'clue.a', text: '十一点我在楼下巡逻，看见有人从侧门出来。' }] },
  { id: 'C', name: '报童', identity: '广场街角', avatar: IMG.c, testimony: [] },
];
const clues = [
  { id: 11, name: '十一点 · 钟停了', scope: 'TEAM', sources: [{ title: '邮政大楼', blocks: [{ text: '钟楼的钟停在十一点整，发条完好，是有人把它停下的。', images: [IMG.d] }] }] },
  { id: 12, name: '侧门没有锁', scope: 'TEAM', sources: [{ title: '邮政大楼', blocks: [{ text: '侧门的锁芯里有新的划痕，钥匙是从外面插进去的。', images: [] }] }] },
  { id: 13, name: '账本少一页', scope: 'PUBLIC', sources: [{ title: '古董店', blocks: [{ text: '十一月十日那一页被整齐地裁掉，边上还有一点蜡。', images: [IMG.board] }] }] },
];
const qaPick = { mode: 'PICK', title: '谁在钟楼留下了钥匙？', options: [{ id: 'A', label: '古董店老板' }, { id: 'B', label: '守夜人' }, { id: 'C', label: '报童' }], attempts: 0, finished: false, passed: false };
const acc = (over, qa) => ({ sessionId: 's9', version: 2, nodeId: 7, status: 'RUNNING', playKit: { qa: Object.assign({}, qaPick, qa) }, clues,
  accusation: Object.assign({ enabled: true, suspects, corkboardImage: IMG.board, introScreens: ['三个人那晚都在钟楼附近。听完证词，指认一个人。'], earlyAccuse: { allowed: false } }, over) });
const done = (result, screen, qa, status) => Object.assign(acc({ result, screen }, qa), { status });
const imgQa = { sessionId: 's2', version: 1, playKit: { qa: { mode: 'PICK', title: '信封里响的是什么？', attempts: 0, finished: false, passed: false,
  options: [{ id: 'A', label: '一把黄铜钥匙', img: IMG.a, alt: '钥匙' }, { id: 'B', label: '一枚铜板', img: IMG.b, alt: '铜板' }, { id: 'C', label: '一串念珠', img: IMG.c, alt: '念珠' }, { id: 'D', label: '一截粉笔', img: IMG.d, alt: '粉笔' }] } } };

export const STATES = [
  // 嵌入图片题(整屏档承载,同一组件;内嵌档由故事流给变量,见 RESULT 真机未验)
  { id: 'lk-qa-img', view: imgQa },
  { id: 'lk-qa-img-picked', view: imgQa, steps: [{ tapSel: '.qa__pic', wait: 900 }] },
  { id: 'lk-qa-img-wrong', view: Object.assign({}, imgQa, { version: 2, playKit: { qa: Object.assign({}, imgQa.playKit.qa, { attempts: 1, lastOptionId: 'B', lastFeedback: '不是铜板。老人说，它比钱重要。' }) } }) },
  // 逐字猜词
  { id: 'lk-hang', view: { sessionId: 's1', version: 3, playKit: { hangman: hangSeg() } } },
  { id: 'lk-hang-typing', view: { sessionId: 's1', version: 3, playKit: { hangman: hangSeg() } },
    steps: [{ tapSel: '.hm__cell:not(.is-given)', wait: 500 }, { eval: `(() => { const el = document.querySelector('cy-playkit-hangman .hm__in') || document.querySelector('.hm__in'); return 1 })()` }, { type: ['.hm__in', 'ndl'], wait: 900 }] },
  { id: 'lk-hang-solved', view: { sessionId: 's1', version: 4, playKit: { hangman: hangSeg({ solved: true, display: 'ENDLESS',
    slots: 'ENDLESS'.split('').map((ch, i) => ({ i, ch, kind: i === 0 || i === 6 ? 'PREFILLED' : 'FILLED' })) }) } }, wait: 3200 },
  // 判案
  { id: 'lk-acc', view: acc({}) },
  { id: 'lk-acc-clue', view: acc({}), steps: [{ tapSel: '.ac__pin', wait: 1200 }] },
  { id: 'lk-acc-flip', view: acc({}), steps: [{ tapSel: '.ac__arrow', wait: 1200 }] },
  { id: 'lk-acc-confirm', view: acc({}), steps: [{ tapAny: ['指认 古董店老板', '确认指认'], wait: 1200 }] },
  { id: 'lk-acc-wrong', view: acc({ result: { verdict: 'WRONG', correct: false, early: false, terminal: false, canRetry: true } }, { attempts: 1, lastFeedback: '他有不在场证明' }) },
  { id: 'lk-acc-solved', view: done({ verdict: 'CORRECT', correct: true, early: false, terminal: true, canRetry: false }, '钥匙就在守夜人的值班室里。钟楼的秘密到此揭开。', { finished: true, passed: true, attempts: 1 }, 'COMPLETED'), wait: 4200 },
  { id: 'lk-acc-failed', view: done({ verdict: 'FAILED', correct: false, early: false, terminal: true, canRetry: false }, '他趁夜色离开了小城。账本上那一页，再也找不回来。', { finished: true, passed: false, attempts: 3 }, 'FAILED'), wait: 4200 },
  { id: 'lk-acc-pending', view: acc({}, { finished: true, passed: false, attempts: 1 }), wait: 4200 },
  // 掷骰子摇一摇只改监听,画面无变化;rig 没有传感器,不截。
];
