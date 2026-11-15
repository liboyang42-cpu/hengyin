package com.chengyinhub.web.controller.business;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.seed.DensityQuery;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.business.service.DistrictDensityService;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.SeedPlaceImportService;
import com.chengyinhub.business.service.SeedRouteImportService;
import com.chengyinhub.business.service.SeedTableReader;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.enums.LimitType;
import com.chengyinhub.common.exception.ServiceException;

/**
 * Q3 seed-tools (GAM-09, GAM-11, POS-09): bulk import / export of official places and routes, AI-assisted node
 * drafts for official places, and the district density report. Imports default to a dry run; every write lands as a
 * draft that still goes through the existing review. Nothing here publishes content.
 */
@RestController
@RequestMapping("/business/seed")
public class SeedContentController extends BaseController
{
    private final SeedPlaceImportService places;
    private final SeedRouteImportService routes;
    private final DistrictDensityService density;
    private final SeedContentMapper seedMapper;
    private final IAiCreatorService aiCreatorService;
    private final IOfficialEventV2Service operators;

    public SeedContentController(SeedPlaceImportService places, SeedRouteImportService routes,
            DistrictDensityService density, SeedContentMapper seedMapper, IAiCreatorService aiCreatorService,
            IOfficialEventV2Service operators)
    {
        this.places = places; this.routes = routes; this.density = density; this.seedMapper = seedMapper;
        this.aiCreatorService = aiCreatorService; this.operators = operators;
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @PostMapping("/places/template")
    public void placeTemplate(HttpServletResponse response) throws IOException
    {
        csv(response, "official-places-template.csv");
        places.writeTemplate(response.getOutputStream());
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @PostMapping("/places/export")
    public void exportPlaces(HttpServletResponse response, String cityCode) throws IOException
    {
        csv(response, "official-places.csv");
        places.writeExport(cityCode, response.getOutputStream());
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "批量导入官方地点", businessType = BusinessType.IMPORT)
    @PostMapping("/places/import")
    public AjaxResult importPlaces(@RequestParam("file") MultipartFile file, String cityCode, Integer dedupeMeters,
            @RequestParam(defaultValue = "true") boolean dryRun) throws IOException
    {
        List<SeedTableReader.Line> lines = SeedTableReader.read(file.getOriginalFilename(), file.getBytes());
        return success(places.importPlaces(getUserId(), lines, cityCode, dedupeMeters, dryRun));
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @PostMapping("/routes/template")
    public void routeTemplate(HttpServletResponse response) throws IOException
    {
        csv(response, "official-routes-template.csv");
        response.getOutputStream().write(routes.template().getBytes(StandardCharsets.UTF_8));
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @Log(title = "批量导入官方路线", businessType = BusinessType.IMPORT)
    @PostMapping("/routes/import")
    public AjaxResult importRoutes(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean dryRun) throws IOException
    {
        List<SeedTableReader.Line> lines = SeedTableReader.read(file.getOriginalFilename(), file.getBytes());
        return success(routes.importRoutes(getUserId(), getUsername(), lines, dryRun));
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @GetMapping("/density/areas")
    public AjaxResult densityAreas()
    {
        return success(density.areas());
    }

    @PreAuthorize("@ss.hasPermi('business:roamPoi:list')")
    @PostMapping("/density")
    public AjaxResult densityReport(@RequestBody DensityQuery query)
    {
        return success(density.report(query));
    }

    /**
     * GAM-11: AI draft of a node for one official place, generated under the authorised official operator account.
     * Returns the draft only; the operator edits it in the official editor and saves it for review.
     */
    @PreAuthorize("@ss.hasPermi('business:roamPoi:edit')")
    @RateLimiter(time = 60, count = 30, limitType = LimitType.IP)
    @PostMapping("/ai-node/{placeId}")
    public AjaxResult aiNodeDraft(@PathVariable Long placeId)
    {
        Long owner = operators.resolveAuthorizedOfficialMember(getUserId());
        if (owner == null || owner <= 0) return error("请使用已授权的官方运营账号");
        List<SeedPlace> found = seedMapper.selectPlacesByIds(List.of(placeId));
        SeedPlace place = found.isEmpty() ? null : found.get(0);
        if (place == null || !Integer.valueOf(1).equals(place.getType()) || place.getMerchantId() != null)
            return error("官方地点不存在");
        AiNodeGenReq req = new AiNodeGenReq();
        req.setNodeName(place.getName());
        req.setAddress(place.getAddress());
        req.setThemeContext(place.getDescription());
        req.setValidationMethod(3);
        req.setPurpose("到场观察答题：玩家站在这里能看到答案，四个选项只有一个正确");
        try
        {
            AiNodeGenResp resp = aiCreatorService.nodeGenerate(req, owner);
            if (resp == null || resp.getNode() == null || resp.getNode().getQuestionName() == null)
                return error("AI 这次没有给出可用草稿，请重试或手动填写");
            return success(resp);
        }
        catch (AiServiceException | ServiceException e)
        {
            return error(e.getMessage());
        }
    }

    private static void csv(HttpServletResponse response, String fileName)
    {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
    }
}
