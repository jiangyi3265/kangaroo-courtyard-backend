package com.ruoyi.kangaroo.ext.user;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgLike;

/**
 * 岗位列表的关键词搜索（2026-10-16 第二轮改版，同日两次复核修订）。规则匹配，不接大模型、不调外部服务，下面写的就是全部规则：
 *
 * 1. 「感觉」词表：关键词里出现下面这些说法时，算一条「感觉」：岗位的标题 / 标签 / 地址 / 介绍里写了这个说法，
 *    或者是线下岗位、在对应名单里的城市（城市按 KgJobCity 同一口径：等于或以城市 / 别名结尾；线上任务见第 3 条）。
 *      · 海边：海边、海景、看海、靠海、沙滩、海岛、海滨、赶海 → 常见的海边旅居城市（COASTAL）；
 *        文字里的「海边 / 海景 / 海岛 / 海滨」紧跟在「洱 / 邛」后面的不算（洱海、邛海是湖，「洱海边」不是海边）：
 *        这四个词不用 like，改用 MySQL 8 的 REGEXP '(^|[^洱邛])海边'；正则只由代码里的常量拼成，用户输入不进正则，照样参数绑定；
 *        关键词本身也一样：用户写的「洱海边 / 邛海边 / 洱海景观」不认成海边，整词留作普通词按字面找（「洱海边的民宿」= 洱海边 + 民宿）；
 *        同一句里另有不跟在湖名后面的「海边」照样认（见 takeVibe）；
 *      · 一二线：一二线、大城市 → 一线 + 新一线 + 二线城市；新一线 → 新一线；一线城市 → 一线；二线城市 → 二线
 *        （名单按第一财经·新一线城市研究所 2023 年城市商业魅力排行榜）；单写「一线」不认（「一线海景」不是城市等级）；
 *      · 小众 / 安静：小众、安静、清静、僻静、人少、宁静、慢生活 → 人少、节奏慢的小城和目的地（QUIET）；
 *        古镇（双廊、喜洲、沙溪，QUIET_TOWNS）不是城市：岗位城市写的是所属城市（「云南大理」），古镇名写在地址里，
 *        按「岗位城市是它所属的城市，并且地址里有古镇名」认（之前拿古镇名比岗位城市，永远对不上；只比地址又会带出中山市沙溪镇）；
 *      · 风景：自然风光、风景、山水 → 以自然风光出名的目的地（SCENIC）。
 *    几条感觉之间是「或者」，合成一组条件：「风景美，海边，一二线城市，小众安静」= 风景好的、海边的、一二线城市的、小众安静的岗位都算
 *    （这句就是换宿页搜索框的占位例子；之前每条感觉各自「并且」，整句一个岗位都搜不到）。
 * 2. 剩下的文字怎么拆成词：
 *    a. 先按空格、标点拆成几段（认过的感觉词也当空格）；
 *    b. 每段再按「的」和连接词（城市 / 地方 / 附近 / 想去 / 我想 / 喜欢 / 工作 / 岗位…，见 JOINERS）切开，但只在词和词之间切：
 *       切完左右留下的每一块要么是空的，要么至少两个字，要么全是虚字（美 / 好 / 很 / 我…，见 SINGLE_FILLERS，随后丢掉）；
 *       做不到就这一处不切，「的」/ 连接词原样留在词里。从左往右找第一个能切的地方，切完左右两块再各自照这条接着切：
 *         · 「大理的咖啡」= 大理 + 咖啡；「我想去大理附近的民宿」= 大理 + 民宿；「风景美的地方」= 只剩感觉；
 *         · 整段就是连接词（「工作」「城市」「想去的地方」）= 不要；
 *         · 「目的地」不切（切开只剩「目」「地」），「工作室」「工作日」不切（只剩「室」「日」），整词去找；
 *       （之前「的」和连接词在哪儿都切：「目的地」变成「目」+「地」、「工作室」变成「室」，都搜不到。
 *         也没有改成「整段等于连接词才去掉」：那样「我想去大理」「民宿工作」会整句按字面找，一个都搜不到）
 *    c. 开头的「去 / 找 / 想」去掉（「去大理」= 大理；去掉后不足两个字就不去），只剩「美 / 好 / 很 / 个」这类虚字的不要；
 *       其余按字面找；换工的新旧称呼互相兼容，不扩成「换宿」，不当城市「义县」。
 *    每个词各成一组，几个词之间是「并且」（「大理 咖啡」= 大理的咖啡岗位）；一组里命中其一就算：
 *      · 标题 / 标签 / 地址 / 介绍里有这个词（包含）；
 *      · 或者是线下岗位，岗位城市等于这个词、或以这个词 / 它的别名结尾（KgJobCity，和城市筛选同一口径：
 *        「大连」不再带出「五大连池」，一个字只认相等）；写的是区县或城市别名（思明区、平潭岛）时，按城市筛选同一套归一，
 *        归到的城市（厦门、平潭）和它的别名也算。之前是 j.city like %词%（包含）。
 *    有感觉又有普通词时，感觉那一组和每个词之间也是「并且」（「海边 咖啡」= 海边的咖啡岗位）。
 * 3. 线上任务全国可接，和城市筛选一样不按城市找、也不按城市排除：凡是按城市的条件（感觉的城市名单、普通词的城市写法、
 *    古镇）都只对线下岗位生效（KgJobMapper 里套在 j.job_type <> 'online' 里），线上任务只看文字。
 *    之前没排除：线上页签搜「海边」「平潭」「小众安静」，会列出平潭门店发的、标题和介绍里一个「海」字都没有的线上任务。
 *    古镇（地址里有古镇名，并且岗位城市是它所属的城市）也排除线上任务：它说的是「岗位就在这个古镇」，靠的是工作地点；
 *    线上任务没有工作地点，城市、地址要么是「不限 / 远程」，要么是后台照发布门店填的，按它认会把大理门店发的线上任务算成「小众安静」。
 *    标题 / 标签 / 地址 / 介绍的文字匹配（包括海边的正则）照旧对所有类型生效；城市列不再做包含匹配，只走上面的城市口径。
 * 4. 关键词最长 64 字，最多 6 组条件（感觉合成的那一组算一组，多出来的词不再收窄）；所有写法都转义 % _ \（KgLike），一律参数绑定；
 *    正则只由代码里的常量拼成（HAN_ONLY 校验），用户输入不进正则，mapper 里也没有 ${}。
 * 列表的数量（total）和行是同一条 SQL 分页算出来的（PageHelper），条件怎么拼两者都一样。
 */
