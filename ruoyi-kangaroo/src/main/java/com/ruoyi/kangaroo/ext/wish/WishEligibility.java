package com.ruoyi.kangaroo.ext.wish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ruoyi.common.exception.ServiceException;

/** Server-side text screening supplements the recorded human review of the actual goods and images. */
public final class WishEligibility {
    public static final int FOOD = 1;
    public static final int TOBACCO = 2;
    public static final int ALCOHOL = 4;
    /** 三类受限范围合起来；reviewed_requirements 里只有这几位是品类 */
    public static final int SCOPES = FOOD | TOBACCO | ALCOHOL;
    /**
     * 存在 reviewed_requirements 里的标记位（不是品类）：后台确认正文关键词误判，人工核实的品类为准，
     * 正文关键词不再叠加（正文、图片之后再改就失效）。不加列，借用这一列的空闲位。
     */
    public static final int MANUAL = 8;
    /** 人工核实为线上信息咨询；不代表实物食品交付获豁免。 */
    public static final int INFORMATION = 16;
    private WishEligibility() { }

    public static String normalized(Object value) {
        String input = Normalizer.normalize(value == null ? "" : String.valueOf(value), Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder();
        input.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    public static int inspect(Object value) {
        String text = normalized(value);
        String physical = text.replaceAll("(游戏|虚拟)(枪支|弹药|子弹)(图标|素材|模型|贴图)", "")
                .replaceAll("玩具(枪支|弹药|子弹)(模型|设计图)", "");
        if (contains(physical, "药品", "藥品", "药物", "中药", "中成药", "西药", "處方藥", "处方药", "非处方药", "买药", "買藥", "代购药", "寄药", "带药",
                "感冒药", "退烧药", "消炎药", "安眠药", "阿莫西林", "布洛芬", "阿司匹林", "胰岛素", "处方笺",
                "止痛片", "降压药", "维生素片", "维生素保健", "医疗器械", "醫療器械", "血糖仪", "血压计", "制氧机", "保健品", "保健食品", "膳食补充剂",
                "活体动物", "活體動物", "活猫", "活狗", "活体宠物", "宠物托运", "寵物托運", "代养宠物", "枪支", "真枪", "枪弹", "弹药", "子弹", "爆炸物",
                "炸药", "烟花", "鞭炮", "汽油", "硫酸", "毒品", "冰毒", "海洛因", "大麻", "管制刀具",
                "代驾", "代駕", "高空作业", "高空清洗", "外墙清洗", "攀爬维修", "贷款", "借贷", "借款",
                "代办信用卡", "代办银行卡", "投资理财", "理财服务", "代炒股", "证券交易", "股票开户",
                "证件代办", "代办证件", "代办身份证", "代办护照", "代办签证", "法律咨询", "法律服务", "代写诉状", "诉讼代理")
                || text.matches(".*(购买|代买|买|寄送|托运|运输|运送|出售|领养|代养).{0,5}(小猫|小狗|宠物猫|宠物狗|活鸡|活鸭|活鱼|兔子|仓鼠)(?!砂|粮|玩具|模型|公仔|照片|明信片).*" )
                || text.matches(".*(买|寄|运|送|带).{0,4}(一只|两只|1只|2只)(猫|狗|兔|鸟)(?!砂|粮|玩具|模型|公仔|照片|明信片).*")) {
            throw new ServiceException("内容涉及未开放的药械保健、活体、危险品或高风险服务，请调整心愿内容", 422);
        }
        int requirements = 0;
        // References to packaging/design and empty craft materials still need a human classification.
        String goods = text.replace("食品包装设计", "").replace("食品包装袋设计", "").replace("零食包装设计", "")
                .replace("蛋糕模型", "").replace("水果模型", "");
        if (contains(goods, "食品", "食物", "零食", "小吃", "螺蛳粉", "螺丝粉", "桂林米粉", "老友粉", "米线", "糕点", "饼干",
                "蛋糕", "面包", "月饼", "粽子", "糖果", "巧克力", "茶叶", "咖啡", "蜂蜜", "奶茶", "饮料", "牛奶", "酸奶",
                "水果", "果干", "果脯", "牛肉", "猪肉", "羊肉", "鸡肉", "鸭肉", "鱼肉", "鸡蛋", "鸭蛋", "大米", "面粉", "面条", "米面", "粮食",
                "食盐", "食用油", "调味料", "酱油", "食醋", "蔬菜", "青菜", "土豆", "番茄", "西红柿", "一箱苹果", "一斤苹果", "香蕉", "橙子", "榴莲",
                "肉脯", "香肠", "腊肉", "海鲜", "熟食", "生鲜", "罐头", "辣条", "饺子", "汤圆", "坚果", "炒菜", "做饭")) requirements |= FOOD;
        if (contains(text, "烟草", "煙草", "香烟", "香煙", "卷烟", "雪茄", "电子烟", "電子煙", "烟丝", "烟叶", "tobacco", "cigarette")
                || text.matches(".*(买|购|寄|带|送|要).{0,4}(一条烟|一包烟|两条烟|一盒烟|一条中华|两条中华|一条黄鹤楼).*")) requirements |= TOBACCO;
        String drink = text.replace("酒店", "").replace("酒杯", "").replace("酒架", "").replace("酒柜", "").replace("酒具", "");
        if (contains(drink, "白酒", "啤酒", "葡萄酒", "红酒", "黄酒", "米酒", "果酒", "洋酒", "烈酒", "茅台", "五粮液", "酒精饮料", "whisky", "whiskey", "vodka")
                || drink.matches(".*(买|购|寄|带|送|拿|要).{0,4}酒.*")) requirements |= ALCOHOL;
        return requirements;
    }

    public static int inspectWish(Map<String,Object> wish) {
        int result = inspect(String.valueOf(wish.get("title"))+" "+String.valueOf(wish.get("description")));
        if ("food".equals(wish.get("category"))) result |= FOOD;
        if ("licensed".equals(wish.get("category"))) {
            int specified = result & (TOBACCO | ALCOHOL);
            result |= specified == 0 ? TOBACCO | ALCOHOL : specified;
        }
        return result;
    }

    /**
     * 不看正文关键词、只按所选类目必须有的范围：食品类 → 食品；烟酒特殊许可 → 烟草、酒类至少一项（declared 一项都没有时两项都要）。
     * 后台改判关键词误判时用它做下限。
     */
    public static int categoryFloor(Map<String,Object> wish, int declared) {
        int floor = 0;
        if ("food".equals(wish.get("category"))) floor |= FOOD;
        if ("licensed".equals(wish.get("category")) && (declared & (TOBACCO | ALCOHOL)) == 0) floor |= TOBACCO | ALCOHOL;
        return floor;
    }

    public static boolean clearlyOrdinary(Map<String,Object> wish) {
        String text=normalized(String.valueOf(wish.get("title"))+" "+String.valueOf(wish.get("description")));
        return contains(text,"明信片","风景照片","动物园照片","猫砂","游戏素材","包装设计图","手绘画像","手工艺摆件");
    }

    public static boolean requestsChangedGoods(String content) {
        String text=normalized(content);
        // Chat can report violations. Only explicit new requests are screened as proposed scope changes.
        if(contains(text,"不要","不需要","拒绝","退款","投诉","违规","禁止","误发","没有","不能","并非"))return false;
        return contains(text,"改成","改买","加购","再买","另外买","再带","顺便","补充要求","新增","购买","代买","帮买","帮我寄","请寄","请带");
    }

    public static int declared(Object value) {
        if (!(value instanceof Collection)) throw new ServiceException("请明确勾选已核实的食品、烟草、酒类范围或普通品类", 422);
        Collection<?> values = (Collection<?>) value;
        int result = 0;
        if (values.isEmpty()) throw new ServiceException("请明确填写人工核实的品类", 422);
        for (Object item : values) {
            if ("food".equals(item)) result |= FOOD;
            else if ("tobacco".equals(item)) result |= TOBACCO;
            else if ("alcohol".equals(item)) result |= ALCOHOL;
            else if (!"ordinary".equals(item)) throw new ServiceException("人工核实品类无效", 422);
        }
        if (result != 0 && values.contains("ordinary")) throw new ServiceException("普通品类不能与受限品类同时选择", 422);
        return result;
    }

    public static List<String> labels(int requirements) {
        List<String> result = new ArrayList<>();
        if ((requirements & FOOD) != 0) result.add("food");
        if ((requirements & TOBACCO) != 0) result.add("tobacco");
        if ((requirements & ALCOHOL) != 0) result.add("alcohol");
        if (result.isEmpty()) result.add("ordinary");
        return result;
    }

    public static String contentHash(Map<String,Object> wish) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String key : new String[]{"title", "description", "category", "targetCity", "images"}) {
                digest.update(String.valueOf(wish.get(key)).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    public static String reviewHash(Map<String,Object> wish, boolean information) {
        if (!information) return contentHash(wish);
        Map<String,Object> bound = new java.util.LinkedHashMap<>(wish);
        bound.put("description", String.valueOf(wish.get("description")) + "\u0000delivery=" + wish.get("delivery"));
        return contentHash(bound);
    }

    public static boolean reviewMatches(Map<String,Object> wish) {
        int flags = wish.get("reviewedRequirements") instanceof Number ? ((Number) wish.get("reviewedRequirements")).intValue() : 0;
        return reviewHash(wish, (flags & INFORMATION) != 0).equals(String.valueOf(wish.get("reviewContentHash")));
    }

    public static boolean informationOnly(Map<String,Object> wish) {
        int flags = wish.get("reviewedRequirements") instanceof Number ? ((Number) wish.get("reviewedRequirements")).intValue() : 0;
        return (flags & INFORMATION) != 0 && "digital".equals(wish.get("delivery"))
                && !"licensed".equals(wish.get("category")) && reviewMatches(wish);
    }

    public static int deliveryRequirements(Map<String,Object> wish, Object note) {
        int scope = inspect(note);
        // 咨询回答可以提到食物名称；新增采购、寄送仍按食品交付核验。
        return informationOnly(wish) && !requestsChangedGoods(String.valueOf(note)) ? scope & ~FOOD : scope;
    }
}
