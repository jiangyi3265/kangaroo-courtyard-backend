package com.ruoyi.kangaroo.ext.user;

import java.util.*;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.*;

@Component
public class KgHomeMemberModule implements ExtEntityContributor {
    private final KgJdbc db; private final KgMemberService members; private final KgCityService cities;
    public KgHomeMemberModule(KgJdbc db,KgMemberService members,KgCityService cities) {this.db=db;this.members=members;this.cities=cities;}
    static void checkWindow(Map<String,Object> cols) {
        LocalDateTime start=windowTime(cols.get("start_time")),end=windowTime(cols.get("end_time"));
        if(start!=null && end!=null && !start.isBefore(end)) throw new ServiceException("结束时间必须晚于开始时间",422);
    }
    static void checkBenefitPhone(Map<String,Object> cols) {
        if(!cols.containsKey("phone")) return;
        String phone=KgJdbc.asStr(cols.get("phone"));
        if(phone==null || phone.trim().isEmpty()) return;
        // Same dialing contract as the member detail page: spaces and hyphens
        // are formatting only; a leading + is allowed for overseas shops.
        if(!phone.replaceAll("[\\s-]", "").matches("\\+?\\d{5,20}"))
            throw new ServiceException("店铺电话格式不合法，请填写5–20位号码，可带区号、空格或短横线",422);
    }
    private static LocalDateTime windowTime(Object value) {
        if(value==null) return null;
        // The controller normalizes new values to LocalDateTime; partial edits
        // also merge existing JDBC timestamps. Compare both as local SQL time.
        if(value instanceof LocalDateTime) return (LocalDateTime)value;
        Date parsed=com.ruoyi.kangaroo.ext.staff.StaffSupport.parse(value);
        if(parsed==null) throw new ServiceException("有效期时间格式不合法",422);
        return new Timestamp(parsed.getTime()).toLocalDateTime();
    }
    private void validate(Map<String,Object> cols,boolean creating,String table,String pk) {
        Map<String,Object> merged=new HashMap<>();
        if(!creating) {
            Map<String,Object> old=db.must("select start_time,end_time from "+table+" where "+pk+"=:id for update",KgJdbc.p("id",cols.get("_pk")),"记录不存在");
            merged.put("start_time",old.get("startTime"));merged.put("end_time",old.get("endTime"));
        }
        merged.putAll(cols); checkWindow(merged);
        if(cols.containsKey("city")) cols.put("city",cities.businessCity(KgJdbc.asStr(cols.get("city"))));
    }
    @Override public List<ExtEntity> entities() {
        ExtEntity picks=new ExtEntity("homepick","首页推荐内容","kg_home_pick","pick_id","kangaroo:homepick")
            .field(ExtField.of("section","首页栏目","select").options("activity:近期活动,job:为你推荐,training:全职培训横幅").required().search("eq"))
            .field(ExtField.of("targetId","推荐内容","int").required().range(1L,Long.MAX_VALUE).hideInList())
            .field(ExtField.of("targetTitle","内容标题","text").readOnly())
            .field(ExtField.of("city","展示城市","text").max(32).search("eq"))
            .field(ExtField.of("sort","显示顺序","int").required().range(0L,9999L))
            .field(ExtField.of("startTime","展示开始","datetime"))
            .field(ExtField.of("endTime","展示结束","datetime"))
            .field(ExtField.of("status","状态","select").options("0:启用,1:停用").required().search("eq"))
            .order("sort,pick_id")
            .validate((cols,creating)-> {
                validate(cols,creating,"kg_home_pick","pick_id");
                Map<String,Object> current=creating?new HashMap<>():db.must("select section,target_id,city,status from kg_home_pick where pick_id=:id",KgJdbc.p("id",cols.get("_pk")),"记录不存在");
                if(!creating && "1".equals(String.valueOf(cols.getOrDefault("status",current.get("status"))))) return;
                String section=String.valueOf(cols.getOrDefault("section",current.get("section")));
                Object target=cols.getOrDefault("target_id",current.get("targetId"));
                String city=String.valueOf(cols.getOrDefault("city",current.getOrDefault("city","")));
                // 2026-10-18「全职培训横幅」（training）推荐的也是活动：和「近期活动」同一套校验（开放报名、还没开始）
                String sql=("activity".equals(section)||"training".equals(section))?"select title,city from kg_activity where activity_id=:id and del_flag='0' and status='open' and start_time>now()":"select title,city,job_type from kg_job j where job_id=:id and j.del_flag='0' and j.status='published' and exists(select 1 from kg_store s where s.store_id=j.store_id and s.status='0' and s.del_flag='0')";
                Map<String,Object> row=db.must(sql,KgJdbc.p("id",target),"请先将活动开放报名或岗位发布，再选择推荐");
                cols.put("target_title",row.get("title"));
                if(!city.isEmpty() && !"online".equals(row.get("jobType")) && !city.equals(row.get("city"))) throw new ServiceException("推荐城市需与内容所属城市一致，或留空作为全国默认推荐",422);
            });
        ExtEntity levels=new ExtEntity("memberlevel","会员等级","kg_member_level","level_id","kangaroo:memberlevel")
            .field(ExtField.of("levelName","等级名称","text").required().max(32))
            .field(ExtField.of("minPoints","所需成长积分","int").required().range(0L,100000000L))
            .field(ExtField.of("description","等级权益（每行一项）","textarea").max(255))
            .noAdd().noDelete().order("level_id").validate(members::validateLevel);
        ExtEntity benefits=new ExtEntity("memberbenefit","会员店铺权益","kg_member_benefit","benefit_id","kangaroo:memberbenefit")
            .field(ExtField.of("merchantName","合作店铺","text").required().max(64).search("like"))
            .field(ExtField.of("title","权益名称","text").required().max(64))
            .field(ExtField.of("city","城市","text").required().max(32).search("eq"))
            .field(ExtField.of("category","店铺分类","select").options("stay:住宿,food:餐饮,coffee:咖啡茶饮,bar:酒吧,experience:体验玩乐,shopping:购物,other:其他").required().search("eq"))
            .field(ExtField.of("cover","店铺图片","image"))
            .field(ExtField.of("discountText","折扣或优惠说明","text").required().max(64))
            // V40：店铺介绍，小程序权益详情在折扣下面显示（纯文本、保留换行；不填就不显示）；列表里不显示，免得表格太宽
            .field(ExtField.of("intro","店铺介绍","textarea").max(1000).hideInList())
            .field(ExtField.of("minLevel","最低适用等级","select").options("0:所有成长会员,1:小袋鼠及以上,2:袋鼠及以上,3:大袋鼠及以上,4:巨袋鼠及以上,5:袋鼠王").required())
            .field(ExtField.of("rules","使用规则与限制","textarea").required().max(3000).hideInList())
            .field(ExtField.of("address","详细地址","text").max(255).hideInList())
            .field(ExtField.of("phone","店铺电话","text").max(32).hideInList())
            .field(ExtField.of("startTime","生效时间","datetime"))
            .field(ExtField.of("endTime","失效时间","datetime"))
            .field(ExtField.of("sort","显示顺序","int").required().range(0L,9999L))
            .field(ExtField.of("status","上架状态","select").options("0:上架,1:下架").required().search("eq"))
            .order("sort,benefit_id desc").validate((cols,creating)-> {
                checkBenefitPhone(cols);
                validate(cols,creating,"kg_member_benefit","benefit_id");
            });
        return Arrays.asList(picks,levels,benefits);
    }
}
