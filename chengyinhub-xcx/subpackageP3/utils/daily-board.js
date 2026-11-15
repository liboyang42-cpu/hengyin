'use strict'
// P7 daily-loop 小程序侧(T6 2026-11-15):成长中心「今日任务」与连续天数。
// 数据走 App 同一组接口:任务 = /api/growth/center 的 daily;连续天数、补签卡、下一枚徽章 = /api/play/growth。
// 文案与 App(Resources/DailyLoop.xcstrings)一致。服务端没下发这些字段(旧版本)时整节不出现,与今天一样;
// 下发了但读不出(daily 为 null)时如实写「暂时还用不了」,不伪装成 0。

const MISSION_NAME = {
  daily_walk: '走一走',
  daily_reveal: '点亮迷雾',
  daily_play: '玩一关',
  daily_shop: '逛一家店',
}

const COPY = {
  title: '今日任务',
  empty: '今天没有任务，明天再来看看。',
  unavailable: '每日任务暂时还用不了。',
  other: '每日任务',
  noReward: '这项任务还没有设置奖励',
  streakTitle: '连续天数',
  doneToday: '今天已打卡',
  atRisk: '今天还没打卡',
  ladderDone: '连续天数徽章已经全部拿到。',
  freezesUnavailable: '补签卡暂时还用不了。',
}

function isObject(v) { return !!v && typeof v === 'object' && !Array.isArray(v) }
function count(v) { return typeof v === 'number' && Number.isInteger(v) && v >= 0 ? v : null }
function flag(v) { return typeof v === 'boolean' ? v : null }

function shapeMission(raw) {
  if (!isObject(raw) || typeof raw.missionCode !== 'string' || !raw.missionCode) return null
  const target = Math.max(1, count(raw.target) || 1)
  const progress = Math.min(target, count(raw.progress) || 0)
  const completed = raw.completed === true
  const reward = count(raw.expReward) || 0
  const name = MISSION_NAME[raw.event]
    || (typeof raw.missionName === 'string' && raw.missionName.trim() ? raw.missionName.trim() : COPY.other)
  return {
    code: raw.missionCode,
    name,
    progressText: (completed ? target : progress) + '/' + target,
    completed,
    rewardText: reward <= 0 ? COPY.noReward : (completed ? '已得 ' + reward + ' 探索值' : '奖励 ' + reward + ' 探索值'),
  }
}

// centerData = /api/growth/center 的 data;没有 daily 键 → null(不显示这一节)
function shapeMissions(centerData) {
  if (!isObject(centerData) || !Object.prototype.hasOwnProperty.call(centerData, 'daily')) return null
  const daily = centerData.daily
  if (!isObject(daily) || !Array.isArray(daily.missions)) return { state: 'unavailable', text: COPY.unavailable, missions: [], countText: '' }
  const missions = daily.missions.map(shapeMission).filter(Boolean)
  if (!missions.length) return { state: 'empty', text: COPY.empty, missions: [], countText: '' }
  const done = missions.filter(m => m.completed).length
  return { state: 'ready', text: '', missions, countText: '已完成 ' + done + '/' + missions.length }
}

// playData = /api/play/growth 的 data;没有 currentStreak → null(旧服务端,不显示)
function shapeStreak(playData) {
  if (!isObject(playData)) return null
  const days = count(playData.currentStreak)
  if (days === null) return null
  const activeToday = flag(playData.streakActiveToday)
  const freezes = count(playData.streakFreezes)
  const cap = count(playData.streakFreezeMax)
  const every = count(playData.streakFreezeEveryDays)
  const pending = count(playData.streakFreezesPending)
  const next = count(playData.nextStreakBadgeDays)
  const meters = count(playData.walkMinMeters)
  const cells = count(playData.walkMinCells)
  const lines = []
  if (freezes !== null && cap !== null) {
    if (every && every > 0) lines.push('每连续 ' + every + ' 天得 1 张；漏掉一天会自动用掉一张。')
    if (pending && pending > 0) lines.push('正在补上漏掉的天，要用 ' + pending + ' 张补签卡')
  }
  lines.push(next ? '连续 ' + next + ' 天可得下一枚徽章' : COPY.ladderDone)
  if (meters !== null && cells !== null) lines.push('一次漫游走满 ' + meters + ' 米或点亮 ' + cells + ' 个新格子就算今天来过。')
  return {
    daysText: '连续 ' + days + ' 天',
    statusText: activeToday === true ? COPY.doneToday : (activeToday === false ? COPY.atRisk : ''),
    freezesText: freezes !== null && cap !== null ? '补签卡：' + freezes + '/' + cap + ' 张' : COPY.freezesUnavailable,
    lines,
  }
}

function shapeDailyBoard(centerData, playData) {
  const missions = shapeMissions(centerData)
  const streak = shapeStreak(playData)
  return { show: !!(missions || streak), missions, streak }
}

module.exports = { COPY, MISSION_NAME, shapeMission, shapeMissions, shapeStreak, shapeDailyBoard }
