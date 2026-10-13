// 故事流的环境音与音效(2026-09-23 用户裁决「优化顺序第 2 项」):照原型用 WebAudio 合成,不用音频素材。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const storyAudio = require('../../utils/story-audio.js')

function fakeContext() {
  const made = []
  const node = (kind) => {
    const n = {
      kind, started: 0, stopped: 0, type: '', buffer: null, loop: false,
      frequency: { value: 0, setValueAtTime() {}, exponentialRampToValueAtTime() {} },
      Q: { value: 0 },
      gain: { value: 0, setValueAtTime() {}, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {}, setTargetAtTime(v) { this.value = v } },
      connect() {}, start() { this.started += 1 }, stop() { this.stopped += 1 },
    }
    made.push(n); return n
  }
  return {
    made, currentTime: 0, sampleRate: 8000, destination: {},
    createGain: () => node('gain'), createOscillator: () => node('osc'), createBiquadFilter: () => node('filter'),
    createBufferSource: () => node('source'),
    createBuffer: (ch, len) => ({ getChannelData: () => new Float32Array(len) }),
  }
}

test('环境音按氛围选;同一个不重起;换一个旧的淡出停掉', () => {
  const ctx = fakeContext()
  const a = storyAudio.create({ createContext: () => ctx, schedule: (fn) => fn() })
  assert.equal(storyAudio.bedForMood('YELLOW'), 'afternoon')
  assert.equal(storyAudio.bedForMood('WHITE'), 'office')
  assert.equal(storyAudio.bedForMood(''), 'city')
  a.bed('afternoon')
  const firstSources = ctx.made.filter((n) => n.kind === 'source')
  assert.ok(firstSources.length >= 1, '午后要有一层循环噪声(蝉鸣)')
  a.bed('afternoon')
  assert.equal(ctx.made.filter((n) => n.kind === 'source').length, firstSources.length, '同名不重起')
  a.bed('office')
  assert.ok(firstSources.every((n) => n.stopped >= 1), '换景后旧的那层要停掉,不叠着响')
})

test('关掉声音:环境音停、音效不出;打开后照常', () => {
  const ctx = fakeContext()
  const a = storyAudio.create({ createContext: () => ctx, schedule: (fn) => fn() })
  a.bed('city')
  a.setOn(false)
  assert.ok(ctx.made.filter((n) => n.kind === 'source').every((n) => n.stopped >= 1))
  const before = ctx.made.length
  a.sfx('ok'); a.bed('dream')
  assert.equal(ctx.made.length, before, '静音时不该再建任何声音节点')
  a.setOn(true); a.sfx('ok')
  assert.ok(ctx.made.length > before)
})

test('环境不支持 WebAudio 时静默降级,不抛', () => {
  const a = storyAudio.create({ createContext: () => null })
  a.bed('city'); a.sfx('boot'); a.setOn(false); a.dispose()
})
