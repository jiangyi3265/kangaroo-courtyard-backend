package com.ruoyi.kangaroo.ext.operations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;

/** Business dates close at 18:00 in China, independent of the host timezone. */
public final class OpsRules {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static final DateTimeFormatter DT=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private OpsRules() {}
    public static String text(Object x){return x==null?"":String.valueOf(x).trim();}
    public static long number(Object x){return x instanceof Number?((Number)x).longValue():0L;}
    public static void require(boolean ok,String message){if(!ok)throw new ServiceException(message,422);}
    public static LocalDate date(String x){try{return LocalDate.parse(x);}catch(Exception e){throw new ServiceException("日期格式应为年-月-日",422);}}
    public static YearMonth month(String x){try{return YearMonth.parse(x);}catch(Exception e){throw new ServiceException("月份格式应为年-月",422);}}
    public static LocalDateTime end(LocalDate d){return d.atTime(18,0);}
    public static LocalDateTime start(LocalDate d){return end(d.minusDays(1));}
    public static LocalDateTime monthStart(YearMonth m){return start(m.atDay(1));}
    public static LocalDateTime monthEnd(YearMonth m){return end(m.atEndOfMonth());}
    public static String role(String r){require(Arrays.asList("keeper","manager").contains(r),"请选择小院日报或人力日报");return r;}
    public static BigDecimal decimal(Object raw,String field){try{BigDecimal x=new BigDecimal(text(raw));require(x.scale()<=2&&x.signum()>=0&&x.compareTo(new BigDecimal("100000000"))<=0,field+"应为0到1亿元且最多两位小数");return x;}catch(NumberFormatException e){throw new ServiceException("请正确填写"+field,422);}}
    public static long fen(Object raw,String field){return decimal(raw,field).movePointRight(2).longValueExact();}
    public static String line(Object raw,String name,int max,boolean required){String s=text(raw);require(!required||!s.isEmpty(),"请填写"+name+"，没有请填无");require(s.codePointCount(0,s.length())<=max,name+"不能超过"+max+"字");return s;}
    public static Map<String,Object> map(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i+1<kv.length;i+=2)m.put(String.valueOf(kv[i]),kv[i+1]);return m;}
    public static Map<String,Object> progress(BigDecimal target,BigDecimal completed,YearMonth m,LocalDateTime cutoff,int normal,int slow){
        require(target!=null&&target.signum()>0,"月度目标必须大于0");
        require(slow>=1&&slow<normal&&normal<=100,"进度阈值须满足0＜严重落后阈值＜正常阈值≤100");
        LocalDateTime begin=monthStart(m),finish=monthEnd(m);
        long total=Duration.between(begin,finish).getSeconds();
        long used=Math.max(0,Math.min(total,Duration.between(begin,cutoff).getSeconds()));
        BigDecimal time=BigDecimal.valueOf(used).divide(BigDecimal.valueOf(total),8,RoundingMode.HALF_UP);
        int remain=used==total?0:(int)((total-used+86399)/86400);
        Map<String,Object> out=map("target",target,"completed",completed,"remainingDays",remain,"timeProgress",time.multiply(BigDecimal.valueOf(100)).setScale(1,RoundingMode.HALF_UP));
        if(completed==null){out.putAll(map("missing",null,"completionRate",null,"dailyRequired",null,"status","not_applicable","statusLabel","暂无可计算数据"));return out;}
        BigDecimal missing=target.subtract(completed),rate=completed.divide(target,8,RoundingMode.HALF_UP);
        String state=completed.compareTo(target)>=0||used==0?"normal":used==total?"severe":rate.compareTo(time.multiply(BigDecimal.valueOf(normal).movePointLeft(2)))>=0?"normal":rate.compareTo(time.multiply(BigDecimal.valueOf(slow).movePointLeft(2)))>=0?"slow":"severe";
        out.putAll(map("missing",missing,"completionRate",rate.multiply(BigDecimal.valueOf(100)).setScale(1,RoundingMode.HALF_UP),"dailyRequired",remain==0?null:missing.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(remain),1,RoundingMode.CEILING),"status",state,"statusLabel","normal".equals(state)?"正常":"slow".equals(state)?"偏慢":"严重落后"));
        return out;
    }
}
