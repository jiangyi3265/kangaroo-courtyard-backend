package com.ruoyi.kangaroo.ext.user;

import java.util.*;

/** Subscription scenes are separate from in-app notification preferences. No marketing fallback. */
public final class KgSubscribeScenes {
    private KgSubscribeScenes() {}
    public static final Map<String,String> LABELS;
    static {
        Map<String,String> m=new LinkedHashMap<>();
        m.put("job_new","岗位订阅匹配");m.put("apply_progress","报名、面试与到离岗");
        m.put("online_task","线上任务进度");m.put("order","旅居与服务订单");
        m.put("activity","活动进度");m.put("review","服务评价");m.put("community","聊天、留言与回复");
        m.put("insurance","保险办理");m.put("mediation","纠纷调解");m.put("mediation_jury","大众评审");
        m.put("governance_case","权益与合作复核");m.put("account","审核与身份变更");
        m.put("finance","结算、提现与报销");m.put("member","会员权益与积分");m.put("wish","心愿互助进度");
        m.put("ops_daily","日常工作日报");m.put("ops_weekly","每周工作报告");
        m.put("ops_monthly","月度目标与总结");m.put("work_task","工作任务与待办");
        LABELS=Collections.unmodifiableMap(m);
    }
    public static String label(String key) { return LABELS.getOrDefault(key,"业务办理"); }
    public static String of(String category,String type,String title,String route) {
        String b=type==null?"":type,t=title==null?"":title,r=route==null?"":route;
        if(b.startsWith("insurance"))return "insurance";
        if(b.equals("mediation")||b.equals("mediation_jury"))return b;
        if(Arrays.asList("member_case","member_case_result","merchant_case").contains(b))return "governance_case";
        if(b.equals("ops_daily")||b.equals("daily_report")||b.equals("recruit_daily"))return "ops_daily";
        if(b.equals("weekly_report"))return "ops_weekly";
        if(b.equals("ops_monthly")||b.equals("ops_scope")||b.equals("ops_target"))return "ops_monthly";
        if(b.startsWith("wish"))return "wish";
        if(b.equals("job_subscribe")||b.equals("job_urgent"))return "job_new";
        if(b.startsWith("online_")||r.contains("/jobs/deliver")||r.contains("/staff/online-jobs"))return "online_task";
        if(Arrays.asList("wallet","wallet_withdraw","withdraw","business_bill","reimburse","stay_reimburse","refund_trouble").contains(b))return "finance";
        if("order".equals(category)||b.equals("stay_order")||b.equals("service_order"))return "order";
        if(b.startsWith("activity"))return "activity";
        if(b.equals("review")||b.equals("stay_comment"))return "review";
        if("apply".equals(category)||"interview".equals(category)||b.equals("application")||b.equals("volunteer_expiry")||b.equals("resume_forward"))return "apply_progress";
        if(Arrays.asList("staff_task","task","todo","job","merchant_request").contains(b))return "work_task";
        if(Arrays.asList("invite","member_request","point_mall","birthday","gift_claim","appeal").contains(b))return "member";
        if(Arrays.asList("merchant_apply","grant","guide_apply","vlog","stay").contains(b))return "account";
        if((b.equals("post")||b.equals("trip_plan"))&&(t.contains("审核")||t.contains("隐藏")))return "account";
        if(Arrays.asList("chat_conv","support_ticket","notice_reply","comment").contains(b)||"chat".equals(category)||"group".equals(category)||"comment".equals(category))return "community";
        // Likes, follows, game turns and unspecified broadcasts remain in-app.
        return null;
    }
    public static boolean staffOnly(String key) { return key.startsWith("ops_")||key.equals("work_task"); }
}
