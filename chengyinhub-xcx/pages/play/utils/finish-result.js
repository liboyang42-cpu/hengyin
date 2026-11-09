// U08-01 完成页结果行:只按服务端本局战报(GET /api/play/player-journey/report)的 completionKind 说「已通关 / 辅助完成」。
// 有照片、到了结束时间、读到结尾都不算合法完局(清单 §九 U08-01 验收);读不到战报时不升级成「已通关」。
// 服务端合同:PlayerRunReportVO —— LEGITIMATE_COMPLETION / ASSISTED_COMPLETION / ENDED / IN_PROGRESS / NOT_STARTED。

const SERVER_LABELS = {
  LEGITIMATE_COMPLETION: { label: '已通关', win: true },
  ASSISTED_COMPLETION: { label: '辅助完成', win: false },
  ENDED: { label: '已结束', win: false }
}

function readReport(response) {
  if (!response || (response.code != 200) || !response.data || typeof response.data !== 'object') return null
  return response.data
}

// local: { topicCompleted, allDone, chapterDone }
//   topicCompleted 是本次到达回执里服务端判的 completed;allDone 只是本地站点都标了完成,不能单独升级成「已通关」。
// state: 'pending' 战报在途 / 'failed' 读不到 / 'ok' 已读到
// 全部站点已完成但战报未到或读不到:结果未知,不写任何结果词(label 为空,整行不渲染)。
function resolveFinishResult(report, local, state) {
  const l = local || {}
  const kind = report && typeof report.completionKind === 'string' ? report.completionKind : ''
  const server = SERVER_LABELS[kind]
  if (server) return { label: server.label, win: server.win, source: 'report', kind }
  if (l.topicCompleted === true) return { label: '已通关', win: true, source: 'receipt', kind: '' }
  if (l.allDone === true && state !== 'ok') return { label: '', win: false, source: 'unknown', kind: '' }
  return { label: l.chapterDone ? '本章完成' : '已结束', win: false, source: 'local', kind: '' }
}

function reportQuery(activityId, topicId, runId) {
  const query = {}
  if (activityId != null && activityId !== '' && Number(activityId) > 0) query.activityId = activityId
  else if (topicId != null && topicId !== '' && Number(topicId) > 0) query.topicId = topicId
  else return null
  if (runId != null && runId !== '' && Number(runId) > 0) query.runId = runId
  return query
}

module.exports = { resolveFinishResult, readReport, reportQuery }
