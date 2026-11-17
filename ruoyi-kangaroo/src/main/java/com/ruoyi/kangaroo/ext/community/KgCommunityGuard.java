package com.ruoyi.kangaroo.ext.community;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.config.KangarooProperties;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.security.WxMsgSecCheck;

/**
 * 社区共用的守门逻辑：
 * - 内容检查：本地敏感词（block 直接拦截 / review 转人工）+ 手机号、微信号样式的联系方式（转人工）；
 * - 发布状态：review-mode=manual（生产默认）一律先审后发；mock（仅本地联调）过了本地检查就直接发布；
 *   auto（生产可用）只对评论、不带图的旅居日常 / 经验分享生效：本地检查 pass 且微信内容安全接口判通过才直接发布（见 autoApprove），其余照旧先审；
 * - 规则同意（《社区公约》《活动参与须知》《找搭子安全须知》）、手机号核验、拉黑关系。
 *
 * 错误码：423 需要先同意规则（msg 以 CONSENT: 开头，带文档键），428 需要先验证手机号，422 内容被拦截，403 被拉黑。
 */
@Component
public class KgCommunityGuard
{
    public static final String RULES_VERSION = "2026-09";

    private static final Map<String, String[]> DOCS = new LinkedHashMap<String, String[]>();
    static
    {
        DOCS.put("community", new String[] { "社区公约",
                "1. 真实友善：发布真实的旅居、换工与生活内容，不冒充他人，不发布虚假信息。\n"
                + "2. 保护隐私：不在公开内容里留手机号、微信号、住址；私信里也请勿索要身份证、银行卡等信息。\n"
                + "3. 线下安全：约见优先选公共场所，结伴出行告知亲友行程；遇到骚扰请举报或拉黑。\n"
                + "4. 禁止内容：色情、赌博、诈骗、引流刷单、人身攻击、违法活动相关内容一律删除，情节严重的封禁账号。\n"
                + "5. 内容审核：动态、评论、活动与公开行程发布前会经过审核，结果以消息通知。\n"
                + "6. 年龄：社区功能仅向年满 18 周岁的用户开放。" });
        DOCS.put("activity", new String[] { "活动参与须知",
                "1. 请按时到达集合地点，迟到可能无法等候。\n"
                + "2. 户外活动存在一定风险，请根据自身身体状况评估是否参加；活动页未写明「平台已投保」的，请自行购买意外险。\n"
                + "3. 活动中听从领队安排，不擅自离队，注意环保。\n"
                + "4. 在线支付的报名，活动开始 24 小时前取消可申请全额退款（人工审核后原路退回）；24 小时内取消不退款。\n"
                + "5. 你的手机号只对本场活动的领队在活动前后 24 小时内可见，用于集合联络，查看会留痕。\n"
                + "6. 用户自发活动由发起人组织，平台只提供信息展示与报名工具，请谨慎甄别。" });
        // 发找搭子前要同意（KgPostService.publish）；找搭子列表与帖子详情的「出行安全须知」也展示这份全文。
        // 新增文档不改 RULES_VERSION：改了会让所有人重新同意《社区公约》《活动参与须知》
        DOCS.put("buddy", new String[] { "找搭子安全须知",
                "1. 自愿结伴：找搭子是用户之间自愿结伴，各自对自身安全负责；平台只提供信息发布与沟通工具，不组织线下活动。\n"
                + "2. 意外保险：平台未为找搭子投保，建议出发前自行购买旅游或户外意外险。\n"
                + "3. 见面安全：首次见面选人多的公共场所，把行程和同伴信息告诉亲友；女生尽量结伴同行。\n"
                + "4. 目的地安全：不去未开放景区、野山、无人区和危险水域，出发前关注天气与当地提示。\n"
                + "5. 紧急求助：遇到危险或突发情况，立即拨打 110（医疗急救 120、火警 119），并在平台举报。\n"
                + "6. 纠纷处理：发生纠纷由当事人自行协商；涉及违法的，交由公安机关处理。" });
    }

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern WECHAT = Pattern.compile("(?i)(wx|vx|微信|v信|威信)[:：\\s]*[a-zA-Z][-_a-zA-Z0-9]{5,19}");

    @Autowired
    private KgJdbc db;

    @Autowired
    private KangarooProperties properties;

    /** 微信内容安全检查（auto 模式才用） */
    @Autowired(required = false)
    private WxMsgSecCheck secCheck;

    private volatile List<String[]> words = Collections.emptyList();
    private volatile long wordsAt;