public final class KgJobKeyword
{
    private KgJobKeyword()
    {
    }

    /** 关键词最长字数 */
    public static final int MAX_LENGTH = 64;

    /** 最多几组条件 */
    static final int MAX_TERMS = 6;

    /** 海边：常见的海边旅居城市 / 目的地 */
    static final List<String> COASTAL = Collections.unmodifiableList(Arrays.asList(
            "厦门", "青岛", "三亚", "海口", "万宁", "北海", "威海", "烟台", "日照", "大连", "秦皇岛",
            "舟山", "平潭", "宁德", "漳州", "泉州", "珠海", "汕头", "惠州", "湛江", "防城港"));

    /** 叫「海」的湖的头一个字（洱海、邛海）：「洱海边」「邛海边」不算海边 */
    static final String LAKE_SEAS = "洱邛";

    /** 一线城市 */
    static final List<String> TIER_1 = Collections.unmodifiableList(Arrays.asList("北京", "上海", "广州", "深圳"));

    /** 新一线城市（2023） */
    static final List<String> TIER_NEW_1 = Collections.unmodifiableList(Arrays.asList(
            "成都", "重庆", "杭州", "西安", "武汉", "苏州", "郑州", "南京", "天津", "长沙", "东莞", "宁波", "佛山", "合肥", "青岛"));

    /** 二线城市（2023） */
    static final List<String> TIER_2 = Collections.unmodifiableList(Arrays.asList(
            "昆明", "沈阳", "济南", "无锡", "厦门", "福州", "温州", "金华", "哈尔滨", "大连", "贵阳", "南宁", "泉州", "石家庄", "长春",
            "南昌", "惠州", "常州", "嘉兴", "徐州", "南通", "太原", "保定", "珠海", "中山", "兰州", "临沂", "潍坊", "烟台", "绍兴"));

    /** 小众 / 安静：人少、节奏慢的小城和目的地（自治州同时写简称和字典里的全称）；古镇另见 QUIET_TOWNS */
    static final List<String> QUIET = Collections.unmodifiableList(Arrays.asList(
            "腾冲", "香格里拉", "安吉", "阳朔", "平潭", "普洱", "临沧", "保山", "丽水", "林芝", "婺源", "霞浦", "东山",
            "迪庆", "迪庆藏族自治州", "怒江", "怒江傈僳族自治州", "德宏", "德宏傣族景颇族自治州", "甘南", "甘南藏族自治州"));

