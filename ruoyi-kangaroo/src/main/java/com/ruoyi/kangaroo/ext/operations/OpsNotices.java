package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.staff.StaffSupport;
import com.ruoyi.kangaroo.security.admin.AdminCityContext;

/** Internal staff notices only. A unique event receipt and its message commit together. */
@Service
public class OpsNotices {
    @Autowired private KgJdbc db;
    @Autowired private OpsScope scope;
    @Autowired private OpsMonthly monthly;
    @Autowired private StaffSupport staff;
    @Autowired private TransactionTemplate tx;
    public boolean send(long city,long uid,String event,String title,String content,String type,long id,String route){
        scope.requireAdminCity(city,false);
        try{return Boolean.TRUE.equals(tx.execute(status->{
            long receipt=db.insert("kg_ops_notice",p("city_id",city,"recipient_id",uid,"event_key",event,"create_time",LocalDateTime.now(ZONE).format(DT)));
            // Report scope and recipients are resolved above; only the addressed notice is global.
            List<Long> saved=AdminCityContext.cities();Long message;
            try{AdminCityContext.clear();message=staff.notifyStaffWithId(uid,"system",title,content,type,id,route);}
            finally{if(saved==null)AdminCityContext.clear();else AdminCityContext.set(saved);}
            db.updateRow("kg_ops_notice","notice_id",receipt,p("message_id",message));return message!=null;
        }));}catch(DuplicateKeyException e){return false;}
    }
    public int sendMonthly(long id){
        Map<String,Object> row=monthly.adminGet(id);long city=number(row.get("cityId")),owner=number(row.get("ownerId"));Set<Long> users=new LinkedHashSet<>();
        if(owner>0){scope.requireRead(owner,row);users.add(owner);}else users.addAll(scope.supervisors(OpsScope.ids(row)));
        int count=0;for(Long uid:users){if(owner==0&&!scope.canObserve(uid,row))continue;if(send(city,uid,"month:"+id,"月度总结已生成",row.get("monthKey")+" "+row.get("cityName")+"，点击查看目标完成、行动记录和下月建议。","ops_monthly",id,"/pages/staff/operations?summaryId="+id))count++;}return count;
    }
}