    /**
     * 写进内容 review_mode 列的审核方式。auto 只对评论和不带图的旅居日常 / 经验分享生效，
     * 活动、行程、找搭子、带图动态这些照旧人工审核的内容记 manual；机审直接发布的动态由 KgPostService 记 auto。
     */
    public String mode()
    {
        String m = configuredMode();
        return "auto".equals(m) ? "manual" : m;
    }

    /** 配置里的审核方式原值：manual / mock / auto */
    public String configuredMode()
    {
        return properties.getContentReviewMode();
    }

    public boolean autoMode()
    {
        return "auto".equals(configuredMode());
    }

    /**
     * auto 模式的机审：本地检查结果是 pass（没命中转人工词、没有联系方式），并且微信内容安全接口（msgSecCheck v2）
     * 明确判通过，才返回 true，调用方据此直接发布。不是 auto 模式、本地是 review、用户没有 openid、接口出错或超时都返回 false（照旧待审）。
     * 哪些内容可以来问由调用方决定：只有评论和不带图的旅居日常 / 经验分享。
     */
    public boolean autoApprove(String level, Long uid, int scene, String title, String content)
    {
        if (!autoMode() || !"pass".equals(level) || uid == null || secCheck == null)
        {
            return false;
        }
        Map<String, Object> u = db.one("select openid from kg_app_user where user_id = :u and del_flag = '0' and status = '0'", p("u", uid));
        String openid = u == null ? null : KgJdbc.asStr(u.get("openid"));
        return secCheck.passes(openid, scene, title, content);
    }

