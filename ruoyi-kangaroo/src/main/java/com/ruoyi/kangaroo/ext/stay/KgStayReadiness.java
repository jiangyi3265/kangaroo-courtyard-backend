package com.ruoyi.kangaroo.ext.stay;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.stereotype.Service;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;

@Service
public class KgStayReadiness {
    private final KgJdbc db;
    public KgStayReadiness(KgJdbc db) { this.db = db; }
    public List<String> issues(Long id) {
        Map<String,Object> stay = db.must("select * from kg_stay where stay_id = :id and del_flag = '0'", p("id",id), "房源不存在");
        List<String> issues = new ArrayList<>();
        if (db.count("select count(*) from kg_store where store_id = :id and status = '0' and del_flag = '0'",p("id",stay.get("storeId"))) == 0)
            // 有订单的房源不允许改归属（KgStayModule），这时提示"去换门店"是走不通的
            issues.add(db.count("select count(*) from kg_stay_order where stay_id = :id and del_flag = '0'",p("id",id)) > 0
                    ? "关联门店不存在或已停用。该房源已有订单，不能直接更换门店：请先在门店管理中恢复原门店，或联系技术人员核对订单后迁移归属。"
                    : "关联门店不存在或已停用，请在房源修改中选择实际所属门店。");
        if (db.count("select count(*) from kg_stay_room_type where stay_id = :id and status = '0' and del_flag = '0'",p("id",id)) == 0)
            issues.add("没有上架房型，请到房型管理新增或上架房型。");
        for (String key : Arrays.asList("city", "address", "cover")) {
            Object v = stay.get(key);
            if (v == null || String.valueOf(v).trim().isEmpty()) issues.add("请补全" + ("city".equals(key) ? "城市" : "address".equals(key) ? "详细地址" : "封面") + "。");
        }
        return issues;
    }
    public void requireReady(Long id) {
        List<String> issues = issues(id);
        if (!issues.isEmpty()) throw new ServiceException(String.join("\n",issues), 422);
    }
    public List<String> qualificationIssues(Long id) {
        List<String> issues=new ArrayList<>();
        for(String[] kind:KgStayStaffService.QUALS) {
            if(!"1".equals(kind[2])) continue;
            Map<String,Object> doc=db.one("select expire_date from kg_stay_qualification where stay_id=:id and qual_type=:kind and status='0' order by qual_id desc limit 1",p("id",id,"kind",kind[0]));
            if(doc==null) issues.add("缺少"+kind[1]+"，请在员工端房源的资质上传页补充。");
            else if(doc.get("expireDate")!=null && doc.get("expireDate").toString().substring(0,10).compareTo(java.time.LocalDate.now().toString())<0) issues.add(kind[1]+"已过期，请更新资质。");
        }
        return issues;
    }
    public void requireSubmissionReady(Long id) {
        List<String> issues=issues(id);issues.addAll(qualificationIssues(id));
        if(!issues.isEmpty()) throw new ServiceException(String.join("\n",issues),422);
    }
}
