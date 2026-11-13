// P-08:带领场里队长还没放行的章节,玩家停在门前看到「等队长放行」;P-35:放行时给玩家一句。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { leadHoldsChapter, releaseCue } = require('../../pages/play/utils/lead-chapter-hold.js');

const cards = [{ id: 10, opening: true }, { id: 11 }, { id: 12 }, { id: 13 }, { id: 99, ending: true }];
const running = (current) => ({ exists: true, status: 2, currentChapterId: current, chapterName: '钟楼背后' });

test('队长还在第一章:第二、三章被挡,第一章与已过章节不挡', () => {
  assert.equal(leadHoldsChapter(running(11), 12, cards), true);
  assert.equal(leadHoldsChapter(running(11), 13, cards), true);
  assert.equal(leadHoldsChapter(running(11), 11, cards), false);
  assert.equal(leadHoldsChapter(running(12), 11, cards), false);
});

test('负控:非带领 / 集合中 / 已结束 / 未知章节一律不挡(真闸在服务端)', () => {
  assert.equal(leadHoldsChapter(null, 12, cards), false);
  assert.equal(leadHoldsChapter({ exists: false }, 12, cards), false);
  assert.equal(leadHoldsChapter({ exists: true, status: 1, currentChapterId: 11 }, 12, cards), false);
  assert.equal(leadHoldsChapter({ exists: true, status: 3, currentChapterId: 11 }, 12, cards), false);
  assert.equal(leadHoldsChapter(running(null), 12, cards), false);
  assert.equal(leadHoldsChapter(running(11), 404, cards), false);
  assert.equal(leadHoldsChapter(running(11), 99, cards), false);
});

test('P-35 放行提示:章节变了才说,首次读到不说', () => {
  assert.equal(releaseCue(running(11), running(12)), '队长放行了「钟楼背后」');
  assert.equal(releaseCue(running(11), Object.assign(running(12), { chapterName: '' })), '队长放行了下一章');
  assert.equal(releaseCue(running(11), running(11)), '');
  assert.equal(releaseCue({ exists: false }, running(12)), '');
  assert.equal(releaseCue(running(null), running(12)), '');
});

test('play 页接线:开玩前先问、章节门挡住不自动进正文、轮询放行后接着走', () => {
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');
  assert.match(js, /if \(this\._leadHolds\(node\.chapterId\)\) \{/);
  // D-46(业主 2026-11-11「点了才能进」):章节门不再有页面计时兜底,被挡与否都只等玩家点按
  assert.doesNotMatch(js, /_gateTimer\s*=\s*setTimeout/);
  assert.match(js, /const cue = releaseCue\(prevLead, that\.data\.lead\);/);
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/play/index.wxml'), 'utf8');
  // 第四轮:被挡住时挂「等待放行」屏(见 play-chapter-hold-screen-p08-r4.test.js),不在地图上另挂一行
  assert.match(wxml, /chapterGate\.held && !chapterGate\.away\}\}" aria-role="status" aria-label="等待放行">/);
  assert.doesNotMatch(wxml, /chgate-hold/);
  assert.match(js, /that\._syncChapterHold\(\);/);
  assert.match(js, /this\._releaseChapterGate\(\); return; \}/);
  assert.match(wxml, /class="lg-wait" aria-hidden="true"/);
});
