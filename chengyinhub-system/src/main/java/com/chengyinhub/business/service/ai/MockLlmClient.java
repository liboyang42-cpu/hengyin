package com.chengyinhub.business.service.ai;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * Mock 大模型客户端（本期默认,无需 API Key）。
 *
 * 按 systemPrompt 中的 [BIZ:xxx] 标记返回确定性结构化假 JSON,
 * 保证 AI 链路 / 频控 / 内容安全 / 落库全部可测。
 * 切真模型只需把 ai.model.provider 改为 qwen/deepseek。
 *
 * 约定 userPrompt 为结构化上下文 JSON(由 AiCreatorServiceImpl 拼装)。
 *
 * @author chengyinhub
 */
@Component("mockLlmClient")
public class MockLlmClient implements LlmClient
{
    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    @Override
    public String name()
    {
        return "mock";
    }

    @Override
    public String chat(String systemPrompt, String userPrompt)
    {
        String biz = extractBiz(systemPrompt);
        log.info("[MockLlm] biz={}, userPromptLen={}", biz, userPrompt == null ? 0 : userPrompt.length());
        JSONObject ctx = parseContext(userPrompt);
        switch (biz == null ? "" : biz)
        {
            case "merchant_plan":
                return mockMerchantPlan(ctx);
            case "theme_draft":
                return mockThemeDraft(ctx);
            case "node_generate":
                return mockNodeGenerate(ctx);
            case "chapter_overview":
                return mockChapterOverview(ctx);
            case "safety_precheck":
                return mockSafetyPrecheck(ctx);
            case "merchant_task_suggest":
                return mockMerchantTaskSuggest(ctx);
            case "club_design":
                return mockClubDesign(ctx);
            default:
                return mockThemeDraft(ctx);
        }
    }

    /** 从 systemPrompt 提取 [BIZ:xxx] 标记。 */
    private String extractBiz(String systemPrompt)
    {
        if (systemPrompt == null)
        {
            return null;
        }
        int s = systemPrompt.indexOf("[BIZ:");
        if (s < 0)
        {
            return null;
        }
        int e = systemPrompt.indexOf(']', s);
        if (e < 0)
        {
            return null;
        }
        return systemPrompt.substring(s + 5, e).trim();
    }

    private JSONObject parseContext(String userPrompt)
    {
        if (userPrompt == null || userPrompt.trim().isEmpty())
        {
            return new JSONObject();
        }
        try
        {
            return JSONObject.parseObject(userPrompt);
        }
        catch (Exception e)
        {
            JSONObject o = new JSONObject();
            o.put("idea", userPrompt);
            return o;
        }
    }

    /** M1 组题:基于传入候选商家组合 2 套方案(候选事实真实,文案为兜底假数据)。 */
    private String mockMerchantPlan(JSONObject ctx)
    {
        JSONArray candidates = ctx.getJSONArray("candidates");
        String idea = ctx.getString("idea");
        int duration = ctx.getIntValue("durationMin", 90);

        JSONArray plans = new JSONArray();
        plans.add(buildPlan(candidates, idea, duration, "夜色漫游·光与味", 0));
        if (candidates != null && candidates.size() >= 3)
        {
            plans.add(buildPlan(candidates, idea, duration, "城市切片·小众探索", 1));
        }
        JSONObject root = new JSONObject();
        root.put("plans", plans);
        return root.toJSONString();
    }

