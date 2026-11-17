package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.stereotype.Service;

/** 成长等级与合作店权益共用服务端积分，客户端不提供等级或资格。 */
@Service
public class KgMemberService {
    private final KgJdbc db;
    public KgMemberService(KgJdbc db) { this.db = db; }
    public List<Map<String,Object>> levels() {
        return db.list("select level_id, level_id - 1 level, level_name, min_points, description from kg_member_level order by level_id", p());
    }
    public Map<String,Object> summary(long points) {
        List<Map<String,Object>> levels = levels();
        if (levels.isEmpty()) throw new ServiceException("会员等级设置暂不可用");
        int index = levelIndex(points, levels);
        Map<String,Object> current = levels.get(index), out = new LinkedHashMap<>();
        boolean top = index == levels.size()-1;
        long next = top ? 0 : KgJdbc.asLong(levels.get(index+1).get("minPoints"));
        out.put("level", current.get("level")); out.put("levelName", current.get("levelName"));
        out.put("levels", levels);
        out.put("pointsProgress", top ? points + " 积分" : points + "/" + next);
        out.put("pointsPercent", top ? 100 : Math.max(0, Math.min(100, Math.round(points * 100.0 / Math.max(1,next)))));
        out.put("upgradeTip", top ? "已达到最高成长等级" : "再获得 " + Math.max(0,next-points) + " 积分升到「" + levels.get(index+1).get("levelName") + "」");
        return out;
    }
    public Map<String,Object> summaryFor(Long uid,long points) {
        Map<String,Object> out=summary(points);
        if(uid==null || db.count("select count(*) from information_schema.tables where table_schema=database() and table_name='kg_member_restriction'",p())==0)return out;
        Map<String,Object> r=db.one("select level_penalty,frozen from kg_member_restriction where app_user_id=:u",p("u",uid));
        if(r==null)return out;
        int penalty=(int)KgMemberWallet.num(r.get("levelPenalty"));
        boolean frozen="1".equals(r.get("frozen"));
        int level=Math.max(0,(int)KgMemberWallet.num(out.get("level"))-penalty);
        @SuppressWarnings("unchecked") List<Map<String,Object>> ls=(List<Map<String,Object>>)out.get("levels");
        out.put("level",level);out.put("levelName",ls.get(level).get("levelName"));out.put("memberFrozen",frozen);
        if(frozen || penalty>0)out.put("upgradeTip",frozen?"会员权益已冻结，可在权益中心查看通知与申诉":"当前等级已按平台复核结果调整");
        return out;
    }
    /**
     * 2026-10-17 会员信息屏「我的权益」：每一级能享的东西，只拿后台真有的两样——
     *   items：后台「会员等级」里这一级的「等级说明」（运营按行填写，完整返回全部权益及限制条件）；
     *   shopCount：这一级能用的合作店权益条数（上架中、在有效期内、适用等级 ≤ 这一级）。
     * 两样都没有的等级前端写「暂无专属权益」，不拿样稿里的「会员价预订」「入住欢迎茶」之类凑
     */
    public List<Map<String,Object>> perks() {
        List<Map<String,Object>> levels = levels(), out = new ArrayList<>();
        Map<Integer,Long> byMin = new HashMap<>();
        for (Map<String,Object> r : db.list("select min_level, count(*) n from kg_member_benefit where"+ACTIVE+" group by min_level", p())) {
            Long min = KgJdbc.asLong(r.get("minLevel"));
            if (min != null) byMin.merge(min.intValue(), KgJdbc.asLong(r.get("n")), Long::sum);
        }
        for (Map<String,Object> level : levels) {
            int index = KgJdbc.asLong(level.get("level")).intValue();
            long shops = 0;
            for (Map.Entry<Integer,Long> e : byMin.entrySet()) if (e.getKey() <= index) shops += e.getValue();
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("level", index); m.put("levelName", level.get("levelName")); m.put("minPoints", level.get("minPoints"));
            m.put("items", perkItems(KgJdbc.asStr(level.get("description"))));
            m.put("shopCount", shops);
            out.add(m);
        }
        return out;
    }
    static List<String> perkItems(String description) {
        List<String> out = new ArrayList<>();
        if (description == null) return out;
        for (String s : description.split(description.contains("\n") || description.contains("\r") ? "[\\r\\n]+" : "[、；;]+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            out.add(t);
        }
        return out;
    }
    static int levelIndex(long points, List<Map<String,Object>> levels) {
        int index=0;
        for(int i=0;i<levels.size();i++) if(points>=KgJdbc.asLong(levels.get(i).get("minPoints"))) index=i;
        return index;
    }
    private static final String ACTIVE = " del_flag='0' and status='0' and (start_time is null or start_time<=now()) and (end_time is null or end_time>now())";
    public Map<String,Object> benefits(Long userId, String keyword, String city, String category, int page) {
        Map<String,Object> ps=p(); String where=" where"+ACTIVE;
        if(keyword!=null && !keyword.trim().isEmpty()) { where+=" and (merchant_name like :q escape '!' or title like :q escape '!' or discount_text like :q escape '!')"; ps.put("q","%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        if(city!=null && !city.isEmpty()) {where+=" and city=:city";ps.put("city",city);}
        if(category!=null && !category.isEmpty()) {where+=" and category=:category";ps.put("category",category);}
        Map<String,Object> out=db.page("select benefit_id,merchant_name,title,city,category,cover,discount_text,min_level,end_time from kg_member_benefit"+where+" order by sort,benefit_id desc",ps,Math.min(100000,Math.max(1,page)),20);
        // 之前按全部流水之和算等级；积分能花以后改按「累计获得」（只加正数流水），花积分不会让权益资格掉下去
        Long points=userId==null?null:db.count(KgPointLedger.EARNED_SQL,p("u",userId));
        Map<String,Object> member=summaryFor(userId,points==null?0:points);
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)out.get("rows");
        for(Map<String,Object> row:rows) decorate(row, member, userId!=null);
        out.put("member",userId==null?null:member);
        // Level entitlements are independent of shop inventory and list filters.
        // A member who has not claimed vouchers can still read the configured benefits.
        out.put("levelPerks",perks());
        out.put("executionEnabled",db.count("select count(*) from kg_member_execution where id=1 and enabled='1'",p())>0);
        out.put("cities",db.list("select distinct city from kg_member_benefit where"+ACTIVE+" order by city",p()));
        return out;
    }
    // 店铺介绍（intro，V40）只在详情里返回，列表不带，免得每页 20 条都捎上长文本
    public Map<String,Object> benefit(Long userId, Long id) {
        Map<String,Object> row=db.must("select benefit_id,merchant_name,title,city,category,cover,discount_text,intro,min_level,rules,address,phone,start_time,end_time from kg_member_benefit where benefit_id=:id and"+ACTIVE,p("id",id),"该权益已下架或不在有效期内");
        long points=userId==null?0:db.count(KgPointLedger.EARNED_SQL,p("u",userId));
        decorate(row,summaryFor(userId,points),userId!=null); return row;
    }
    private void decorate(Map<String,Object> row,Map<String,Object> member,boolean signedIn) {
        int min=KgJdbc.asLong(row.get("minLevel")).intValue();
        if(db.count("select count(*) from kg_member_execution where id=1 and enabled='1'",p())>0)min=Math.max(min,"stay".equals(row.get("category"))?2:3);
        @SuppressWarnings("unchecked") List<Map<String,Object>> levels=(List<Map<String,Object>>)member.get("levels");
        row.put("levelLabel",min==0?"所有成长会员":levels.get(min).get("levelName")+"及以上");
        boolean eligible=signedIn && !Boolean.TRUE.equals(member.get("memberFrozen")) && KgJdbc.asLong(member.get("level"))>=min;
        row.put("eligible",eligible);
        row.put("eligibilityText",!signedIn?"登录查看可用权益":eligible?"当前等级可享":"达到适用等级后可享");
    }
    /** Only public presentation flags, never points balances or private restrictions. */
    public Map<String,Object> identity(Long uid){
        if(uid==null || db.count("select count(*) from kg_member_execution where id=1 and enabled='1'",p())==0)return p("level",0);
        Map<String,Object> m=summaryFor(uid,db.count(KgPointLedger.EARNED_SQL,p("u",uid)));
        int level=Boolean.TRUE.equals(m.get("memberFrozen"))?0:(int)KgMemberWallet.num(m.get("level"));
        Map<String,Object> identity=p("level",level,"name",level>0?m.get("levelName"):"","highlight",level>=3,"frame",level>=3,"badge",level>=4,"exclusive",level>=5);
        if(!Boolean.TRUE.equals(m.get("memberFrozen"))) identity.put("achievement",db.one("select d.name,d.tone from kg_badge_award a join kg_badge_def d on d.code=a.badge_code where a.app_user_id=:u and a.worn='1' and a.visible='1' and a.status='active' and d.enabled='1' and (a.expires_at is null or a.expires_at>now()) limit 1",p("u",uid)));return identity;
    }
    public void validateLevel(Map<String,Object> cols,boolean creating) {
        List<Map<String,Object>> levels=db.list("select level_id,min_points from kg_member_level order by level_id for update",p());
        long id=KgJdbc.asLong(cols.get("_pk"));
        long previous=-1;
        for(Map<String,Object> level:levels) {
            long value=Objects.equals(KgJdbc.asLong(level.get("levelId")),id)&&cols.containsKey("min_points")?KgJdbc.asLong(cols.get("min_points")):KgJdbc.asLong(level.get("minPoints"));
            if(value<=previous || (previous==-1 && value!=0)) throw new ServiceException("第一级需为 0 积分，后续等级门槛必须逐级递增",422);
            previous=value;
        }
    }
}
