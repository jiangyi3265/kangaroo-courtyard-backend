package com.ruoyi.kangaroo.ext.user;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能词典：把自由文本按关键词归到固定的技能标签上（规则匹配，不是 AI）。
 * 同一套词典用在：简历技能画像、岗位匹配度、找机会推荐理由。
 */
public final class KgSkills
{
    private KgSkills()
    {
    }

    /** 标签 → { 图标, 色调, 关键词... } */
    static final Map<String, String[]> DICT = new LinkedHashMap<String, String[]>();
    static
    {
        DICT.put("咖啡制作", new String[] { "coffee", "green", "咖啡", "手冲", "意式", "咖啡师", "浓缩", "萃取" });
        DICT.put("拉花", new String[] { "coffee", "ochre", "拉花", "拿铁艺术", "latte" });
        // 「拍摄」是门店发布岗位的关键词（2026-10-16 第二轮改版），归到摄影：筛「拍摄」也带出写着摄影 / 拍照的岗位
        DICT.put("摄影", new String[] { "camera", "indigo", "摄影", "拍照", "相机", "旅拍", "修图", "拍摄" });
        DICT.put("视频剪辑", new String[] { "video", "indigo", "剪辑", "视频", "剪映", "vlog", "短视频" });
        DICT.put("门店服务", new String[] { "briefcase", "ochre", "门店", "店员", "前台", "服务员", "店务" });
        DICT.put("收银接待", new String[] { "calculator", "gray", "收银", "接待", "迎宾", "点单" });
        DICT.put("社群运营", new String[] { "users", "green", "社群", "运营", "公众号", "小红书", "私域" });
        DICT.put("活动策划", new String[] { "clipboard-check", "indigo", "活动", "策划", "组织", "市集" });
        DICT.put("内容创作", new String[] { "file-text", "ochre", "文案", "写作", "内容", "创作", "攻略" });
        DICT.put("手作", new String[] { "scissors", "green", "手作", "手工", "陶艺", "皮具", "扎染", "编织", "花艺", "木工" });
        DICT.put("烘焙", new String[] { "cake", "ochre", "烘焙", "面包", "甜品", "蛋糕" });
        DICT.put("教学讲解", new String[] { "graduation-cap", "indigo", "教学", "老师", "讲解", "培训", "带课" });
        DICT.put("外语", new String[] { "languages", "indigo", "英语", "日语", "韩语", "外语", "翻译" });
        DICT.put("民宿管家", new String[] { "house", "green", "民宿", "管家", "客房", "保洁", "布草" });
        // 岗位列表筛选面板的「技能需求」（2026-10-16 第二轮改版）：和门店发布岗位的关键词同名（KgStaffJobService.DUTY），多选命中其一
        DICT.put("做饭", new String[] { "chef-hat", "ochre", "做饭", "厨", "烹饪", "下厨" });
        DICT.put("开车", new String[] { "car", "gray", "开车", "驾照", "驾驶", "司机" });
        DICT.put("玩游戏", new String[] { "gamepad-2", "green", "游戏", "剧本杀", "桌游" });
    }

    /** 标签图标（词表外的标签返回通用图标） */
    public static String iconOf(String tag)
    {
        String[] v = DICT.get(tag);
        return v == null ? "tag" : v[0];
    }

    public static List<String> allTags()
    {
        return new ArrayList<String>(DICT.keySet());
    }

    /**
     * 列表页「技能 / 任务类型」胶囊用的检索词：词典里的标签展开成它的关键词（「摄影」→ 摄影、拍照、相机…）；
     * 正好是某个标签的关键词时（门店关键词「拍摄」是摄影的关键词）按那个标签展开，它自己排在最前；
     * 词典外的（线上任务的「内容写作」这类）按斜杠拆开原样用；每个词最长 16 字，最多 12 个，空的返回空列表
     */
    public static List<String> wordsOf(String tag)
    {
        List<String> out = new ArrayList<String>();
        String t = tag == null ? "" : tag.trim();
        if (t.isEmpty())
        {
            return out;
        }
        String[] v = DICT.get(t);
        if (v == null)
        {
            v = synonymEntry(t);
        }
        if (v != null)
        {
            out.add(t);
            for (int i = 2; i < v.length; i++)
            {
                if (!out.contains(v[i]))
                {
                    out.add(v[i]);
                }
            }
            return out;
        }
        for (String part : t.split("/"))
        {
            String p = part.trim();
            if (!p.isEmpty() && p.length() <= 16 && !out.contains(p) && out.size() < 12)
            {
                out.add(p);
            }
        }
        return out;
    }