    private JSONObject buildPlan(JSONArray candidates, String idea, int duration, String title, int offset)
    {
        JSONObject plan = new JSONObject();
        plan.put("title", title);
        plan.put("subtitle", "AI 辅助生成 · " + (idea == null ? "城市定向" : truncate(idea, 12)));
        plan.put("storyline", "从一处暖场开始,沿着这条线走走停停,完成打卡与任务,收尾在一个有奖的节点。");
        JSONArray tags = new JSONArray();
        tags.add("城市定向");
        if (idea != null && idea.contains("夜"))
        {
            tags.add("夜间");
        }
        tags.add("Citywalk");
        plan.put("tags", tags);
        plan.put("estDurationMin", duration);
        plan.put("fitReason", "步行可达、候选商家审核通过,贴合主题场景。");
        JSONArray risks = new JSONArray();
        risks.add("部分商家营业时间有限,需提醒玩家提前到达。");
        plan.put("risks", risks);

        JSONArray nodes = new JSONArray();
        if (candidates != null)
        {
            int take = Math.min(3, candidates.size());
            for (int i = 0; i < take; i++)
            {
                JSONObject c = candidates.getJSONObject((i + offset) % candidates.size());
                JSONObject n = new JSONObject();
                n.put("order", i + 1);
                n.put("candidateId", c.getLong("candidateId"));
                n.put("merchantName", c.getString("merchantName"));
                n.put("address", c.getString("address"));
                n.put("longitude", c.getString("longitude"));
                n.put("latitude", c.getString("latitude"));
                n.put("businessTime", c.getString("businessTime"));
                n.put("roleText", i == 0 ? "起点·暖场" : (i == take - 1 ? "终点·奖励" : "途中·任务"));
                n.put("task", "到店完成打卡任务并拍照记录。");
                n.put("validationMethod", 4); // 4=扫码打卡
                n.put("hint1", "留意店内的特色陈设。");
                n.put("hint2", "靠近收银台或入口处。");
                n.put("feedbackText", "漂亮,这一站拿下!");
                n.put("rewardSuggest", "以商家库存为准,可设优惠券反馈。");
                nodes.add(n);
            }
        }
        plan.put("nodes", nodes);
        return plan;
    }

    /** M2 主题草稿:一句话 → 主题骨架(无坐标,留用户在 fabu 补点)。 */
    private String mockThemeDraft(JSONObject ctx)
    {
        String idea = ctx.getString("idea");
        JSONArray existing = ctx.getJSONArray("existingNodeNames");

        JSONObject plan = new JSONObject();
        plan.put("title", "AI 草稿 · " + (idea == null ? "城市定向主题" : truncate(idea, 10)));
        plan.put("subtitle", "AI 辅助生成,请在编辑器中完善");
        plan.put("storyline", idea == null ? "一条有节奏的城市探索路线。" : ("围绕「" + truncate(idea, 20) + "」设计的故事线。"));
        JSONArray tags = new JSONArray();
        tags.add("AI 草稿");
        plan.put("tags", tags);
        plan.put("estDurationMin", 90);

        JSONArray nodes = new JSONArray();
        int nodeCount = (existing != null && existing.size() > 0) ? existing.size() : 3;
        for (int i = 0; i < nodeCount; i++)
        {
            JSONObject n = new JSONObject();
            n.put("order", i + 1);
            // 主题草稿共用 AiPlanNode 的 merchantName 字段承载“待确认点位名”；
            // 真正的地址与坐标仍必须由发布者在地图中逐个确认。
            n.put("merchantName", "待选点位" + (i + 1));
            n.put("roleText", i == 0 ? "起点" : (i == nodeCount - 1 ? "终点" : "途经"));
            n.put("task", "到点完成打卡与任务。");
            n.put("validationMethod", 4);
            n.put("hint1", "观察周围标志性建筑。");
            n.put("hint2", "靠近入口处寻找线索。");
            n.put("feedbackText", "这一站完成,继续!");
            n.put("rewardSuggest", "");
            nodes.add(n);
        }
        plan.put("nodes", nodes);

        JSONObject root = new JSONObject();
        root.put("plans", new JSONArray());
        root.getJSONArray("plans").add(plan);
        return root.toJSONString();
    }

