package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.PlayBingoCard;

import java.util.Map;

/** 宾果任务格。 */
public interface IPlayBingoService {

    /**
     * 节点完成后刷新这张卡。开卡(如果还没有)→ 重算该填的格 → 发新解锁的奖档。
     *
     * <p>⚠️ **调用方必须把它包在 try-catch 里**,失败只记 error。
     * 宾果是锦上添花,它出问题不能让节点完成失败 —— 玩家会卡在这一站反复重试。
     *
     * @return 刷新后的卡;失败时由调用方吞掉异常,这里正常抛
     */
    PlayBingoCard onNodeDone(Long memberId, Long topicId);

    /** 读一张卡给前端渲染;没有就返回 null(还没开始玩这个主题)。 */
    PlayBingoCard view(Long memberId, Long topicId);

    /**
     * 玩家侧那一屏要的九宫格投影,给高级玩法运行期的 {@code playKit.bingo} 段用。
     *
     * <p>与 {@link #view} 的区别:那张卡只是掩码,格子的名称与点亮条件冻在开卡那一刻的
     * 快照列里 —— 渲染成人看的这一屏得把快照解开、补齐九格,那是宾果自己的事,
     * 不该让运行期去猜位序。
     *
     * <p>★ 只读不写:没开卡就是「一格都还没点亮」,回一副空格子,不会顺手开卡。
     *
     * <p>⚠️ 与 {@link #onNodeDone} 同一条纪律:**调用方必须包在 try-catch 里**。
     * 这一段只呈现主题级进度,读不到就不下发,不能拖累它所在的整个玩法视图。
     *
     * @return 与前端钉死的六键(title/labels/cellSpecs/filledPositions/lineReward/fullReward);
     *         主题没开九宫格(或参数不对)返回 null,意思是「没有这一段」
     */
    Map<String, Object> playKitView(Long memberId, Long topicId);
}
