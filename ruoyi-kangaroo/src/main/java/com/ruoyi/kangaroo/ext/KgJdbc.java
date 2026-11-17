package com.ruoyi.kangaroo.ext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;

/**
 * 扩展模块（旅居 / 社区 / 经营 / 员工端扩展 / 用户端扩展）的数据访问助手。
 *
 * 为什么不按老模块那样每张表一套 domain + mapper XML：扩展模块有几十张表，大多是"按页面要的字段查出来给前端"，
 * 逐表写实体类与 XML 会把代码量翻几倍，而不带来任何行为上的差别。这里统一用命名参数 SQL：
 *   · 查询结果的列名转成驼峰（room_id → roomId），日期统一成 'yyyy-MM-dd HH:mm:ss' / 'yyyy-MM-dd' 字符串
 *     —— 小程序端的日期解析都按这个格式写，驱动返回 LocalDateTime 时 Jackson 会输出带 T 的 ISO 串；
 *   · 金额一律以"分"为单位存 bigint，结果里就是 Long；
 *   · 和 MyBatis 用同一个数据源（主数据源），@Transactional 下两边在同一个事务里。
 * 表名与列名只能来自代码里的常量（登记表 ExtEntity 或各服务里的 SQL 字面量），绝不拼接用户输入。
 */
@Component
public class KgJdbc
{
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final NamedParameterJdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.ruoyi.kangaroo.security.admin.AdminCitySqlGuard adminCityGuard;
    private String scoped(String sql, org.springframework.jdbc.core.namedparam.SqlParameterSource params) {
        if (!com.ruoyi.kangaroo.security.admin.AdminCityContext.limited()) return sql;
        if (adminCityGuard == null) throw new ServiceException("后台城市权限组件未就绪",403);
        return adminCityGuard.sql(sql, e -> params.getValue(((net.sf.jsqlparser.expression.JdbcNamedParameter)e).getName()));
    }
    private String scoped(String sql, Map<String,?> params) {
        return scoped(sql, new MapSqlParameterSource(params == null ? new HashMap<String,Object>() : params));
    }