    public static Map<String, Object> doc(String key)
    {
        String[] d = DOCS.get(key);
        if (d == null)
        {
            throw new ServiceException("规则不存在");
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("doc", key);
        m.put("title", "《" + d[0] + "》");
        m.put("version", RULES_VERSION);
        m.put("content", d[1]);
        return m;
    }

    private List<String[]> words()
    {
        if (System.currentTimeMillis() - wordsAt > 60_000L)
        {
            List<String[]> list = new ArrayList<String[]>();
            for (Map<String, Object> r : db.list("select word, level from kg_sensitive_word where status = '0'", p()))
            {
                list.add(new String[] { KgJdbc.asStr(r.get("word")).toLowerCase(), KgJdbc.asStr(r.get("level")) });
            }
            words = list;
            wordsAt = System.currentTimeMillis();
        }
        return words;
    }

    /**
     * 检查文本：命中 block 词直接抛 422（不回显命中的词）；命中 review 词或出现联系方式返回 "review"；否则 "pass"。
     */
    public String check(String... texts)
    {
        String s = joined(texts);
        String level = "pass";
        for (String[] w : words())
        {
            if (!w[0].isEmpty() && s.contains(w[0]))
            {
                if ("block".equals(w[1]))
                {
                    throw new ServiceException("内容包含不允许发布的信息，请修改后再发", 422);
                }
                level = "review";
            }
        }
        if (contact(s))
        {
            level = "review";
        }
        return level;
    }

    /** 文本里有没有手机号、微信号等联系方式（check 转人工的原因之一；需要给用户说清原因时单独用） */
    public boolean hasContact(String... texts)
    {
        return contact(joined(texts));
    }

    private static String joined(String... texts)
    {
        StringBuilder all = new StringBuilder();
        for (String t : texts)
        {
            if (t != null)
            {
                all.append(t).append('\n');
            }
        }
        return all.toString().toLowerCase().replaceAll("\\s+", " ");
    }

    private static boolean contact(String s)
    {
        String digits = s.replaceAll("[\\s-]", "");
        // 2026-10-11：再用私信遮挡同一套规则查一遍（全角数字、+86、数字中间加空格 / 点、QQ 号 / QQ 邮箱、「微信号是：」都认得出）
        return PHONE.matcher(digits).find() || WECHAT.matcher(s).find() || ContactMask.contains(s);
    }

    /** 新内容的初始状态：人工模式一律 pending；联调模拟模式下 pass → online，review → pending；auto 模式这里也给 pending，能不能直接发布由 autoApprove 再判 */
    public String initialStatus(String level)
    {
        return "mock".equals(mode()) && "pass".equals(level) ? "online" : "pending";
    }

    public boolean consented(Long uid, String doc)
    {
        return uid != null && db.count("select count(*) from kg_consent where app_user_id = :u and doc = :d and version = :v",
                p("u", uid, "d", doc, "v", RULES_VERSION)) > 0;
    }

    public void requireConsent(Long uid, String doc)
    {
        if (!consented(uid, doc))
        {
            throw new ServiceException("CONSENT:" + doc + ":请先阅读并同意" + doc(doc).get("title"), 423);
        }
    }

    public void consent(Long uid, String doc)
    {
        doc(doc);
        db.update("insert ignore into kg_consent (app_user_id, doc, version, agree_time) values (:u, :d, :v, now())",
                p("u", uid, "d", doc, "v", RULES_VERSION));
    }

    public boolean phoneVerified(Long uid)
    {
        return db.count("select count(*) from kg_app_user where user_id = :u and phone_verified_time is not null and phone is not null", p("u", uid)) > 0;
    }

    public void requirePhone(Long uid)
    {
        if (!phoneVerified(uid))
        {
            throw new ServiceException("请先验证手机号（用于活动通知与联络，不会向其他用户展示）", 428);
        }
    }

    /** 两人之间任一方拉黑了对方 */
    public boolean blocked(Long a, Long b)
    {
        return a != null && b != null && db.count("select count(*) from kg_user_block where (user_id = :a and blocked_id = :b) or (user_id = :b and blocked_id = :a)",
                p("a", a, "b", b)) > 0;
    }

    public void requireNotBlocked(Long me, Long other)
    {
        if (blocked(me, other))
        {
            throw new ServiceException("你们之间存在拉黑关系，无法互动", 403);
        }
    }

    // ---------------------------------------------------------------- 关注关系 / 谁可以私信我（2026-10-11 内测建议）

    /** 「谁可以私信我」（kg_app_user.dm_privacy）：all 所有人（默认）/ following 我关注的人 / mutual 互相关注的人 */
    public static final Map<String, String> DM_PRIVACY = new LinkedHashMap<String, String>();
    static
    {
        DM_PRIVACY.put("all", "所有人");
        DM_PRIVACY.put("following", "我关注的人");
        DM_PRIVACY.put("mutual", "互相关注的人");
    }

    /** a 关注了 b */
    public boolean follows(Long a, Long b)
    {
        return a != null && b != null && !a.equals(b)
                && db.count("select count(*) from kg_user_follow where follower_id = :a and followee_id = :b", p("a", a, "b", b)) > 0;
    }

    /** 两人互相关注 */
    public boolean mutualFollow(Long a, Long b)
    {
        return a != null && b != null && !a.equals(b)
                && db.count("select count(*) from kg_user_follow where (follower_id = :a and followee_id = :b) or (follower_id = :b and followee_id = :a)",
                        p("a", a, "b", b)) >= 2;
    }

    /** 某人的私信设置；没设过、或库里是不认识的值，一律按「所有人」 */
    public String dmPrivacy(Long uid)
    {
        if (uid == null)
        {
            return "all";
        }
        Map<String, Object> u = db.one("select dm_privacy from kg_app_user where user_id = :u", p("u", uid));
        String v = u == null ? null : KgJdbc.asStr(u.get("dmPrivacy"));
        return v != null && DM_PRIVACY.containsKey(v) ? v : "all";
    }

    public void setDmPrivacy(Long uid, String value)
    {
        if (value == null || !DM_PRIVACY.containsKey(value))
        {
            throw new ServiceException("只能选所有人、我关注的人或互相关注的人", 422);
        }
        db.update("update kg_app_user set dm_privacy = :v where user_id = :u", p("v", value, "u", uid));
    }

    /**
     * me 想给 target 发私信：target 的「谁可以私信我」不允许时返回一句给 me 看的原因，允许返回 null。
     * 之前谁都能私信任何人；现在 target 可以只让自己关注的人、或互相关注的人私信。
     */
    public String dmRefusal(Long me, Long target)
    {
        String v = dmPrivacy(target);
        if ("following".equals(v) && !follows(target, me))
        {
            return "对方设置了只接收 TA 关注的人的私信，暂时不能发起私信";
        }
        if ("mutual".equals(v) && !mutualFollow(me, target))
        {
            return "对方设置了只接收互相关注的人的私信，互相关注后才能发起私信";
        }
        return null;
    }

    /** 当前用户的昵称与头像（快照用） */
    public Map<String, Object> user(Long uid)
    {
        Map<String, Object> u = db.one("select user_id, nickname, avatar, city, phone, phone_verified_time from kg_app_user where user_id = :u", p("u", uid));
        if (u == null)
        {
            throw new ServiceException("账号不存在", 401);
        }
        String nick = KgJdbc.asStr(u.get("nickname"));
        u.put("displayName", nick == null || nick.isEmpty() ? "用户" + uid : nick);
        return u;
    }
}
