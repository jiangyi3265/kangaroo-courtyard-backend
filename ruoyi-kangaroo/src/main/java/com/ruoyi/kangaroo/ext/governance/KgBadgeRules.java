package com.ruoyi.kangaroo.ext.governance;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Fixed business rules shared by badge reconciliation and the eligibility shown in the app. */
public final class KgBadgeRules {
    public static final int VERSION = 88;
    public static final List<String> FIXED_CODES = Arrays.asList("referrer", "referrer_gold", "juror_gold", "traveler");

    private KgBadgeRules() {}

    public static boolean flag(Map<String, Object> metrics, String key) {
        return Boolean.TRUE.equals(metrics.get(key)) || "1".equals(String.valueOf(metrics.get(key)));
    }

    public static long count(Map<String, Object> metrics, String key) {
        return Governance.num(metrics.get(key));
    }

    public static boolean referralIntegrity(Map<String, Object> metrics, boolean gold) {
        return flag(metrics, "accountAvailable") && metrics.get("creditScore") != null
                && count(metrics, "creditScore") >= (gold ? 90 : 80)
                && !flag(metrics, "referralFraud") && (!gold || !flag(metrics, "hasViolation"));
    }

    public static boolean referrer(Map<String, Object> metrics) {
        long experienced = count(metrics, "experiencedPeople");
        return referralIntegrity(metrics, false)
                && (experienced >= 10 || (count(metrics, "registeredPeople") >= 20 && experienced >= 5)
                || flag(metrics, "downgradedFromGold"));
    }

    public static boolean goldReferrer(Map<String, Object> metrics) {
        long rank = count(metrics, "quarterRank");
        return referralIntegrity(metrics, true)
                && (count(metrics, "experiencedPeople") >= 50
                || (count(metrics, "quarterExperiencedPeople") >= 20 && rank >= 1 && rank <= 10)
                || count(metrics, "activeReferredWorkers") >= 10);
    }

    public static boolean goldJuror(Map<String, Object> metrics) {
        long participated = count(metrics, "participations");
        return flag(metrics, "accountAvailable") && !flag(metrics, "disqualified")
                && !flag(metrics, "suspended")
                && count(metrics, "absenceCount") == 0
                && ((participated >= 20 && count(metrics, "qualityVotes") >= 5)
                || (participated >= 10 && count(metrics, "adoptedVotes") * 10 >= participated * 7));
    }

    public static boolean traveler(Map<String, Object> metrics) {
        long experiences = count(metrics, "completedExperiences");
        boolean quality = count(metrics, "qualityContents") >= 10 && count(metrics, "featuredContents") >= 3;
        return flag(metrics, "accountAvailable") && metrics.get("creditScore") != null
                && count(metrics, "creditScore") >= 85 && !flag(metrics, "falseContent")
                && ((experiences >= 6 && quality)
                || (count(metrics, "travelDays") >= 60 && quality)
                || (experiences >= 3 && count(metrics, "officialFeaturedContents") >= 3));
    }

    public static boolean eligible(String code, Map<String, Object> metrics) {
        switch (code) {
            case "referrer": return referrer(metrics);
            case "referrer_gold": return goldReferrer(metrics);
            case "juror_gold": return goldJuror(metrics);
            case "traveler": return traveler(metrics);
            default: return false;
        }
    }

    public static LocalDate quarterStart(LocalDate date) {
        return LocalDate.of(date.getYear(), ((date.getMonthValue() - 1) / 3) * 3 + 1, 1);
    }

    public static String ruleText(String code) {
        switch (code) {
            case "referrer":
                return "累计推荐注册并首次完成换工、兼职或住宿体验满10人，或累计推荐注册满20人且其中满5人完成首次体验。信用分不低于80且无刷推荐记录。金牌推荐官连续两季未达标降级后，达到信用及无刷推荐要求即可保留。长期有效，核实刷推荐或虚假推荐后撤销并追回对应奖励。";
            case "referrer_gold":
                return "累计推荐完成首次体验满50人，或当自然季度满20人且平台季度排名前10（并列同名次），或推荐用户中满10人为已确认在岗的换工、兼职、全职活跃用户。信用分不低于90且无违规。首次达标即授予，获得当季不记失败，连续两个结束季度不达标降为推荐官。";
            case "juror_gold":
                return "累计有效参与大众评审满20次且优质评审满5次，或有效参与满10次且个人意见采纳率达到70%。无泄露、偏袒、缺席记录。长期有效，核实泄露、偏袒或连续3次敷衍投票后撤销并按规则暂停资格。";
            case "traveler":
                return "完成换工、兼职、全职或住宿体验满6次且优质评价或社区内容满10篇（其中精华满3篇），或累计旅居满60天且达到相同内容条件，或体验满3次且内容获官方转发或周热门满3篇。信用分不低于85且无虚假内容记录。长期有效，虚假或刷量内容核实后撤销并追回对应积分。";
            default: return "";
        }
    }
}
