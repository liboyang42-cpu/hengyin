package com.chengyinhub.business.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.chengyinhub.business.domain.CmsTopicChapter;

/**
 * 主题章节Mapper接口
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public interface CmsTopicChapterMapper 
{
    /** All physically retained content, including hidden rows; caller holds the topic content fence. */
    Long countContentByTopicForUpdate(@Param("topicId") Long topicId);

    /** Parent fence first; current row read proves the locator still belongs to that parent. */
    CmsTopicChapter selectContentByIdForUpdate(@Param("id") Long id);

    /**
     * 查询主题章节
     * 
     * @param id 主题章节主键
     * @return 主题章节
     */
    public CmsTopicChapter selectCmsTopicChapterById(Long id);

    /**
     * 查询主题章节列表
     * 
     * @param cmsTopicChapter 主题章节
     * @return 主题章节集合
     */
    public List<CmsTopicChapter> selectCmsTopicChapterList(CmsTopicChapter cmsTopicChapter);

    /** 主题组合审核按稳定顺序锁定章节。 */
    List<CmsTopicChapter> selectByTopicForUpdate(@Param("topicId") Long topicId);

    /**
     * 新增主题章节
     * 
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    public int insertCmsTopicChapter(CmsTopicChapter cmsTopicChapter);

    /**
     * [D21] 无条件覆盖章节的四个供给条款列 —— <b>null 就是要写进去的值</b>。
     *
     * <p>⚠️ <b>不能用 {@code updateCmsTopicChapter} 代替</b>:那条是 {@code <if test="xx != null">}
     * 逐列拼的,传 null 只会**跳过**那一列、不会清空它。于是「计酬档改成引流档」会把
     * {@code max_per_head_fee} 留在库里 —— 应用层校验放行了(DTO 里是 null),库里却不是;
     * 等哪天这章改回计酬档,那个没人记得的旧上限会**静默生效**。
     *
     * <p>只给 {@code configureSupplyTerms} 用。其它地方改这四列一律走那个方法(它带四道闸)。
     */
    /**
     * 归属人开关某一章的商家承接(稿 468:1126「开放报名」)。
     *
     * <p>⚠️ <b>不能用 {@code updateCmsTopicChapter} 代替</b>:那条是裸 {@code where id = #{id}}。
     * 归属若只在 Java 侧判,那是「先读后写」——两次请求之间归属可能已经变了。
     * 这条把 owner 谓词放进 WHERE,库级兜住。
     *
     * <p>同时挡住已结束的章节:结束不可逆,不能靠一条能来回拨的开关复活。
     *
     * @return 0 表示没写成(不是自己的 / 已结束 / 章节不在),调用方必须当失败处理
     */
    public int setRecruitEnabledIfOwned(@Param("chapterId") Long chapterId,
                                        @Param("memberId") Long memberId,
                                        @Param("enabled") Integer enabled);

    /**
     * 归属人结束某一章(稿 468:1126「结束本章」,不可逆)。
     *
     * <p>除 owner 谓词外还带 {@code finish_time is null} 这道 CAS:Java 侧的
     * {@code if (getFinishTime() != null)} 是先读后写,两个并发请求都能过,
     * 第二次会覆盖掉第一次记下的时刻 —— 而结算按那个时刻对账。
     *
     * <p>顺带把 {@code recruit_enabled} 归零:已结束的章节不该还开着招商。
     *
     * @return 0 表示没写成(不是自己的 / 已经结束过 / 章节不在)
     */
    public int finishChapterIfOwned(@Param("chapterId") Long chapterId,
                                    @Param("memberId") Long memberId);

    public int updateSupplyTerms(@Param("chapterId") Long chapterId,
                                 @Param("termsMode") String termsMode,
                                 @Param("perkMinValue") java.math.BigDecimal perkMinValue,
                                 @Param("maxPerHeadFee") java.math.BigDecimal maxPerHeadFee,
                                 @Param("maxMerchant") Integer maxMerchant,
                                 @Param("updateTime") java.util.Date updateTime);

    /**
     * 修改主题章节
     * 
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    public int updateCmsTopicChapter(CmsTopicChapter cmsTopicChapter);

    /**
     * 删除主题章节
     * 
     * @param id 主题章节主键
     * @return 结果
     */
    public int deleteCmsTopicChapterById(Long id);

    /**
     * 批量删除主题章节
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteCmsTopicChapterByIds(Long[] ids);

    /**
     * P0-15 购票路径的序列化点:按 id 升序锁住这些章节行。
     *
     * 章节可续售容量 = Σ(生效offer的 quota_total − quota_used) − 该章已售未核销 entitlement 数
     * —— 是个跨两表的聚合,库里没有单行可锁。裸算会幻读:两个玩家同时算出「还剩 1」,
     * uk_entitlement 是 (registration_id, chapter_id) 跨玩家永不冲突 ⇒ 双双插入成功 ⇒ 超卖。
     * 故购票事务必须先锁住全部必选章行,再算聚合、再插 entitlement。
     *
     * ⚠️ **调用方必须传升序 id**(本方法只负责 order by,但 MyBatis foreach 的顺序即加锁顺序)——
     * 两个事务锁同一组章节时顺序相反会 ABBA 死锁。
     * ⚠️ 核销路径**不碰**章节行(它按 entitlement → offer → redemption 走),故与购票无 ABBA。
     * 全局加锁顺序:cms_topic_chapter → registration_entitlement → merchant_chapter_offer → redemption。
     *
     * @return 锁到的章节 id(数量少于入参 = 有章节不存在,调用方须拒)
     */
    public List<Long> lockChaptersForCapacity(@Param("ids") List<Long> ids);

    /** ③ 的必选章(required=1);通关判定与容量校验都按它。 */
    public List<CmsTopicChapter> selectRequiredByTopic(@Param("topicId") Long topicId);
}
