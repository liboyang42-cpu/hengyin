package com.chengyinhub.web.controller.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.PlayerObjectCard;
import com.chengyinhub.business.domain.vo.PlayerObjectCardVO;
import com.chengyinhub.business.service.ObjectCardService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 玩家的立体藏品册(拍物成卡 · 契约 §4.4):只有「看我自己的册子」和「给它改个名」两件事。
 *
 * <p>没有删除、没有分享、没有别人的视角 —— 那一版还没做(契约 §10 明确不做),
 * 提前留一个入口就是留一个没人守的越权面。
 *
 * <p>两个入口都<b>不接收归属参数</b>:memberId 只从登录态取。客户端一旦能声明「这些卡是谁的」,
 * 私有册子当场变成公开册子。这条钉在 ApiObjectCardControllerTest 的签名用例上。
 */
@RestController
@Tag(name = "立体藏品卡")
@RequestMapping("/api/object-card")
public class ApiObjectCardController extends BaseController {

    static final int PAGE_SIZE_DEFAULT = 20;
    static final int PAGE_SIZE_MAX = 50;
    /** 与库里 title 列宽、发布侧卡名上限同一条口径。 */
    static final int TITLE_MAX = 64;

    @Autowired
    private ObjectCardService objectCardService;

    /** 包级可见(非 private):测试覆写它注入身份,好让越权面走真入口而不是只测纯函数。 */
    Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    @Operation(summary = "我的藏品卡(分页)")
    @PostMapping("/list")
    public AjaxResult list(Integer pageNum, Integer pageSize, String category) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        int size = clampPageSize(pageSize);
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;

        // 空白当「全部」;分类值由库里的等值条件把关,认不出的值自然是空列表
        String only = category == null || category.trim().isEmpty() ? null : category.trim();
        List<PlayerObjectCard> rows = objectCardService.list(memberId, only, safeOffset(pageNum, size), size);
        List<PlayerObjectCardVO> safeRows = new ArrayList<PlayerObjectCardVO>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (PlayerObjectCard row : rows) safeRows.add(PlayerObjectCardVO.from(row));
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("list", safeRows);
        data.put("total", objectCardService.count(memberId, only));
        data.put("pageNum", num);
        data.put("pageSize", size);
        return success(data);
    }

    @Operation(summary = "给藏品卡改个名")
    @PostMapping("/rename")
    public AjaxResult rename(Long id, String title) {
        Long memberId = uid();
        if (memberId == null || memberId <= 0) return error("请先登录");
        if (id == null || id <= 0) return error("参数有误");
        String name = title == null ? "" : title.trim();
        if (name.isEmpty()) return error("给这张卡起个名字");
        if (name.length() > TITLE_MAX) return error("卡名不能超过 " + TITLE_MAX + " 字");

        PlayerObjectCard renamed = objectCardService.rename(memberId, id, name);
        if (renamed == null) return error("这张卡不是你的,或已经不存在了");
        return success(PlayerObjectCardVO.from(renamed));
    }

    /** 分页夹取:默认 20,上限 50 —— 册子可以很长,不能让客户端一次拉光。 */
    static int clampPageSize(Integer raw) {
        if (raw == null || raw <= 0) return PAGE_SIZE_DEFAULT;
        return Math.min(raw, PAGE_SIZE_MAX);
    }

    /** 由 pageNum/size 算 offset。pageNum 极大时整数溢出会算出负 offset,MySQL LIMIT 拒绝→500。 */
    static int safeOffset(Integer pageNum, int size) {
        long num = (pageNum == null || pageNum < 1) ? 1L : pageNum.longValue();
        long off = (num - 1L) * size;
        if (off < 0L) off = 0L;
        long maxOffset = 1_000_000L;   // 深到 100 万条已远超一个玩家的藏品量,超过就回第一页语义
        if (off > maxOffset) off = 0L;
        return (int) off;
    }
}
