package com.ruoyi.kangaroo.ext.stay;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 旅居模块的后台登记：房源（审核上线 / 驳回 / 下架 / 恢复）、房型、房态价格、早鸟特价、旅居订单（退款审批）、
 * 增值服务、服务订单（退款审批）。订单只读，状态只能经动作按钮按状态机流转。
 */
@Component
public class KgStayModule implements ExtEntityContributor
{
    private static final String YES_NO = "1:是,0:否";

    private final KgJdbc db;

    public KgStayModule(KgJdbc db)
    {
        this.db = db;
    }

    private com.ruoyi.kangaroo.ext.user.KgCityService cities;
    @org.springframework.beans.factory.annotation.Autowired
    private KgStayMediaService media;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCities(com.ruoyi.kangaroo.ext.user.KgCityService cities)
    {
        this.cities = cities;
    }

    /** 订单自动流转（到点未核销 → no_show、离店后 → completed）直接拿这两个值拼时间，所以必须是 HH:mm */
    static void checkTimes(java.util.Map<String, Object> cols)
    {
        for (String k : Arrays.asList("checkin_time", "checkout_time"))
        {
            Object v = cols.get(k);
            if (v != null && !String.valueOf(v).isEmpty() && !String.valueOf(v).matches("^([01][0-9]|2[0-3]):[0-5][0-9]$"))
            {
                throw new ServiceException("入住 / 退房时间格式应为 HH:mm，例如 14:00", 422);
            }
        }
    }

    /**
     * 2026-10-17 R2 的单行文字（所在区县、主理人年龄 / 籍贯 / 擅长、出行参考）：去掉首尾空白，换行和连续空白并成一个空格。
     * 详情页按一行显示，后台文本框里敲的回车会变成怪异的断行；和员工端 KgStayStaffService.ONE_LINE 同一口径
     */
    static void oneLine(java.util.Map<String, Object> cols)
    {
        for (String k : KgStayStaffService.ONE_LINE.keySet())
        {
            Object v = cols.get(k);
            if (v instanceof String)
            {
                cols.put(k, ((String) v).trim().replaceAll("\\s+", " "));
            }
        }
    }

    /**
     * 2026-10-17 R3（V53）：后台改小院视频 / 房型视频时只收平台视频上传接口给的路径（清空 = 删掉视频），封面 / 背景图只收平台图片；
     * 生活碎片文案按员工端同一口径整理成最多 3 行；标题压成一行
     */
    static void checkMedia(java.util.Map<String, Object> cols)
    {
        for (String k : Arrays.asList("intro_video", "video_url"))
        {
            if (cols.containsKey(k))
            {
                String v = cols.get(k) == null ? "" : String.valueOf(cols.get(k)).trim();
                if (!v.isEmpty() && !com.ruoyi.kangaroo.ext.core.KgVideoUploads.isPlatformVideo(v))
                {
                    throw new ServiceException("请通过后台或员工端上传平台视频，清空表示删掉视频", 422);
                }
                cols.put(k, v);
            }
        }
        for (String[] k : new String[][] { { "intro_video_cover", "小院视频封面" }, { "video_cover", "房型视频封面" }, { "diary_bg", "生活碎片背景图" } })
        {
            if (cols.containsKey(k[0]))
            {
                cols.put(k[0], KgStayMediaService.image(cols.get(k[0]), k[1]));
            }
        }
        if (cols.containsKey("diary_title"))
        {
            cols.put("diary_title", KgStayMediaService.oneLine(cols.get("diary_title"), KgStayMediaService.TITLE_MAX, "生活碎片标题"));
        }
        if (cols.containsKey("diary_subtitle"))
        {
            cols.put("diary_subtitle", KgStayMediaService.oneLine(cols.get("diary_subtitle"), KgStayMediaService.SUBTITLE_MAX, "生活碎片副标题"));
        }
        if (cols.containsKey("diary_intro"))
        {
            cols.put("diary_intro", KgStayMediaService.introLines(cols.get("diary_intro")));
        }
    }

    /** 7 天价（V70）：填了就必须 7 个都合法，基础晚价跟着存成最低的那个；清空 = 每天按基础晚价 */
    static void checkWeekPrice(java.util.Map<String, Object> cols)
    {
        if (!cols.containsKey("week_price_fen"))
        {
            return;
        }
        Object raw = cols.get("week_price_fen");
        if (raw == null || String.valueOf(raw).trim().isEmpty())
        {
            cols.put("week_price_fen", null);
            return;
        }
        String week = KgRoomWeekPrice.normalize(String.valueOf(raw).replace('，', ','));
        cols.put("week_price_fen", week);
        cols.put("base_price_fen", KgRoomWeekPrice.min(week));
    }