    /** M2 节点助手:为节点生成玩法内容。 */
    private String mockNodeGenerate(JSONObject ctx)
    {
        String nodeName = ctx.getString("nodeName");
        Integer method = ctx.getInteger("validationMethod");

        JSONObject node = new JSONObject();
        int vm = (method == null || method == 0) ? 1 : method;
        node.put("validationMethod", vm);
        node.put("ruleInstructions", "到达「" + (nodeName == null ? "该地点" : nodeName) + "」后按要求完成任务。");
        node.put("questionName", "在这里你能看到什么标志性元素?");
        if (vm == 3)
        {
            node.put("optionA", "招牌");
            node.put("optionB", "门牌号");
            node.put("optionC", "雕塑");
            node.put("optionD", "地砖花纹");
            node.put("correctAnswer", "B");
        }
        else
        {
            node.put("questionAnswer", "门牌号");
        }
        node.put("hint1", "抬头看看店面上方。");
        node.put("hint2", "靠近入口的数字。");
        node.put("answerReveal", "答案藏在门牌号里。");
        node.put("feedbackText", "找到了!线索到手。");
        node.put("storyText", "你站在「" + (nodeName == null ? "这处地点" : nodeName)
            + "」前,喧嚣忽然退远。留意檐角与光影,故事正从这里展开。");

        JSONObject root = new JSONObject();
        root.put("node", node);
        return root.toJSONString();
    }

    /** 章节概述:确定性假概述,含章节名让前端断言可控。 */
    private String mockChapterOverview(JSONObject ctx)
    {
        String chapterName = ctx.getString("chapterName");
        String cn = (chapterName == null || chapterName.isEmpty()) ? "本章" : chapterName;
        JSONObject root = new JSONObject();
        root.put("overview", "「" + cn + "」把你带入这段城市漫游:沿途的招牌、气味与转角都是线索,放慢脚步,让地点自己开口讲故事。");
        return root.toJSONString();
    }

    /** M2 安全预检:内容安全 + 缺口/风险(基于传入内容做规则判断,mock 不真正调内容安全)。 */
    private String mockSafetyPrecheck(JSONObject ctx)
    {
        List<JSONObject> issues = new ArrayList<>();
        String title = ctx.getString("title");
        JSONArray nodes = ctx.getJSONArray("nodes");

        if (title == null || title.trim().isEmpty())
        {
            JSONObject i = new JSONObject();
            i.put("level", "error");
            i.put("type", "missing_title");
            i.put("message", "缺少主题标题。");
            issues.add(i);
        }
        if (nodes == null || nodes.isEmpty())
        {
            JSONObject i = new JSONObject();
            i.put("level", "error");
            i.put("type", "missing_nodes");
            i.put("message", "至少需要一个节点。");
            issues.add(i);
        }
        else
        {
            for (int i = 0; i < nodes.size(); i++)
            {
                JSONObject n = nodes.getJSONObject(i);
                if (isEmpty(n.getString("task")))
                {
                    JSONObject iss = new JSONObject();
                    iss.put("level", "warn");
                    iss.put("type", "missing_task");
                    iss.put("message", "节点 " + (i + 1) + " 缺少任务描述。");
                    issues.add(iss);
                }
            }
        }

        JSONObject root = new JSONObject();
        root.put("pass", issues.stream().noneMatch(o -> "error".equals(o.getString("level"))));
        root.put("issues", issues);
        return root.toJSONString();
    }

