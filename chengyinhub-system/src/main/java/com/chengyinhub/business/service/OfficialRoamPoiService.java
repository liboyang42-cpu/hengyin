package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.dto.OfficialRoamPoiDraft;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.common.exception.ServiceException;
import java.util.Date;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 官方地点沿用 roam_poi 与会员玩法模板，保存后必须再次审核。 */
@Service
public class OfficialRoamPoiService {
    private final RoamMapper pois;
    private final ICmsMemberTemplateService templates;
    private final IOfficialEventV2Service operators;
    private final GrowthBadgeMapper badges;

    public OfficialRoamPoiService(RoamMapper pois, ICmsMemberTemplateService templates,
            IOfficialEventV2Service operators, GrowthBadgeMapper badges) {
        this.pois = pois; this.templates = templates; this.operators = operators; this.badges = badges;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long save(Long sysUserId, OfficialRoamPoiDraft input) {
        Long owner = operators.resolveAuthorizedOfficialMember(sysUserId);
        if (owner == null || owner <= 0) throw new ServiceException("请使用已授权的官方运营账号");
        validate(input);
        RoamPoi p = input.poi;
        if (p.getId() != null) {
            RoamPoi previous = pois.selectOfficialPoiForUpdate(p.getId());
            if (previous == null) throw new ServiceException("官方地点不存在或不允许编辑");
            if (previous.getTemplateId() != null) {
                CmsMemberTemplate oldGame = templates.selectCmsMemberTemplateById(previous.getTemplateId());
                if (oldGame == null || !owner.equals(oldGame.getMemberId()))
                    throw new ServiceException("无权编辑其他主办方的据点");
            }
        }
        CmsMemberTemplate requested = input.game;
        CmsMemberTemplate game = new CmsMemberTemplate();
        game.setMemberId(owner); game.setTitle(requested.getTitle().trim());
        game.setDescription(requested.getDescription()); game.setInteractionType("photo_spot");
        game.setValidationMethod(3); game.setQuestionName(requested.getQuestionName().trim());
        game.setQuestionA(requested.getQuestionA().trim()); game.setQuestionB(requested.getQuestionB().trim());
        game.setQuestionC(requested.getQuestionC()); game.setQuestionD(requested.getQuestionD());
        game.setCorrectAnswer(requested.getCorrectAnswer().trim().toUpperCase());
        game.setPhotoRequireDesc(requested.getPhotoRequireDesc() == null ? null : requested.getPhotoRequireDesc().trim());
        game.setFeedbackText("挑战完成，获得本地点探索勋章");
        game.setIsSync(0); game.setDraftStatus(1); game.setStatus(1L);
        // 修改生成新模板，旧完成记录不被新题目覆盖。
        if (templates.insertCmsMemberTemplate(game) != 1 || game.getId() == null)
            throw new ServiceException("据点玩法保存失败");
        p.setTemplateId(game.getId()); p.setType(1); p.setMerchantId(null);
        p.setStatus(0); p.setAuditStatus(0); p.setApplicationType(3);
        p.setPhotoRequired(!Boolean.FALSE.equals(p.getPhotoRequired())); p.setRadiusM(80); p.setXp(20); p.setCreateTime(new Date());
        p.setBadgeCode(null);
        int changed = p.getId() == null ? pois.insertOfficialPoi(p) : pois.updateOfficialPoi(p);
        if (changed != 1 || p.getId() == null) throw new ServiceException("官方据点保存失败");
        return p.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long id) {
        RoamPoi p = pois.selectOfficialPoiForUpdate(id);
        if (p == null || !Integer.valueOf(3).equals(p.getApplicationType())
                || !Integer.valueOf(0).equals(p.getAuditStatus()))
            throw new ServiceException("官方据点不在待审核状态");
        CmsMemberTemplate game = templates.selectCmsMemberTemplateById(p.getTemplateId());
        OfficialRoamPoiDraft draft = new OfficialRoamPoiDraft(); draft.poi = p; draft.game = game; validate(draft);
        String code = "ROAM_POI_" + p.getId();
        if (badges.selectByCode(code) == null) {
            GrowthBadge badge = new GrowthBadge();
            badge.setBadgeCode(code); badge.setBadgeName(p.getName() + "·探索者");
            badge.setBadgeDesc(Boolean.TRUE.equals(p.getPhotoRequired()) ? "完成地点拍照与观察挑战" : "完成地点观察答题挑战"); badge.setIconUrl(p.getCoverImg());
            badge.setStatus("0"); badge.setSortNo(0); badge.setAssetType("COLLECTION");
            badge.setTriggerEvent("POI_GAME_COMPLETED"); badge.setUnlockRule("{}");
            badge.setUnlockRuleJson("{\"eventType\":\"POI_GAME_COMPLETED\",\"poiId\":" + p.getId() + "}");
            if (badges.insertGrowthBadge(badge) != 1) throw new ServiceException("地点勋章配置失败");
        }
        if (pois.approveOfficialPoi(id, code) != 1) throw new ServiceException("审核状态已变化，请刷新");
    }

    public static void validate(OfficialRoamPoiDraft input) {
        if (input == null || input.poi == null || input.game == null) throw new ServiceException("请填写地点与游戏");
        RoamPoi p = input.poi; CmsMemberTemplate g = input.game;
        required(p.getName(), 80, "地点名称"); required(p.getAddress(), 255, "地址");
        required(p.getDescription(), 255, "地点介绍"); required(p.getCityCode(), 20, "城市码");
        required(g.getTitle(), 80, "游戏名称"); required(g.getQuestionName(), 255, "观察题");
        required(g.getQuestionA(), 255, "选项A"); required(g.getQuestionB(), 255, "选项B");
        if ((g.getQuestionC() != null && g.getQuestionC().length() > 255)
                || (g.getQuestionD() != null && g.getQuestionD().length() > 255)
                || (g.getDescription() != null && g.getDescription().length() > 255))
            throw new ServiceException("游戏介绍或选项过长");
        if (!Boolean.FALSE.equals(p.getPhotoRequired())) required(g.getPhotoRequireDesc(), 255, "拍照要求");
        String key = g.getCorrectAnswer() == null ? "" : g.getCorrectAnswer().trim().toUpperCase();
        int index = "ABCD".indexOf(key);
        String[] options = {g.getQuestionA(), g.getQuestionB(), g.getQuestionC(), g.getQuestionD()};
        if (key.length() != 1 || index < 0 || options[index] == null || options[index].trim().isEmpty())
            throw new ServiceException("正确答案必须对应有效选项");
        if (p.getLat() == null || p.getLng() == null || p.getLat().abs().doubleValue() > 90
                || p.getLng().abs().doubleValue() > 180) throw new ServiceException("请填写有效坐标");
        if (p.getTags() != null && p.getTags().length() > 255) throw new ServiceException("分类标签过长");
        asset(p.getCoverImg());
        try {
            JSONArray images = JSON.parseArray(p.getGalleryJson());
            if (images == null || images.isEmpty() || images.size() > 6) throw new IllegalArgumentException();
            for (Object image : images) { if (!(image instanceof String)) throw new IllegalArgumentException(); asset((String) image); }
            if (!images.contains(p.getCoverImg())) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new ServiceException("相册需含封面及1至6张有效平台图片"); }
        quotes(p.getQuotesJson());
        if (p.getPhotoCredit() != null && p.getPhotoCredit().length() > 1000) throw new ServiceException("照片来源与许可过长");
    }

    /** 公开引述:可空;至多 6 条,每条原文 ≤160 字,必须写明来源与 https 原文链接,作者 / 日期可选。
     *  展示时标出处,不进玩家评价,所以这里只核对形状,不核对内容真伪(真伪由运营核对原文)。 */
    static void quotes(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONArray rows = JSON.parseArray(json);
            if (rows == null || rows.size() > 6) throw new IllegalArgumentException();
            for (Object row : rows) {
                if (!(row instanceof com.alibaba.fastjson2.JSONObject)) throw new IllegalArgumentException();
                com.alibaba.fastjson2.JSONObject q = (com.alibaba.fastjson2.JSONObject) row;
                String text = q.getString("text"), source = q.getString("source"), url = q.getString("url");
                if (text == null || text.trim().isEmpty() || text.length() > 160) throw new IllegalArgumentException();
                if (source == null || source.trim().isEmpty() || source.length() > 40) throw new IllegalArgumentException();
                if (url == null || url.length() > 500 || !url.matches("https://[^\\s]+")) throw new IllegalArgumentException();
                String author = q.getString("author"), date = q.getString("date");
                if ((author != null && author.length() > 40) || (date != null && !date.matches("\\d{4}-\\d{2}-\\d{2}")))
                    throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) { throw new ServiceException("公开引述需为至多6条、每条含原文、来源和 https 原文链接"); }
    }

    private static void required(String value, int limit, String label) {
        if (value == null || value.trim().isEmpty() || value.length() > limit) throw new ServiceException("请填写有效的" + label);
    }
    /** 只收平台上传并登记审核事实的图片引用:后台本地上传 /profile/upload/yyyy/MM/dd/<32位>.jpg,
     *  或 OSS 上传对象 https://<桶域名>/upload/<32位>.jpg(不带签名参数)。其他写法在接口输出时会被
     *  媒体审核过滤清空 —— 地图点位画不出照片、卡片没有封面,所以保存时就拒收。 */
    static void asset(String key) {
        if (key == null || key.length() > 255
                || !(key.matches("/profile/upload/[0-9]{4}/[0-9]{2}/[0-9]{2}/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)")
                    || key.matches("https://[A-Za-z0-9.-]+/upload/[0-9a-f]{32}\\.(jpg|jpeg|png|gif)")))
            throw new ServiceException("图片须为平台上传并审核通过的图片");
    }
}
