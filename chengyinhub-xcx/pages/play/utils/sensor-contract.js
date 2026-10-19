// 传感器小游戏的可信边界与降级(C1:F0334–F0339 · M0103–M0106 · F0308/F0309)
//
// 一张表回答四个问题,逐玩法写死,别在组件里各说各的:
//   1. 测的是什么、单位是什么、本机怎么校准、校准什么时候失效(M0103);
//   2. 结算要什么证据、允许什么授权替代(M0104);
//   3. 没传感器 / 权限被拒 / 资源失败 / 低动态时怎么降级(M0105 设备能力矩阵);
//   4. 切后台时是继续、中断这一轮,还是整次作废重开(M0106)。
//
// ★ 能不能领奖不由这里说了算 —— 由服务端回包的 rewardCeiling 决定(SELF_REPORTED → XP_BADGE_ONLY)。
//   这张表只给「服务端没回这个字段时」的保守默认:设备自报成绩一律按娱乐成绩说。
//   服务端拒收时界面只说明原因,不自行判完成,也不换旧路径领奖。

const EVIDENCE_TEXT = {
  SELF_REPORTED: '本机成绩',
  SERVER_TIMED: '统一计时',
  TASK_CODE_MATCH: '任务码匹配',
  VISION_VERDICT: '照片审核',
};

/** 设备自报成绩只算娱乐成绩:能过关、记探索值与徽章,不发券、积分或掉落。 */
const ENTERTAINMENT_NOTE = '娱乐成绩：只记探索值与徽章，不发券或积分';

const GAMES = {
  reaction: {
    unit: '毫秒',
    measures: '屏幕变绿到你点下的本机毫秒数',
    calibration: null,
    evidence: 'SELF_REPORTED',
    alternatives: [],
    background: 'abort-attempt',
    backgroundText: '切到后台本次作废，回来点一下重新开始',
    capability: {
      noSensor: '只用触屏，不需要传感器',
      permissionDenied: '不需要授权',
      resourceFailed: '开始时没联网就不计成绩，重新开始即可',
      lowMotion: '减弱动态时只换颜色、不闪动，规则不变',
    },
  },
  ballShake: {
    unit: '次',
    measures: '球撞到屏幕四边的次数',
    calibration: { what: '开局那一刻的握持姿势记成水平', range: '相对零点的倾斜', unit: '重力加速度 g', invalidWhen: '切后台、退出或重新开始后要重新校准' },
    evidence: 'SELF_REPORTED',
    alternatives: [],
    background: 'abort-attempt',
    backgroundText: '切到后台本局中断，不计成绩；回来重新开始会重新校准',
    capability: {
      noSensor: '这台设备没有可用的重力感应：本玩法无法进行，不会给你记一个模拟分数',
      permissionDenied: '重力感应不需要授权；读不到时按「无传感器」处理',
      resourceFailed: '读数中断即本局中断，可以重新开始',
      lowMotion: '减弱动态时不画拖影，规则不变',
    },
  },
  quietHold: {
    unit: '秒',
    measures: '持续低于本机安静阈值的秒数（本机 heldMs）',
    calibration: { what: '开局先听 2 秒环境音', range: '高于环境底噪的相对音量', unit: '相对振幅（不是分贝）', invalidWhen: '换地方、切后台或重新开始后重新校准' },
    evidence: 'SELF_REPORTED',
    alternatives: ['拒绝麦克风时可以跳过这一项，不记成绩、不发奖'],
    background: 'abort-attempt',
    backgroundText: '切到后台本次作废，熄屏那几秒不算安静',
    capability: {
      noSensor: '没有麦克风：本玩法无法进行',
      permissionDenied: '没开麦克风：可以去设置里打开，或跳过这一项（不记成绩）',
      resourceFailed: '录音中断即本次作废，可以重新开始',
      lowMotion: '减弱动态时只换底色，规则不变',
    },
  },
  shout: {
    unit: '秒',
    measures: '持续高于本机响亮阈值的秒数（本机 heldMs）',
    calibration: { what: '开局先听 2 秒环境音', range: '高于环境底噪的相对音量', unit: '相对振幅（不是分贝）', invalidWhen: '换地方、切后台或重新开始后重新校准' },
    evidence: 'SELF_REPORTED',
    alternatives: ['拒绝麦克风时可以跳过这一项，不记成绩、不发奖'],
    background: 'abort-attempt',
    backgroundText: '切到后台本次作废，回来重新开始',
    comfort: '不用喊到嗓子疼：比环境音明显大就算过线，注意周围的人',
    capability: {
      noSensor: '没有麦克风：本玩法无法进行',
      permissionDenied: '没开麦克风：可以去设置里打开，或跳过这一项（不记成绩）',
      resourceFailed: '录音中断即本次作废，可以重新开始',
      lowMotion: '减弱动态时不闪红，规则不变',
    },
  },
  stopwatch: {
    unit: '秒',
    measures: '本机从开始到停下的时长',
    calibration: null,
    evidence: 'SELF_REPORTED',
    alternatives: [],
    background: 'abort-attempt',
    backgroundText: '切到后台本局作废；只认最近一次开始，旧成绩不能再交',
    capability: {
      noSensor: '只用触屏，不需要传感器',
      permissionDenied: '不需要授权',
      resourceFailed: '开始时没联网就不计成绩',
      lowMotion: '减弱动态时数字照常隐藏，规则不变',
    },
  },
  compass: {
    unit: '度',
    measures: '本机罗盘方位角（0–359°）',
    calibration: { what: '手机画「8」字、远离金属', range: '0–359° 方位角', unit: '度', invalidWhen: '读数精度低、靠近磁铁或金属、切后台后需重新校准' },
    evidence: 'SELF_REPORTED',
    alternatives: [],
    background: 'abort-round',
    backgroundText: '切到后台保持计时清零，回来重新对准',
    capability: {
      noSensor: '这台设备没有罗盘：本玩法无法进行，不会记成绩',
      permissionDenied: '罗盘不需要授权；读不到时按「无罗盘」处理',
      resourceFailed: '罗盘启动失败：来源不足，无法判定方向',
      lowMotion: '减弱动态时指针不做动画，规则不变',
    },
  },
};

