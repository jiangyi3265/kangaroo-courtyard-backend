package com.ruoyi.kangaroo.ext.user;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 责任书条款守卫（纯静态，不连库）：
 * 2026-10 起「保险说明」不再劝报名人自行购买意外险，改为如实说明没有保单号就还没有保障；
 * 版本号随之升到 2026-10；旧版 2026-09 的那条文字留在源码注释里，已签记录的摘要要能照它重算出来。
 */
class KgContractTermsTest
{
    /** 本地验收库 kg_contract_sign 里 2026-09 版签署记录的 content_hash（51 条全是这一个），即改字前 termsText() 的 SHA-256 */
    private static final String HASH_2026_09 = "b4376a4834142c35b190538d706385bb12627e6f3a149e4786543c570149ffc5";

    private static String[] row(String title)
    {
        for (String[] t : KgUserHubService.TERMS)
        {
            if (title.equals(t[1]))
            {
                return t;
            }
        }
        return fail("责任书里找不到「" + title + "」这一条");
    }

    @Test
    void insuranceClauseNoLongerAdvisesSelfPurchase()
    {
        for (String[] t : KgUserHubService.TERMS)
        {
            for (String cell : t)
            {
                assertFalse(cell.contains("自行购买"), "条款里不能再劝人自行购买保险：" + t[1]);
            }
        }
        String ins = row("保险说明")[2];
        assertTrue(ins.contains("保单信息"), "是否已投保以本页「保单信息」为准");
        assertTrue(ins.contains("不代表已有保障"), "如实提醒：没有保单号就还没有保障");
        assertEquals("门店确认录用后可为你申请投保，是否已投保以本页「保单信息」为准；未显示保单号前，不代表已有保障。", ins);
        assertFalse(KgUserHubService.termsText().contains("自行购买"));
    }

    @Test
    void wordingChangeBumpsTheVersionInsideTheHashedText()
    {
        assertEquals("2026-11", KgUserHubService.CONTRACT_VERSION);
        assertTrue(KgUserHubService.termsText().startsWith("袋鼠全球青年岗位责任确认书（2026-11）\n"));
        assertEquals(6, KgUserHubService.TERMS.length);
        assertNotEquals(HASH_2026_09, KgUserHubService.sha256(KgUserHubService.termsText()), "改了字摘要必须变，旧页面签署时才会被 409 拦下");
    }

    /** 源码注释里留的 2026-09 旧文字 + 其余五条现有文字，按 termsText 的拼法要能重算出已签记录里的摘要 */
    @Test
    void oldClauseKeptInCommentReproducesStoredHashes() throws Exception
    {
        Path src = Paths.get(System.getProperty("basedir", "."), "src/main/java/com/ruoyi/kangaroo/ext/user/KgUserHubService.java");
        String code = new String(Files.readAllBytes(src), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("//\\s+2026-09 保险说明：(\\S+)").matcher(code);
        assertTrue(m.find(), "TERMS 上方要留 2026-09 版保险说明的原文");
        String old = m.group(1);
        assertTrue(old.contains("建议自行购买意外险"), "注释里留的是旧文字原样");
        StringBuilder sb = new StringBuilder("袋鼠小院岗位责任确认书（2026-09）\n");
        for (String[] t : KgUserHubService.TERMS)
        {
            sb.append(t[1]).append("：").append("保险说明".equals(t[1]) ? old : t[2]).append("\n");
        }
        assertEquals(HASH_2026_09, KgUserHubService.sha256(sb.toString()));
    }
}