    private void checkRoomPrice(java.util.Map<String, Object> cols, boolean creating)
    {
        KgRoomPeriodPrice.validateColumns(cols);
        if (cols.containsKey("week_total_fen"))
        {
            if (cols.containsKey("base_price_fen") || cols.containsKey("week_price_fen"))
                throw new ServiceException("7天总价与旧版每晚价格不能同时提交，请刷新页面后重试", 422);
            Object total = cols.remove("week_total_fen");
            java.util.Map<String,Object> previous = creating ? null : db.must(
                    "select base_price_fen,week_price_fen from kg_stay_room_type where room_type_id=:id and del_flag='0' for update",
                    KgJdbc.p("id", cols.get("_pk")), "房型不存在");
            KgRoomWeekPrice.applyTotal(cols, total, previous);
        }
        else checkWeekPrice(cols);
        if (creating && !cols.containsKey("base_price_fen")) throw new ServiceException("请填写7天总价", 422);
    }

    private void checkCalendarStock(java.util.Map<String, Object> cols)
    {
        if (cols.containsKey("stock")) KgRoomInventory.checkCalendar(db, cols.get("_pk"), cols.get("stock"));
    }

    private void checkRoomStock(java.util.Map<String, Object> cols, boolean creating)
    {
        if (cols.containsKey("stay_id")) {
            Long stayId=KgJdbc.asLong(cols.get("stay_id"));
            if (db.count("select count(*) from kg_stay where stay_id=:id and del_flag='0'",KgJdbc.p("id",stayId))==0)
                throw new ServiceException("请选择实际存在的所属房源，房型关联的是房源编号，不是门店编号",422);
            if (!creating) {
                java.util.Map<String,Object> old=db.must("select stay_id from kg_stay_room_type where room_type_id=:id and del_flag='0' for update",KgJdbc.p("id",cols.get("_pk")),"房型不存在");
                if (!java.util.Objects.equals(stayId,KgJdbc.asLong(old.get("stayId"))) &&
                    (db.count("select count(*) from kg_stay_order where room_type_id=:id",KgJdbc.p("id",cols.get("_pk")))>0 ||
                     db.count("select count(*) from kg_stay_calendar where room_type_id=:id",KgJdbc.p("id",cols.get("_pk")))>0))
                    throw new ServiceException("该房型已有订单或房态记录，不能直接变更所属房源，请在正确房源下新增房型",409);
            }
        }
        if (!creating && cols.containsKey("stock")) KgRoomInventory.checkDefault(db, cols.get("_pk"), cols.get("stock"));
    }

