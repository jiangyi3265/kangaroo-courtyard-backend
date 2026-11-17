package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.service.IKgMessageService;
import com.ruoyi.kangaroo.util.MaskUtils;

/**
 * 邀请新人送积分。
 *
 * - 每个账号一个邀请码（6 位，去掉了容易看错的 0/O、1/I/L），第一次打开「我的邀请」时生成，库里唯一；
 * - 被邀请的人打开带 ?inviter=邀请码 的分享页、登录后自动绑定（POST /app/me/inviter）：只认 24 小时内新注册、
 *   还没报过名 / 下过单的账号，不能填自己的码，绑定后不能改（kg_app_user.invited_by 只写一次）；
 * - 被邀请的人完成第一次换工换宿，或第一次线上任务成果验收通过时，邀请人得 kangaroo.points.inviteReward 积分，
 *   每位被邀请人只算一次（kg_point_entry 唯一键：biz_type = invite、biz_id = 被邀请人账号）。奖励是积分，不是现金。
 *   确认完成 / 验收通过的员工就是邀请人，或邀请人是那家门店的在职员工时不发（免得员工邀请小号、在自己店里刷积分）。
 */
@Service
public class KgInviteService
{
    /** 邀请码字符表：不含 0 O 1 I L，口头报码、手抄都不容易错 */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    static final int CODE_LENGTH = 6;

    /** 输入时接受 6–8 位（以后码变长也不用改校验） */
    static final String CODE_PATTERN = "[" + ALPHABET + "]{6,8}";

    /** 「新账号」：注册不超过 24 小时 */
    static final int NEW_ACCOUNT_HOURS = 24;

    private static final Random RANDOM = new SecureRandom();

    @Autowired(required=false) private KgMembershipRewards memberRewards;
    /** 按城市授权的员工也算这家店的员工；单测没注入时不算 */
    @Autowired(required=false) private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;
    @Autowired
    private KgJdbc db;

    @Autowired
    private KgPointLedger ledger;

    @Autowired
    private IKgMessageService messages;

