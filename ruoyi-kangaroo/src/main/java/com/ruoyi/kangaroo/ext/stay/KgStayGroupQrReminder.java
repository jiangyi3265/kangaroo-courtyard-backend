package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.date;
import static com.ruoyi.kangaroo.ext.stay.KgStayBrowseService.str;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.ruoyi.kangaroo.enums.MessageCategory;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;

/**
 * 住客群码到期提醒（2026-10-20 主人：「我设置为小院管理员的，每到第六天，他的员工端会有提醒更换」）。
 * 微信群二维码只有 7 天有效，上传当天往后 7 天就是「群码有效期至」；客人订单页过了这天就不再显示群码（KgStayOrderService）。
 * 每天 9:00 看一遍已上线的小院：
 *  · 群码明天到期（上传后第 6 天）→ 给这家店的主理人、门店管家（员工端身份）各发一条「住客群码明天到期」；
 *    9 点那次没跑上（重启等），到期当天补发一条「今天到期」，同一个有效期只提醒一次；
 *  · 过了有效期还没换 → 再提醒一次「已过期」（只看过期 3 天内的，上线前就过期很久的不打扰）。
 * 同一个有效期发没发过，按消息中心里这家小院、这个标题、这个有效期的消息认（不加表）；员工换了新码、有效期变了，下一轮重新算。
 */
@Service
public class KgStayGroupQrReminder
{
    private static final Logger log = LoggerFactory.getLogger(KgStayGroupQrReminder.class);

    static final String BIZ = "stay_group_qr";
    static final String SOON = "住客群码明天到期";
    static final String TODAY = "住客群码今天到期";
    static final String EXPIRED = "住客群码已过期";
    /** 点消息进员工端「小院管理」，找到这家小院 →「更多」→「住客群码」上传新码 */
    static final String ROUTE = "/pages/staff/rooms";
    /** 过期多少天内还补一条「已过期」 */
    static final int EXPIRED_DAYS = 3;
    /** 收提醒的角色：主理人、门店管家（后台「员工授权」里设的；员工、线上任务专员不收） */
    static final List<String> ROLES = Arrays.asList(StaffRole.MANAGER, StaffRole.KEEPER);
    /** 角色切换后只发小院主理人：新换工主理人进不了小院管理，换不了码 */
    static final List<String> ROLES_AFTER_SWITCH = Arrays.asList(StaffRole.KEEPER);

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    /** 按城市授权的员工；单测没注入时不算 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.staff.CityGrantScope cityGrants;

    LocalDate today()
    {
        return LocalDate.now();
    }

    @Scheduled(cron = "${kangaroo.stay.group-qr-remind-cron:0 0 9 * * ?}")
    public void daily()
    {
        try
        {
            int n = run(today());
            if (n > 0)
            {
                log.info("住客群码到期提醒：发出 {} 条", n);
            }
        }
        catch (RuntimeException e)
        {
            log.warn("住客群码到期提醒失败：{}", e.getMessage());
        }
    }

    /** 返回发出的消息条数 */
    int run(LocalDate today)
    {
        int sent = 0;
        List<Map<String, Object>> stays = db.list("select s.stay_id, s.stay_name, s.store_id, s.group_qr_expire from kg_stay s join kg_store st on st.store_id = s.store_id "
                + "where s.del_flag = '0' and s.status = 'online' and st.status = '0' and st.del_flag = '0' and coalesce(s.group_qr, '') <> '' "
                + "and s.group_qr_expire is not null and s.group_qr_expire >= :from and s.group_qr_expire <= :to order by s.stay_id",
                p("from", today.minusDays(EXPIRED_DAYS).toString(), "to", today.plusDays(1).toString()));
        for (Map<String, Object> s : stays)
        {
            LocalDate exp = date(str(s.get("groupQrExpire")));
            if (exp == null)
            {
                continue;
            }
            Long stayId = KgJdbc.asLong(s.get("stayId"));
            boolean expired = exp.isBefore(today);
            String title = expired ? EXPIRED : (exp.isAfter(today) ? SOON : TODAY);
            String marker = "有效期至 " + exp;
            // 「明天到期」「今天到期」算同一次提醒；「已过期」另算一次
            List<String> same = expired ? Arrays.asList(EXPIRED) : Arrays.asList(SOON, TODAY);
            if (db.count("select count(*) from kg_message where biz_type = :b and biz_id = :s and mode = 'staff' and title in (:t) and content like :m",
                    p("b", BIZ, "s", stayId, "t", same, "m", "%" + marker + "%")) > 0)
            {
                continue;
            }
            List<String> roles = com.ruoyi.kangaroo.ext.staff.RolePhase.isNew() ? ROLES_AFTER_SWITCH : ROLES;
            List<Map<String, Object>> people = db.list("select distinct g.app_user_id from kg_staff_grant g where g.store_id = :st and g.status = '0' "
                    + "and g.del_flag = '0' and g.role_code in (:roles) order by g.app_user_id", p("st", KgJdbc.asLong(s.get("storeId")), "roles", roles));
            // 本城按城市授权的小院主理人也要收到（这家店有门店授权或撤销记录的人以门店授权为准，已在上面）
            if (cityGrants != null)
            {
                people = new java.util.ArrayList<Map<String, Object>>(people);
                for (Long uid : cityGrants.holderIds(KgJdbc.asLong(s.get("storeId")), roles))
                {
                    people.add(p("appUserId", uid));
                }
            }
            String text = content(str(s.get("stayName")), exp, expired, exp.isAfter(today));
            for (Map<String, Object> u : people)
            {
                support.notifyStaff(KgJdbc.asLong(u.get("appUserId")), MessageCategory.SYSTEM, title, text, BIZ, stayId, ROUTE);
                sent++;
            }
        }
        return sent;
    }

    static String content(String stayName, LocalDate exp, boolean expired, boolean tomorrow)
    {
        String day = exp.getMonthValue() + "月" + exp.getDayOfMonth() + "日";
        String name = "「" + (stayName == null || stayName.isEmpty() ? "小院" : stayName) + "」";
        if (expired)
        {
            return name + "的住客群二维码已在 " + day + " 过期，客人现在看不到群码、进不了群。请在微信群里重新生成二维码，"
                    + "到「小院管理」找到这家小院，点「更多」→「住客群码」上传新码。（原群码有效期至 " + exp + "）";
        }
        return name + "的住客群二维码" + (tomorrow ? "明天" : "今天") + "（" + day + "）到期，微信群码只有 7 天有效。请在微信群里重新生成二维码，"
                + "到「小院管理」找到这家小院，点「更多」→「住客群码」上传新码，不然到期后客人就扫不进群了。（当前群码有效期至 " + exp + "）";
    }
}
