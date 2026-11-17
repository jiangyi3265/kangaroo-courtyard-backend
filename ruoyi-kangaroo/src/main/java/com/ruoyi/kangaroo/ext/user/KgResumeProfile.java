package com.ruoyi.kangaroo.ext.user;
import java.util.*;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
/** 扩展资料只保存白名单字段；媒体必须属于该简历本人。 */
@Service
public class KgResumeProfile {
    private final KgResumeMedia files;
    public KgResumeProfile(KgResumeMedia files) { this.files=files; }
    private Map<String,Object> parse(Object raw) {
        return raw == null || raw.toString().trim().isEmpty() ? new LinkedHashMap<>() : new LinkedHashMap<>(JSON.parseObject(raw.toString()));
    }
    public String merge(Long uid, Object old, Map<String,Object> body) {
        Map<String,Object> p=parse(old);
        for(String key:Arrays.asList("hobbies","personality","background")) if(body.containsKey(key)) {
            String value=Objects.toString(body.get(key),"").trim();
            if(value.length()>(key.equals("background")?1000:200)) throw new ServiceException("资料文字过长，请精简后保存",422);
            p.put(key,value);
        }
        if(body.containsKey("lifePhoto")) {
            String key=Objects.toString(body.get("lifePhoto"),"");
            if(!key.isEmpty()) files.requireOwned(uid,key,false);
            p.put("lifePhoto",key);
        }
        if(body.containsKey("media")) {
            if(!(body.get("media") instanceof List) || ((List<?>)body.get("media")).size()>6) throw new ServiceException("最多上传 6 个作品",422);
            List<Map<String,Object>> list=new ArrayList<>();
            for(Object item:(List<?>)body.get("media")) {
                if(!(item instanceof Map)) throw new ServiceException("作品信息无效",422);
                Map<?,?> m=(Map<?,?>)item;
                String key=Objects.toString(m.get("key"),"");
                files.requireOwned(uid,key,true);
                String name=Objects.toString(m.get("name"),"作品");
                Map<String,Object> entry = new LinkedHashMap<>();
                entry.put("key",key);
                entry.put("kind",key.endsWith(".mp4")?"video":"image");
                entry.put("name",name.substring(0,Math.min(name.length(),120)));
                list.add(entry);
            }
            p.put("media",list);
        }
        return JSON.toJSONString(p);
    }
    public Map<String,Object> read(Long uid,Object raw) {
        Map<String,Object> p=parse(raw);
        String portrait=Objects.toString(p.get("lifePhoto"),"");
        if(!portrait.isEmpty() && files.owned(uid,portrait)) p.put("lifePhotoUrl",files.url(portrait));
        if(p.get("media") instanceof List) for(Object item:(List<?>)p.get("media")) {
            Map<String,Object> m=(Map<String,Object>)item;
            String key=Objects.toString(m.get("key"),"");
            if(files.owned(uid,key)) m.put("url",files.url(key));
        }
        return p;
    }
}
