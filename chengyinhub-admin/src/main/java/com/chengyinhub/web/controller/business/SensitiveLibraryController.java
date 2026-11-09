package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.ContentSensitiveAllow;
import com.chengyinhub.business.domain.ContentSensitiveImage;
import com.chengyinhub.business.domain.ContentSensitiveWord;
import com.chengyinhub.business.mapper.ContentSensitiveMapper;
import com.chengyinhub.business.service.ISensitiveContentLibrary;
import com.chengyinhub.business.service.safety.ImageFingerprint;
import com.chengyinhub.business.service.safety.SensitiveWordMatcher;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Round5D 敏感内容库后台:词与放行短语的列表/新增/停用/批量导入,登记已知违规图片(只存指纹,不存原图),
 * 查看最近命中(只有类型/ID/分类)。每次改动立即重载本实例,其它实例 30 秒内跟上。
 *
 * <p>导入格式(纯文本,UTF-8,一行一条,# 开头为注释):
 * <pre>
 *   词条,分类,动作      分类缺省 OTHER;动作 BLOCK(默认,提交即拒)或 LOG(只记命中不拦,试运行)
 *   放行短语           放行短语导入一行一个
 * </pre>
 */
@RestController
@RequestMapping("/business/sensitive")
public class SensitiveLibraryController extends BaseController {

    static final Set<String> CATEGORIES = new HashSet<String>(Arrays.asList(
            "POLITICS", "PORN", "GAMBLING", "DRUGS", "VIOLENCE", "FRAUD", "ABUSE", "AD", "OTHER"));
    static final int MAX_IMPORT_LINES = 5000;
    static final int MAX_ENTRY_LENGTH = 100;

    private final ContentSensitiveMapper mapper;
    private final ISensitiveContentLibrary library;

    public SensitiveLibraryController(ContentSensitiveMapper mapper, ISensitiveContentLibrary library) {
        this.mapper = mapper;
        this.library = library;
    }

    // ---------------- 词 ----------------

    @GetMapping("/words/list")
    @PreAuthorize("@ss.hasPermi('business:sensitive:list')")
    public TableDataInfo words(@RequestParam(required = false) String word,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) Integer enabled) {
        startPage();
        return getDataTable(mapper.selectWordList(word, category, enabled));
    }

    @PostMapping("/words")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词库", businessType = BusinessType.INSERT, isSaveRequestData = false)
    public AjaxResult addWord(@RequestBody ContentSensitiveWord body) {
        String err = saveWord(body == null ? null : body.getWord(), body == null ? null : body.getCategory(),
                body == null ? null : body.getAction());
        if (err != null) return error(err);
        library.reloadNow();
        return success();
    }

    @PutMapping("/words/{id}/enabled/{enabled}")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词库", businessType = BusinessType.UPDATE)
    public AjaxResult setWordEnabled(@PathVariable Long id, @PathVariable Integer enabled) {
        int rows = mapper.updateWordEnabled(id, enabled != null && enabled == 1 ? 1 : 0, getUsername(), new Date());
        library.reloadNow();
        return toAjax(rows);
    }

    @PostMapping("/words/import")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词库", businessType = BusinessType.IMPORT, isSaveRequestData = false)
    public AjaxResult importWords(@RequestBody Map<String, String> body) {
        List<String> lines = lines(body);
        if (lines == null) return error("一次最多导入 " + MAX_IMPORT_LINES + " 行");
        int ok = 0;
        int skipped = 0;
        for (String line : lines) {
            String[] parts = line.split("[,，\\t]", -1);
            String err = saveWord(parts[0], parts.length > 1 ? parts[1] : null, parts.length > 2 ? parts[2] : null);
            if (err == null) ok++; else skipped++;
        }
        library.reloadNow();
        return importResult(ok, skipped);
    }

    // ---------------- 放行短语 ----------------

    @GetMapping("/allows/list")
    @PreAuthorize("@ss.hasPermi('business:sensitive:list')")
    public TableDataInfo allows(@RequestParam(required = false) String phrase) {
        startPage();
        return getDataTable(mapper.selectAllowList(phrase));
    }

    @PostMapping("/allows")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词放行短语", businessType = BusinessType.INSERT)
    public AjaxResult addAllow(@RequestBody ContentSensitiveAllow body) {
        String err = saveAllow(body == null ? null : body.getPhrase());
        if (err != null) return error(err);
        library.reloadNow();
        return success();
    }

    @PutMapping("/allows/{id}/enabled/{enabled}")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词放行短语", businessType = BusinessType.UPDATE)
    public AjaxResult setAllowEnabled(@PathVariable Long id, @PathVariable Integer enabled) {
        int rows = mapper.updateAllowEnabled(id, enabled != null && enabled == 1 ? 1 : 0, getUsername(), new Date());
        library.reloadNow();
        return toAjax(rows);
    }

    @PostMapping("/allows/import")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "敏感词放行短语", businessType = BusinessType.IMPORT)
    public AjaxResult importAllows(@RequestBody Map<String, String> body) {
        List<String> lines = lines(body);
        if (lines == null) return error("一次最多导入 " + MAX_IMPORT_LINES + " 行");
        int ok = 0;
        int skipped = 0;
        for (String line : lines) {
            if (saveAllow(line) == null) ok++; else skipped++;
        }
        library.reloadNow();
        return importResult(ok, skipped);
    }

    // ---------------- 图片指纹 ----------------

    @GetMapping("/images/list")
    @PreAuthorize("@ss.hasPermi('business:sensitive:list')")
    public TableDataInfo images() {
        startPage();
        return getDataTable(mapper.selectImageList());
    }

    /** 上传一张已知违规图:只算 SHA-256 与 dHash 存库,原图不落盘、不入对象存储。 */
    @PostMapping("/images")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "已知违规图片", businessType = BusinessType.INSERT)
    public AjaxResult addImage(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "category", required = false) String category,
                               @RequestParam(value = "note", required = false) String note) throws java.io.IOException {
        if (file == null || file.isEmpty()) return error("请选择图片");
        if (file.getSize() > 10L * 1024 * 1024) return error("图片不能超过 10MB");
        ImageFingerprint fp = ImageFingerprint.of(file.getBytes());
        ContentSensitiveImage img = new ContentSensitiveImage();
        img.setSha256(fp.getSha256());
        img.setDhash(fp.getDhash());
        img.setCategory(category(category));
        img.setNote(note == null ? null : note.length() > 200 ? note.substring(0, 200) : note);
        img.setCreateBy(getUsername());
        img.setCreateTime(new Date());
        mapper.insertImage(img);
        library.reloadNow();
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("sha256", fp.getSha256());
        out.put("nearDuplicateSupported", fp.getDhash() != null);
        return success(out);
    }

    @PutMapping("/images/{id}/enabled/{enabled}")
    @PreAuthorize("@ss.hasPermi('business:sensitive:edit')")
    @Log(title = "已知违规图片", businessType = BusinessType.UPDATE)
    public AjaxResult setImageEnabled(@PathVariable Long id, @PathVariable Integer enabled) {
        int rows = mapper.updateImageEnabled(id, enabled != null && enabled == 1 ? 1 : 0, new Date());
        library.reloadNow();
        return toAjax(rows);
    }

    // ---------------- 命中记录 ----------------

    @GetMapping("/hits/list")
    @PreAuthorize("@ss.hasPermi('business:sensitive:list')")
    public AjaxResult hits(@RequestParam(value = "limit", required = false) Integer limit) {
        int n = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
        return success(mapper.selectRecentHits(n));
    }

    // ---------------- helpers ----------------

    private String saveWord(String word, String category, String action) {
        String w = word == null ? "" : word.trim();
        if (w.isEmpty() || w.length() > MAX_ENTRY_LENGTH) return "词条为空或过长";
        ContentSensitiveWord row = new ContentSensitiveWord();
        row.setWord(w);
        row.setCategory(category(category));
        row.setAction(SensitiveWordMatcher.ACTION_LOG.equalsIgnoreCase(action == null ? "" : action.trim())
                ? SensitiveWordMatcher.ACTION_LOG : SensitiveWordMatcher.ACTION_BLOCK);
        row.setEnabled(1);
        row.setCreateBy(getUsername());
        row.setCreateTime(new Date());
        mapper.insertWord(row);
        return null;
    }

    private String saveAllow(String phrase) {
        String p = phrase == null ? "" : phrase.trim();
        if (p.isEmpty() || p.length() > MAX_ENTRY_LENGTH) return "短语为空或过长";
        ContentSensitiveAllow row = new ContentSensitiveAllow();
        row.setPhrase(p);
        row.setEnabled(1);
        row.setCreateBy(getUsername());
        row.setCreateTime(new Date());
        mapper.insertAllow(row);
        return null;
    }

    static String category(String raw) {
        String c = raw == null ? "" : raw.trim().toUpperCase();
        return CATEGORIES.contains(c) ? c : "OTHER";
    }

    /** 拆行,去空行与 # 注释;超过上限返回 null。 */
    static List<String> lines(Map<String, String> body) {
        String text = body == null ? null : body.get("text");
        List<String> out = new java.util.ArrayList<String>();
        if (text == null) return out;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(line);
            if (out.size() > MAX_IMPORT_LINES) return null;
        }
        return out;
    }

    private AjaxResult importResult(int ok, int skipped) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("imported", ok);
        out.put("skipped", skipped);
        return success(out);
    }
}
