package com.ruoyi.kangaroo.ext.staff;

import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Monetary terms for new merchant tasks; legacy assignments retain their fixed reward. */
final class KgOnlineReward {
    static final long PLATFORM_UNIT_FEN=300L;
    static long unitFen(Object raw) {
        String value=raw==null?"":raw.toString();
        if(!value.matches("[1-9][0-9]{0,5}"))throw new ServiceException("请填写接单人每条报酬（1–5000元，最多两位小数）",422);
        long fen=Long.parseLong(value);
        if(fen<100 || fen>500000)throw new ServiceException("接单人每条报酬须为1–5000元",422);
        return fen;
    }
    static long rewardUnit(Map<String,Object> row) { return unitFen(row.get("unitRewardFen")); }
    static long platformUnit(Map<String,Object> row) { return row.get("platformFeeUnitFen")==null?0:((Number)row.get("platformFeeUnitFen")).longValue(); }
    static boolean perItem(Map<String,Object> row){return row!=null && "per_item".equals(row.get("rewardMode"));}
    static int count(Object raw,int max,String label) {
        String s=raw==null?"":raw.toString().trim();
        if(!s.matches("[1-9][0-9]{0,2}"))throw new ServiceException(label+"须为 1–"+max+" 的整数",422);
        int n=Integer.parseInt(s);
        if(n>max || max<1)throw new ServiceException(label+"不能超过 "+max+" 条",422);
        return n;
    }
    static void describe(Map<String,Object> row,Map<String,Object> out) {
        if(!perItem(row))return;
        out.put("rewardMode","per_item");out.put("unitRewardFen",rewardUnit(row));out.put("itemsPerPerson",row.get("itemsPerPerson"));
        out.put("platformFeeUnitFen",platformUnit(row));
        out.put("rewardText","¥"+java.math.BigDecimal.valueOf(rewardUnit(row),2).stripTrailingZeros().toPlainString()+" / 合格条，每人最多 "+row.get("itemsPerPerson")+" 条；按实际验收合格条数结算");
    }
    static Map<String,Object> settlement(Map<String,Object> task,Map<String,Object> submission,Object qualified,long legacyFen) {
        if(!perItem(task))return p("amount_fen",legacyFen);
        int planned=count(task.get("itemsPerPerson"),100,"任务约定条数");
        int submitted=count(submission.get("submittedCount"),planned,"本次交付条数");
        int passed=count(qualified,submitted,"合格条数（无合格成果请退回修改）");
        return p("amount_fen",passed*rewardUnit(task),"qualified_count",passed,"unit_reward_fen",rewardUnit(task),
            "platform_fee_fen",passed*platformUnit(task),"platform_fee_unit_fen",platformUnit(task));
    }
}