    /** 正好是某一个标签的关键词时返回那个标签的词表；不是关键词、或被几个标签共用时返回 null（不猜） */
    private static String[] synonymEntry(String word)
    {
        String[] hit = null;
        for (String[] v : DICT.values())
        {
            for (int i = 2; i < v.length; i++)
            {
                if (v[i].equals(word))
                {
                    if (hit != null && hit != v)
                    {
                        return null;
                    }
                    hit = v;
                }
            }
        }
        return hit;
    }

    /**
     * 列表筛选面板的「技能需求」多选（skills=做饭,拍摄）：每个技能按 wordsOf 展开后合在一起，命中其中任一个词就算。
     * 去重后最多 40 个词（每个技能最多 12 个），空的返回空列表
     */
    public static List<String> wordsOfAll(List<String> tags)
    {
        List<String> out = new ArrayList<String>();
        if (tags == null)
        {
            return out;
        }
        for (String tag : tags)
        {
            for (String w : wordsOf(tag))
            {
                if (!out.contains(w) && out.size() < 40)
                {
                    out.add(w);
                }
            }
        }
        return out;
    }

    /** 岗位的每天工时是文字（"每日4-6小时"），取其中最大的数字作为上限；没有数字返回 0 */
    public static long hoursOf(Object v)
    {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+(\\.\\d+)?").matcher(v == null ? "" : String.valueOf(v));
        double max = 0;
        while (m.find())
        {
            max = Math.max(max, Double.parseDouble(m.group()));
        }
        return max > 24 ? 0 : Math.round(Math.ceil(max));
    }

    public static List<String> tagsOf(String text)
    {
        List<String> out = new ArrayList<String>();
        String t = text == null ? "" : text.toLowerCase();
        for (Map.Entry<String, String[]> e : DICT.entrySet())
        {
            String[] v = e.getValue();
            for (int i = 2; i < v.length; i++)
            {
                if (t.contains(v[i].toLowerCase()))
                {
                    out.add(e.getKey());
                    break;
                }
            }
        }
        return out;
    }

    /** 画像：每个命中的标签给出强度（命中关键词次数折算）与原文出处片段 */
    public static List<Map<String, Object>> profile(String text)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String t = text == null ? "" : text;
        String lower = t.toLowerCase();
        for (Map.Entry<String, String[]> e : DICT.entrySet())
        {
            String[] v = e.getValue();
            int hits = 0;
            Set<String> sources = new LinkedHashSet<String>();
            for (int i = 2; i < v.length; i++)
            {
                String k = v[i].toLowerCase();
                int from = 0;
                int at;
                while ((at = lower.indexOf(k, from)) >= 0)
                {
                    hits++;
                    int a = Math.max(0, at - 8);
                    int b = Math.min(t.length(), at + k.length() + 8);
                    sources.add((a > 0 ? "…" : "") + t.substring(a, b).replace('\n', ' ') + (b < t.length() ? "…" : ""));
                    from = at + k.length();
                }
            }
            if (hits > 0)
            {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("name", e.getKey());
                m.put("icon", v[0]);
                m.put("tone", v[1]);
                m.put("hits", hits);
                m.put("pct", Math.min(98, 55 + hits * 12) + "%");
                m.put("sources", new ArrayList<String>(sources).subList(0, Math.min(3, sources.size())));
                out.add(m);
            }
        }
        out.sort((a, b) -> Integer.compare((Integer) b.get("hits"), (Integer) a.get("hits")));
        return out;
    }
}
