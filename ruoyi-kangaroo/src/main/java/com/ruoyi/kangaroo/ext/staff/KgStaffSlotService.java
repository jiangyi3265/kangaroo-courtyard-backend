package com.ruoyi.kangaroo.ext.staff;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import java.time.*;
import java.time.format.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.MessageCategory;

@Service
public class KgStaffSlotService {
    private final KgJdbc db;
    private final StaffSupport support;
    public KgStaffSlotService(KgJdbc db, StaffSupport support) { this.db=db; this.support=support; }
    public List<Map<String,Object>> list(Long storeId) {
        StaffSupport.Ctx c=support.ctx(storeId);
        support.need(c,"jobs",StaffPerms.VIEW,"slot:view","查看面试时段");
        // 已约的时段显示这场面试单独填的会议信息（存在 kg_interview.remark），没填才显示时段预设的
        return db.list("select s.slot_id,s.start_time,s.end_time,s.status,coalesce(nullif(trim(i.remark),''),s.meeting_info) meeting_info from kg_interview_slot s "
            + "left join kg_interview i on i.slot_id=s.slot_id and i.app_id=s.booked_app_id and i.status='booked' "
            + "where s.store_id=:s and s.del_flag='0' and s.end_time>now() order by s.start_time limit 100",p("s",c.storeId));
    }
    static Date time(Object raw) {
        try { return Date.from(LocalDateTime.parse(String.valueOf(raw),DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)).toInstant(ZoneOffset.ofHours(8))); }
        catch (Exception e) { throw new ServiceException("请填写有效的北京时间，格式为 yyyy-MM-dd HH:mm:ss",422); }
    }
    @Transactional(rollbackFor=Exception.class)
    public Long add(Long storeId, Map<String,Object> body) {
        StaffSupport.Ctx c=support.ctx(storeId);
        support.need(c,"jobs",StaffPerms.EDIT,"slot:add","新增面试时段");
        Date start=time(body.get("startTime")), end=time(body.get("endTime"));
        if(!start.after(new Date()) || !end.after(start) || end.getTime()-start.getTime()>4*3600000L) throw new ServiceException("时段需在未来，结束晚于开始，最长 4 小时",422);
        String meeting=body.get("meetingInfo")==null?"":String.valueOf(body.get("meetingInfo")).trim();
        if(meeting.length()>500) throw new ServiceException("会议信息最多 500 字",422);
        db.must("select store_id from kg_store where store_id=:s and status='0' and del_flag='0' for update",p("s",c.storeId),"门店已停用");
        if(db.count("select count(*) from kg_interview_slot where store_id=:s and del_flag='0' and status in ('open','booked') and start_time<:e and end_time>:b",p("s",c.storeId,"b",start,"e",end))>0)
            throw new ServiceException("与已有面试时段重叠，请换一个时间",409);
        long id=db.insert("kg_interview_slot",p("store_id",c.storeId,"start_time",start,"end_time",end,"status","open","meeting_info",meeting,"create_by","staff:"+c.uid(),"create_time",new Date()));
        support.op(c,"slot:add","新增面试时段","general",String.valueOf(body.get("startTime")),true,null);
        notifyWaiting(c.storeId,null,id);
        return id;
    }
    /**
     * 门店后来补了面试时段：之前审核通过时店里没有可约时段的候选人，进度页只看到「当前没有可选的面试时段」，
     * 之后门店加了时段也不会告诉他们，只能自己隔一阵回来刷新，流程很容易停在这里。
     * 现在新增时段时，给本店还在「审核通过、待约面试」的到店岗位报名人发一条提醒（站内信；开了订阅的同时推微信），点开进审核进度页选时段。
     * 只提醒加这个时段之前一个可约时段都没有的人：已经有时段可选的不重复打扰，连着加几个时段也只在第一个时提醒一次。
     * jobId 为空表示门店通用时段（小程序里加的都是）；后台给某个岗位单独加时段时传岗位，只提醒这个岗位的报名人
     */
    public void notifyWaiting(Long storeId, Long jobId, long slotId) {
        for (Map<String,Object> a : db.list("select a.app_id, a.app_user_id, j.title from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.store_id=:s and a.status='review_passed' and a.del_flag='0' and j.job_type<>'online' " + (jobId==null?"":"and a.job_id=:job ")
                + "and not exists(select 1 from kg_interview_slot x where x.store_id=a.store_id and x.slot_id<>:id and x.status='open' and x.del_flag='0' "
                + "and x.start_time>now() and (x.job_id is null or x.job_id=a.job_id)) order by a.app_id limit 200",p("s",storeId,"job",jobId,"id",slotId))) {
            Long appId=KgJdbc.asLong(a.get("appId"));
            support.notifyUser(KgJdbc.asLong(a.get("appUserId")),MessageCategory.INTERVIEW,"门店已开放面试时段",
                "你报名的「"+a.get("title")+"」门店新开放了面试时段，去选一个合适的时间吧。","application",appId,"/pages/jobs/progress?appId="+appId);
        }
    }
    @Transactional(rollbackFor=Exception.class)
    public void close(Long storeId,Long id) {
        StaffSupport.Ctx c=support.ctx(storeId);
        support.need(c,"jobs",StaffPerms.EDIT,"slot:close","关闭面试时段");
        if(db.update("update kg_interview_slot set status='closed',update_time=now(),update_by=:u where slot_id=:id and store_id=:s and status='open' and booked_app_id is null and del_flag='0'",p("u","staff:"+c.uid(),"id",id,"s",c.storeId))==0)
            throw new ServiceException("时段已被预约、关闭或无权操作，请刷新",409);
    }
}
