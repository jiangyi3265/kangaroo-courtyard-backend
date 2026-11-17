package com.ruoyi.kangaroo.ext.user;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Published legal text and its content version, shared by reading and consent. */
@Service
public class KgLegalDocuments {
    private final KgJdbc db;
    public KgLegalDocuments(KgJdbc db) { this.db=db; }
    public Map<String,Object> read(String key) {
        String title="privacy".equals(key)?"隐私政策":"terms".equals(key)?"用户协议":null;
        if(title==null)throw new ServiceException("文档不存在",404);
        List<String> titles="terms".equals(key)?Arrays.asList("用户协议","用户服务协议","《用户协议》","《用户服务协议》"):Arrays.asList("隐私政策","《隐私政策》");
        Map<String,Object> row=db.one("select notice_id,notice_content,coalesce(update_time,create_time) t from sys_notice where notice_title in (:titles) and status='0' order by notice_id desc limit 1",p("titles",titles));
        Object raw=row==null?null:row.get("noticeContent");
        String html=raw instanceof byte[]?new String((byte[])raw,StandardCharsets.UTF_8):raw==null?"":String.valueOf(raw);
        String plain=html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>","").replaceAll("<[^>]*>","")
            .replaceAll("(?i)&nbsp;|&#160;|&#x0*a0;","").replaceAll("[\\s\\u00a0\\u200b\\u3000]","");
        boolean configured=plain.length()>=80;
        String stamp=row==null||row.get("t")==null?"":String.valueOf(row.get("t"));
        return p("key",key,"title",title,"configured",configured,"html",configured?html:"",
            "version",configured?DigestUtils.sha256Hex(key+"\n"+html).substring(0,16):"",
            "updated",stamp.length()>=10?stamp.substring(0,10):null);
    }
    public List<Map<String,Object>> requirePublished() {
        List<Map<String,Object>> docs=Arrays.asList(read("terms"),read("privacy"));
        for(Map<String,Object> doc:docs)if(!Boolean.TRUE.equals(doc.get("configured")))
            throw new ServiceException("协议内容暂不可用，请稍后重试；你仍可先浏览",503);
        return docs;
    }
}