    /**
     * 删除前的订单检查：房源 / 房型 / 增值服务一旦被删（软删），员工端就再也管不到还挂在它们名下的、
     * 已售出的夜晚和待核销的服务（房态页查不到房源、核销页查不到服务），订单却还要继续履约。
     * 有未完成的订单时只能下架，等订单走完再删。
     */
    private java.util.function.Consumer<Long> noOpenOrders(String sql, String what)
    {
        return id -> {
            long n = db.count(sql, KgJdbc.p("id", id));
            if (n > 0)
            {
                throw new ServiceException("还有 " + n + " 张未完成的" + what + "订单，不能删除；请先下架，等订单履约或退款完成后再删除", 409);
            }
        };
    }

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity stay = new ExtEntity("stay", "房源", "kg_stay", "stay_id", "kangaroo:stay")
                .field(ExtField.of("houseType", "房源类型", "select").required().options("direct:自营,managed:代运营").search("eq"))
                .field(ExtField.of("stayName", "房源名称", "text").required().max(64).search("like"))
                .field(ExtField.of("storeId", "门店ID", "int").required().search("eq"))
                .field(ExtField.of("city", "城市", "text").required().max(32).search("eq"))
                .field(ExtField.of("area", "商圈", "text").max(32))
                // 2026-10-17 R2：所在区县（小程序列表「位置」下拉按它精确筛选）；填 kg_city 里的区县名（如 道外区），员工端是从这座城市的区县里选的
                .field(ExtField.of("district", "所在区县", "text").max(32).search("eq"))
                .field(ExtField.of("areaDesc", "位置描述", "text").max(64).hideInList())
                .field(ExtField.of("address", "详细地址", "text").max(128).hideInList())
                .field(ExtField.of("latitude", "纬度", "text").max(16).hideInList())
                .field(ExtField.of("longitude", "经度", "text").max(16).hideInList())
                .field(ExtField.of("cover", "封面", "image"))
                .field(ExtField.of("images", "图集", "images").hideInList())
                // 2026-10-17 R3（V53，效果图 7「3分钟看小院」、「近期生活碎片」页顶部）：视频由员工端上传后自动填，后台可以清空或看预览；
                // 背景图、标题、文案员工端也能改（生活碎片的每一条在「生活碎片」菜单里审核）
                .field(ExtField.of("introVideo", "小院视频（3分钟看小院）", "media").max(255).hideInList())
                .field(ExtField.of("introVideoCover", "小院视频封面", "image").hideInList())
                .field(ExtField.of("diaryBg", "生活碎片背景图", "image").hideInList())
                .field(ExtField.of("diaryTitle", "生活碎片标题（空 = 小院日记）", "text").max(32).hideInList())
                .field(ExtField.of("diarySubtitle", "生活碎片副标题（空 = 默认文案）", "text").max(80).hideInList())
                .field(ExtField.of("diaryIntro", "生活碎片文案（每行一句，最多 3 行）", "textarea").max(255).hideInList())
                .field(ExtField.of("tags", "标签（逗号分隔）", "text").max(255).hideInList())
                .field(ExtField.of("features", "特色（逗号分隔）", "text").max(255).hideInList())
                // 设施代码见字典 kg_stay_facility（2026-10-17 V51 补了 study 学习室、gym 健身房）
                .field(ExtField.of("facilities", "设施代码（bath,ac,wifi,washer,parking,balcony,hotwater,study,gym,floorheat）", "text").max(255).hideInList())
                .field(ExtField.of("roomKind", "房型大类", "select").dict("kg_room_kind").hideInList())
                .field(ExtField.of("cookable", "可做饭", "select").options(YES_NO).hideInList())
                .field(ExtField.of("kitchen", "带厨房", "select").options(YES_NO).hideInList())
                .field(ExtField.of("scenicDistanceM", "距核心景点（米）", "int").range(0L, 1000000L).hideInList())
                .field(ExtField.of("longStayNights", "长住起始晚数", "int").range(0L, 365L).hideInList())
                .field(ExtField.of("longStayRate", "长住折扣（%）", "int").range(1L, 100L).hideInList())
                .field(ExtField.of("slogan", "标语", "text").max(64).hideInList())
                .field(ExtField.of("sloganSub", "副标语", "text").max(128).hideInList())
                .field(ExtField.of("hostName", "主理人称呼", "text").max(32).hideInList())
                .field(ExtField.of("hostAvatar", "主理人头像", "image").hideInList())
                .field(ExtField.of("hostIntro", "主理人一句话", "text").max(128).hideInList())
                .field(ExtField.of("hostStory", "小院故事", "textarea").max(1000).hideInList())
                // 2026-10-17 R2（效果图 7）：主理人年龄 / 籍贯 / 擅长、出行参考四段，都是手填文字（不接大模型），详情页只显示填了的
                .field(ExtField.of("hostAge", "主理人年龄（如 32岁 / 90后）", "text").max(16).hideInList())
                .field(ExtField.of("hostHometown", "主理人籍贯", "text").max(32).hideInList())
                .field(ExtField.of("hostSkills", "主理人擅长", "text").max(128).hideInList())
                .field(ExtField.of("travelPros", "出行参考·优点", "textarea").max(255).hideInList())
                .field(ExtField.of("travelCons", "出行参考·缺点", "textarea").max(255).hideInList())
                .field(ExtField.of("travelAirport", "出行参考·机场怎么到", "textarea").max(255).hideInList())
                .field(ExtField.of("travelRail", "出行参考·高铁怎么到", "textarea").max(255).hideInList())
                .field(ExtField.of("checkinTime", "入住时间", "text").max(5).hideInList())
                .field(ExtField.of("checkoutTime", "退房时间", "text").max(5).hideInList())
                .field(ExtField.of("cancelPolicy", "取消政策", "select").dict("kg_cancel_policy"))
                .field(ExtField.of("cancelPolicyConfig", "自定义退款规则", "textarea").max(1000).hideInList())
                .field(ExtField.of("bookingNoticeConfig", "预订须知", "textarea").max(40000).editorDefault((java.util.function.Supplier<Object>) StayBookingNotice::defaultConfig).hideInList())
                .field(ExtField.of("groupName", "住客群名称", "text").max(64).hideInList())
                .field(ExtField.of("groupQr", "住客群码", "image").hideInList())
                .field(ExtField.of("groupQrExpire", "群码有效期至", "date").hideInList())
                .field(ExtField.of("status", "状态", "select").dict("kg_stay_status").search("eq").readOnly())
                .field(ExtField.of("auditReason", "驳回/下架原因", "text").readOnly().hideInList())
                .field(ExtField.of("rating", "评分", "text").readOnly())
                .field(ExtField.of("reviewCount", "评价数", "int").readOnly().hideInList())
                .field(ExtField.of("completedOrders", "完成订单", "int").readOnly())
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L).hideInList())
                .order("sort, stay_id")
                .beforeDelete(noOpenOrders("select count(*) from kg_stay_order where stay_id = :id and del_flag = '0' "
                        + "and status in ('pending_payment','confirmed','checked_in','refund_pending','refunding')", "旅居"))
                .validate((cols, creating) -> {
                    checkTimes(cols);
                    StayCustomPolicy.validateColumns(cols);
                    StayBookingNotice.validateColumns(cols);
                    oneLine(cols);
                    checkMedia(cols);
                    StayLocation.normalize(cols);
                    if (!creating && cols.containsKey("address") && !cols.containsKey("latitude")) {
                        java.util.Map<String,Object> old = db.must("select address from kg_stay where stay_id=:id for update", KgJdbc.p("id",cols.get("_pk")), "房源不存在");
                        if (!java.util.Objects.equals(cols.get("address"), old.get("address"))) {
                            cols.put("latitude", null);
                            cols.put("longitude", null);
                        }
                    }
                    // 住客群码：换了新码却没填「群码有效期至」时，按今天往后 7 天记（微信群码 7 天有效）；
                    // 有了有效期，到期前一天才会给小院主理人发换码提醒（KgStayGroupQrReminder），过期后客人那边也会自动不显示
                    if (cols.containsKey("group_qr") && cols.get("group_qr_expire") == null
                            && cols.get("group_qr") != null && !String.valueOf(cols.get("group_qr")).trim().isEmpty()) {
                        boolean qrChanged = creating;
                        if (!creating) {
                            java.util.Map<String,Object> old = db.one("select group_qr from kg_stay where stay_id = :id", KgJdbc.p("id",cols.get("_pk")));
                            qrChanged = old == null || !String.valueOf(cols.get("group_qr")).trim().equals(old.get("groupQr") == null ? "" : String.valueOf(old.get("groupQr")).trim());
                        }
                        if (qrChanged) cols.put("group_qr_expire", java.time.LocalDate.now().plusDays(7));
                    }
                    // 房源城市统一成字典名（平潭岛 → 平潭）：小程序按 city = 字典名筛房源，订房页城市列表也按它分组
                    if (cities != null && cols.get("city") != null && !String.valueOf(cols.get("city")).trim().isEmpty())
                        cols.put("city", cities.businessCity(String.valueOf(cols.get("city"))));
                    if (cols.containsKey("store_id")) {
                        Long target = KgJdbc.asLong(cols.get("store_id"));
                        // 门店没改时不再校验它是否有效：原门店停用且房源有订单时，旧逻辑既不让留原门店、也不让换，房源任何字段都存不了
                        boolean storeChanged = creating;
                        if (!creating) {
                            java.util.Map<String,Object> old = db.must("select store_id from kg_stay where stay_id = :id and del_flag = '0' for update", KgJdbc.p("id",cols.get("_pk")), "房源不存在");
                            storeChanged = !java.util.Objects.equals(target, KgJdbc.asLong(old.get("storeId")));
                        }
                        if (storeChanged) {
                            if (db.count("select count(*) from kg_store where store_id = :id and status = '0' and del_flag = '0'", KgJdbc.p("id",target)) == 0)
                                throw new ServiceException("请选择有效的所属门店",422);
                            if (!creating && db.count("select count(*) from kg_stay_order where stay_id = :id and del_flag = '0'", KgJdbc.p("id",cols.get("_pk"))) > 0)
                                throw new ServiceException("该房源已有订单，不能直接变更所属门店",409);
                        }
                    }
                })
                .action("visibility", "展示检查", "get", "/kangaroo/stayops/stay/{stayId}/visibility", "kangaroo:stay:query", null, false, null)
                .action("rooms", "管理房型", "route", "KgExtRoomType", "kangaroo:roomtype:list", null, false, null)
                .action("submit", "提交审核", "put", "/kangaroo/stayops/stay/{stayId}/submit", "kangaroo:stay:edit", "确认房型、地址、封面和资质已补齐，提交房源审核？", false, Collections.singletonMap("status",Arrays.asList("draft","rejected")))
                .action("approve", "审核上线", "put", "/kangaroo/stayops/stay/{stayId}/approve", "kangaroo:stay:audit",
                        "确认房源信息与资质无误，审核通过并上线？", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/stayops/stay/{stayId}/reject", "kangaroo:stay:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("offline", "下架", "put", "/kangaroo/stayops/stay/{stayId}/offline", "kangaroo:stay:audit",
                        "下架后用户搜不到这处房源，已有订单照常履约。", true, Collections.singletonMap("status", Arrays.asList("online")))
                .action("online", "恢复上线", "put", "/kangaroo/stayops/stay/{stayId}/online", "kangaroo:stay:audit",
                        "内容未改动，直接恢复上线？", false, Collections.singletonMap("status", Arrays.asList("offline")));

        ExtEntity roomType = new ExtEntity("roomtype", "房型", "kg_stay_room_type", "room_type_id", "kangaroo:roomtype")
                .field(ExtField.of("stayId", "所属房源", "int").required().search("eq"))
                .field(ExtField.of("typeName", "房型名称", "text").required().max(64).search("like"))
                .field(ExtField.of("roomKind", "房型大类", "select").dict("kg_room_kind").required())
                .field(ExtField.of("summary", "一句话描述", "text").max(128).hideInList())
                .field(ExtField.of("bedDesc", "床型", "text").max(64))
                .field(ExtField.of("areaSqm", "面积（㎡）", "int").range(1L, 10000L).hideInList())
                .field(ExtField.of("capacity", "可住人数", "int").required().range(1L, 50L))
                .field(ExtField.of("unitLabel", "库存单位", "select").options("间:间,床位:床位").required())
                .field(ExtField.of("stock", "默认每晚库存", "int").required().range(0L, 999L))
                .field(ExtField.of("basePriceFen", "商家结算价", "money").range(100L, 100000000L).legacyInput())
                // 2026-11-03（V70）：周一到周日 7 天每晚价（分，逗号分隔 7 个）；填了以后基础晚价自动存成其中最低的，空 = 每天按基础晚价
                .field(ExtField.of("weekPriceFen", "7天每晚价", "text").max(80).hideInList().legacyInput())
                .field(ExtField.of("weekTotalFen", "7天总价（元）", "money").range(700L, 70000000L))
                .field(ExtField.of("halfMonthTotalFen", "半个月总价（15晚）", "money").range(1500L, 150000000L))
                .field(ExtField.of("monthTotalFen", "一个月总价（30晚）", "money").range(3000L, 300000000L))
                .field(ExtField.of("cover", "房型图", "image"))
                // 2026-10-17 R3（V53）：房型短视频（≤1分钟），员工端上传后自动填
                .field(ExtField.of("videoUrl", "房型视频（≤1分钟）", "media").max(255).hideInList())
                .field(ExtField.of("videoCover", "房型视频封面", "image").hideInList())
                .field(ExtField.of("features", "亮点（逗号分隔）", "text").max(255).hideInList())
                .field(ExtField.of("status", "状态", "select").options("0:上架,1:下架").required().search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L).hideInList())
                .order("stay_id, sort, room_type_id")
                .afterRead(row -> row.put("weekTotalFen", KgRoomWeekPrice.total(row)))
                .validate((cols, creating) -> {
                    checkMedia(cols);
                    checkRoomStock(cols, creating);
                    checkRoomPrice(cols, creating);
                })
                .beforeDelete(noOpenOrders("select count(*) from kg_stay_order where room_type_id = :id and del_flag = '0' "
                        + "and status in ('pending_payment','confirmed','checked_in','refund_pending','refunding')", "旅居"));

        ExtEntity cal = new ExtEntity("staycal", "房态价格", "kg_stay_calendar", "cal_id", "kangaroo:staycal")
                // 房型 / 房源 / 日期是这一行的身份：改了等于把已占的房量挪到别的日期或房型上，只读（2026-09-24 复核发现）
                .field(ExtField.of("roomTypeId", "房型ID", "int").search("eq").readOnly())
                .field(ExtField.of("stayId", "房源ID", "int").search("eq").readOnly())
                .field(ExtField.of("stayDate", "日期", "date").search("range").readOnly())
                .field(ExtField.of("priceFen", "当晚价（空=基础价）", "money").range(100L, 100000000L))
                .field(ExtField.of("stock", "当晚库存（空=默认）", "int").range(0L, 999L))
                .field(ExtField.of("occupied", "已占用", "int").readOnly())
                .field(ExtField.of("closed", "关房", "select").options("0:可订,1:关房").search("eq"))
                .order("room_type_id, stay_date")
                .validate((cols, creating) -> checkCalendarStock(cols))
                .hardDelete()
                // 房态行由下单 / 员工端改价自动生成；后台只改价与开关房，删行会让已占房量丢失（菜单只有 staycal:edit）
                .noAdd()
                .noDelete();

        ExtEntity promo = new ExtEntity("staypromo", "早鸟特价", "kg_stay_promo", "promo_id", "kangaroo:staypromo")
                .field(ExtField.of("stayId", "房源ID", "int").required().search("eq"))
                .field(ExtField.of("roomTypeId", "房型ID", "int").required())
                .field(ExtField.of("title", "标题", "text").max(64).search("like"))
                .field(ExtField.of("priceFen", "特价晚价", "money").required().range(100L, 100000000L))
                .field(ExtField.of("quota", "可售单数", "int").required().range(1L, 9999L))
                .field(ExtField.of("sold", "已售", "int").readOnly())
                .field(ExtField.of("stayStart", "适用入住日起", "date").required())
                .field(ExtField.of("stayEnd", "适用入住日止", "date").required())
                .field(ExtField.of("saleEnd", "售卖截止", "datetime").required())
                .field(ExtField.of("refundable", "可取消", "select").options("1:可取消（按房源政策）,0:不可退").required())
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").required().search("eq"));

        ExtEntity order = new ExtEntity("stayorder", "旅居订单", "kg_stay_order", "order_id", "kangaroo:stayorder")
                .field(ExtField.of("orderNo", "订单号", "text").search("like"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("stayId", "房源ID", "int").hideInList())
                .field(ExtField.of("guestName", "入住人", "text").search("like"))
                .field(ExtField.of("guestPhone", "联系电话", "text").hideInList())
                .field(ExtField.of("checkIn", "入住", "date").search("range"))
                .field(ExtField.of("checkOut", "离店", "date"))
                .field(ExtField.of("nights", "晚数", "int"))
                .field(ExtField.of("payFen", "应付", "money"))
                .field(ExtField.of("status", "状态", "select").dict("kg_stay_order_status").search("eq"))
                .field(ExtField.of("payNo", "支付单号", "text").hideInList())
                .field(ExtField.of("refundFen", "退款金额", "money"))
                .field(ExtField.of("refundReason", "退款原因", "text").hideInList())
                .field(ExtField.of("refundNote", "处理说明", "text").hideInList())
                .field(ExtField.of("verifiedBy", "核销员工", "text").hideInList())
                .field(ExtField.of("createTime", "下单时间", "datetime").search("range"))
                .readOnly()
                .action("refundApprove", "同意退款", "put", "/kangaroo/stayops/stayorder/{orderNo}/refund-approve", "kangaroo:stayorder:refund",
                        "同意后释放房量并提交原路退款，到账时间以支付渠道为准。", false,
                        Collections.singletonMap("status", Arrays.asList("refund_pending")))
                .action("refundReject", "驳回退款", "put", "/kangaroo/stayops/stayorder/{orderNo}/refund-reject", "kangaroo:stayorder:refund",
                        null, true, Collections.singletonMap("status", Arrays.asList("refund_pending")));

        ExtEntity svc = new ExtEntity("shopservice", "增值服务", "kg_shop_service", "service_id", "kangaroo:shopservice")
                .field(ExtField.of("storeId", "门店ID", "int").required().search("eq"))
                .field(ExtField.of("category", "类目", "select").dict("kg_shop_category").required().search("eq"))
                .field(ExtField.of("serviceName", "服务名称", "text").required().max(64).search("like"))
                .field(ExtField.of("cover", "封面", "image"))
                .field(ExtField.of("priceFen", "单价", "money").required().range(1L, 100000000L))
                .field(ExtField.of("unitLabel", "单位", "text").max(8))
                .field(ExtField.of("specLabel", "规格", "text").max(32).hideInList())
                .field(ExtField.of("durationMin", "时长（分钟）", "int").range(1L, 1440L).hideInList())
                .field(ExtField.of("content", "服务内容", "textarea").max(1000).hideInList())
                .field(ExtField.of("location", "服务地点", "text").max(128).hideInList())
                .field(ExtField.of("badges", "徽标（逗号分隔）", "text").max(255).hideInList())
                .field(ExtField.of("advanceHours", "需提前（小时）", "int").range(0L, 720L).hideInList())
                .field(ExtField.of("timeSlots", "可约时段（逗号分隔）", "text").max(255).hideInList())
                .field(ExtField.of("slotCapacity", "每时段名额", "int").range(1L, 999L).hideInList())
                .field(ExtField.of("rescheduleLimit", "可改期次数", "int").range(0L, 10L).hideInList())
                .field(ExtField.of("refundable", "可退款", "select").options(YES_NO))
                .field(ExtField.of("soldTotal", "已售", "int").readOnly())
                .field(ExtField.of("status", "状态", "select").options("0:上架,1:下架").required().search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L).hideInList())
                .beforeDelete(noOpenOrders("select count(*) from kg_shop_order where service_id = :id and del_flag = '0' "
                        + "and status in ('pending_payment','unused','refund_pending','refunding')", "服务"));

        ExtEntity shopOrder = new ExtEntity("shoporder", "服务订单", "kg_shop_order", "order_id", "kangaroo:shoporder")
                .field(ExtField.of("orderNo", "订单号", "text").search("like"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("serviceId", "服务ID", "int").hideInList())
                .field(ExtField.of("stayOrderNo", "入住订单", "text").hideInList())
                .field(ExtField.of("userName", "使用人", "text").search("like"))
                .field(ExtField.of("qty", "数量", "int"))
                .field(ExtField.of("payFen", "应付", "money"))
                .field(ExtField.of("useDate", "使用日期", "date").search("range"))
                .field(ExtField.of("timeSlot", "时段", "text"))
                .field(ExtField.of("status", "状态", "select").dict("kg_shop_order_status").search("eq"))
                .field(ExtField.of("refundFen", "退款金额", "money").hideInList())
                .field(ExtField.of("refundReason", "退款原因", "text").hideInList())
                .field(ExtField.of("verifiedBy", "核销员工", "text").hideInList())
                .field(ExtField.of("createTime", "下单时间", "datetime").search("range"))
                .readOnly()
                .action("refundApprove", "同意退款", "put", "/kangaroo/stayops/shoporder/{orderNo}/refund-approve", "kangaroo:shoporder:refund",
                        "同意后提交原路退款，到账时间以支付渠道为准。", false,
                        Collections.singletonMap("status", Arrays.asList("refund_pending")))
                .action("refundReject", "驳回退款", "put", "/kangaroo/stayops/shoporder/{orderNo}/refund-reject", "kangaroo:shoporder:refund",
                        null, true, Collections.singletonMap("status", Arrays.asList("refund_pending")));

        ExtEntity vlog = new ExtEntity("stayvlog", "房源 Vlog", "kg_stay_vlog", "vlog_id", "kangaroo:stayvlog")
                .field(ExtField.of("stayId", "房源ID", "int").search("eq").readOnly())
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").readOnly())
                .field(ExtField.of("title", "标题", "text").max(64).search("like"))
                .field(ExtField.of("cover", "封面", "image"))
                .field(ExtField.of("platform", "来源", "select").options("channels:视频号,douyin:抖音,xhs:小红书").search("eq").readOnly())
                .field(ExtField.of("link", "外部链接", "text").max(500).hideInList().readOnly())
                .field(ExtField.of("authorName", "作者", "text").readOnly())
                .field(ExtField.of("authorRole", "身份", "select").options("host:主理人,guest:住客,staff:员工").readOnly())
                .field(ExtField.of("authorized", "已取得授权", "select").options("1:是,0:否").readOnly())
                .field(ExtField.of("orderNo", "关联订单", "text").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,online:已上线,rejected:已驳回,offline:已下架").search("eq").readOnly())
                .field(ExtField.of("auditReason", "驳回/下架原因", "text").hideInList().readOnly())
                .field(ExtField.of("views", "小程序内打开", "int").readOnly())
                .field(ExtField.of("likes", "点赞", "int").readOnly())
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range").readOnly())
                .noAdd()
                .action("approve", "通过", "put", "/kangaroo/stayops/vlog/{vlogId}/approve", "kangaroo:stayvlog:audit",
                        "确认内容合规、已取得出镜人授权？", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/stayops/vlog/{vlogId}/reject", "kangaroo:stayvlog:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("offline", "下架", "put", "/kangaroo/stayops/vlog/{vlogId}/offline", "kangaroo:stayvlog:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("online")));

        ExtEntity qual = new ExtEntity("stayqual", "房源资质", "kg_stay_qualification", "qual_id", "kangaroo:stayqual")
                .field(ExtField.of("stayId", "房源ID", "int").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("qualType", "类型", "select").options("license:营业执照,fire:消防安全检查合格证,food:食品安全许可证,special:特种行业许可证,idcard:负责人身份证").search("eq"))
                .field(ExtField.of("fileName", "文件名", "text"))
                .field(ExtField.of("expireDate", "有效期至", "date"))
                .field(ExtField.of("status", "状态", "select").options("0:有效,1:已替换").search("eq"))
                .field(ExtField.of("createTime", "上传时间", "datetime").search("range"))
                .readOnly().hardDelete()
                .action("view", "查看文件", "get", "/kangaroo/stayops/qual/{qualId}/file-url", "kangaroo:stayqual:view", null, false, null);

        // 生活碎片支持后台添加、编辑、隐藏和删除；归属与媒体格式在事务内校验。
        ExtEntity diary = new ExtEntity("staydiary", "生活碎片", "kg_stay_diary", "diary_id", "kangaroo:staydiary")
                .field(ExtField.of("stayId", "所属房源", "int").search("eq").required().createOnly())
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").readOnly())
                .field(ExtField.of("mediaType", "类型", "select").options("image:照片,video:视频").search("eq").required())
                .field(ExtField.of("mediaUrl", "照片 / 视频", "media").required())
                .field(ExtField.of("coverUrl", "视频封面", "image").hideInList())
                .field(ExtField.of("caption", "一句话", "text").max(32).search("like"))
                .field(ExtField.of("shotDate", "拍摄日期", "date"))
                .field(ExtField.of("status", "显示", "select").options("0:显示,1:隐藏").search("eq"))
                .field(ExtField.of("createBy", "上传人", "text").hideInList().readOnly())
                .field(ExtField.of("createTime", "上传时间", "datetime").search("range").readOnly())
                .order("diary_id desc")
                .validate((cols, creating) -> media.validateAdminDiary(cols, creating));

        // 小院评论（2026-10-31，V65）：小伙伴写的，一律先审后显示。后台只审，不能新增、修改、删除；由平台审，不发给门店
        ExtEntity stayComment = new ExtEntity("staycomment", "小院评论", "kg_stay_comment", "comment_id", "kangaroo:staycomment")
                .field(ExtField.of("stayId", "房源ID", "int").search("eq"))
                .field(ExtField.of("stayName", "小院", "text").search("like"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").hideInList())
                .field(ExtField.of("appUserId", "作者ID", "int").search("eq"))
                .field(ExtField.of("sourceType", "身份", "select").options("guest:已核实住客,volunteer:已核实换工,unverified:未核实").search("eq"))
                .field(ExtField.of("content", "内容", "textarea").search("like"))
                .field(ExtField.of("images", "图片", "images"))
                .field(ExtField.of("videoUrl", "视频", "media"))
                .field(ExtField.of("videoCover", "视频封面", "image").hideInList())
                .field(ExtField.of("portraitConfirmed", "作者确认不侵犯肖像", "select").options("1:已确认,0:未确认").hideInList())
                .field(ExtField.of("riskFlag", "风险", "select").options("none:无,contact:含联系方式,word:命中复核词").search("eq"))
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,online:已通过,rejected:已驳回,offline:已下架").search("eq"))
                .field(ExtField.of("reported", "被举报", "int"))
                .field(ExtField.of("auditReason", "驳回/下架理由", "text").hideInList())
                .field(ExtField.of("auditBy", "审核人", "text").hideInList())
                .field(ExtField.of("auditTime", "审核时间", "datetime").hideInList())
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range"))
                .order("case when status = 'pending' then 0 else 1 end, comment_id desc")
                .readOnly()
                .action("approve", "通过", "put", "/kangaroo/stayops/staycomment/{commentId}/approve", "kangaroo:staycomment:audit",
                        "确认内容合规、不含联系方式，并已取得出镜人授权？带视频的请看一下时长（不超过 60 秒）。通过后会公开显示，并通知作者和这家小院的主理人。", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/stayops/staycomment/{commentId}/reject", "kangaroo:staycomment:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("offline", "下架", "put", "/kangaroo/stayops/staycomment/{commentId}/offline", "kangaroo:staycomment:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("online")));

        return Arrays.asList(stay, roomType, cal, promo, order, svc, shopOrder, vlog, qual, diary, stayComment);
    }
}