    static String newCode(Random rnd)
    {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++)
        {
            sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 用户输入的码：去掉空格、转大写；不像邀请码的返回 null */
    static String normalize(String code)
    {
        String c = code == null ? "" : code.replaceAll("\\s+", "").toUpperCase();
        return c.matches(CODE_PATTERN) ? c : null;
    }

    /** 我的邀请码：已有就直接用，没有就生成一个（撞码换一个重试；并发生成时以先写进去的那个为准） */
    public String ensureCode(Long uid)
    {
        Map<String, Object> u = db.one("select invite_code from kg_app_user where user_id = :u and del_flag = '0'", p("u", uid));
        if (u == null)
        {
            throw new ServiceException("账号不存在", 404);
        }
        String code = KgJdbc.asStr(u.get("inviteCode"));
        if (code != null && !code.isEmpty())
        {
            return code;
        }
        for (int i = 0; i < 8; i++)
        {
            String candidate = newCode(RANDOM);
            try
            {
                if (db.update("update kg_app_user set invite_code = :c where user_id = :u and invite_code is null", p("c", candidate, "u", uid)) > 0)
                {
                    return candidate;
                }
                Map<String, Object> again = db.one("select invite_code from kg_app_user where user_id = :u", p("u", uid));
                String existing = again == null ? null : KgJdbc.asStr(again.get("inviteCode"));
                if (existing != null && !existing.isEmpty())
                {
                    return existing;
                }
            }
            catch (DuplicateKeyException e)
            {
                // 和别人的码撞了，换一个再试
            }
        }
        throw new ServiceException("邀请码生成失败，请稍后再试");
    }

    /** 「我的邀请」页：我的码、分享路径、规则、我邀请的人和奖励情况 */
    public Map<String, Object> mine(Long uid)
    {
        String code = ensureCode(uid);
        boolean execution=memberRewards!=null && memberRewards.enabled();
        int reward = execution?5:ledger.inviteReward();
        List<Map<String, Object>> invitees = new ArrayList<Map<String, Object>>();
        long earned = 0;
        int done = 0;
        for (Map<String, Object> r : db.list("select u.user_id, u.nickname, u.invited_time, u.register_time, "
                + "(select sum(pe.delta) from kg_point_entry pe where pe.app_user_id=:me and ((pe.biz_type='invite' and pe.biz_id=u.user_id) or (pe.biz_type='m_ref_service' and exists(select 1 from kg_application a where a.app_id=pe.biz_id and a.app_user_id=u.user_id)) or (pe.biz_type='m_ref_training' and exists(select 1 from kg_activity_signup g where g.signup_id=pe.biz_id and g.app_user_id=u.user_id)))) "
                + "+(select coalesce(sum(pe.delta),0) from kg_badge_violation v join kg_point_entry pe on pe.biz_type='badge_penalty' and pe.biz_id=v.violation_id and pe.app_user_id=v.app_user_id where v.app_user_id=:me and v.violation_type='referral_fraud' and v.target_type='invited' and v.target_id=u.user_id) reward, "
                + "exists(select 1 from kg_badge_violation v where v.app_user_id=:me and v.violation_type='referral_fraud' and v.target_type='invited' and v.target_id=u.user_id) invalid "
                + "from kg_app_user u where u.invited_by = :me order by u.invited_time desc, u.user_id desc limit 200", p("me", uid)))
        {
            Long got = KgJdbc.asLong(r.get("reward"));
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("name", maskNickname(KgJdbc.asStr(r.get("nickname"))));
            String joined = KgJdbc.asStr(r.get("invitedTime") != null ? r.get("invitedTime") : r.get("registerTime"));
            m.put("joinDate", joined == null || joined.length() < 10 ? "" : joined.substring(0, 10));
            boolean invalid = Boolean.TRUE.equals(r.get("invalid")) || "1".equals(String.valueOf(r.get("invalid")));
            m.put("done", got != null && !invalid);
            m.put("points", got == null ? 0 : got);
            m.put("statusText", invalid ? "核实为虚假邀请，奖励已撤回" : (got != null ? "已完成 +" + got : "还没完成"));
            if (got != null && !invalid)
            {
                earned += got;
                done++;
            }
            invitees.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("code", code);
        out.put("sharePath", "/pages/home/index?inviter=" + code);
        out.put("shareTitle", "我在袋鼠全球青年做换工换宿，一起来吧");
        out.put("reward", reward);
        out.put("rule", reward > 0 ? "邀请的朋友完成第一次换工或线上任务后，你得 " + reward + " 积分"
                : "邀请奖励暂时停发，朋友照样可以用你的邀请码加入");
        out.put("notes", java.util.Arrays.asList(
                "朋友通过你分享的小程序页面进入并登录，就会自动记在你名下",
                "只算新注册、还没报过名或下过单的朋友，每位朋友只奖励一次",
                "奖励是积分，可以用来加急投递，不能换成现金"));
        if(execution){
            out.put("rule","朋友完成换工奖励5分，完成连续3天及以上兼职奖励3分，完成全职培训奖励5分");
            out.put("notes",java.util.Arrays.asList("朋友通过你的分享页进入并绑定推荐关系，完成真实服务后积分自动到账","同一位朋友完成不同服务可多次获得对应奖励；同一服务不会重复发放","积分统一累计，可用于兑换免费住宿；不折现，禁止虚假推荐"));
            out.put("executionEnabled",true);
        }
        out.put("invitees", invitees);
        out.put("inviteCount", invitees.size());
        out.put("doneCount", done);
        out.put("earnedPoints", earned);
        out.put("canBind", bindBlocker(uid) == null);
        return out;
    }

    /**
     * 绑定邀请人。不符合条件时不报错，只返回 bound=false 和原因（自动绑定的入口会直接忽略），
     * 这样新老用户打开别人的分享链接都不会被打扰。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bind(Long uid, String rawCode)
    {
        String code = normalize(rawCode);
        if (code == null)
        {
            return result(false, "邀请码不对，请核对后再填");
        }
        // 锁住自己这一行：同一账号并发绑定只有一个能成
        Map<String, Object> me = db.one("select user_id, invited_by, status, del_flag from kg_app_user where user_id = :u for update", p("u", uid));
        if (me == null || !"0".equals(KgJdbc.asStr(me.get("status"))) || !"0".equals(KgJdbc.asStr(me.get("delFlag"))))
        {
            return result(false, "账号不可用");
        }
        if (me.get("invitedBy") != null)
        {
            return result(false, "你已经绑定过邀请人了，不能再改");
        }
        String blocker = bindBlocker(uid);
        if (blocker != null)
        {
            return result(false, blocker);
        }
        Map<String, Object> inviter = db.one("select user_id, invited_by, status, del_flag from kg_app_user where invite_code = :c", p("c", code));
        if (inviter == null || !"0".equals(KgJdbc.asStr(inviter.get("status"))) || !"0".equals(KgJdbc.asStr(inviter.get("delFlag"))))
        {
            return result(false, "没有找到这个邀请码");
        }
        Long inviterId = KgJdbc.asLong(inviter.get("userId"));
        if (uid.equals(inviterId))
        {
            return result(false, "不能填自己的邀请码");
        }
        if (uid.equals(KgJdbc.asLong(inviter.get("invitedBy"))))
        {
            // 两个新号互相邀请刷奖励：对方是你邀请来的，就不能再把对方记成你的邀请人
            return result(false, "对方是你邀请来的朋友，不能互相邀请");
        }
        if (db.update("update kg_app_user set invited_by = :i, invited_time = now() where user_id = :u and invited_by is null", p("i", inviterId, "u", uid)) == 0)
        {
            return result(false, "你已经绑定过邀请人了，不能再改");
        }
        return result(true, memberRewards!=null && memberRewards.enabled()?"已记下邀请人。完成符合条件的换工、兼职或全职培训后，朋友会获得积分":"已记下邀请人。你完成第一次换工或线上任务后，对方会得到积分");
    }

    /** 不能再绑定邀请人的原因；能绑返回 null。只认 24 小时内注册、还没有任何报名 / 订单的新账号 */
    String bindBlocker(Long uid)
    {
        Map<String, Object> u = db.one("select invited_by, (coalesce(register_time, create_time) >= date_sub(now(), interval " + NEW_ACCOUNT_HOURS + " hour)) fresh "
                + "from kg_app_user where user_id = :u", p("u", uid));
        if (u == null)
        {
            return "账号不可用";
        }
        if (u.get("invitedBy") != null)
        {
            return "你已经绑定过邀请人了，不能再改";
        }
        if (KgPointLedger.num(u.get("fresh")) != 1)
        {
            return "只有注册 24 小时内的新用户可以填写邀请码";
        }
        long used = db.count("select (select count(*) from kg_application where app_user_id = :u) + (select count(*) from kg_stay_order where app_user_id = :u) "
                + "+ (select count(*) from kg_shop_order where app_user_id = :u) + (select count(*) from kg_activity_signup where app_user_id = :u)", p("u", uid));
        if (used > 0)
        {
            return "已经报过名或下过单的账号不能再填写邀请码";
        }
        return null;
    }

    /**
     * 被邀请人完成了第一次换工（what = volunteer）或第一次线上任务验收通过（what = online）：给邀请人发奖励。
     * 必须在完成那件事的同一个事务里调用（跟着一起提交 / 回滚）。每位被邀请人只奖励一次，之后再调用直接返回 false。
     *
     * storeId = 这段换工 / 这个线上任务所属的门店；confirmedBy = 点「确认换工完成」或「验收通过」的员工账号（系统自动完成传 null）。
     * 之前不管是谁确认的都发：门店员工可以邀请一个新号、在自己店里确认完成，把邀请积分发给自己；
     * 现在确认人就是邀请人、或邀请人现在是这家门店的在职员工（任何角色）时不发。这次不发不算用掉名额（不记流水），
     * 朋友之后在别的门店完成换工 / 线上任务、由别人确认时照常发一次。
     */
    public boolean rewardInviter(Long inviteeId, String what, Long storeId, Long confirmedBy)
    {
        if(memberRewards != null && memberRewards.enabled()){ memberRewards.sync(inviteeId); return false; }
        if (inviteeId == null)
        {
            return false;
        }
        Map<String, Object> invitee = db.one("select invited_by, nickname from kg_app_user where user_id = :u", p("u", inviteeId));
        Long inviterId = invitee == null ? null : KgJdbc.asLong(invitee.get("invitedBy"));
        if (inviterId == null)
        {
            return false;
        }
        Map<String, Object> inviter = db.one("select status, del_flag from kg_app_user where user_id = :u for update", p("u", inviterId));
        if (inviter == null || !"0".equals(KgJdbc.asStr(inviter.get("status"))) || !"0".equals(KgJdbc.asStr(inviter.get("delFlag"))))
        {
            return false;
        }
        if (db.one("select violation_id from kg_badge_violation where app_user_id=:u and violation_type='referral_fraud' "
                + "and target_type='invited' and target_id=:i for update", p("u", inviterId, "i", inviteeId)) != null)
        {
            return false;
        }
        // 门店不明时宁可不发（调用方都会传）；确认人是邀请人自己、或邀请人是这家店的员工：不发
        if (storeId == null || inviterId.equals(confirmedBy) || isStoreStaff(inviterId, storeId))
        {
            return false;
        }
        int reward = ledger.inviteReward();
        String thing = "online".equals(what) ? "任务" : "换工";
        String reason = "邀请的朋友" + maskNickname(KgJdbc.asStr(invitee.get("nickname"))) + "完成第一次" + ("online".equals(what) ? "线上任务" : "换工");
        if (!ledger.award(inviterId, reward, reason, "invite", inviteeId))
        {
            return false;
        }
        messages.send(inviterId, "user", MessageCategory.SYSTEM, "邀请奖励到账",
                "你邀请的朋友完成了第一次" + thing + "，获得 " + reward + " 积分", "invite", inviteeId, "/pages/profile/invite");
        return true;
    }

    /** 这个账号现在是不是这家门店的在职员工（任何角色，授权生效中） */
    boolean isStoreStaff(Long uid, Long storeId)
    {
        return db.count("select count(*) from kg_staff_grant where app_user_id = :u and store_id = :s and status = '0' and del_flag = '0'",
                p("u", uid, "s", storeId)) > 0
                // 本城按城市授权、在这家店按所选角色干活的人也是本店员工
                || (cityGrants != null && cityGrants.roleAt(uid, storeId) != null);
    }

    static String maskNickname(String nickname)
    {
        String n = nickname == null ? "" : nickname.trim();
        if (n.isEmpty())
        {
            return "朋友";
        }
        return MaskUtils.name(n);
    }

    private static Map<String, Object> result(boolean bound, String message)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bound", bound);
        m.put("message", message);
        return m;
    }
}
