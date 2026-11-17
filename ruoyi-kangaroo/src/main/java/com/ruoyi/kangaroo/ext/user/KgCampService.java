package com.ruoyi.kangaroo.ext.user;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** 青年营地双城群，独立于签约后的地区换工群与住客群。 */
@Service
public class KgCampService {
    @Autowired private KgJdbc db;
    @Autowired private KgCityService cities;

    public Map<String, Object> groups(Long userId, String current, String destination, long visitedAt) {
        com.ruoyi.kangaroo.ext.community.KgCommunityFeature.requireGroupChat(db);
        String source = "browse";
        if (userId != null) {
            Map<String, Object> latest = db.one("select city, visited_at from ("
                + "select j.city, coalesce(unix_timestamp(coalesce(a.submit_time,a.create_time))*1000,0) visited_at from kg_application a join kg_job j on j.job_id=a.job_id "
                + "where a.app_user_id=:u and a.del_flag='0' and a.status not in ('withdrawn','review_rejected','terminated') "
                + "union all select a.city, coalesce(unix_timestamp(g.create_time)*1000,0) visited_at from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id "
                + "where g.app_user_id=:u and g.status in ('confirmed','pending_payment','waitlist')) recent "
                + "where city is not null and city<>'' order by visited_at desc limit 1", p("u", userId));
            if (latest != null && (text(destination).isEmpty() || KgJdbc.asLong(latest.get("visitedAt")) > Math.min(visitedAt, System.currentTimeMillis()))) {
                destination = text(latest.get("city")); source = "signup";
            }
        }
        return p("current", group(current, false), "destination", group(destination, true), "destinationSource", source);
    }

    private Map<String, Object> group(String raw, boolean destination) {
        String city = text(raw).isEmpty() ? "" : text(cities.jobCity(raw));
        Map<String, Object> row = city.isEmpty() ? null : db.one("select camp_local_qr, camp_destination_qr from kg_city where name=:city and status='0' order by city_id limit 1", p("city", city));
        String qr = row == null ? "" : safeImage(row.get(destination ? "campDestinationQr" : "campLocalQr"));
        return p("city", city, "name", (city.isEmpty() ? "待选择城市" : city) + (destination ? "目的地群" : "同城群"),
            "state", qr.isEmpty() ? "unavailable" : "ready", "qrUrl", qr);
    }

    static String safeImage(Object raw) {
        try { List<String> images = JobImages.parse(text(raw)); return images != null && images.size() == 1 ? images.get(0) : ""; }
        catch (ServiceException e) { return ""; }
    }

    public static void validate(Map<String, Object> cols) {
        for (String key : new String[] { "camp_local_qr", "camp_destination_qr" }) {
            if (text(cols.get(key)).isEmpty()) continue;
            List<String> images = JobImages.require(cols.get(key));
            if (images.size() != 1) throw new ServiceException("每个青年营地社群请上传一张企业微信群活码", 422);
            cols.put(key, images.get(0));
        }
    }
    private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
}
