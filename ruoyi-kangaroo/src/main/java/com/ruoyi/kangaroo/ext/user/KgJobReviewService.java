package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.core.KgJobReviewDimensions;

/** Public, store-scoped application reviews. Summary stays independent of list filters. */
@Service
public class KgJobReviewService
{
    @Autowired private KgJdbc db;
    static final List<String> MENTIONS = Arrays.asList("交通方便", "氛围很好", "工作轻松", "分工明确", "吃住满意", "房间干净", "同事友好", "工作较累");
    private static final String FROM = " from kg_review v join kg_application a on a.app_id=v.biz_id join kg_job j on j.job_id=a.job_id ";
    private static final String VISIBLE = " where coalesce(j.recruit_store_id,j.store_id)=:s and v.store_id=j.store_id and v.biz_type='application' and v.status='0' and v.del_flag='0'";
    private static final String MEDIA = "((v.images is not null and trim(v.images) not in ('','[]')) or (v.videos is not null and trim(v.videos) not in ('','[]')))";

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static long num(Object o) { return o instanceof Number ? ((Number) o).longValue() : 0; }
    private String scope(Long storeId, String filter, Map<String, Object> params)
    {
        if (storeId == null || storeId <= 0) throw new ServiceException("门店不存在", 404);
        Map<String, Object> store = db.one("select store_id, store_name from kg_store where store_id=:s and status='0' and del_flag='0'", p("s", storeId));
        if (store == null) throw new ServiceException("门店不存在或已停用", 404);
        params.put("s", storeId);
        String f = str(filter);
        String type = ("换工".equals(f) || "义工".equals(f)) ? "volunteer" : "兼职".equals(f) ? "parttime" : "全职".equals(f) ? "fulltime" : "";
        if (!f.isEmpty() && !"application".equals(f) && type.isEmpty()) throw new ServiceException("评价类型不合法", 422);
        params.put("type", type);
        return VISIBLE + (type.isEmpty() ? "" : " and j.job_type=:type");
    }

    public Map<String, Object> summary(Long storeId, String filter)
    {
        Map<String, Object> ps = new HashMap<>(); String where = scope(storeId, filter, ps);
        Map<String, Object> agg = db.one("select count(*) count, round(avg(v.rating),1) avg, sum(v.rating>=4) good, sum(v.rating<=2) bad, sum(" + MEDIA + ") media" + FROM + where, ps);
        if (agg == null) agg = new HashMap<>();
        long count = num(agg.get("count"));
        List<Map<String, Object>> dimensions = db.list("select v.dims" + FROM + where, ps);
        List<Map<String, Object>> mentions = new ArrayList<>();
        StringBuilder fields = new StringBuilder("select ");
        for (int i = 0; i < MENTIONS.size(); i++)
        {
            if (i > 0) fields.append(", ");
            fields.append("sum(v.content like :m").append(i).append(") m").append(i);
            ps.put("m" + i, "%" + MENTIONS.get(i) + "%");
        }
        Map<String, Object> hits = db.one(fields + FROM + where, ps);
        for (int i = 0; i < MENTIONS.size(); i++)
        {
            long n = hits == null ? 0 : num(hits.get("m" + i));
            if (n > 0) mentions.add(p("label", MENTIONS.get(i), "count", n));
        }
        mentions.sort((a, b) -> Long.compare(num(b.get("count")), num(a.get("count"))));
        return p("count", count, "avg", agg.get("avg"), "recommendRate", count == 0 ? null : Math.round(num(agg.get("good")) * 100.0 / count),
                "dims", KgJobReviewDimensions.aggregate(dimensions), "legacyDimensions", dimensions.stream().anyMatch(r -> KgJobReviewDimensions.legacy(r.get("dims"))),
                "mentions", mentions, "tabs", p("all", count, "media", num(agg.get("media")), "bad", num(agg.get("bad"))));
    }

    public Map<String, Object> list(Long storeId, String filter, String tab, int days, String sort, String mention, int pageNum)
    {
        String t = str(tab), order = str(sort), m = str(mention);
        if (!Arrays.asList("", "all", "media", "bad").contains(t) || !Arrays.asList("", "latest", "high", "low").contains(order)
                || !(days == 0 || days == 30 || days == 90 || days == 365) || pageNum < 1 || pageNum > 10000 || (!m.isEmpty() && !MENTIONS.contains(m)))
            throw new ServiceException("评价筛选参数不合法", 422);
        Map<String, Object> ps = new HashMap<>(); String where = scope(storeId, filter, ps);
        if ("media".equals(t)) where += " and " + MEDIA;
        if ("bad".equals(t)) where += " and v.rating<=2";
        if (days > 0) { where += " and v.create_time>=date_sub(now(),interval :days day)"; ps.put("days", days); }
        if (!m.isEmpty()) { where += " and v.content like :mention"; ps.put("mention", "%" + m + "%"); }
        long total = db.count("select count(*)" + FROM + where, ps);
        ps.put("offset", (pageNum - 1) * 10);
        String sorting = "high".equals(order) ? "v.rating desc, " : "low".equals(order) ? "v.rating asc, " : "";
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : db.list("select v.review_id, v.rating, v.dims, v.content, v.images, v.videos, v.video_covers, v.anonymous, v.reply, v.create_time, u.nickname, u.avatar, j.job_type, a.stay_days"
                + FROM + " left join kg_app_user u on u.user_id=v.app_user_id" + where + " order by " + sorting + "v.review_id desc limit :offset,10", ps))
        {
            boolean anonymous = "1".equals(str(row.get("anonymous")));
            String content = str(row.get("content"));
            List<String> tags = new ArrayList<>(); for (String word : MENTIONS) if (content.contains(word)) tags.add(word);
            List<String> urls = urls(row.get("videos")), covers = urls(row.get("videoCovers"));
            String type = str(row.get("jobType"));
            result.add(p("reviewId", row.get("reviewId"), "name", anonymous ? "匿名伙伴" : str(row.get("nickname")).isEmpty() ? "伙伴" : row.get("nickname"),
                    "avatar", anonymous ? "" : row.get("avatar"), "tag", "volunteer".equals(type) ? "换宿体验" : "parttime".equals(type) ? "兼职体验" : "岗位体验",
                    "date", str(row.get("createTime")).length() >= 7 ? str(row.get("createTime")).substring(0,7).replace('-', '.') : "",
                    "rating", row.get("rating"), "dims", KgJobReviewDimensions.aggregate(Collections.singletonList(row)), "content", content,
                    "images", urls(row.get("images")), "video", urls.isEmpty() ? null : p("url", urls.get(0), "cover", covers.isEmpty() ? "" : covers.get(0)),
                    "chips", tags, "reply", row.get("reply"), "stayDays", row.get("stayDays")));
        }
        return p("rows", result, "total", total, "hasMore", (long) pageNum * 10 < total);
    }
    private static List<String> urls(Object value)
    {
        List<String> out = new ArrayList<>();
        for (String s : str(value).split(",")) if (!s.trim().isEmpty() && !"[]".equals(s.trim())) out.add(s.trim());
        return out;
    }
}
