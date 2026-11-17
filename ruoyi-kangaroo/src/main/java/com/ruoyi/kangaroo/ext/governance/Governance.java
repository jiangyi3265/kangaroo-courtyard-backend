package com.ruoyi.kangaroo.ext.governance;

import java.util.*;
import java.time.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** Strict command parsing shared by the authenticated governance APIs. */
public final class Governance {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private Governance(){}
    public static String str(Object v){return v==null?"":String.valueOf(v).trim();}
    public static long num(Object v){try{return Long.parseLong(str(v));}catch(Exception e){return 0;}}
    public static Long id(Object v){long n=num(v);if(n<=0)throw new ServiceException("缺少有效编号",422);return n;}
    // 提示里用中文名，不把接口字段名（userStatement、sourceKey…）露给用户
    private static final Map<String,String> LABELS=new HashMap<>();
    static{String[] kv={"note","说明","month","月份","basis","依据","proof","凭证","violation","违规类型","facts","事实经过","orderType","订单类型","category","纠纷类型",
            "narrative","事情经过","claims","诉求","phone","联系电话","userStatement","用户陈述","merchantStatement","店家陈述","evidenceList","核实证据","finding","初步判断",
            "decision","裁决内容","measures","处理措施","creditImpact","信用影响","juryReason","评审意见采纳说明","result","复核结果","anonymousNarrative","脱敏后的事情经过",
            "anonymousClaims","脱敏后的诉求","choice","评审选项","reason","理由","code","徽章","sourceKey","来源编号","content","内容","violationType","违规类型","targetType","处理对象类型"};
        for(int i=0;i<kv.length;i+=2)LABELS.put(kv[i],kv[i+1]);}
    public static String text(Map<String,Object>b,String key,int min,int max){
        String s=str(b.get(key));
        if(s.length()<min||s.length()>max){
            // requestKey 是页面自动带的防重复编号，用户改不了，只提示刷新
            if("requestKey".equals(key))throw new ServiceException("页面已过期，请刷新后重新提交",422);
            String label=LABELS.getOrDefault(key,"填写内容");
            throw new ServiceException(min<=0?label+"不能超过 "+max+" 字":min==max?label+"格式不对，请重新选择或填写":label+"请填写 "+min+"–"+max+" 字",422);
        }
        return s;
    }
    /** 查不到时按指定状态码报错（db.must 不带状态码，前端看到的是 500） */
    public static Map<String,Object> must(KgJdbc db,String sql,Map<String,?> ps,String message,int code){Map<String,Object> row=db.one(sql,ps);if(row==null)throw new ServiceException(message,code);return row;}
    public static LocalDateTime time(Object v){try{return LocalDateTime.parse(str(v).replace(' ','T'));}catch(Exception e){throw new ServiceException("请填写完整日期和时间",422);}}
    public static LocalDateTime now(){return LocalDateTime.now(ZONE);}
    public static List<Long> ids(Object v){List<Long> out=new ArrayList<>();if(v instanceof List)for(Object x:(List<?>)v){Long n=id(x);if(!out.contains(n))out.add(n);}return out;}
    public static void require(boolean ok,String message){if(!ok)throw new ServiceException(message,409);}
    public static void denied(){throw new ServiceException("无权访问此记录",403);}
}
