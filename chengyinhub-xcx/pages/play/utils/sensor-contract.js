// sensor-contract · 传感器小游戏的可信边界(C1 选项 A,2026-10-22 拍板)
//
// ★ 服务端每次开表发一个挑战身份 attemptId(绑本人 + 本局 + 本节点,单次消费、会过期)
//   和一个奖励上限 rewardCeiling。设备自报成绩(安静/喊/罗盘/停表…)只算娱乐成绩:
//   能过关、记探索值与徽章,不发积分、券、掉落。
// ★ 这里**只搬运服务端说的**:字段缺失时不承诺奖励、不说「已核验」,也不自己推一个默认上限。
//   能不能领奖由服务端完成回包决定,界面不自行补发,也不换旧路径领奖。

const CEILINGS = ['FULL', 'XP_BADGE_ONLY'];

/** 服务端说只记探索值与徽章时的那一句;别的情况(含字段缺失)一个字都不说。 */
const XP_BADGE_ONLY_NOTE = '本机成绩：只记探索值与徽章，不发券或积分';
/** 扫码比对的是节点静态码:证明码对得上,不证明人在现场。 */
const SCAN_MATCH_NOTE = '核对的是这一站的任务码，不代表到场核验';
/** 拍照:视觉模型当场真判 PASS(服务端 photoVerified=true)才这么说。 */
const PHOTO_VERIFIED_NOTE = '照片已核验';
/** 次数用尽兜底放行:算过,但不是「这张过了」。 */
const PHOTO_FALLBACK_VERDICT = '机会用完了，这张先算过';

/** 服务端发的挑战身份:正的安全整数才认,别的一律当没有(不猜、不补)。 */
function attemptOf(value) {
  const n = typeof value === 'string' && /^\d+$/.test(value.trim()) ? Number(value.trim()) : value;
  return typeof n === 'number' && Number.isSafeInteger(n) && n > 0 ? n : 0;
}

function ceilingOf(value) {
  return CEILINGS.indexOf(value) >= 0 ? value : '';
}

/**
 * 会话视图 playKit 段 → 组件要的两个键。恒产出(分发器绑了就得有人产出),
 * 服务端没给时是 0 / 空串 —— 组件据此「什么都不说」,不是按保守默认去说。
 */
function kitFields(seg) {
  const s = seg || {};
  return {
    attemptId: attemptOf(s.attemptId),
    rewardCeiling: ceilingOf(s.rewardCeiling),
  };
}

/** 提交 payload 带上挑战身份;没有就原样返回(存量口径,服务端照旧复核)。 */
function withAttempt(payload, detail) {
  const id = attemptOf(detail && detail.attemptId);
  return id ? Object.assign({}, payload, { attemptId: id }) : payload;
}

/**
 * 本次开局之后服务端新发的挑战身份。开局那一刻手里那个号(上一次没交掉的)不算 ——
 * 拿旧号去交只会被判作废;新号还没回来就不带,走存量复核口径,奖励上限照样由服务端封顶。
 */
function freshAttempt(current, atRunStart) {
  const id = attemptOf(current);
  return id && id !== attemptOf(atRunStart) ? id : 0;
}

/** 底部那句奖励说明。只有服务端明说 XP_BADGE_ONLY 才说;FULL 或缺失都不说(不承诺)。 */
function rewardNote(rewardCeiling) {
  return ceilingOf(rewardCeiling) === 'XP_BADGE_ONLY' ? XP_BADGE_ONLY_NOTE : '';
}

/** 安静/喊一嗓子的阈值说明:相对校准底噪的振幅比例,明说不是分贝。 */
function relativeThresholdText(level) {
  const n = Number(level);
  if (!Number.isFinite(n) || n <= 0) return '';
  return '本机阈值 ' + Math.round(Math.min(1, n) * 100) + '%（相对满幅振幅，不是分贝）';
}

module.exports = {
  XP_BADGE_ONLY_NOTE, SCAN_MATCH_NOTE, PHOTO_VERIFIED_NOTE, PHOTO_FALLBACK_VERDICT,
  attemptOf, kitFields, withAttempt, freshAttempt, rewardNote, relativeThresholdText,
};
