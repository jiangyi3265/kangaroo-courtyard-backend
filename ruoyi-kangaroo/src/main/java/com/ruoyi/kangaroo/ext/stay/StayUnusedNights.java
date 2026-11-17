package com.ruoyi.kangaroo.ext.stay;
import java.time.*;
import java.math.*;
import java.util.*;
import com.alibaba.fastjson2.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
/** A suggestion only: settlement uses the persisted order and platform approval. */
public final class StayUnusedNights {
  private StayUnusedNights() {}
  public static Map<String,Object> estimate(Map<String,Object> o,long paid,long refunded,LocalDate today) {
    LocalDate start=LocalDate.parse(String.valueOf(o.get("checkIn")).substring(0,10));
    LocalDate end=LocalDate.parse(String.valueOf(o.get("checkOut")).substring(0,10));
    String actual=String.valueOf(o.get("checkedOutTime"));
    LocalDate from=actual.length()>=10?LocalDate.parse(actual.substring(0,10)):today;
    if(from.isBefore(start))from=start;
    if(from.isAfter(end))from=end;
    int nights=(int)java.time.temporal.ChronoUnit.DAYS.between(from,end);
    long total=0,unused=0; Set<LocalDate> dates=new HashSet<>();
    try {
      for(Object item:JSON.parseArray(String.valueOf(o.get("nightly")))) {
        JSONObject n=(JSONObject)item;LocalDate d=LocalDate.parse(n.getString("date"));long price=n.getLongValue("priceFen");
        if(price<0||d.isBefore(start)||!d.isBefore(end)||!dates.add(d))throw new IllegalArgumentException();
        total=Math.addExact(total,price);if(!d.isBefore(from))unused=Math.addExact(unused,price);
      }
      if(dates.size()!=java.time.temporal.ChronoUnit.DAYS.between(start,end))throw new IllegalArgumentException();
    } catch(Exception e) { return p("unusedNights",nights,"estimateAvailable",false,"suggestedFen",0,"checkoutDate",from.toString()); }
    // Nightly prices are per unit; the common unit multiplier cancels in this ratio.
    long base=total==0?0:BigDecimal.valueOf(Math.max(0,paid)).multiply(BigDecimal.valueOf(unused)).divide(BigDecimal.valueOf(total),0,RoundingMode.DOWN).longValueExact();
    long suggested=Math.max(0,Math.min(Math.max(0,paid-refunded),base-Math.max(0,refunded)));
    return p("unusedNights",nights,"estimateAvailable",true,"suggestedFen",suggested,"checkoutDate",from.toString());
  }
}