    /** 小众 / 安静里的古镇 → 它所属的城市（岗位城市写的是这些城市，古镇名在地址里）。沙溪在剑川县，剑川归大理州 */
    static final Map<String, List<String>> QUIET_TOWNS;

    static
    {
        Map<String, List<String>> towns = new LinkedHashMap<String, List<String>>();
        towns.put("双廊", Collections.singletonList("大理"));
        towns.put("喜洲", Collections.singletonList("大理"));
        towns.put("沙溪", Collections.unmodifiableList(Arrays.asList("大理", "剑川")));
        QUIET_TOWNS = Collections.unmodifiableMap(towns);
    }

    /** 风景：以自然风光出名的目的地 */
    static final List<String> SCENIC = Collections.unmodifiableList(Arrays.asList(
            "大理", "丽江", "香格里拉", "腾冲", "西双版纳", "阳朔", "桂林", "黄山", "张家界", "林芝", "安吉", "婺源",
            "阿坝", "阿坝州", "甘孜", "甘孜藏族自治州"));

    /** 一条说法：触发词 → 所属的组、城市名单；lakeGuard = 文字匹配时排除紧跟在「洱 / 邛」后面的（见 LAKE_SEAS） */
    static final class Vibe
    {
        final String word;
        final String group;
        final List<String> cities;
        final boolean lakeGuard;

        Vibe(String word, String group, List<String> cities, boolean lakeGuard)
        {
            this.word = word;
            this.group = group;
            this.cities = cities;
            this.lakeGuard = lakeGuard;
        }
    }

    /** 按顺序认（具体的在前，认过的字从关键词里去掉：「新一线城市」不会再被当成「一线城市」） */
    static final List<Vibe> VIBES;

    /** 只由汉字组成：拼进正则的说法只能是这种常量（没有任何正则元字符） */
    private static final Pattern HAN_ONLY = Pattern.compile("[\\u4e00-\\u9fa5]+");

    static
    {
        List<String> big = new ArrayList<String>();
        for (List<String> l : Arrays.asList(TIER_1, TIER_NEW_1, TIER_2))
        {
            for (String c : l)
            {
                if (!big.contains(c))
                {
                    big.add(c);
                }
            }
        }
        List<String> bigCities = Collections.unmodifiableList(big);
        List<Vibe> v = new ArrayList<Vibe>();
        for (String w : new String[] { "海边", "海景", "看海", "靠海", "沙滩", "海岛", "海滨", "赶海" })
        {
            // 「海」打头的说法前面可能是湖名（洱海边、洱海景观），「看海」「沙滩」这些不会
            v.add(new Vibe(w, "coastal", COASTAL, w.charAt(0) == '海'));
        }
        v.add(new Vibe("一二线", "big", bigCities, false));
        v.add(new Vibe("大城市", "big", bigCities, false));
        v.add(new Vibe("新一线", "tier-new-1", TIER_NEW_1, false));
        v.add(new Vibe("一线城市", "tier-1", TIER_1, false));
        v.add(new Vibe("二线城市", "tier-2", TIER_2, false));
        for (String w : new String[] { "小众", "安静", "清静", "僻静", "人少", "宁静", "慢生活" })
        {
            v.add(new Vibe(w, "quiet", QUIET, false));
        }
        for (String w : new String[] { "自然风光", "风景", "山水" })
        {
            v.add(new Vibe(w, "scenic", SCENIC, false));
        }
        for (Vibe x : v)
        {
            if (x.lakeGuard && !HAN_ONLY.matcher(x.word).matches())
            {
                throw new IllegalStateException("拼进正则的说法只能是汉字：" + x.word);
            }
        }
        VIBES = Collections.unmodifiableList(v);
    }

    /**
     * 连接词：在词和词之间时切开并丢掉（「大理附近民宿」= 大理 + 民宿，「海边城市」= 海边，整段「工作」= 不要）；
     * 切开会留下单个字时不切（「工作室」「工作日」整词去找）。规则见类注释第 2 条 b
     */
    static final List<String> JOINERS = Collections.unmodifiableList(Arrays.asList(
            "城市", "地方", "去处", "附近", "想去", "想要", "我想", "喜欢", "一点", "比较", "有点", "感觉", "氛围", "风格",
            "等等", "比如", "之类", "那种", "一些", "一个", "特别", "非常", "一下", "看看", "工作", "岗位", "机会"));

    /** 「的」：和连接词同一条规则，只在词和词之间切（「大理的咖啡」切，「目的地」不切） */
    static final String DE = "的";

    /** 切词点：连接词和「的」，同一位置先认长的 */
    private static final List<String> CUTS;

