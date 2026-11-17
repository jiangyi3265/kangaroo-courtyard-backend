package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded exports reuse the exact scoped list/detail queries; never export only the current UI page. */
@Service
public class OpsBatchExport {
    @Autowired private OpsDaily daily;
    @Autowired private OpsMonthly monthly;
    @Autowired private OpsQueries queries;
    @Autowired private OpsExport export;

    @Transactional(readOnly=true)
    @SuppressWarnings("unchecked")
    public List<List<String>> rows(String kind,String month,String from,String to,Long city,String role,Long owner,String status){
        require(Arrays.asList("daily","monthly","issues").contains(kind),"汇总类型无效");
        List<List<String>> out=new ArrayList<>();
        out.add(Arrays.asList("筛选范围",city==null?"当前账号授权城市":String.valueOf(city),text(role),owner==null?"全部人员":String.valueOf(owner),text(status)));
        if("monthly".equals(kind)){
            List<Map<String,Object>> reports=monthly.list(month,city,role,owner);
            require(reports.size()<=300,"一次最多导出300份报表，请缩小筛选范围");
            out.add(Arrays.asList("月份",month,"报表数",String.valueOf(reports.size())));
            for(Map<String,Object> report:reports){out.add(Collections.singletonList("────────"));out.addAll(export.rows(monthly.adminGet(number(report.get("summaryId")))));}
        }else{
            out.add(Arrays.asList("daily".equals(kind)?"日报日期":"异常建立日期",text(from),text(to)));
            int page=1,limit="daily".equals(kind)?300:3000;
            if("issues".equals(kind))out.add(Arrays.asList("异常编号","城市 / 门店","类型","状态","建立时间","闭环时间"));
            while(true){
                Map<String,Object> result="daily".equals(kind)?daily.list(from,to,city,role,owner,status,page):queries.issues(city,status,page,from,to);
                require(number(result.get("total"))<=limit,"一次最多导出"+limit+"条，请缩小筛选范围");
                List<Map<String,Object>> records=(List<Map<String,Object>>)result.get("rows");
                for(Map<String,Object> record:records){
                    if("daily".equals(kind)){out.add(Collections.singletonList("────────"));out.addAll(export.rows(daily.adminGet(number(record.get("reportId")))));}
                    else out.add(Arrays.asList(text(record.get("caseNo")),text(record.get("city"))+" / "+text(record.get("storeName")),text(record.get("category")),record.get("closedAt")==null?"未闭环":"已闭环",text(record.get("createTime")),text(record.get("closedAt"))));
                }
                if(page*30>=number(result.get("total"))||records.isEmpty())break;
                page++;
            }
        }
        out.add(Arrays.asList("说明","按源记录展示，不将覆盖相同门店的多份日报累加为财务总额。"));return out;
    }
}
