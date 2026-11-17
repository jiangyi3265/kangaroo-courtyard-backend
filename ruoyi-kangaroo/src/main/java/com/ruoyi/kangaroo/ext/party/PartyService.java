package com.ruoyi.kangaroo.ext.party;
import java.util.*;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.security.AppLoginUser;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
@Service public class PartyService {
 @Autowired private StringRedisTemplate redis;
 private final PartyEngine engine=new PartyEngine(); private final ObjectMapper json=new ObjectMapper();private final SecureRandom random=new SecureRandom();
 private static final String PREFIX="kg:party:";
 private static final DefaultRedisScript<String> CREATE=new DefaultRedisScript<>("local old=redis.call('GET',KEYS[1]);if old then return old end local pointer=redis.call('GET',KEYS[2]);if pointer then local room=redis.call('GET','kg:party:room:'..pointer);if room and cjson.decode(room).phase~='closed' then return 'busy' end redis.call('DEL',KEYS[2]) end if redis.call('EXISTS',KEYS[3])==1 then return '' end redis.call('SET',KEYS[1],ARGV[1],'EX',86400);redis.call('SET',KEYS[2],ARGV[1],'EX',86400);redis.call('SET',KEYS[3],ARGV[2],'EX',86400);return ARGV[1]",String.class);
 private static final DefaultRedisScript<Long> CAS=new DefaultRedisScript<>("if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end if ARGV[3]=='join' then local current=redis.call('GET',KEYS[2]);if current and current~=ARGV[4] then local prior=redis.call('GET','kg:party:room:'..current);if prior and cjson.decode(prior).phase~='closed' then return -1 end end redis.call('SET',KEYS[2],ARGV[4],'EX',86400) end if ARGV[3]=='leave' and redis.call('GET',KEYS[2])==ARGV[4] then redis.call('DEL',KEYS[2]) end redis.call('SET',KEYS[1],ARGV[2],'EX',86400);return 1",Long.class);
 private String key(String code){if(code==null||!code.matches("[1-9][0-9]{5}"))throw new ServiceException("房间号应为6位数字",422);return PREFIX+"room:"+code;}
 private String pointer(long uid){return PREFIX+"current:"+uid;}
 private String encode(PartyRoom r){try{return json.writeValueAsString(r);}catch(Exception e){throw new IllegalStateException(e);}}
 private PartyRoom decode(String raw){if(raw==null)throw new ServiceException("房间已过期",404);try{return json.readValue(raw,PartyRoom.class);}catch(Exception e){throw new IllegalStateException(e);}}
 public Map<String,Object> create(AppLoginUser user,String mode,String request){
  if(request==null||!request.matches("[A-Za-z0-9_-]{8,80}"))throw new ServiceException("请求编号无效",422);
  for(int i=0;i<10;i++){String code=String.valueOf(100000+random.nextInt(900000));PartyRoom r=engine.create(code,user.getUserId(),user.getNickname(),mode,System.currentTimeMillis());String saved=redis.execute(CREATE,Arrays.asList(PREFIX+"create:"+user.getUserId()+":"+request,pointer(user.getUserId()),key(code)),code,encode(r));if("busy".equals(saved))throw new ServiceException("已有房间，请先继续或退出原房间",409);if(saved!=null&&!saved.isEmpty())return get(saved,user.getUserId());}
  throw new ServiceException("开房繁忙，请重试",503);
 }
 public Map<String,Object> mine(long uid){String code=redis.opsForValue().get(pointer(uid));if(code==null)return p("room",null);try{Map<String,Object> r=get(code,uid);return p("room",r.get("me")==null||"closed".equals(r.get("phase"))?null:r);}catch(ServiceException e){if(!Integer.valueOf(404).equals(e.getCode()))throw e;return p("room",null);}}
 public Map<String,Object> get(String code,Long uid){return mutate(code,uid,null,null,null);}
 public Map<String,Object> command(String code,AppLoginUser user,String action,Map<String,Object> b){return mutate(code,user.getUserId(),user.getNickname(),action,b);}
 private Map<String,Object> mutate(String code,Long uid,String name,String action,Map<String,Object> b){
  for(int i=0;i<15;i++){String original=redis.opsForValue().get(key(code));PartyRoom r=decode(original);long now=System.currentTimeMillis();boolean timed=engine.advance(r,now);
   if(timed){r.version++;if(!Long.valueOf(1).equals(redis.execute(CAS,Arrays.asList(key(code),PREFIX+"unused"),original,encode(r),"",code)))continue;if(action!=null)continue;return engine.view(r,uid,now);}
   if(action==null)return engine.view(r,uid,now);
   engine.command(r,uid,name,action,b,now);r.version++;
   Long result=redis.execute(CAS,Arrays.asList(key(code),pointer(uid)),original,encode(r),action,code);
   if(Long.valueOf(-1).equals(result))throw new ServiceException("请先退出原房间再加入",409);
   if(Long.valueOf(1).equals(result))return engine.view(r,uid,now);
  }throw new ServiceException("房间正在同步，请重试",409);
 }
}
