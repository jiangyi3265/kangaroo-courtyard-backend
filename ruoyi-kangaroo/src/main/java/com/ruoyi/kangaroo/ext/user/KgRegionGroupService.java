package com.ruoyi.kangaroo.ext.user;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 按岗位目的地读取地区换工群，与门店聊天和住客群独立。 */
@Service
public class KgRegionGroupService
{
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    @Autowired private KgJdbc db;
    @Autowired private KgCityService cities;

    public Map<String, Object> forCity(String rawCity)
    {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireGroupChat(db);
        String city = text(cities.jobCity(rawCity));
        Map<String, Object> row = city.isEmpty() ? null : db.one("select region_group_name, region_group_qr, region_group_expire, region_group_note "
                + "from kg_city where name = :city and status = '0' order by city_id limit 1", KgJdbc.p("city", city));
        return view(city, row, LocalDate.now(ZONE));
    }

    static Map<String, Object> view(String city, Map<String, Object> row, LocalDate today)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        String name = row == null ? "" : text(row.get("regionGroupName"));
        String qr = row == null ? "" : text(row.get("regionGroupQr"));
        LocalDate expire = row == null ? null : date(row.get("regionGroupExpire"));
        boolean validImage = false;
        if (!qr.isEmpty()) {
            try { List<String> images = JobImages.parse(qr); validImage = images != null && images.size() == 1; if (validImage) qr = images.get(0); }
            catch (ServiceException ignored) { /* 历史无效图片不下发 */ }
        }
        boolean expired = expire != null && expire.isBefore(today);
        boolean open = validImage && expire != null && !expired;
        out.put("city", city);
        out.put("name", name.isEmpty() ? (city.isEmpty() ? "地区" : city) + "换工畅玩群" : name);
        out.put("note", row == null ? "" : text(row.get("regionGroupNote")));
        out.put("state", open ? "ready" : expired && validImage ? "expired" : "unavailable");
        out.put("qrUrl", open ? qr : null);
        out.put("expire", expire == null ? null : expire.toString());
        out.put("validUntil", open ? expire.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() : null);
        return out;
    }

    /** 后台城市表单：有效期必须明确填写，不根据上传时间猜测。 */
    public static void validate(Map<String, Object> cols)
    {
        String qr = text(cols.get("region_group_qr"));
        if (qr.isEmpty()) return;
        List<String> images;
        try { images = JobImages.require(qr); }
        catch (ServiceException e) { throw new ServiceException("地区群二维码无效，请重新上传", 422); }
        if (images.size() != 1) throw new ServiceException("地区群二维码只能上传一张", 422);
        if (date(cols.get("region_group_expire")) == null) throw new ServiceException("请填写地区群二维码的有效期", 422);
        cols.put("region_group_qr", images.get(0));
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static LocalDate date(Object value) {
        String s = text(value);
        try { return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null; }
        catch (RuntimeException e) { return null; }
    }
}
