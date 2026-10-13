package com.chengyinhub.business.service;

import com.chengyinhub.web.controller.business.ClubCorporatePayeeController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 的「财务一次审核」到底靠什么成立。
 *
 * <p>契约禁的是<b>俱乐部 owner 自审</b>，不是「录入的管理员不得审核」。后者曾被实现成
 * {@code financeUserId != createdBy} 的两人制 —— 在已定的「后台只用超管单账号」运营模型下，
 * 那等于所有 profile 永远过不了审、探店日主题永远进不了 SELLING。已删除。
 *
 * <p>删掉那条比较之后，真正撑住契约的是这三件结构性事实，本类逐条钉死：
 * <ol>
 *   <li>俱乐部侧（app token）<b>压根没有</b>写资料/审核入口 ⇒ owner 无从自审；</li>
 *   <li>管理端审核挂的是专用财务权限，不是宽泛的 business:club:*；</li>
 *   <li>审核是带 current + PENDING 旧状态条件的 CAS ⇒ 只可能有一次。</li>
 * </ol>
 */
class ClubCorporatePayeeExternalSelfVerifyClosedTest {

    /** 归属 club 的 app 侧 Controller 全在这个包下（见 ApiClubCompensationController）。 */
    private static final File API_CONTROLLERS =
            new File("src/main/java/com/chengyinhub/web/controller/api");

    @Test
    void noClubFacingApiControllerCanReachThePayeeProfileAtAll() {
        assertTrue(API_CONTROLLERS.isDirectory(), "找不到 app 侧 Controller 目录:" + API_CONTROLLERS.getAbsolutePath());
        List<String> offenders = new ArrayList<String>();
        for (File source : javaSources(API_CONTROLLERS)) {
            String text = read(source);
            if (text.contains("IClubCorporatePayeeService") || text.contains("ClubCorporatePayeeProfile")
                    || text.contains("club-payee")) {
                offenders.add(source.getPath());
            }
        }
        assertEquals(new ArrayList<String>(), offenders,
                "俱乐部 owner 只有 app token，这些 app 侧 Controller 一旦碰到收款人资料就等于开了自审的门:"
                        + offenders);
    }

    @Test
    void theVerifyEndpointCarriesTheDedicatedFinancePermission() throws Exception {
        Method verify = ClubCorporatePayeeController.class.getDeclaredMethod(
                "verify", Long.class, Long.class, java.util.Map.class);
        PreAuthorize guard = verify.getAnnotation(PreAuthorize.class);

        assertNotNull(guard, "审核端点必须有 @PreAuthorize");
        assertTrue(guard.value().contains("business:clubpayee:verify"),
                "必须是专用财务权限，宽泛的 business:club:edit 不得替代，实际:" + guard.value());
    }

    @Test
    void theVerifyWriteIsAOneShotCasNotAPlainUpdate() {
        String xml = resource("mapper/business/ClubCorporatePayeeProfileMapper.xml").toLowerCase(Locale.ROOT);
        int at = xml.indexOf("<update id=\"verifyversioncas\">");
        assertTrue(at >= 0, "审核必须是带旧状态条件的 CAS 语句");
        String statement = xml.substring(at, xml.indexOf("</update>", at));

        assertTrue(statement.contains("is_current = 1"), "只有 current 版本可被审核");
        assertTrue(statement.contains("verify_status = 'pending'"),
                "旧状态必须是 PENDING —— 这才是「一次审核」，两个财务并发只有一个拿到行");
        assertTrue(statement.contains("license_hash = #{licensehash}"),
                "执照 hash 也要写进 where，审核期间被换执照必须落空");
    }

    private static List<File> javaSources(File directory) {
        List<File> found = new ArrayList<File>();
        File[] children = directory.listFiles();
        if (children == null) return found;
        for (File child : children) {
            if (child.isDirectory()) found.addAll(javaSources(child));
            else if (child.getName().endsWith(".java")) found.add(child);
        }
        return found;
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + file, e);
        }
    }

    private static String resource(String path) {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("找不到 " + path);
            Scanner scanner = new Scanner(input, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            throw new IllegalStateException("读不到 " + path, e);
        }
    }
}
