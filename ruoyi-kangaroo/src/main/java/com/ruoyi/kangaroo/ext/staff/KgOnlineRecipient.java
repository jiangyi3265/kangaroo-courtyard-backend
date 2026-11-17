package com.ruoyi.kangaroo.ext.staff;

import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgPrivateFiles;
import com.ruoyi.kangaroo.ext.core.KgImageMagic;
import com.ruoyi.kangaroo.security.AppUserContext;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Private payment instructions for one accepted assignment; never initiates a transfer. */
@Service
public class KgOnlineRecipient {
    @Autowired private KgJdbc db;
    @Autowired private KgPrivateFiles files;

    // Caller must have already authorized the worker or this assignment's payer.
    Map<String,Object> view(Long appId, boolean editable) {
        Map<String,Object> row=db.one("select * from kg_online_recipient where app_id=:id",p("id",appId));
        if(row==null) return p("submitted",false,"canEdit",editable);
        String key=KgJdbc.asStr(row.get("qrKey"));
        return p("submitted",true,"canEdit",editable,"realName",row.get("realName"),"method",row.get("method"),
            "account",row.get("account"),"qrUrl",key.isEmpty()?null:files.sign(key,600));
    }

    static String realName(Object value) {
        String name=value==null?"":KgJdbc.asStr(value).trim();
        if(name.length()<2 || name.length()>50 || name.chars().anyMatch(c -> Character.isISOControl(c) || c=='<' || c=='>' || c==0x2028 || c==0x2029)) throw new ServiceException("请填写2–50字的收款人真实姓名",422);
        return name;
    }
    static String account(Object value) {
        String account=value==null?"":KgJdbc.asStr(value).trim();
        if(account.length()>128 || !account.matches("(?:1[0-9]{10}|[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})")) throw new ServiceException("请填写支付宝绑定的手机号或邮箱",422);
        return account;
    }
    private void authorizeAndLock(Long appId) {
        Map<String,Object> owner=db.must("select app_user_id from kg_application where app_id=:id and del_flag='0'",p("id",appId),"接单记录不存在");
        if(!AppUserContext.require().getUserId().equals(KgJdbc.asLong(owner.get("appUserId")))) throw new ServiceException("接单记录不存在",404);
        Map<String,Object> settlement=db.one("select status from kg_online_settlement where app_id=:id for update",p("id",appId));
        if(settlement==null || !"pending_payout".equals(settlement.get("status"))) throw new ServiceException("验收通过后、登记付款前可填写收款信息",409);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> save(Long appId, Map<String,Object> body) {
        authorizeAndLock(appId);
        String name=realName(body.get("realName")),method=KgJdbc.asStr(body.get("method")),account="",key="";
        if("alipay".equals(method)) account=account(body.get("account"));
        else if("qr".equals(method)) {
            Map<String,Object> old=db.one("select * from kg_online_recipient where app_id=:id",p("id",appId));
            key=old==null?"":KgJdbc.asStr(old.get("qrKey"));
            if(key.isEmpty()) throw new ServiceException("请上传收款二维码",422);
        } else throw new ServiceException("请选择收款二维码或支付宝账号",422);
        persist(appId,name,method,account,key);
        return view(appId,true);
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> saveQr(Long appId,String name,MultipartFile file) {
        authorizeAndLock(appId);
        name=realName(name);
        KgImageMagic.require(file,"jpg","png");
        String key=KgJdbc.asStr(files.save(file,"onlinereceiver").get("key"));
        persist(appId,name,"qr","",key);
        return view(appId,true);
    }
    private void persist(Long appId,String name,String method,String account,String key) {
        db.update("insert into kg_online_recipient (app_id,real_name,method,account,qr_key,update_time) values (:id,:name,:method,:account,:key,now()) "
            +"on duplicate key update real_name=:name,method=:method,account=:account,qr_key=:key,update_time=now()",p("id",appId,"name",name,"method",method,"account",account,"key",key));
    }
}
