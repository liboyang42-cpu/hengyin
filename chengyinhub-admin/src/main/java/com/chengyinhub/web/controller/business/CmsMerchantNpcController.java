package com.chengyinhub.web.controller.business;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * 门店 AI 形象(npc_profile scope_type=2「门店分身」)后台维护。
 *
 * 首发范围只到「店铺主页静态出场」:一个像素头像 + 名字 + 一句招呼语,不接对话。
 * 因此本控制器不写 persona —— 人设只有对话轨才读,现在填了也没有消费方,
 * 只是把「像是配好了」的假象留给下一个人。表里 persona 是 NOT NULL,故落空串。
 * 接对话时在这里加 persona 字段 + 把 scope 2 接进 NpcChatService 即可。
 *
 * 审核:后台是可信来源(运营超管账号),新增即 audit_status=1,不做二段审核仪式;
 * 下架用 enabled 开关,读路径(selectEnabledByScope)会同时校 enabled/audit/del_flag。
 */
@RestController
@RequestMapping("/business/merchantNpc")
public class CmsMerchantNpcController extends BaseController {

    /** name VARCHAR(32) / avatar VARCHAR(255) / greeting VARCHAR(255):越界在这拦,别交给数据库截断。 */
    private static final int NAME_MAX = 32;
    private static final int AVATAR_MAX = 255;
    private static final int GREETING_MAX = 255;

    @Autowired
    private NpcProfileMapper npcProfileMapper;

    @Autowired
    private IMmsMerchantService mmsMerchantService;