    public KgJdbc(DataSource dataSource)
    {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /** 参数构造：p("a", 1, "b", "x") */
    public static Map<String, Object> p(Object... kv)
    {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public List<Map<String, Object>> list(String sql, Map<String, ?> params)
    {
        List<Map<String, Object>> raw = jdbc.queryForList(scoped(sql, params), new MapSqlParameterSource(params == null ? new HashMap<String, Object>() : params));
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(raw.size());
        for (Map<String, Object> r : raw)
        {
            out.add(camel(r));
        }
        return out;
    }

    /** 查一行；没有返回 null */
    public Map<String, Object> one(String sql, Map<String, ?> params)
    {
        List<Map<String, Object>> rows = list(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查一行，没有就抛业务异常（接口返回 500 + 这句提示） */
    public Map<String, Object> must(String sql, Map<String, ?> params, String notFound)
    {
        Map<String, Object> r = one(sql, params);
        if (r == null)
        {
            throw new ServiceException(notFound);
        }
        return r;
    }

    public long count(String sql, Map<String, ?> params)
    {
        Number n = jdbc.queryForObject(scoped(sql, params), new MapSqlParameterSource(params == null ? new HashMap<String, Object>() : params), Number.class);
        return n == null ? 0L : n.longValue();
    }

    /**
     * 分页：sql 不带 limit；返回 {rows, total, hasMore, pageNum, pageSize}。
     * pageSize 收敛到 [1, 50]，pageNum 最小 1。
     */
    public Map<String, Object> page(String sql, Map<String, ?> params, Integer pageNum, Integer pageSize)
    {
        int size = pageSize == null ? 10 : Math.max(1, Math.min(50, pageSize));
        // 页码设上限：(页码 - 1) * 每页条数 在 int 里会溢出成负数，LIMIT 偏移为负就是 500（2026-09-28 复核发现）
        int num = pageNum == null ? 1 : Math.max(1, Math.min(100000, pageNum));
        Map<String, Object> ps = new HashMap<String, Object>();
        if (params != null)
        {
            ps.putAll(params);
        }
        long total = count("select count(1) from (" + sql + ") kg_page_t", ps);
        ps.put("kgOffset", (num - 1) * size);
        ps.put("kgLimit", size);
        List<Map<String, Object>> rows = list(sql + " limit :kgOffset, :kgLimit", ps);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("hasMore", (long) num * size < total);
        out.put("pageNum", num);
        out.put("pageSize", size);
        return out;
    }

    public int update(String sql, Map<String, ?> params)
    {
        return jdbc.update(scoped(sql, params), new MapSqlParameterSource(params == null ? new HashMap<String, Object>() : params));
    }

    /**
     * 在当前事务里占一个幂等键（kg_idempotency.uk_idem_key）。返回 false = 同一个键已被占（可能是并发的同一次提交，
     * 本方法会一直等到对方事务结束才返回）。调用方随后要用**加锁读**（for share / for update）查先到那次的结果：
     * 可重复读隔离级别下普通 select 读的是本事务的快照，看不到对方刚提交的行。
     * 键和业务写入在同一个事务里：业务失败回滚时键一起消失，不会留下挡住重试的孤儿键。
     */
    public boolean claimIdem(String key, String bizType)
    {
        try
        {
            update("insert into kg_idempotency (idem_key, biz_type, create_time) values (:k, :t, now())", p("k", key, "t", bizType));
            return true;
        }
        catch (org.springframework.dao.DuplicateKeyException e)
        {
            return false;
        }
        catch (org.springframework.dao.PessimisticLockingFailureException e)
        {
            // 三个以上同键请求排队、而先到的那个回滚时，MySQL 会把其中一个等待者判为死锁牺牲者（1213）。
            // 它的事务已被数据库回滚；如实告诉客户端"正在提交"，别抛成 500
            throw new ServiceException("这笔订单正在提交，请稍后在「我的订单」查看", 409);
        }
    }

    /**
     * 平台自己存储的图片路径（上传接口返回的 /profile/… 或随包的 /kangaroo/…）。
     * 只允许路径字符：原先用 startsWith("/profile/") 判断，「/profile/a.png,https://外链」这样带逗号的一串也能通过，
     * 而图片列表是用逗号拼接存储的，取出来时就成了一条外链（追踪像素 / 钓鱼图）。
     */
    public static boolean isPlatformImage(String s)
    {
        return s != null && s.length() < 255 && !s.contains("..")
                && (s.matches("/profile/[A-Za-z0-9_./-]+") || s.matches("/kangaroo/[A-Za-z0-9_./-]+") || s.matches("/static/media/[A-Za-z0-9_./-]+"));
    }

    /**
     * 是不是真实存在的 yyyy-MM-dd 日期。2026-02-30 这类只满足「四位-两位-两位」形状的假日期，
     * 用正则放行之后会在 LocalDate.parse 或入库时变成 500（游客接口也会）；统一用这个判断。
     */
    public static boolean isDate(String s)
    {
        if (s == null || s.length() != 10)
        {
            return false;
        }
        try
        {
            java.time.LocalDate.parse(s);
            return true;
        }
        catch (java.time.format.DateTimeParseException e)
        {
            return false;
        }
    }

    /** 插入一行，返回自增主键。cols 的键是列名（下划线），来自代码常量。 */
    public long insert(String table, Map<String, Object> cols)
    {
        StringBuilder names = new StringBuilder();
        StringBuilder values = new StringBuilder();
        MapSqlParameterSource ps = new MapSqlParameterSource();
        int i = 0;
        for (Map.Entry<String, Object> e : cols.entrySet())
        {
            if (i > 0)
            {
                names.append(", ");
                values.append(", ");
            }
            names.append(e.getKey());
            values.append(":c").append(i);
            ps.addValue("c" + i, e.getValue());
            i++;
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(scoped("insert into " + table + " (" + names + ") values (" + values + ")", ps), ps, kh);
        Number key = kh.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 按主键更新若干列；返回影响行数 */
    public int updateRow(String table, String pkCol, Object pk, Map<String, Object> cols)
    {
        if (cols.isEmpty())
        {
            return 0;
        }
        StringBuilder set = new StringBuilder();
        MapSqlParameterSource ps = new MapSqlParameterSource();
        int i = 0;
        for (Map.Entry<String, Object> e : cols.entrySet())
        {
            if (i > 0)
            {
                set.append(", ");
            }
            set.append(e.getKey()).append(" = :c").append(i);
            ps.addValue("c" + i, e.getValue());
            i++;
        }
        ps.addValue("kgPk", pk);
        return jdbc.update(scoped("update " + table + " set " + set + " where " + pkCol + " = :kgPk", ps), ps);
    }

    // ------------------------------------------------------------------ 工具

    public static Map<String, Object> camel(Map<String, Object> row)
    {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : row.entrySet())
        {
            out.put(toCamel(e.getKey()), normalize(e.getValue()));
        }
        return out;
    }

    public static String toCamel(String col)
    {
        if (col == null || col.indexOf('_') < 0)
        {
            return col;
        }
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : col.toCharArray())
        {
            if (c == '_')
            {
                up = true;
            }
            else
            {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    public static String toSnake(String camel)
    {
        StringBuilder sb = new StringBuilder();
        for (char c : camel.toCharArray())
        {
            if (Character.isUpperCase(c))
            {
                sb.append('_').append(Character.toLowerCase(c));
            }
            else
            {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Object normalize(Object v)
    {
        if (v instanceof LocalDateTime)
        {
            return ((LocalDateTime) v).format(DT);
        }
        if (v instanceof Timestamp)
        {
            return ((Timestamp) v).toLocalDateTime().format(DT);
        }
        if (v instanceof LocalDate)
        {
            return ((LocalDate) v).format(D);
        }
        if (v instanceof java.sql.Date)
        {
            return ((java.sql.Date) v).toLocalDate().format(D);
        }
        if (v instanceof Date)
        {
            return new Timestamp(((Date) v).getTime()).toLocalDateTime().format(DT);
        }
        if (v instanceof BigDecimal)
        {
            BigDecimal b = (BigDecimal) v;
            return b.scale() <= 0 ? (Object) b.longValue() : b;
        }
        return v;
    }

    /** 取 Long（数据库里的数字列、前端传的字符串数字都认） */
    public static Long asLong(Object v)
    {
        if (v == null || "".equals(v))
        {
            return null;
        }
        if (v instanceof Number)
        {
            return ((Number) v).longValue();
        }
        try
        {
            return Long.valueOf(String.valueOf(v).trim());
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("数字格式不正确：" + v);
        }
    }

    public static String asStr(Object v)
    {
        return v == null ? null : String.valueOf(v);
    }
}