    static
    {
        List<String> c = new ArrayList<String>(JOINERS);
        c.add(DE);
        Collections.sort(c, (x, y) -> y.length() - x.length());
        CUTS = Collections.unmodifiableList(c);
    }

    /** 拆出来的词开头的这些字去掉（「去大理」= 大理）；去掉后不足两个字就不去 */
    static final String LEADING = "去找想";

    /** 只由这些单字组成的词不要（「风景美」认过「风景」后剩下的「美」） */
    static final String SINGLE_FILLERS = "美好很挺超个些点和与或等也都就去找想要我吧呢啊呀";

    /** 空格和标点：先按它们拆成几段 */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,，、;；。.!！?？/|+＋()（）\"“”'‘’:：~～\\-]+");

    /**
     * 关键词 → 给 KgJobMapper.selectAppJobList 的 params.keywordTerms。每组是一个 Map，组与组之间「并且」，组内任一命中：
     *   · likes：转义好的 %写法%，比标题 / 标签 / 地址 / 介绍（like，所有类型）；
     *   · regexps：海边一类说法的正则（只有感觉那一组可能有，常量拼成），同样比这四列（所有类型）；
     *   · cityLikes / cityExacts：城市写法（KgJobCity.putMapperParams 的写法）：感觉组是城市名单，普通词是它自己 + 别名 + 归一到的城市；
     *   · towns：古镇，每项 {addressLike, cityLikes, cityExacts} = 地址里有古镇名并且岗位城市是它所属的城市。
     *   cityLikes / cityExacts / towns 只对线下岗位生效（mapper 里套在 j.job_type <> 'online' 里，见类注释第 3 条）。
     * 有感觉词时，所有感觉合成第一组（几条感觉之间「或者」），后面每个普通词各一组。
     * 没有可用的词（空、全是连接词）返回空列表 = 不按关键词筛。
     * cities 为 null（单测）时城市只按名字本身匹配，不展开别名、不归一区县
     */
    public static List<Map<String, Object>> terms(String keyword, KgCityService cities)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty())
        {
            return out;
        }
        if (k.length() > MAX_LENGTH)
        {
            throw new ServiceException("搜索内容不超过 " + MAX_LENGTH + " 个字", 422);
        }
        // 1. 感觉词：认到的说法和它们各组的城市名单合成一组（「或者」）
        List<String> likes = new ArrayList<String>();
        List<String> regexps = new ArrayList<String>();
        List<String> cityTerms = new ArrayList<String>();
        List<String> groups = new ArrayList<String>();
        String rest = k;
        for (Vibe v : VIBES)
        {
            String taken = takeVibe(rest, v);
            if (taken == null)
            {
                continue;
            }
            rest = taken;
            if (v.lakeGuard)
            {
                regexps.add(notAfterLake(v.word));
            }
            else
            {
                likes.add(KgLike.contains(v.word));
            }
            if (groups.contains(v.group))
            {
                continue;
            }
            groups.add(v.group);
            for (String c : v.cities)
            {
                addAll(cityTerms, KgJobCity.terms(cities, c));
            }
        }
        if (!groups.isEmpty())
        {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("likes", likes);
            if (!regexps.isEmpty())
            {
                m.put("regexps", regexps);
            }
            KgJobCity.putMapperParams(m, cityTerms);
            if (groups.contains("quiet"))
            {
                m.put("towns", towns(cities));
            }
            out.add(m);
        }
        // 2. 剩下的词：先按空格标点拆段，每段再只在词和词之间按「的」/ 连接词切（cut）
        List<String> words = new ArrayList<String>();
        for (String seg : SEPARATORS.split(rest))
        {
            for (String part : cut(seg))
            {
                String w = word(part);
                if (!w.isEmpty() && !words.contains(w))
                {
                    words.add(w);
                }
            }
        }
        // 每个词一组：文字里有它，或者（线下岗位）城市等于它 / 以它或它的别名结尾
        for (String w : words)
        {
            if (out.size() >= MAX_TERMS)
            {
                break;
            }
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            List<String> spellings = new ArrayList<String>();
            spellings.add(KgLike.contains(w));
            String renamed = w.replace("义工", "换工"), legacy = w.replace("换工", "义工");
            if (!renamed.equals(w)) spellings.add(KgLike.contains(renamed));
            if (!legacy.equals(w)) spellings.add(KgLike.contains(legacy));
            m.put("likes", spellings);
            List<String> wordCities = new ArrayList<String>(KgJobCity.terms(cities, w));
            // 写的是区县或城市别名（思明区、平潭岛）：和城市筛选同一套归一，也认归到的那个城市（连同它的别名）的岗位
            String city = cities == null ? null : cities.businessCity(w);
            if (city != null && city.trim().length() >= 2 && !city.trim().equals(w))
            {
                addAll(wordCities, KgJobCity.terms(cities, city.trim()));
            }
            KgJobCity.putMapperParams(m, wordCities);
            out.add(m);
        }
        return out;
    }

    /**
     * 一段（不含空格标点）按「的」和连接词切成词，只在词和词之间切（类注释第 2 条 b）：
     * 从左往右找第一个切得开的地方（同一位置先认长的），切完左右两块再各自照样切；一处都切不开就整段返回。
     * 切得开 = 切完左右两块都站得住（stands）
     */
    static List<String> cut(String s)
    {
        for (int i = 0; i < s.length(); i++)
        {
            for (String c : CUTS)
            {
                if (!s.startsWith(c, i))
                {
                    continue;
                }
                String left = s.substring(0, i);
                String right = s.substring(i + c.length());
                if (stands(left) && stands(right))
                {
                    List<String> out = new ArrayList<String>(cut(left));
                    out.addAll(cut(right));
                    return out;
                }
            }
        }
        return s.isEmpty() ? Collections.<String>emptyList() : Collections.singletonList(s);
    }

    /** 切开后留下的一块站得住：空的、至少两个字，或者是一个虚字（随后丢掉）。「目的地」切开剩「目」，站不住，所以不切 */
    private static boolean stands(String piece)
    {
        return piece.length() != 1 || fillerOnly(piece);
    }

    private static boolean fillerOnly(String w)
    {
        for (int i = 0; i < w.length(); i++)
        {
            if (SINGLE_FILLERS.indexOf(w.charAt(i)) < 0)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 从关键词里认一条说法：认到的每一处换成空格（当切词的空格），返回换完的关键词；一处都没认到返回 null。
     * 要防湖名的说法（lakeGuard）紧跟在「洱 / 邛」后面的那一处不认、原样留下：「洱海边」整词留给第 2 步当普通词按字面找，
     * 和文字匹配那边的正则（notAfterLake）同一口径。之前不管前面是什么一律认成海边，「洱海边」只剩一个「洱」，
     * 而海边的正则又专门排除了洱海边，搜「洱海边」一条都没有
     */
    static String takeVibe(String rest, Vibe v)
    {
        StringBuilder out = new StringBuilder(rest.length());
        boolean taken = false;
        int from = 0;
        int i;
        while ((i = rest.indexOf(v.word, from)) >= 0)
        {
            boolean afterLake = v.lakeGuard && i > 0 && LAKE_SEAS.indexOf(rest.charAt(i - 1)) >= 0;
            out.append(rest, from, i).append(afterLake ? v.word : " ");
            taken |= !afterLake;
            from = i + v.word.length();
        }
        if (!taken)
        {
            return null;
        }
        return out.append(rest, from, rest.length()).toString();
    }

    /** 「海边」→ (^|[^洱邛])海边：开头就是这个词，或者前一个字不是湖名的头一个字（只用于汉字常量，见 HAN_ONLY） */
    static String notAfterLake(String word)
    {
        return "(^|[^" + LAKE_SEAS + "])" + word;
    }

    /** 古镇：每个古镇一项 {addressLike: %古镇名%, cityLikes / cityExacts: 所属城市的写法} */
    static List<Map<String, Object>> towns(KgCityService cities)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, List<String>> e : QUIET_TOWNS.entrySet())
        {
            List<String> terms = new ArrayList<String>();
            for (String c : e.getValue())
            {
                addAll(terms, KgJobCity.terms(cities, c));
            }
            Map<String, Object> t = new LinkedHashMap<String, Object>();
            t.put("addressLike", KgLike.contains(e.getKey()));
            KgJobCity.putMapperParams(t, terms);
            out.add(t);
        }
        return out;
    }

    private static void addAll(List<String> to, List<String> from)
    {
        for (String t : from)
        {
            if (!to.contains(t))
            {
                to.add(t);
            }
        }
    }

    /** 拆出来的一段 → 拿去匹配的词；不要的返回空串 */
    static String word(String part)
    {
        String w = part == null ? "" : part.trim();
        while (w.length() > 2 && LEADING.indexOf(w.charAt(0)) >= 0)
        {
            w = w.substring(1);
        }
        return fillerOnly(w) ? "" : w;
    }
}