    /** 列表(含停用,不含已删)。merchantId / name 为空即不过滤。 */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) Long merchantId,
                           @RequestParam(required = false) String name) {
        return success(npcProfileMapper.selectMerchantNpcList(merchantId, name));
    }

    /** 详情。id 指向非门店分身时按「不存在」处理。 */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id) {
        NpcProfile profile = npcProfileMapper.selectMerchantNpcById(id);
        if (profile == null) return error("门店形象不存在");
        return success(profile);
    }

    /** 新增。 */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:add')")
    @PostMapping
    public AjaxResult add(@RequestBody NpcProfile body) {
        if (body == null) return error("参数有误");
        if (body.getScopeId() == null || body.getScopeId() < 1) return error("请选择商家");
        if (mmsMerchantService.selectMmsMerchantById(body.getScopeId()) == null) {
            return error("商家不存在");
        }
        String invalid = validateDisplayFields(body, true);
        if (invalid != null) return error(invalid);

        NpcProfile profile = new NpcProfile();
        profile.setScopeId(body.getScopeId());
        profile.setName(body.getName().trim());
        profile.setAvatar(body.getAvatar().trim());
        profile.setGreeting(trimToNull(body.getGreeting()));
        profile.setSort(body.getSort() == null ? 0 : body.getSort());
        // 静态出场不读人设;persona NOT NULL 故落空串,不编一段假人设塞进去。
        profile.setPersona("");
        profile.setAuditStatus(1);
        profile.setEnabled(normalizedEnabled(body.getEnabled()));
        return toAjax(npcProfileMapper.insertMerchantNpc(profile));
    }

    /** 编辑。scope_type / scope_id 不可迁移:换商家等于换一条形象,重新建。 */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:edit')")
    @PutMapping
    public AjaxResult edit(@RequestBody NpcProfile body) {
        if (body == null || body.getId() == null) return error("参数有误");
        if (npcProfileMapper.selectMerchantNpcById(body.getId()) == null) {
            return error("门店形象不存在");
        }
        if (body.getName() == null && body.getAvatar() == null && body.getGreeting() == null
                && body.getSort() == null && body.getEnabled() == null) {
            // 全空的 body 会让 <set> 只剩 update_time,数据库照样回 1 行 ——
            // 那会把「什么都没改」报成「已保存」。
            return error("没有可编辑字段");
        }
        String invalid = validateDisplayFields(body, false);
        if (invalid != null) return error(invalid);

        NpcProfile profile = new NpcProfile();
        profile.setId(body.getId());
        profile.setName(trimToNull(body.getName()));
        profile.setAvatar(trimToNull(body.getAvatar()));
        profile.setGreeting(body.getGreeting() == null ? null : body.getGreeting().trim());
        profile.setSort(body.getSort());
        profile.setEnabled(body.getEnabled() == null ? null : normalizedEnabled(body.getEnabled()));
        return toAjax(npcProfileMapper.updateMerchantNpc(profile));
    }

    /**
     * 审核商家自助提交的门店形象:通过(1)或驳回(2)。
     *
     * <p>★★ <b>没有这个接口,整条链是断的。</b>商家在 App/小程序提交一律落
     * {@code audit_status=0};而本控制器的 add 建的是**已过审**(后台是可信来源)、
     * edit 不碰该字段 —— 也就是商家配完永远不会上线,而且<b>零报错零告警</b>:
     * 玩家侧表现是「所有店铺都没有形象」,跟「运营还没配」长得一模一样。
     * 2026-09-05 补上。
     *
     * <p>★ 驳回必须给原因:没有原因,商家只会把同一份原样再交一遍 —— 一个死循环。
     *
     * <p>★ mapper 的 WHERE 带 {@code audit_status = 0},<b>只审待审的</b>:
     * 一次误点不会把已经上线的形象打回。返回 0 行即「它已经不是待审状态了」。
     */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:edit')")
    @PostMapping("/audit")
    public AjaxResult audit(@RequestBody Map<String, Object> body) {
        if (body == null) return error("参数有误");
        // ★ 用不抛的解析:直接 Long.valueOf 遇到非数字会抛 NumberFormatException,
        //   经 GlobalExceptionHandler 变成 500 —— 一个「参数写错了」被报成服务器故障,
        //   排查的人会去看后端日志而不是自己的请求体。这条是被单测抓出来的。
        Long id = parseLong(body.get("id"));
        if (id == null || id < 1) return error("请选择要审核的形象");

        Integer status = parseInt(body.get("auditStatus"));
        // 只认 1/2。认不出的值一律拒 —— 审核状态是可见性开关,不能靠猜。
        if (status == null || (status != 1 && status != 2)) {
            return error("审核结果只能是通过或驳回");
        }

        String reason = trimToNull(body.get("auditReason") == null ? null
                : String.valueOf(body.get("auditReason")));
        if (status == 2 && (reason == null || reason.isEmpty())) {
            return error("驳回必须写明原因,否则商家不知道该改什么");
        }

        if (npcProfileMapper.selectMerchantNpcById(id) == null) {
            return error("门店形象不存在");
        }

        NpcProfile profile = new NpcProfile();
        profile.setId(id);
        profile.setAuditStatus(status);
        // ★ 通过时清空原因:留着上一次的驳回理由,商家会看到「已上线」旁挂着「头像不是本人」。
        profile.setAuditReason(status == 2 ? reason : null);
        int rows = npcProfileMapper.auditMerchantNpc(profile);
        if (rows != 1) {
            // WHERE 里钉了 audit_status=0,0 行 = 它已经不在待审状态(别人审过了 / 商家又改了)。
            return error("这条已经不是待审状态了,刷新后再看");
        }
        return success(status == 1 ? "已通过,形象将出现在玩家侧" : "已驳回,原因已回传给商家");
    }

    /** 解析成 Long。认不出返回 null,不抛 —— 见 audit() 里那条注释。 */
    private static Long parseLong(Object raw) {
        if (raw == null) return null;
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析成 Integer。认不出返回 null,不抛。 */
    private static Integer parseInt(Object raw) {
        if (raw == null) return null;
        try {
            return Integer.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 删除(软删)。 */
    @PreAuthorize("@ss.hasPermi('business:merchantNpc:remove')")
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable("id") Long id) {
        if (npcProfileMapper.selectMerchantNpcById(id) == null) {
            return error("门店形象不存在");
        }
        return toAjax(npcProfileMapper.deleteMerchantNpcById(id));
    }

    /**
     * 展示字段校验。required=true(新增)时 name/avatar 必填;
     * required=false(编辑)时允许缺省表示「本次不改」,但给了就必须合法 —— 不能靠传空串把头像清空,
     * 形象卡没有头像就不叫形象。
     */
    private String validateDisplayFields(NpcProfile body, boolean required) {
        String name = trimToNull(body.getName());
        if (required && name == null) return "请填写形象名称";
        if (name != null && name.length() > NAME_MAX) return "形象名称不能超过" + NAME_MAX + "字";
        if (body.getName() != null && name == null) return "形象名称不能为空";

        String avatar = trimToNull(body.getAvatar());
        if (required && avatar == null) return "请上传像素头像";
        if (avatar != null && avatar.length() > AVATAR_MAX) return "头像地址过长";
        if (body.getAvatar() != null && avatar == null) return "像素头像不能为空";

        if (body.getGreeting() != null && body.getGreeting().trim().length() > GREETING_MAX) {
            return "招呼语不能超过" + GREETING_MAX + "字";
        }
        if (body.getSort() != null && (body.getSort() < 0 || body.getSort() > 999)) {
            return "排序需在 0-999 之间";
        }
        return null;
    }

    /** 缺省即展示中:后台新建一个形象就是为了让它出现。 */
    private Integer normalizedEnabled(Integer enabled) {
        if (enabled == null) return 1;
        return enabled == 0 ? 0 : 1;
    }

    private String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
