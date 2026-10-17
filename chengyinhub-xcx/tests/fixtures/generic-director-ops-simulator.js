function installDirectorFixture(manual) {
  const pages = getCurrentPages()
  const page = pages[pages.length - 1]
  if (!page || page.route !== 'pages/club/topic-detail/index') throw new Error('Only the director page may receive this local fixture')
  const control = {
    run: { operationStatus: 'RUNNING', authorityMode: 'ONLINE', authorityEpoch: 2, eventSeq: 3, bindings: {}, exitedTeams: {}, openedActs: {} },
    revision: 9,
    teams: [{ teamId: 81, player: { hp: 2, luck: 1, actions: 3, gameplay: { actIndex: 0 } }, ownerRevision: 4, exited: false },
      { teamId: 82, player: { hp: 3, luck: 2, actions: 3, gameplay: { actIndex: 0 } }, ownerRevision: 2, exited: false }],
    stations: [],
    guideTasks: [
      { questKey: 'quest.basic', nodeId: 11, basic: { mode: 'TYPE', options: [] } },
      { questKey: 'quest.objective', nodeId: 12,
        qa: { mode: 'PICK', title: '队伍选择的方向', options: [{ id: 'east', label: '向东' }, { id: 'west', label: '向西' }] },
        sort: { prompt: '队伍给出的顺序', items: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }] },
        match: { prompt: '队伍给出的连线', left: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }], right: [{ id: 'x', label: '一' }, { id: 'y', label: '二' }] },
        classify: { prompt: '队伍给出的分类', items: [{ id: 'a', label: '甲' }, { id: 'b', label: '乙' }], bins: [{ id: 'x', label: '第一类' }, { id: 'y', label: '第二类' }] } },
    ],
    availableActions: ['RUN_INTERRUPT', 'RUN_CANCEL', 'STAFF_TEAM_EXIT', 'RUN_PAUSE', 'ACT_UNLOCK', 'QUEST_GUIDE', 'ARRIVAL_CONFIRM', 'ARRIVAL_REVOKE'],
  }
  const club = {
    sessionId: 31, activityId: 71, perspective: 'CLUB', status: 'RUNNING', revision: 9, authorityEpoch: 2,
    availableActions: ['FINISH'], club: { playMode: 'ROUTE', readiness: {}, teams: [], stations: [] },
  }
  if (manual === true) {
    control.run.authorityMode = 'MANUAL'
    control.availableActions = ['RUN_INTERRUPT', 'RUN_CANCEL', 'STAFF_TEAM_EXIT', 'STAFF_MANUAL_SIGN', 'MANUAL_IMPORT', 'MANUAL_REVIEW', 'AUTHORITY_RESUME']
  }
  const application = getApp()
  application.sendRequest = function (options) {
    if (options.method === 'GET' && options.url === '/api/game/session/shared/control') options.success({ code: 200, data: control })
    else if (options.method === 'GET' && options.url === '/api/game/session/view') options.success({ code: 200, data: club })
    else if (options.method === 'GET' && options.url === '/api/game/session/shared/export') options.success({ code: 200, data: {
      sessionId: 31, activityId: 71, revision: 9, authorityEpoch: 2, eventSeq: 3, configHash: 'a'.repeat(64), highWaterHash: 'b'.repeat(64),
      highWaterAt: 1791577800000, balances: [], events: [], operationStatus: 'RUNNING', authorityMode: 'MANUAL',
      manual: { nextSequence: 1, needsReview: false, signatures: {}, unknownRequests: [] },
    } })
    else if (typeof options.fail === 'function') options.fail({ errMsg: 'Local fixture blocks every other request, including all writes' })
    return { abort() {} }
  }
  page._activityId = 71
  page._directorAdapter = null
  page.setData({ loaded: true, loadError: false, missingParam: false, directorActive: true, statusKey: 'running',
    quickActions: [], showVerify: false, showReject: false, showNext: false,
    topic: { name: '通用玩法 · 本地导演台', structureText: '2 支队伍 · 运营核验', cover: '', statusChips: [] } })
  return page._getAdapter().load(71).then(function (projection) {
    page.applyProjection(projection)
    return { route: page.route, width: wx.getWindowInfo().windowWidth, actions: page.data.genericOpsActions.map(action => action.id), writesBlocked: true }
  })
}

module.exports = { installDirectorFixture }
