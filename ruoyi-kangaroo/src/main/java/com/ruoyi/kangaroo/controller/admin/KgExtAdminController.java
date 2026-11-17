package com.ruoyi.kangaroo.controller.admin;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.ExtEntities;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.pay.KgPayService;

/**
 * 后台通用增删改查：/kangaroo/ext/{实体键}/…
 *
 * 旅居、社区、经营等扩展模块里"只需要运营能查、能改、能下架"的表都走这里，
 * 由 ExtEntity 登记驱动；带资金或状态流转的动作（退款、打款登记、审核）各模块另有专门接口。
 * 权限：kangaroo:<实体>:list / query / add / edit / remove，由 extEntities.perm() 拼出，
 * 未登记的实体一律拒绝。字段只接受登记过且可编辑的，逐个按类型校验。
 */
@RestController
@RequestMapping("/kangaroo/ext")
public class KgExtAdminController extends BaseController
{
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private ExtEntities entities;

    @Autowired
    private KgJdbc db;

    /** 结算申请列表 / 详情标「余额不足」用；required = false：只用 mock 拼的单元测试里没有它 */
    @Autowired(required = false)
    private com.ruoyi.kangaroo.ext.manage.KgWalletService wallet;

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'list'))")
    @GetMapping("/{entity}/meta")
    public AjaxResult meta(@P("entity") @PathVariable String entity)
    {
        return AjaxResult.success(entities.require(entity).meta());
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'list'))")
    @GetMapping("/{entity}/list")
    public TableDataInfo list(@P("entity") @PathVariable String entity, @RequestParam Map<String, String> q)
    {
        ExtEntity e = entities.require(entity);
        Map<String, Object> ps = new HashMap<String, Object>();
        String where = filterWhere(e, q, ps);
        Integer pageNum = q.get("pageNum") == null ? 1 : Integer.valueOf(q.get("pageNum"));
        Integer pageSize = q.get("pageSize") == null ? 10 : Integer.valueOf(q.get("pageSize"));
        Map<String, Object> page = db.page("select " + e.selectCols + " from " + e.table + where + " order by " + e.orderBy,
                ps, pageNum, pageSize);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("rows");
        if (e.afterRead != null) rows.forEach(e.afterRead);
        if ("payorder".equals(e.key))
        {
            markRefunding(rows);
        }
        if ("withdraw".equals(e.key) && wallet != null)
        {
            // 已通过待打款、申请人真实余额为负的：标「余额不足：当前 -97.00」，登记打款先确认差额
            wallet.markShortfall(rows);
        }
        TableDataInfo t = new TableDataInfo(rows, ((Number) page.get("total")).longValue());
        t.setCode(200);
        t.setMsg("查询成功");
        return t;
    }

    /**
     * 支付单：微信退款是先登记、再由定时任务向微信发起，微信确认后支付单才变「已退款」。
     * 这期间列表还显示「已支付」、「全额退款」按钮也还在，再点只会收到「有尚未完成的退款」。
     * 给有在途退款的行标上说明并收起这个按钮（ExtCrud 认 extNote / extHiddenActions 两个键）。
     */
    private void markRefunding(List<Map<String, Object>> rows)
    {
        List<String> nos = new ArrayList<String>();
        for (Map<String, Object> r : rows)
        {
            if (r.get("payNo") != null)
            {
                nos.add(String.valueOf(r.get("payNo")));
            }
        }
        if (nos.isEmpty())
        {
            return;
        }
        Map<String, String> state = new HashMap<String, String>();
        // 只看还没办完的退款：被微信关闭的（closed，钱没退出）之前也收起了按钮，这笔支付就再也退不了（2026-10-13）
        for (Map<String, Object> r : db.list("select pay_no, status from kg_pay_refund where pay_no in (:nos) and " + KgPayService.ACTIVE_REFUND_SQL + " order by refund_seq",
                KgJdbc.p("nos", nos)))
        {
            state.put(String.valueOf(r.get("payNo")), String.valueOf(r.get("status")));
        }
        for (Map<String, Object> r : rows)
        {
            String st = state.get(String.valueOf(r.get("payNo")));
            if (st != null)
            {
                // abnormal：微信那边退款异常，要到微信支付商户平台人工处理（「退款进度」里有说明）
                r.put("extNote", "abnormal".equals(st) ? "退款异常" : "退款中");
                r.put("extHiddenActions", Arrays.asList("refund"));
            }
        }
    }

    private String filterWhere(ExtEntity e, Map<String, String> q, Map<String, Object> ps)
    {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if ("stay".equals(e.key) && ("direct".equals(q.get("operationGroup")) || "partner".equals(q.get("operationGroup")))) {
            where.append(" and exists (select 1 from kg_store og where og.store_id = kg_stay.store_id and coalesce(og.op_type,'direct') ")
                .append("direct".equals(q.get("operationGroup")) ? "= 'direct')" : "<> 'direct')");
        }
        if (e.softDelete)
        {
            where.append(" and del_flag = '0'");
        }
        if (e.baseWhere != null)
        {
            where.append(" and ").append(e.baseWhere);
        }
        for (ExtField f : e.fields)
        {
            if ("eq".equals(f.search) && notBlank(q.get(f.name)))
            {
                where.append(" and ").append(f.column).append(" = :").append(f.name);
                ps.put(f.name, q.get(f.name).trim());
            }
            else if ("like".equals(f.search) && notBlank(q.get(f.name)))
            {
                where.append(" and ").append(f.column).append(" like concat('%', :").append(f.name).append(", '%')");
                ps.put(f.name, q.get(f.name).trim());
            }
            else if ("range".equals(f.search))
            {
                String b = q.get(f.name + "Begin");
                String t = q.get(f.name + "End");
                if (notBlank(b))
                {
                    where.append(" and ").append(f.column).append(" >= :").append(f.name).append("Begin");
                    ps.put(f.name + "Begin", b.trim().length() == 10 ? b.trim() + " 00:00:00" : b.trim());
                }
                if (notBlank(t))
                {
                    where.append(" and ").append(f.column).append(" <= :").append(f.name).append("End");
                    ps.put(f.name + "End", t.trim().length() == 10 ? t.trim() + " 23:59:59" : t.trim());
                }
            }
        }
        return where.toString();
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'export'))")
    @Log(title = "线上任务表格导出", businessType = BusinessType.EXPORT)
    @PostMapping("/{entity}/export")
    public void export(@P("entity") @PathVariable String entity, @RequestParam Map<String, String> q,
            javax.servlet.http.HttpServletResponse response) throws java.io.IOException
    {
        if (!Arrays.asList("onlinesubmit", "onlinesettle").contains(entity)) throw new ServiceException("该页面不支持导出", 422);
        ExtEntity e = entities.require(entity);
        Map<String, Object> ps = new HashMap<String, Object>();
        String where = filterWhere(e, q, ps);
        // Fetch one extra row to detect the limit without silently truncating a financial export.
        List<Map<String, Object>> rows = db.list("select " + e.selectCols + " from " + e.table + where + " order by " + e.orderBy + " limit 10001", ps);
        if (rows.size() > 10000) throw new ServiceException("导出超过 10000 条，请缩小日期或状态范围后重试", 422);
        com.ruoyi.kangaroo.ext.OnlineTableExport.write(e, rows, response);
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'query'))")
    @GetMapping("/{entity}/{id}")
    public AjaxResult get(@P("entity") @PathVariable String entity, @PathVariable Long id)
    {
        ExtEntity e = entities.require(entity);
        Map<String, Object> row = db.must("select " + e.selectCols + " from " + e.table + " where " + e.pk + " = :id"
                + (e.softDelete ? " and del_flag = '0'" : ""), KgJdbc.p("id", id), "记录不存在或已删除");
        if (e.afterRead != null) e.afterRead.accept(row);
        if ("withdraw".equals(e.key) && wallet != null && row != null)
        {
            List<Map<String, Object>> one = new ArrayList<Map<String, Object>>();
            one.add(row);
            wallet.markShortfall(one);
        }
        return AjaxResult.success(row);
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'add'))")
    @Log(title = "扩展数据", businessType = BusinessType.INSERT)
    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/{entity}")
    public AjaxResult add(@P("entity") @PathVariable String entity, @RequestBody Map<String, Object> body)
    {
        ExtEntity e = entities.require(entity);
        if (!e.allowAdd)
        {
            throw new ServiceException(e.title + "不支持在后台新增");
        }
        Map<String, Object> cols = collect(e, body, true);
        runValidators(e, cols, true);
        cols.put("create_by", SecurityUtils.getUsername());
        cols.put("create_time", new Date());
        long newId = db.insert(e.table, cols);
        if (e.afterChange != null)
        {
            e.afterChange.accept(newId);
        }
        return AjaxResult.success(newId);
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'edit'))")
    @Log(title = "扩展数据", businessType = BusinessType.UPDATE)
    @Transactional(rollbackFor = Exception.class)
    @PutMapping("/{entity}")
    public AjaxResult edit(@P("entity") @PathVariable String entity, @RequestBody Map<String, Object> body)
    {
        ExtEntity e = entities.require(entity);
        if (!e.allowEdit)
        {
            throw new ServiceException(e.title + "不支持在后台修改");
        }
        Long id = KgJdbc.asLong(body.get(KgJdbc.toCamel(e.pk)));
        if (id == null)
        {
            throw new ServiceException("缺少主键");
        }
        Map<String, Object> cols = collect(e, body, false);
        cols.put("_pk", id);   // 给实体校验钩子用（校验里取走），不会写库
        runValidators(e, cols, false);
        cols.remove("_pk");
        cols.put("update_by", SecurityUtils.getUsername());
        cols.put("update_time", new Date());
        int n = db.updateRow(e.table, e.pk, id, cols);
        if (n == 0)
        {
            throw new ServiceException("记录不存在或已删除");
        }
        if (e.afterChange != null)
        {
            e.afterChange.accept(id);
        }
        return AjaxResult.success();
    }

    @PreAuthorize("@ss.hasPermi(@extEntities.perm(#entity, 'remove'))")
    @Log(title = "扩展数据", businessType = BusinessType.DELETE)
    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/{entity}/{ids}")
    public AjaxResult remove(@P("entity") @PathVariable String entity, @PathVariable Long[] ids)
    {
        ExtEntity e = entities.require(entity);
        if (!e.allowDelete)
        {
            throw new ServiceException(e.title + "不支持删除（有业务流水挂在上面），请改用状态下架");
        }
        int n = 0;
        if (e.beforeDelete != null)
        {
            // 先全部检查一遍再动手：批量删除时不能删一半才发现后面有订单
            for (Long id : ids)
            {
                e.beforeDelete.accept(id);
            }
        }
        for (Long id : ids)
        {
            int one = e.softDelete
                    ? db.update("update " + e.table + " set del_flag = '2', update_by = :u, update_time = now() where "
                            + e.pk + " = :id and del_flag = '0'", KgJdbc.p("id", id, "u", SecurityUtils.getUsername()))
                    : db.update("delete from " + e.table + " where " + e.pk + " = :id", KgJdbc.p("id", id));
            n += one;
            if (one > 0 && e.afterChange != null)
            {
                e.afterChange.accept(id);
            }
        }
        return toAjax(n);
    }

    // ------------------------------------------------------------------ 字段收集与校验

    private Map<String, Object> collect(ExtEntity e, Map<String, Object> body, boolean creating)
    {
        Map<String, Object> cols = new LinkedHashMap<String, Object>();
        for (ExtField f : e.fields)
        {
            if (!f.editable || (!creating && f.createOnly))
            {
                continue;
            }
            boolean present = body.containsKey(f.name);
            Object raw = body.get(f.name);
            boolean blank = raw == null || String.valueOf(raw).trim().isEmpty();
            if (f.required && blank && (creating || present))
            {
                throw new ServiceException(f.label + "不能为空");
            }
            if (!present)
            {
                continue;
            }
            if (blank)
            {
                // 留空的处理按列的真实定义来（2026-09-28 复核发现：此前一律写 NULL，写进 NOT NULL 列就是 500，
                // 固定费率分佣规则、收款账户、钱包人工调整都建不出来，原样保存房源也会失败）：
                //  · 新增：跳过，让列的默认值生效；
                //  · 修改：NOT NULL 的文本列写 ''、数字列写 0，NOT NULL 的日期列保持原值不动，可空的列才写 NULL。
                String info = e.colInfo.get(f.column.toLowerCase());
                boolean notNull = info != null && info.startsWith("!");
                if (creating)
                {
                    if (!notNull && info != null)
                    {
                        cols.put(f.column, null);
                    }
                    continue;
                }
                if (!notNull)
                {
                    cols.put(f.column, null);
                }
                else if (info.endsWith("S"))
                {
                    cols.put(f.column, "");
                }
                else if (info.endsWith("N"))
                {
                    cols.put(f.column, 0L);
                }
                continue;
            }
            cols.put(f.column, convert(f, raw));
        }
        return cols;
    }

    /** 实体级校验 + 引用存在性校验：校验不过一律 4xx 说明原因，不让错误数据进库、也不落成 500 */
    private void runValidators(ExtEntity e, Map<String, Object> cols, boolean creating)
    {
        for (ExtField f : e.fields)
        {
            Object v = cols.get(f.column);
            if (f.refTable != null && v != null && !(v instanceof String && ((String) v).isEmpty()))
            {
                if (db.count("select count(*) from " + f.refTable + " where " + f.refColumn + " = :v", KgJdbc.p("v", v)) == 0)
                {
                    throw new ServiceException(f.label + "不存在：" + v, 422);
                }
            }
        }
        if (e.validator != null)
        {
            e.validator.accept(cols, creating);
        }
        cols.remove("_pk");
    }

    private Object convert(ExtField f, Object raw)
    {
        String s = String.valueOf(raw).trim();
        switch (f.type)
        {
            case "int":
            case "money":
                Long v;
                try { v = new java.math.BigDecimal(s).longValueExact(); }
                catch (NumberFormatException | ArithmeticException ex) { throw new ServiceException(f.label + "必须填写有效整数", 422); }
                if (f.min != null && v < f.min || f.max != null && v > f.max)
                {
                    throw new ServiceException(f.label + "超出允许范围");
                }
                return v;
            case "date":
                try
                {
                    return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s, D);
                }
                catch (DateTimeParseException ex)
                {
                    throw new ServiceException(f.label + "日期格式应为 yyyy-MM-dd");
                }
            case "datetime":
                try
                {
                    return LocalDateTime.parse(s.replace('T', ' ').length() == 16 ? s.replace('T', ' ') + ":00" : s.replace('T', ' '), DT);
                }
                catch (DateTimeParseException ex)
                {
                    throw new ServiceException(f.label + "时间格式应为 yyyy-MM-dd HH:mm:ss");
                }
            case "switch":
                if (!"0".equals(s) && !"1".equals(s))
                {
                    throw new ServiceException(f.label + "取值不合法");
                }
                return s;
            case "select":
                checkOption(f, s);
                return s;
            default:
                if (s.length() > f.maxLen)
                {
                    throw new ServiceException(f.label + "不能超过 " + f.maxLen + " 个字");
                }
                return s;
        }
    }

    private void checkOption(ExtField f, String value)
    {
        List<String> allowed = new ArrayList<String>();
        if (f.options != null)
        {
            for (String kv : f.options.split(","))
            {
                allowed.add(kv.split(":")[0]);
            }
        }
        else if (f.dict != null)
        {
            for (Map<String, Object> r : db.list("select dict_value from sys_dict_data where dict_type = :t and status = '0'",
                    KgJdbc.p("t", f.dict)))
            {
                allowed.add(String.valueOf(r.get("dictValue")));
            }
        }
        if (!allowed.isEmpty() && !allowed.contains(value))
        {
            throw new ServiceException(f.label + "取值不合法：" + value + "（可选 " + Arrays.toString(allowed.toArray()) + "）");
        }
    }

    private static boolean notBlank(String s)
    {
        return s != null && !s.trim().isEmpty();
    }
}