    /** M3 商家任务建议:商户→适合主题/任务角色/合作话术。 */
    private String mockMerchantTaskSuggest(JSONObject ctx)
    {
        return "{\"suggestions\":[{\"title\":\"情侣夜间慢游\",\"themeFit\":\"静安咖啡店有户外座位+晚间营业,天然适合情侣夜间Citywalk的起点或中段休息站\",\"taskRole\":\"暖场·咖啡品鉴\",\"taskIdea\":\"在吧台点一杯当日手冲并拍照打卡,照片需包含门店logo和手冲壶\",\"rewardIdea\":\"买一赠一(建议商家提供,以实际库存为准)\",\"coopScript\":\"我们的咖啡店很适合做情侣夜间慢游的起点——暖场环节可以在这里完成,边喝手冲边介绍路线故事。我们提供户外座位和拍照场景,愿意提供买一赠一优惠作为任务奖励。\"},{\"title\":\"文艺解谜线\",\"themeFit\":\"咖啡店可作为解谜线索的一环,门牌号/墙上标语/特调名字都可变成谜题元素\",\"taskRole\":\"解谜·线索站\",\"taskIdea\":\"在特调菜单上找到隐藏的数字密码,输入答题框解锁下一站提示\",\"rewardIdea\":\"特调解锁价(以商家库存为准)\",\"coopScript\":\"我们的特调菜单每季换新,可以内置解谜线索。适合文艺解谜线的第二站——给玩家一杯特调的功夫正好解一个谜。\"},{\"title\":\"美食探店线\",\"themeFit\":\"咖啡+甜品组合可做探店线的打卡节点,拍照发朋友圈有传播价值\",\"taskRole\":\"打卡·拍照站\",\"taskIdea\":\"拍摄手冲咖啡与甜品的俯拍组合照,上传到活动相册\",\"rewardIdea\":\"第二杯半价(以商家库存为准)\",\"coopScript\":\"我们提供高颜值的咖啡+甜品组合,是探店线的理想拍照节点。玩家拍照发朋友圈也能给双方带来曝光,我们可以配合提供打卡专属折扣。\"}]}";
    }

    /** M3 俱乐部活动策划:一句话→方案+商家+文案。 */
    private String mockClubDesign(JSONObject ctx)
    {
        return "{\"plan\":{\"title\":\"静安夜光·咖啡与诗\",\"subtitle\":\"一条适合文艺青年的夜间Citywalk\",\"storyline\":\"从一家独立咖啡店出发,沿着静安的老弄堂和新马路,在咖啡香与夜色中寻找城市的另一面。三个节点,90分钟,一场流动的文艺对话。\",\"tags\":[\"夜间\",\"文艺\",\"Citywalk\",\"咖啡\"],\"estDurationMin\":90,\"fitReason\":\"适合文艺慢生活风格的俱乐部,夜晚氛围好、步行可达、含拍照和互动元素\",\"nodes\":[{\"order\":1,\"merchantName\":\"XX咖啡(示例)\",\"roleText\":\"起点·暖场\",\"task\":\"在吧台点一杯当日手冲并拍照打卡\",\"rewardSuggest\":\"买一赠一\"},{\"order\":2,\"merchantName\":\"XX书店(示例)\",\"roleText\":\"中段·沉浸\",\"task\":\"找到一本与「夜」有关的书,拍下书名页\",\"rewardSuggest\":\"书店折扣券\"},{\"order\":3,\"merchantName\":\"XX甜品(示例)\",\"roleText\":\"终点·回味\",\"task\":\"品尝招牌甜品并写下三句话的评价\",\"rewardSuggest\":\"甜品买一赠一\"}]},\"merchantSuggestions\":[\"独立咖啡店(有户外座位,晚间营业)\",\"独立书店(有阅读区,氛围好)\",\"精品甜品店(有堂食区)\"],\"promoCopy\":\"🌙 静安夜光·咖啡与诗\\n\\n这是一条为文艺灵魂设计的夜间Citywalk。\\n\\n我们从一杯手冲开始,在弄堂的阴影和新马路的灯光之间穿行,经过书店的纸页和甜品店的橱窗——90分钟,三个节点,一场流动的文艺对话。\\n\\n📅 本周六 19:00\\n📍 静安寺地铁站集合\\n👥 限12人\\n🎫 免费报名,店内消费自理\\n\\n想来吗? 扫码进群👇\"}";
    }

    private boolean isEmpty(String s)
    {
        return s == null || s.trim().isEmpty();
    }

    private String truncate(String s, int max)
    {
        if (s == null)
        {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
