package com.ruoyi.kangaroo.ext.party;
import java.util.*;
import java.util.function.IntSupplier;
import java.security.SecureRandom;
import com.ruoyi.common.exception.ServiceException;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
public class PartyEngine {
 private final IntSupplier die;
 public PartyEngine(){SecureRandom rng=new SecureRandom();die=()->1+rng.nextInt(6);}
 public PartyEngine(IntSupplier die){this.die=die;}
 public static final String[] TILES={"出发","海风小院","旅行补给","山间茶屋","古城书屋","好运站","湖畔露营","海岛客栈","道路养护","山野咖啡","休息站","森林木屋","旅途惊喜","落日餐厅","河畔市集","好运站","星空营地","海角民宿","城市维护","袋鼠旅馆"};
 public static int price(int pos){return property(pos)?100+pos*20:0;}
 public static boolean property(int pos){return pos>=0&&pos<20&&pos%5!=0&&pos!=2&&pos!=8&&pos!=12&&pos!=18;}
 public PartyRoom create(String code,long uid,String name,String mode,long now){
  if(!Arrays.asList("monopoly","dice").contains(mode))throw new ServiceException("玩法不存在",422);
  PartyRoom r=new PartyRoom();r.code=code;r.mode=mode;r.host=uid;add(r,uid,name);return r;
 }
 private void add(PartyRoom r,long uid,String name){PartyRoom.Player x=new PartyRoom.Player();x.id=uid;x.name=name==null||name.trim().isEmpty()?"袋鼠玩家":name.substring(0,Math.min(30,name.length()));r.players.add(x);}
 public PartyRoom.Player member(PartyRoom r,long uid){for(PartyRoom.Player x:r.players)if(x.id==uid)return x;return null;}
 public String token(PartyRoom r){return r.phase+":"+r.turn;}
 private void event(PartyRoom r,String text){r.events.add(text);if(r.events.size()>150)r.events.remove(0);}
 private void finish(PartyRoom r){r.phase="finished";r.deadline=0;r.turn++;event(r,"本局结束，按资产排名结算（游戏金币无现金价值）");}
 public long assets(PartyRoom r,PartyRoom.Player x){long n=x.money;for(Map.Entry<Integer,Long> e:r.owners.entrySet())if(e.getValue()==x.id)n+=price(e.getKey());return n;}
 private void bankrupt(PartyRoom r,PartyRoom.Player x){x.out=true;x.money=0;r.owners.entrySet().removeIf(e->e.getValue()==x.id);event(r,x.name+" 破产离场，地产恢复可购买");}
 private void next(PartyRoom r,long now){
  long alive=r.players.stream().filter(x->!x.out).count();if(alive<=1){finish(r);return;}
  do{r.current=(r.current+1)%r.players.size();if(r.current==0){if(r.round>=80){finish(r);return;}r.round++;}}while(r.players.get(r.current).out);
  r.rolled=false;r.dice=new int[0];r.turn++;r.deadline=now+45000;
 }
 public boolean advance(PartyRoom r,long now){if("playing".equals(r.phase)&&now>=r.deadline){event(r,r.players.get(r.current).name+" 超时，自动结束回合");next(r,now);return true;}return false;}
 public void command(PartyRoom r,long uid,String name,String action,Map<String,Object> b,long now){
  String req=String.valueOf(b.get("requestId"));if(!req.matches("[A-Za-z0-9_-]{8,80}"))throw new ServiceException("请求编号无效",422);
  String receipt=uid+":"+req;if(r.receipts.contains(receipt))return;
  PartyRoom.Player me=member(r,uid);
  if("join".equals(action)){
   if(me!=null&&me.left)throw new ServiceException("已退出本局，请等待下一局",409);
   if(me==null){if(!"waiting".equals(r.phase))throw new ServiceException("本局已开始，请等待下一局",409);if(r.players.size()>=("dice".equals(r.mode)?8:6))throw new ServiceException("座位已满",409);add(r,uid,name);event(r,name+" 入座");}
  } else {
   if(me==null)throw new ServiceException("请先加入房间",403);
   if("leave".equals(action)){
    me.left=true;
    if("playing".equals(r.phase)){if(!me.out)bankrupt(r,me);if(r.players.stream().filter(x->!x.out).count()<=1)finish(r);else if(r.players.get(r.current)==me)next(r,now);}
    else r.players.remove(me);
    if(r.host==uid)r.host=r.players.stream().filter(x->x.id!=uid&&!x.out).map(x->x.id).findFirst().orElse(0L);
    if(r.host==0){r.phase="closed";r.deadline=0;}event(r,me.name+" 退出房间");
   } else {
    if(!token(r).equals(String.valueOf(b.get("phaseToken"))))throw new ServiceException("回合已变化，请刷新后操作",409);
    if("start".equals(action)||"replay".equals(action)){
     if(uid!=r.host||!("waiting".equals(r.phase)||"finished".equals(r.phase)))throw new ServiceException("仅房主可在等待或结束时开局",403);
     r.players.removeIf(x->x.left);if(r.players.size()<2)throw new ServiceException("至少两位玩家才能开始",422);
     r.owners.clear();r.events.clear();for(PartyRoom.Player x:r.players){x.money=1500;x.score=0;x.position=0;x.out=false;}
     r.phase="playing";r.round=1;r.current=0;r.rolled=false;r.dice=new int[0];r.turn++;r.deadline=now+45000;event(r,"游戏开始，每回合45秒，最多80轮");
    }else if("finish".equals(action)){
     if(uid!=r.host||!"playing".equals(r.phase))throw new ServiceException("仅房主可结束本局",403);finish(r);
    }else if("settings".equals(action)){
     if(uid!=r.host||!"waiting".equals(r.phase))throw new ServiceException("仅房主可在开局前设置",403);
     int n;try{n=Integer.parseInt(String.valueOf(b.get("diceCount")));}catch(Exception e){n=0;}
     if(n<1||n>6)throw new ServiceException("骰子数量为1至6",422);r.diceCount=n;
    }else{
     if(!"playing".equals(r.phase)||me.out||r.players.get(r.current)!=me)throw new ServiceException("还未轮到你",403);
     if("roll".equals(action)){
      if(r.rolled)throw new ServiceException("本回合已经掷骰",409);r.rolled=true;
      r.dice=new int["dice".equals(r.mode)?r.diceCount:1];int total=0;for(int i=0;i<r.dice.length;i++){r.dice[i]=die.getAsInt();total+=r.dice[i];}
      event(r,me.name+" 掷出 "+Arrays.toString(r.dice)+"，合计 "+total+" 点");
      if("dice".equals(r.mode))me.score+=total;
      else {
       int raw=me.position+total;if(raw>=20){me.money+=200;event(r,me.name+" 经过出发点，获得200金币");}me.position=raw%20;
       Long owner=r.owners.get(me.position);
       if(owner!=null&&owner!=uid){PartyRoom.Player target=member(r,owner);int rent=price(me.position)/5;int paid=Math.min(me.money,rent);me.money-=paid;target.money+=paid;event(r,me.name+" 向 "+target.name+" 支付租金 "+paid);if(paid<rent)bankrupt(r,me);}
       else if(me.position==2||me.position==8||me.position==18){int cost=100;int paid=Math.min(me.money,cost);me.money-=paid;event(r,me.name+" 支付旅行费用 "+paid);if(paid<cost)bankrupt(r,me);}
       else if(me.position==5||me.position==12||me.position==15){me.money+=100;event(r,me.name+" 获得好运奖励100金币");}
       if(me.out)next(r,now);
      }
     }else if("buy".equals(action)){
      int price=price(me.position);if(!r.rolled||price==0||r.owners.containsKey(me.position))throw new ServiceException("这里不能购买",409);
      if(me.money<price)throw new ServiceException("金币不足，可跳过购买",422);me.money-=price;r.owners.put(me.position,uid);event(r,me.name+" 购买 "+TILES[me.position]+"，花费 "+price+" 金币");
     }else if("end".equals(action)){if(!r.rolled)throw new ServiceException("请先掷骰",422);next(r,now);}
     else if("bankrupt".equals(action)){bankrupt(r,me);next(r,now);}
     else throw new ServiceException("操作不存在",404);
    }
   }
  }
  r.receipts.add(receipt);if(r.receipts.size()>2000)r.receipts.remove(r.receipts.iterator().next());
 }
 public Map<String,Object> view(PartyRoom r,Long uid,long now){
  List<Map<String,Object>> people=new ArrayList<>();PartyRoom.Player me=uid==null?null:member(r,uid);
  for(int i=0;i<r.players.size();i++){PartyRoom.Player x=r.players.get(i);people.add(p("seat",i+1,"name",x.name,"position",x.position,"money",x.money,"assets",assets(r,x),"score",x.score,"out",x.out));}
  List<Map<String,Object>> board=new ArrayList<>();for(int i=0;i<20;i++){Long owner=r.owners.get(i);board.add(p("index",i,"name",TILES[i],"price",price(i),"rent",price(i)/5,"ownerSeat",owner==null?0:r.players.indexOf(member(r,owner))+1));}
  boolean turn=me!=null&&!me.out&&"playing".equals(r.phase)&&r.players.get(r.current)==me;
  return p("code",r.code,"mode",r.mode,"phase",r.phase,"version",r.version,"serverTime",now,"phaseToken",token(r),"deadline",r.deadline,"round",r.round,"diceCount",r.diceCount,"dice",r.dice,"rolled",r.rolled,"currentSeat",r.current+1,
   "me",me==null?null:p("seat",r.players.indexOf(me)+1,"host",me.id==r.host,"out",me.out),"canRoll",turn&&!r.rolled,"canEnd",turn&&r.rolled,"canBuy",turn&&r.rolled&&property(me.position)&&!r.owners.containsKey(me.position)&&me.money>=price(me.position),"players",people,"board",board,"events",me==null?Collections.emptyList():r.events);
 }
}
