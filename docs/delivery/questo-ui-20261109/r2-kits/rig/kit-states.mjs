// 服务端形状的会话视图(字段名照 AdvancedGameRuntimeServiceImpl / HangmanGameplay.publicProjection / accusationView)。
// 图片用小程序包内站内路径(rig 能出图;线上是 OSS https 地址,同一条 displayImage 口径)。
const IMG = { a: '/subpackageP3/images/memoji-1.png', b: '/subpackageP3/images/memoji-2.png', c: '/subpackageP3/images/memoji-3.png', board: '/images/home-banner-2.jpg' };
const hangSeg = (over) => Object.assign({ length: 7, blankCount: 5, caseSensitive: false, solved: false, display: 'E_____S',
  slots: [{ i: 0, ch: 'E', kind: 'PREFILLED' }, { i: 1, kind: 'BLANK' }, { i: 2, kind: 'BLANK' }, { i: 3, kind: 'BLANK' },
    { i: 4, kind: 'BLANK' }, { i: 5, kind: 'BLANK' }, { i: 6, ch: 'S', kind: 'PREFILLED' }] }, over);
const longSeg = { length: 18, blankCount: 13, caseSensitive: false, solved: false, display: '',
  slots: 'NICOLAS AND PERNELLE'.split('').map((ch, i) => ch === ' ' ? { i, ch: ' ', kind: 'SEPARATOR' }
    : ([0, 8, 12].includes(i) ? { i, ch, kind: 'PREFILLED' } : { i, kind: 'BLANK' })) };
const suspects = [
  { id: 'A', name: '古董店老板', identity: '钟楼对面', avatar: IMG.a, testimony: [{ key: 'clue.a', text: '那晚十点我在店里盘点，账本可以作证。' }] },
  { id: 'B', name: '守夜人', identity: '钟楼值班', avatar: IMG.b, testimony: [] },
  { id: 'C', name: '报童', identity: '广场街角', avatar: IMG.c, testimony: [] },
];
const qaPick = { mode: 'PICK', title: '谁在钟楼留下了钥匙？', options: [{ id: 'A', label: '古董店老板' }, { id: 'B', label: '守夜人' }, { id: 'C', label: '报童' }], attempts: 0, finished: false, passed: false };
const acc = (over, qa) => ({ sessionId: 's9', version: 2, nodeId: 7, status: 'RUNNING', playKit: { qa: Object.assign({}, qaPick, qa) },
  accusation: Object.assign({ enabled: true, suspects, corkboardImage: IMG.board, introScreens: ['三个人那晚都在钟楼附近。'], earlyAccuse: { allowed: false } }, over) });
const done = (result, screen, qa, status) => Object.assign(acc({ result, screen }, qa), { status });

export const STATES = [
  { id: 'b2-ug-01', view: { sessionId: 's1', version: 3, playKit: { hangman: hangSeg() } } },
  { id: 'b2-ug-01-typing', view: { sessionId: 's1', version: 3, playKit: { hangman: hangSeg() } },
    steps: [{ tapSel: '.hm__cell:not(.is-given)' }, { eval: `(() => { const el = document.querySelector('cy-playkit-hangman') ; return 1 })()` }] },
  { id: 'b2-ug-01-long', view: { sessionId: 's1', version: 3, playKit: { hangman: longSeg } } },
  { id: 'b2-ug-01-solved', view: { sessionId: 's1', version: 4, playKit: { hangman: hangSeg({ solved: true, display: 'ENDLESS',
    slots: 'ENDLESS'.split('').map((ch, i) => ({ i, ch, kind: i === 0 || i === 6 ? 'PREFILLED' : 'FILLED' })) }) } } },
  { id: 'b2-ug-02', view: { sessionId: 's2', version: 1, playKit: { qa: { mode: 'PICK', title: '谁在钟楼留下了钥匙？', attempts: 0, finished: false, passed: false,
    options: [{ id: 'A', label: '古董店老板', img: IMG.a, alt: '戴眼镜的老板' }, { id: 'B', label: '守夜人', img: IMG.b, alt: '守夜人' }, { id: 'C', label: '报童', img: IMG.c, alt: '报童' }] } } } },
  { id: 'b2-ug-03', view: acc({}) },
  { id: 'b2-ug-03-selected', view: acc({}), steps: [{ tapSel: '.ac__card' }] },
  { id: 'b2-ug-03-confirm', view: acc({}), steps: [{ tapSel: '.ac__card' }, { tapAny: ['确认指认', '指认 古董店老板'], wait: 1200 }] },
  { id: 'b2-ug-03-wrong', view: acc({ result: { verdict: 'WRONG', correct: false, early: false, terminal: false, canRetry: true } }, { attempts: 1, lastFeedback: '他有不在场证明' }) },
  { id: 'b2-ug-04', view: done({ verdict: 'CORRECT', correct: true, early: false, terminal: true, canRetry: false }, '钥匙就在古董店的账本夹层里。钟楼的秘密到此揭开。', { finished: true, passed: true, attempts: 1 }, 'COMPLETED'), wait: 3200 },
  { id: 'b2-ug-04-early', view: done({ verdict: 'CORRECT', correct: true, early: true, terminal: true, canRetry: false }, '你在线索收齐之前就锁定了他。', { finished: true, passed: true, attempts: 1 }, 'COMPLETED'), wait: 3200 },
  { id: 'b2-ug-04-failed', view: done({ verdict: 'FAILED', correct: false, early: false, terminal: true, canRetry: false }, '他趁夜色离开了小城。', { finished: true, passed: false, attempts: 3 }, 'FAILED'), wait: 3200 },
  { id: 'b2-ug-04-pending', view: acc({}, { finished: true, passed: false, attempts: 1 }), wait: 3200 },
  { id: 'b2-ug-10', view: { sessionId: 's3', version: 1, clueTransform: { mode: 'MIRROR' }, playKit: { qa: { mode: 'TYPE', title: '门楣上刻的是哪一年？', attempts: 0, finished: false, passed: false } } } },
];