function rule(game) {
  return GAMES[game] || null;
}

/**
 * 这一屏底部那句证据说明。服务端说 XP_BADGE_ONLY 就按娱乐成绩说;
 * 没回字段时按表里的保守默认(设备自报 = 娱乐成绩)。
 */
function evidenceNote(game, seg) {
  const s = seg || {};
  const r = rule(game);
  if (s.rewardCeiling === 'XP_BADGE_ONLY') return ENTERTAINMENT_NOTE;
  if (s.rewardCeiling === 'FULL') return '';
  return r && r.evidence === 'SELF_REPORTED' ? ENTERTAINMENT_NOTE : '';
}

/** 逐玩法的前后台规则文案(M0106)。 */
function backgroundNote(game) {
  const r = rule(game);
  return r ? r.backgroundText : '';
}

/** 校准说明(M0103):测什么 + 单位 + 失效条件。没有校准的玩法给空串。 */
function calibrationNote(game) {
  const r = rule(game);
  if (!r || !r.calibration) return '';
  const c = r.calibration;
  return c.what + '；读数为' + c.unit + '；' + c.invalidWhen;
}

/** M0105 设备能力矩阵:一行一个玩法,四种降级各一句。 */
function capabilityMatrix() {
  return Object.keys(GAMES).map((game) => Object.assign({ game }, GAMES[game].capability));
}

module.exports = {
  GAMES, EVIDENCE_TEXT, ENTERTAINMENT_NOTE,
  rule, evidenceNote, backgroundNote, calibrationNote, capabilityMatrix,
};
