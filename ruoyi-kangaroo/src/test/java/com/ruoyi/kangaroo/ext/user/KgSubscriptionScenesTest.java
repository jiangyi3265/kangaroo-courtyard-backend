package com.ruoyi.kangaroo.ext.user;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
class KgSubscriptionScenesTest {
 @Test void staffAndUserBusinessScenesAreExplicit(){
  String[][] cases={{"system","ops_daily","ops_daily"},{"system","weekly_report","ops_weekly"},{"system","ops_monthly","ops_monthly"},{"contract","insurance_finance","insurance"},{"system","mediation","mediation"},{"apply","application","apply_progress"},{"system","online_submission","online_task"},{"order","stay_order","order"},{"system","staff_task","work_task"},{"system","wallet","finance"},{"system","wish","wish"},{"system","merchant_apply","account"},{"system","member_request","member"},{"group","chat_conv","community"}};
  for(String[] c:cases)assertEquals(c[2],KgSubscribeScenes.of(c[0],c[1],"通知","/pages/home/index"));
  assertNull(KgSubscribeScenes.of("like","post_like","有人赞了你",""));
  assertNull(KgSubscribeScenes.of("system","unknown","广告",""));
 }
 @Test void scheduledTimeAndEmojiAreRenderedWithoutSplittingSurrogates(){
  String text="😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
  Map<String,Object> data=KgSubscribeService.buildData("thing1=title,time3=now",p("title",text,"now","2026-11-16 18:30"));
  String value=(String)((Map<?,?>)data.get("thing1")).get("value");assertEquals(20,value.codePointCount(0,value.length()));assertFalse(Character.isHighSurrogate(value.charAt(value.length()-1)));
  assertEquals("2026-11-16 18:30",((Map<?,?>)data.get("time3")).get("value"));
 }
 @Test void invalidMappingsFailBeforeAQuotaCanBeConsumed(){
  for(String fields:Arrays.asList("","thing1=missing","evil1=title","time1=title","amount1=title","thing1=title,thing1=title"))assertThrows(IllegalArgumentException.class,()->KgSubscribeService.buildData(fields,"测试","正文"),fields);
 }
 @Test void reportsRequireStaffButPersonalApplicationDoesNot(){assertTrue(KgSubscribeScenes.staffOnly("ops_daily"));assertFalse(KgSubscribeScenes.staffOnly("apply_progress"));}
}
