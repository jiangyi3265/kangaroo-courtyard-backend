package com.ruoyi.kangaroo.ext.user;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import com.ruoyi.common.exception.ServiceException;

/** 原始 DOCX 经运营方确认的正文。每次签署固化字段及岗位快照，不随模板或岗位编辑变化。 */
public final class VolunteerAgreement {
    public static final String VERSION = "W20261115.1";
    public static final String TITLE = "袋鼠青年旅行·换工旅行三方权利义务协议";
    public static final String PLATFORM = "深圳市袋鼠星辰投资有限公司";
    static final String BODY = readBody();
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private VolunteerAgreement() {}
    static String s(Object v) { return v == null ? "" : String.valueOf(v).trim(); }
    static String readBody() {
        try (java.io.InputStream in = new ClassPathResource("agreements/volunteer-tripartite-20261115.txt").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("三方协议正文资源缺失", e); }
    }
    static String required(Map<String,Object> b, String key, String label, int max) {
        String v = s(b.get(key));
        if (v.isEmpty() || v.length() > max || v.indexOf('\u0000') >= 0) throw new ServiceException("请填写" + label + "（不超过" + max + "字）", 422);
        return v;
    }
    static String phone(Map<String,Object> b, String key, String label) {
        String v = required(b,key,label,24);
        if (!v.matches("[+0-9() -]{7,24}") || v.replaceAll("\\D", "").length() < 7) throw new ServiceException(label + "格式不正确",422);
        return v;
    }
    static Map<String,Object> fields(Map<String,Object> b, Map<String,Object> a, LocalDate today) {
        Map<String,Object> f = new LinkedHashMap<>();
        String name = required(b,"name","本人姓名",16);
        if (!KgTravelIdService.validName(name) || !KgUserHubService.sameName(name, s(a.get("realName")))) throw new ServiceException("签署姓名需与本人证件、报名姓名一致",422);
        String id = required(b,"idNo","完整身份证号",18).toUpperCase();
        if (!KgAccountService.idChecksumOk(id)) throw new ServiceException("身份证号格式或校验位不正确",422);
        try {
            LocalDate birthday = LocalDate.parse(id.substring(6,14), DateTimeFormatter.BASIC_ISO_DATE);
            if (birthday.isBefore(LocalDate.of(1900,1,1)) || birthday.plusYears(18).isAfter(today)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new ServiceException("请核对出生日期，换工协议仅限年满18周岁者确认",422); }
        f.put("name",name); f.put("idNo",id);
        f.put("phone",phone(b,"phone","本人联系电话"));
        f.put("emergencyName",required(b,"emergencyName","紧急联系人姓名",32));
        f.put("emergencyPhone",phone(b,"emergencyPhone","紧急联系人电话"));
        f.put("health",required(b,"health","需告知的健康状况（无特殊情况请写无）",500));
        if (!Boolean.TRUE.equals(b.get("familyInformed"))) throw new ServiceException("请确认已告知家属，并告知紧急联系人其信息用途",422);
        f.put("familyInformed",true);
        if (!Boolean.TRUE.equals(b.get("privacyAgreed"))) throw new ServiceException("请确认本次协议个人信息处理说明",422);
        f.put("privacyAgreed",true);
        f.put("storeName",required(b,"storeName","店家签约名称",128));
        f.put("storeAddress",required(b,"storeAddress","店家地址",255));
        f.put("storeContact",required(b,"storeContact","店家联系人",32));
        f.put("storePhone",phone(b,"storePhone","店家联系电话"));
        String consistent = s(b.get("consistent"));
        if (!Arrays.asList("yes","no").contains(consistent)) throw new ServiceException("请选择视频沟通所述条件是否与平台展示一致",422);
        f.put("consistent",consistent);
        f.put("differences", "no".equals(consistent) ? required(b,"differences","不一致的具体事项",1000) : "");
        return f;
    }
    static Map<String,Object> snapshot(Map<String,Object> a) {
        Map<String,Object> j = new LinkedHashMap<>();
        for (String k : Arrays.asList("title","arrivalDate","stayDays","dailyHours","shiftDesc","restMode","shiftTime","boardDesc","boardType","roomType","content")) j.put(k,s(a.get(k)));
        return j;
    }
    static String value(Map<String,Object> d, String key) { return s(d.get(key)).isEmpty() ? "平台未填写" : s(d.get(key)); }
    static String render(Map<String,Object> f, Map<String,Object> job, String no, String day, boolean masked) {
        String id = s(f.get("idNo"));
        StringBuilder text = new StringBuilder(TITLE).append("\n协议版本：").append(VERSION).append("\n协议编号：").append(no).append("\n甲方确认日期：").append(day);
        text.append("\n\n甲方（换工/青年旅行者）\n姓名：").append(f.get("name")).append("\n身份证号：").append(masked ? KgTravelIdService.maskId(id,null) : id)
            .append("\n联系电话：").append(f.get("phone")).append("\n紧急联系人：").append(f.get("emergencyName")).append("\n紧急联系人电话：").append(f.get("emergencyPhone"))
            .append("\n健康状况告知：").append(f.get("health")).append("\n已告知家属本次安排，并告知紧急联系人其信息用途：是")
            .append("\n个人信息处理确认：同意平台为本次协议确认及出行安全处理所填身份证、健康状况和联系方式，并向本次店家的授权处理人展示必要信息；完整身份证加密保存、页面脱敏显示。");
        text.append("\n\n乙方（民宿/酒店/店家）\n名称：").append(f.get("storeName")).append("\n地址：").append(f.get("storeAddress"))
            .append("\n联系人：").append(f.get("storeContact")).append("\n联系电话：").append(f.get("storePhone"));
        text.append("\n\n丙方（平台运营方）：").append(PLATFORM).append("\n平台：袋鼠青年旅行\n联系电话：17864291136\n联系邮箱：1010756980@qq.com");
        text.append("\n\n鉴于甲、乙双方意欲通过丙方平台达成以工换宿的旅行安排，丙方仅为信息撮合与规则监督方。三方经平等协商，达成如下协议：\n\n").append(BODY);
        text.append("\n\n视频沟通所述的住宿、工作内容、工时、餐食与平台展示一致：").append("yes".equals(f.get("consistent")) ? "是" : "不一致")
            .append("\n差异事项：").append("yes".equals(f.get("consistent")) ? "无" : f.get("differences"));
        text.append("\n\n本次岗位与行程快照\n岗位：").append(value(job,"title")).append("\n约定到店日期：").append(value(job,"arrivalDate"))
            .append("\n停留天数：").append(value(job,"stayDays")).append("\n每日协助工时：").append(value(job,"dailyHours"))
            .append("\n排班：").append(value(job,"shiftDesc")).append("\n时段：").append(value(job,"shiftTime")).append("\n休息安排：").append(value(job,"restMode"))
            .append("\n食宿说明：").append(value(job,"boardDesc")).append("\n食宿类型：").append(label(value(job,"boardType"), new String[]{"all","room","meal","part","none"},new String[]{"包食宿","包住","包餐","部分提供","不提供"}))
            .append("\n住宿房型：").append(label(value(job,"roomType"), new String[]{"single","double","triple","multi"},new String[]{"单人间","双人间","三人间","多人间"})).append("\n工作内容：").append(value(job,"content"));
        text.append("\n\n签署页\n甲方在线确认姓名：").append(f.get("name"))
            .append("\n乙方签署人及确认时间：以本协议编号下的门店在线确认记录为准。\n丙方：").append(PLATFORM)
            .append("（提供本版本协议及系统确认留痕，未加盖实体或电子印章）\n甲乙双方确认完成后，本次签署完成；更改填写信息需重新确认。\n保险：平台协助办理，以实际保单及生效时间为准。\n完整身份证加密保存，页面回看脱敏显示。系统记录在线确认，不冒充第三方电子签名或实名核验。");
        return text.toString();
    }
    private static String label(String v,String[] keys,String[] labels) { for(int i=0;i<keys.length;i++)if(keys[i].equals(v))return labels[i];return v; }
}
