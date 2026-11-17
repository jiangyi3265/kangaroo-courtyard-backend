package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.num;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.str;
import static com.ruoyi.kangaroo.ext.user.KgPointMallService.integer;
import static com.ruoyi.kangaroo.ext.user.KgPointMallService.text;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgMemberWallet;

@Service
public class KgPublicationFeeService {
    @Autowired private KgJdbc db;

    public Map<String,Object> config() {
        return db.must("select enabled,amount_fen,version from kg_publication_fee_config where id=1", p(), "发布收费配置不存在");
    }

    @Transactional(rollbackFor=Exception.class)
    public void configure(Map<String,Object> b, String actor) {
        String enabled = Boolean.TRUE.equals(b.get("enabled")) || "1".equals(b.get("enabled")) ? "1" : "0";
        int amount = integer(b.get("amountFen"), "1".equals(enabled) ? 1 : 0, 10000000, "每条发布费用（分）");
        int version = integer(b.get("version"), 0, Integer.MAX_VALUE-1, "配置版本");
        if (db.update("update kg_publication_fee_config set enabled=:e,amount_fen=:a,version=version+1,update_by=:by where id=1 and version=:v",
                p("e", enabled, "a", amount, "by", actor, "v", version)) != 1) throw new ServiceException("收费配置已更新，请刷新后保存", 409);
        log(null, actor, "configure", "enabled="+enabled+", amountFen="+amount+", version="+(version+1));
    }

    /** Called inside task creation's transaction. Claim the request before inserting a task. */
    public Map<String,Object> retry(StaffSupport.Ctx c, Map<String,Object> b) {
        String key = str(b.get("publicationRequestKey"));
        if (key.isEmpty()) return null;
        key = KgMemberWallet.key(key);
        if (db.claimIdem("publication-task:"+c.uid()+":"+key, "publication_task")) return null;
        Map<String,Object> previous = db.must("select job_id,store_id from kg_publication_fee where app_user_id=:u and request_key=:k for update", p("u",c.uid(),"k",key), "提交正在处理中，请稍后重试");
        if (!c.storeId.equals(KgJdbc.asLong(previous.get("storeId")))) throw new ServiceException("请求编号已用于其他门店",409);
        return p("jobId",previous.get("jobId"),"msg","已提交，请查看任务记录");
    }

    public void capture(StaffSupport.Ctx c, Long jobId, Map<String,Object> b) {
        Map<String,Object> config = db.must("select * from kg_publication_fee_config where id=1 for update",p(),"发布收费配置不存在");
        long amount = "1".equals(config.get("enabled")) ? num(config.get("amountFen")) : 0;
        if (amount > 0) {
            KgMemberWallet.key(b.get("publicationRequestKey"));
            if (integer(b.get("publicationFeeVersion"),0,Integer.MAX_VALUE-1,"发布费用确认版本") != num(config.get("version")))
                throw new ServiceException("发布费用已调整，请刷新确认后提交",409);
        }
        long feeId = db.insert("kg_publication_fee",p("job_id",jobId,"store_id",c.storeId,"app_user_id",c.uid(),
                "request_key",str(b.get("publicationRequestKey")).isEmpty()?null:b.get("publicationRequestKey"),
                "title",text(b.get("title"),1,100,"任务标题"),"amount_fen",amount,"config_version",config.get("version")));
        log(feeId,"merchant:"+c.uid(),"capture","提交时确认发布费 "+amount+" 分，审核上架后计费");
    }

    public void published(Long jobId) {
        db.update("update kg_publication_fee set status=if(amount_fen>0,'due','free'),published_time=now(),version=version+1 where job_id=:j and status='pending'",p("j",jobId));
    }

    public void closed(Long jobId) {
        db.update("update kg_publication_fee set status='void',version=version+1 where job_id=:j and status='pending'",p("j",jobId));
    }

    public Map<String,Object> list(Long storeId, int page) {
        Map<String,Object> result=db.page("select f.*,s.store_name,j.status job_status from kg_publication_fee f join kg_job j on j.job_id=f.job_id "
                + "left join kg_store s on s.store_id=f.store_id " + (storeId==null?"":"where f.store_id=:s ")+"order by f.fee_id desc",p("s",storeId),page,20);
        result.put("config",config());
        return result;
    }

    @Transactional(rollbackFor=Exception.class)
    public void command(Long id, String action, Map<String,Object> b, String actor) {
        if (!Arrays.asList("receive","waive").contains(action)) throw new ServiceException("操作无效",422);
        Map<String,Object> fee=db.must("select * from kg_publication_fee where fee_id=:id for update",p("id",id),"费用记录不存在");
        String target="receive".equals(action)?"paid":"waived";
        if (target.equals(fee.get("status"))) return;
        if (!"due".equals(fee.get("status"))) throw new ServiceException("费用状态已变化，请刷新后处理",409);
        String note=text(b.get("note"),2,500,"处理说明");
        String receipt="",proof="";
        if ("receive".equals(action)) {
            if (integer(b.get("amountFen"),1,10000000,"实收金额（分）") != num(fee.get("amountFen"))) throw new ServiceException("实收金额须与账单一致",422);
            receipt=text(b.get("receiptNo"),1,100,"收款凭证编号");
            proof=str(b.get("proof"));
            if (!proof.isEmpty() && !KgJdbc.isPlatformImage(proof)) throw new ServiceException("请上传有效凭证图片",422);
        }
        db.update("update kg_publication_fee set status=:status,receipt_no=:r,proof=:p,note=:n,handled_by=:by,paid_time=if(:status='paid',now(),null),version=version+1 where fee_id=:id",
                p("status",target,"r",receipt,"p",proof,"n",note,"by",actor,"id",id));
        log(id,actor,action,note+"；凭证编号 "+receipt);
    }

    private void log(Long id,String actor,String action,String note) {
        db.insert("kg_publication_fee_log",p("fee_id",id,"actor",actor,"action",action,"note",note));
    }
}
