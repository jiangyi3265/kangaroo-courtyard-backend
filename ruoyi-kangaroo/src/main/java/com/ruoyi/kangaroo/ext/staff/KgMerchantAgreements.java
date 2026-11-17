package com.ruoyi.kangaroo.ext.staff;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Versioned merchant consent. Store and actor are always taken from verified staff context. */
@Service
public class KgMerchantAgreements {
    private final KgJdbc db;
    public KgMerchantAgreements(KgJdbc db) { this.db=db; }
    static String title(String type) {
        if("volunteer".equals(type))return "商家入驻协议（以工换宿专项）";
        if("parttime".equals(type)||"fulltime".equals(type))return "商家入驻协议（兼职/全职专项）";
        if("managed".equals(type))return "商家入驻协议（门店代运营专项）";
        if("volunteer_notice".equals(type))return "袋鼠青年义工旅行·民宿以工换宿使用规范告知书";
        if("stay".equals(type))return "袋鼠青年旅行·旅居房源上架与预订合作协议";
        if("online_task".equals(type))return "线上小程序平台商家发布内容协议";
        throw new ServiceException("该业务没有此项商家协议",404);
    }
    private Map<String,Object> document(String type,boolean lock) {
        String title=title(type);
        Map<String,Object> row=db.one("select notice_id,notice_content,status from sys_notice where notice_title=:t"+("stay".equals(type)?"":" and status='0'")+" order by "+("stay".equals(type)?"(status='0') desc,":"")+"notice_id desc limit 1"+(lock?" for update":""),p("t",title));
        Object raw=row==null?null:row.get("noticeContent");
        String html=raw instanceof byte[]?new String((byte[])raw,StandardCharsets.UTF_8):raw==null?"":String.valueOf(raw);
        if(html.replaceAll("<[^>]*>","").trim().length()<200)throw new ServiceException("商家协议暂未发布，请稍后重试",503);
        return p("title",title,"html",html,"version",DigestUtils.sha256Hex(type+"\n"+html),"type",type,"draft","stay".equals(type) && !"0".equals(String.valueOf(row.get("status"))));
    }
    private Map<String,Object> identity(StaffSupport.Ctx c,String type,String version) {
        return p("s",c.storeId,"u",c.uid(),"t",type,"v",version);
    }
    private Map<String,Object> accepted(StaffSupport.Ctx c,String type,String version) {
        return db.one("select id,title,doc_version,confirmed_at,operator_name,store_name from kg_merchant_agreement_consent where store_id=:s and app_user_id=:u and business_type=:t and doc_version=:v",identity(c,type,version));
    }
    public Map<String,Object> read(StaffSupport.Ctx c,String type) {
        Map<String,Object> d=document(type,false);
        Map<String,Object> record=accepted(c,type,String.valueOf(d.get("version")));
        d.put("accepted",record!=null);d.put("record",record);d.put("storeName",c.storeName);
        d.put("records",db.list("select id,title,doc_version,confirmed_at from kg_merchant_agreement_consent where store_id=:s and app_user_id=:u and business_type=:t order by id desc limit 20",p("s",c.storeId,"u",c.uid(),"t",type)));
        return d;
    }
    public Map<String,Object> record(StaffSupport.Ctx c,Long id) {
        Map<String,Object> r=db.one("select id,title,business_type,doc_version,content_html,store_name,operator_name,confirmed_at from kg_merchant_agreement_consent where id=:id and store_id=:s and app_user_id=:u",p("id",id,"s",c.storeId,"u",c.uid()));
        if(r==null)throw new ServiceException("确认记录不存在",404);
        return r;
    }
    @Transactional(rollbackFor=Exception.class)
    public Map<String,Object> accept(StaffSupport.Ctx c,String type,Map<String,Object> body) {
        if(body==null||!Boolean.TRUE.equals(body.get("agreed")))throw new ServiceException("请阅读并勾选同意商家协议",422);
        Map<String,Object> d=document(type,true);if(Boolean.TRUE.equals(d.get("draft")))throw new ServiceException("旅居协议仍是草稿，发布后才能确认",409);String version=String.valueOf(d.get("version"));
        if(!version.equals(body.get("version")))throw new ServiceException("商家协议已更新，请重新阅读并确认",409);
        // Lock the selected store to serialize duplicate confirmations without suppressing SQL errors.
        db.must("select store_id from kg_store where store_id=:s and del_flag='0' for update",p("s",c.storeId),"门店不存在");
        if(accepted(c,type,version)==null) {
            Map<String,Object> values=identity(c,type,version);
            values.put("title",d.get("title"));values.put("html",d.get("html"));values.put("store",c.storeName==null?"":c.storeName);values.put("name",StaffSupport.cut(c.name(),64));
            db.update("insert into kg_merchant_agreement_consent(store_id,app_user_id,business_type,doc_version,title,content_html,store_name,operator_name,confirmed_at) values(:s,:u,:t,:v,:title,:html,:store,:name,now())",values);
        }
        return read(c,type);
    }
    public void requireAccepted(StaffSupport.Ctx c,String type) {
        Map<String,Object> d=document(type,true);
        if(Boolean.TRUE.equals(d.get("draft")))return;
        if("volunteer".equals(type))requireAccepted(c,"volunteer_notice");
        if(accepted(c,type,String.valueOf(d.get("version")))==null)throw new ServiceException("请先阅读并确认对应商家协议，再提交需求；旧版本请更新小程序",428);
    }
}
