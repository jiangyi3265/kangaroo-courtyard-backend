package com.ruoyi.kangaroo.ext.party;
import java.util.*;
public class PartyRoom {
 public String code,mode,phase="waiting"; public long host,version=1,deadline; public int turn=0,round=1,current=0,diceCount=1;
 public boolean rolled; public int[] dice=new int[0];
 public List<Player> players=new ArrayList<>(); public List<String> events=new ArrayList<>(); public Set<String> receipts=new LinkedHashSet<>();
 public Map<Integer,Long> owners=new LinkedHashMap<>();
 public static class Player {public long id;public String name;public int position,money=1500;public boolean out,left;public int score;}
}
