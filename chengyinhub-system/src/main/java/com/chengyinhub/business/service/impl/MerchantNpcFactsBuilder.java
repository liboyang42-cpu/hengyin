package com.chengyinhub.business.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.IMmsMerchantService;

/**
 * 门店 NPC 的<b>事实包</b>:拼进 system prompt 的那段「这家店是什么样的」。
 *
 * <p>它决定了玩家问「有什么推荐」时,模型手上有没有真东西可答。
 *
 * <p><b>★ 三条硬约束,每条都对应一个真会发生的坏结果:</b>
 *
 * <p>① <b>只放白名单字段。</b>事实包会原样进模型,进模型的东西等于送出了平台。
 * 商家表里有 phone / address 这类联系与位置信息,<b>一律不进</b> ——
 * 玩家问「你们老板电话多少」时模型答不出来才是对的。
 * 口径与 merchant_insight 一致(那边用单测断言 prompt 输入体 grep 不到 nickname/phone/member_id)。
 *
 * <p>② <b>拿不到就不写这一行,绝不编占位。</b>写「介绍:暂无」进 prompt,
 * 模型会把「暂无」当成可以复述的事实,回答里蹦出「我们店的介绍是暂无」。
 * 空字段直接不出现在事实包里。
 *
 * <p>③ <b>末尾必须带边界指令。</b>没有它,模型会拿训练语料里的通用餐饮知识编菜名 ——
 * 玩家照着去点单,店里根本没有这道菜。边界指令是这个功能能不能对外开的前提,
 * 不是「加了更好」的润色。
 *
 * <p><b>刻意不做(不是漏了):</b>在架优惠券不进 v1 事实包。它要引入券服务依赖 +
 * 库存/有效期的实时性问题(模型说「有券」而券刚发完 = 到店扑空)。
 * 商家想让 NPC 提券,现在可以自己写进「店铺知识」——那是他自己维护、自己负责的文本。
 *
 * @author chengyinhub
 */
@Component
public class MerchantNpcFactsBuilder
{
    /** 单个字段进 prompt 的长度上限。商家自己填的 knowledge 可能很长,截断防 token 爆。 */
    private static final int FIELD_MAX = 600;

    /**
     * 边界指令。★ 与事实包同生共死:任何一条组装路径都必须带上它。
     * 抽成常量是为了让「有没有带」在测试里可断言,而不是散在字符串拼接里靠肉眼看。
     */
    static final String BOUNDARY = "\n【回答边界】\n"
            + "1. 只依据上面列出的本店事实回答。事实里没有的菜品、价格、活动,一律不要说,"
            + "更不要凭常识补全 —— 玩家会照着你说的去点单。\n"
            + "2. 不知道就直说「这个我这儿没有记录,你可以到店问问」,这比编一个好。\n"
            + "3. 不要推荐别的店,不要比较同行。\n"
            + "4. 不要透露联系方式、地址坐标之类的信息,也不要索取玩家的个人信息。\n"
            + "5. 用店主本人的口吻,一次回答控制在三句话以内。";

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    /**
     * 拼出 system prompt。
     *
     * @param profile    门店分身(这里只读取 knowledge；persona 由对话服务统一拼接)
     * @param merchantId 商家 ID
     * @return 完整 system prompt;profile 为 null 时返回 null(调用方据此拒绝对话)
     */
    public String build(NpcProfile profile, Long merchantId)
    {
        if (profile == null)
        {
            return null;
        }

        StringBuilder sb = new StringBuilder("【本店事实】\n");
        int factCount = 0;

        MmsMerchant merchant = loadMerchant(merchantId);
        if (merchant != null)
        {
            factCount += appendIfPresent(sb, "店名:", merchant.getName());
            factCount += appendIfPresent(sb, "介绍:", merchant.getDescription());
            factCount += appendIfPresent(sb, "特色标签:", merchant.getTags());
            factCount += appendIfPresent(sb, "品牌故事:", merchant.getStoryTitle());
        }
        factCount += appendIfPresent(sb, "店铺知识:", profile.getKnowledge());

        if (factCount == 0)
        {
            // ★ 一条事实都没有 ⇒ 明确告诉模型「你什么都不知道」。
            //   不写这句的话,事实包只剩一个空标题,模型会当成「没限制」然后自由发挥。
            sb.append("(店主还没有填写任何店铺信息。)\n");
        }

        sb.append(BOUNDARY);
        return sb.toString();
    }

    /**
     * 商家档案查不到不该让对话整个失败 —— persona 还在,退化成「只有人设、没有店铺事实」,
     * 由 build() 里的 factCount==0 分支接住。
     */
    private MmsMerchant loadMerchant(Long merchantId)
    {
        if (merchantId == null)
        {
            return null;
        }
        try
        {
            return mmsMerchantService.selectMmsMerchantById(merchantId);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** 有内容才写一行,返回 1;空/空白返回 0。 */
    private int appendIfPresent(StringBuilder sb, String label, String value)
    {
        if (value == null)
        {
            return 0;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty())
        {
            return 0;
        }
        if (trimmed.length() > FIELD_MAX)
        {
            trimmed = trimmed.substring(0, FIELD_MAX);
        }
        sb.append(label).append(trimmed).append('\n');
        return 1;
    }
}
