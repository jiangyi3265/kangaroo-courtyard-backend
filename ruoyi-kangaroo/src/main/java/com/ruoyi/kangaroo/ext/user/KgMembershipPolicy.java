package com.ruoyi.kangaroo.ext.user;

import java.time.*;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;

/** Document rules, expressed once for rewards, wallet issuance and app copy. */
public final class KgMembershipPolicy {
    private KgMembershipPolicy() {}
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static LocalDate today() { return LocalDate.now(ZONE); }
    public static int year() { return today().getYear(); }
    public static int servicePoints(String type, int days) {
        if ("volunteer".equals(type)) return days >= 30 ? 20 : 0;
        if ("parttime".equals(type)) return days >= 3 ? 15 : days == 1 ? 3 : 0;
        return "training".equals(type) ? 15 : 0;
    }
    public static int referralPoints(String type,int days) { return "volunteer".equals(type)&&days>=30?5:"parttime".equals(type)&&days>=3?3:"training".equals(type)?5:0; }
    public static int nightCost(String code) { switch(code) {case "night4":return 60;case "night2":return 90;case "night1":return 120;default:throw new ServiceException("请选择四人间、双人间或单人间",422);} }
    /** All room types participate by default. An explicit admin mapping takes precedence. */
    public static String roomCode(Map<String,Object> room) {
        String configured=Objects.toString(room.get("memberRoomCode"),"");
        if("disabled".equals(configured))return "";
        if(Arrays.asList("night4","night2","night1").contains(configured))return configured;
        String kind=Objects.toString(room.get("roomKind"),"private"),unit=Objects.toString(room.get("unitLabel"),"");
        String name=Objects.toString(room.get("typeName"),"");
        // Whole-property bookings use the highest points tier; never treat a villa as a dorm bed.
        if("whole".equals(kind))return "night1";
        if("shared".equals(kind)||unit.contains("床"))return "night4";
        if(name.contains("单人")||name.contains("单间"))return "night1";
        Long capacity=com.ruoyi.kangaroo.ext.KgJdbc.asLong(room.get("capacity"));
        return capacity!=null&&capacity>=3?"night4":capacity!=null&&capacity==2?"night2":"night1";
    }
    public static int pinQuota(int level) {return new int[]{0,0,1,2,3,5}[Math.max(0,Math.min(5,level))];}
    public static Map<String,Integer> allowances(int level) {
        Map<String,Integer> m=new LinkedHashMap<>();
        if(level>=2)m.put("merch",1);
        if(level>=3){m.put("night4",level>=5?3:level==4?2:1);m.put("activity",1);m.put("subsidy",level>=5?15000:level==4?8000:3000);}
        if(level>=4)m.put("night2",level>=5?2:1);
        if(level>=5){m.put("night1",1);m.put("upgrade",1);m.put("giftbox",1);}
        return m;
    }
    public static String label(String code) {
        switch(code){case "night4":return "四人间免费间夜";case "night2":return "双人间免费间夜";case "night1":return "单人间免费间夜";case "merch":return "袋鼠周边任选一";case "activity":return "小院活动免费参加";case "subsidy":return "出游补贴";case "upgrade":return "免费升级房型";case "giftbox":return "年度旅行礼包";default:return "会员权益";}
    }
    public static List<Map<String,Object>> rules() {
        List<Map<String,Object>> rows=new ArrayList<>();
        String[][] pairs={{"完成服务","换工完成至少30天得20分；单日兼职3分，连续至少3天兼职15分；完成全职培训15分。由门店确认真实完成，同一服务只发一次。"},{"真实住宿","实际完成住宿每满7晚得5分。取消、退款与提前离店按最终完成记录处理。"},{"真实评价","完成服务后每个订单可评价一次，基础审核通过得2分。满50字、具体细节、实拍图核验、重复率低于70%、无广告违规的精华评价额外5分，具体的差评同样适用。"},{"推荐与分享","被推荐人完成换工奖励5分、连续至少3天兼职3分、全职培训5分。官方转发和每周自动评选的热门帖子各奖励5分。"},{"免费住宿","60/90/120分分别兑换四人间/双人间/单人间1晚，可与等级免费间夜合并使用。每人每年最多免费入住20晚；法定节假日不可用，当年12月31日到期。"},{"使用条件","免费间夜限本人使用，不可折现；最高等级每年可向亲友转赠一次、合计最多3晚。会员底价为最终价，不叠加免费间夜或其他折扣。"},{"积分与年度权益","积分永久有效。按累计有效积分计算等级，兑换不降级；撤销虚假或退款奖励会扣回。年度权益按当前等级领取，升级补差额，次年重新领取，未使用权益不跨年。"},{"社区权益","达到相应等级后，每周可置顶1/2/3/5次，每次2小时，同一时间只可置顶一篇。置顶不会直接获得热门积分。"}};
        for(String[] x:pairs)rows.add(com.ruoyi.kangaroo.ext.KgJdbc.p("title",x[0],"text",x[1]));return rows;
    }
}
