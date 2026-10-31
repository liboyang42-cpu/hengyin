package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;

/**
 * 主题章节Service接口
 * 
 * @author ruoyi
 * @date 2025-09-12
 */
public interface ICmsTopicChapterService 
{
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

    /**
     * 新增主题章节
     * 
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    public int insertCmsTopicChapter(CmsTopicChapter cmsTopicChapter);

    /**
     * 修改主题章节
     * 
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    public int updateCmsTopicChapter(CmsTopicChapter cmsTopicChapter);

    /**
     * 批量删除主题章节
     * 
     * @param ids 需要删除的主题章节主键集合
     * @return 结果
     */
    public int deleteCmsTopicChapterByIds(Long[] ids);

    /**
     * 删除主题章节信息
     *
     * @param id 主题章节主键
     * @return 结果
     */
    public int deleteCmsTopicChapterById(Long id);

    /**
     * [D21] 配置章节的**供给条款** —— <b>这是「③ 的一章怎么让商家参与」的唯一入口</b>,
     * 也是「把一章标成计酬档」的唯一入口。
     *
     * <p><b>为什么不在发布器</b>:计酬 = 平台按人头给商家掏现金,是平台的采购决策,
     * 不是发布者(普通会员)能替平台承诺的;且 D21 规定计酬档只对 ③-自营 开放,
     * 而发布器建主题那一刻内容槽还是 MEMBER,<b>校验不了自营</b>。
     * ⇒ 正确顺序是:先用 P0-35 把内容槽改判成 PLATFORM,再用本方法配条款。
     *
     * <p><b>为什么四个字段一起配、不拆成四个接口</b>:它们是**一套形状**,不是四个开关。
     * 档位一变另外几个的合法性跟着变,分开配就必然配出 enroll 不认的组合 ——
     * 而那时报错的是**商家**,不是配错的运营。
     *
     * <p><b>四道闸</b>:
     * <ol>
     *   <li>档位必须合法({@code PERK/REVSHARE/TRAFFIC});</li>
     *   <li><b>形状必须与档位自洽</b>(与 {@code MerchantOfferServiceImpl} 的三道校验逐字对齐):
     *       计酬档<b>必须有人头费上限且 &gt;0</b>(空 = 商家入驻恒被拒 = 配了个没人能进的章);
     *       非计酬档不得有上限;非权益档不得有权益门槛;</li>
     *   <li>标 REVSHARE 时,主题必须是 ③-自营({@code isPlatformOwned},fail-closed)——
     *       拒了的正确反应是<b>先把内容槽改对</b>,不是把闸放宽;</li>
     *   <li>⚠️ <b>改档位时该章不得有生效供给</b>。理由是资损而非洁癖:容量闸按**章节**档位走(F1),
     *       而 offer 的档位是**入驻时的快照**(刻意的:已谈定的条款不被追溯改写)。
     *       把有 PERK 供给的章改成 REVSHARE ⇒ 容量闸对该章当场失效,可存量 PERK offer
     *       仍按 quota 扣 ⇒ <b>那些商家承诺的接待上限失守 = 超发</b>。
     *       ⚠️ 只在**档位真的变了**时拦:同档位下调整上限/门槛不影响存量 offer
     *       (它们带的是入驻时的快照),拦了反而让运营没法给在营的章补配上限。</li>
     * </ol>
     *
     * @param chapterId 章节主键
     * @param terms     四个字段一套;空的含义三者刻意不同,见 {@link ChapterSupplyTermsDTO}
     * @throws IllegalStateException 任一道闸不过;<b>消息里写明是哪一道</b>,别让运营对着失败猜
     */
    /**
     * 归属人开关某一章的商家承接(稿 468:1126「开放报名」)。
     *
     * <p>归属与「已结束不能再开」都压在 SQL 的 WHERE 里 —— Java 侧判一次是「先读后写」,
     * 两次请求之间归属可能已经变了。
     *
     * @return 0 表示没写成(不是自己的 / 已结束 / 章节不在)
     */
    public int setChapterRecruitAsOwner(Long chapterId, Long memberId, Integer enabled);

    /**
     * 归属人结束某一章(稿 468:1126「结束本章」,不可逆)。
     *
     * <p>WHERE 里带 {@code finish_time is null} 这道 CAS:并发两次只能成一次,
     * 第二次不会覆盖掉第一次记下的时刻(结算按那个时刻对账)。
     *
     * @return 0 表示没写成(不是自己的 / 已经结束过 / 章节不在)
     */
    public int finishChapterAsOwner(Long chapterId, Long memberId);

    public void configureSupplyTerms(Long chapterId, ChapterSupplyTermsDTO terms);


    /** 主办方在工作台改某一站的档位:锁内校验归属,规则与后台 configureSupplyTerms 同一套。 */

    void configureSupplyTermsAsOwner(Long chapterId, Long memberId, ChapterSupplyTermsDTO terms);

    /** 按章节读取人工批次容量；尚未配置时返回三数全空的 DTO。 */
    public ChapterServiceCapacityDTO getServiceCapacity(Long chapterId);

    /** 一次性保存每批人数、批次间隔、并行位三个正整数。 */
    public void configureServiceCapacity(Long chapterId, ChapterServiceCapacityDTO capacity);

    /** F05 读取章节商家工作包(开售快照 merchantWorkPackage 的来源);未配置返回 null。 */
    public com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta getWorkPackage(Long chapterId);

    /** F05 一次性写入章节商家工作包六项,缺一拒写;开售后冻结。 */
    public void configureWorkPackage(Long chapterId, com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta workPackage);
}
