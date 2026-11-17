package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.enums.ApplicationStatus;
import com.ruoyi.kangaroo.enums.StaffRole;
import com.ruoyi.kangaroo.ext.KgJdbc;
import com.ruoyi.kangaroo.ext.user.KgTravelIdService;
import com.ruoyi.kangaroo.security.AppLoginUser;
import com.ruoyi.kangaroo.security.StaffGrantView;

/**
 * 到店名单导出（2026-11-03 主人：「投递简历多的当地主理人，可以一键选择时间，一键导出，所有投递简历面试的身份证号和姓名，还有手机号去哪家，开始时间，结束时间」）。
 *
 * 谁能导：按权限矩阵「岗位管理·导出」（StaffPerms jobs/export）—— 换工主理人、切换后的城市负责人（本城门店已由 CityLeadScope 展开进登录态）；
 *   小院主理人、合作商家、线上任务专员没有这一格。只导出他在登录态授权里、这一格为「有」的门店，客户端传的 storeId 只能在这些门店里挑。
 * 导什么：这些门店的到店岗位（不含线上任务）、还在进行中的报名（已提交 → 已入职；撤回、未通过、终止的不导），
 *   按「开始时间」落在所选日期里筛：开始 = 报名人填的到店日期（签约到店第 3 屏），没填就用报名时的可到岗日期；结束 = 开始 + 停留天数 − 1。
 * 证件号：读本人提交的出行证件（KgTravelIdService，V69）；没交的写「未提交」，密文解不开的写「需重新提交」。
 *   预览接口只给脱敏证件号和条数，完整证件号只出现在 Excel 里；每导一次，按门店各记一条员工操作日志（数据导出，含条数和日期范围）。
 */
@Service
public class KgApplicantExportService
{
    /** 进行中的报名：已提交到已入职 */
    static final List<String> ACTIVE = Arrays.asList(ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, ApplicationStatus.INTERVIEW_BOOKED,
            ApplicationStatus.INTERVIEW_DONE, ApplicationStatus.USER_CONFIRMED, ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED,
            ApplicationStatus.ONBOARDED);
    /** 「只要已录用」：门店确认录用之后 */
    static final List<String> HIRED = Arrays.asList(ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED, ApplicationStatus.ONBOARDED);
    /** 一次最多跨一年；一次最多 3000 行（再多就分段导） */
    static final int MAX_DAYS = 366;
    static final int MAX_ROWS = 3000;
    static final int PREVIEW_ROWS = 30;
    static final String NOT_SUBMITTED = "未提交";
    static final String RESUBMIT = "需重新提交";
    static final String[] HEADER = { "序号", "姓名", "身份证号", "手机号", "门店", "岗位", "开始时间", "结束时间", "天数", "报名状态" };

    @Autowired
    private KgJdbc db;

    @Autowired
    private StaffSupport support;

    @Autowired
    private KgTravelIdService travelIds;

    /** 一次导出的条件：日期范围、门店范围、是否只要已录用 */
    public static class Query
    {
        public LocalDate from;
        public LocalDate to;
        public List<Long> stores = new ArrayList<Long>();
        /** 页面上的门店列表：[{storeId, storeName}]，能导出的这几家 */
        public List<Map<String, Object>> storeList = new ArrayList<Map<String, Object>>();
        public boolean hiredOnly;
    }

    private static String roleOf(StaffGrantView g)
    {
        return StaffRole.exists(g.getRoleCode()) ? g.getRoleCode() : StaffRole.STAFF;
    }

    /** 能导出的门店（登录态授权里「岗位管理·导出」为有的）；storeId 不为空时只留这一家 */
    static List<StaffGrantView> exportable(AppLoginUser me, Long storeId)
    {
        List<StaffGrantView> out = new ArrayList<StaffGrantView>();
        List<Long> seen = new ArrayList<Long>();
        for (StaffGrantView g : me.getStaffGrants() == null ? new ArrayList<StaffGrantView>() : me.getStaffGrants())
        {
            if (g.getStoreId() == null || seen.contains(g.getStoreId()) || (storeId != null && !storeId.equals(g.getStoreId())))
            {
                continue;
            }
            if (RolePhase.usable(g.getRoleCode()) && StaffPerms.allows(roleOf(g), "jobs", StaffPerms.EXPORT))
            {
                out.add(g);
                seen.add(g.getStoreId());
            }
        }
        return out;
    }

    static LocalDate day(String s, String label)
    {
        if (s == null || s.trim().isEmpty())
        {
            throw new ServiceException("请选择" + label, 422);
        }
        try
        {
            return LocalDate.parse(s.trim().length() > 10 ? s.trim().substring(0, 10) : s.trim());
        }
        catch (DateTimeParseException e)
        {
            throw new ServiceException(label + "格式应为 YYYY-MM-DD", 422);
        }
    }

    /** 解析并把关：日期合法、跨度不超过一年；门店一家都导不了时记一条被拒日志并 403 */
    public Query query(AppLoginUser me, Long storeId, String from, String to, String scope)
    {
        Query q = new Query();
        q.from = day(from, "开始日期");
        q.to = day(to, "结束日期");
        if (q.to.isBefore(q.from))
        {
            throw new ServiceException("结束日期不能早于开始日期", 422);
        }
        if (ChronoUnit.DAYS.between(q.from, q.to) + 1 > MAX_DAYS)
        {
            throw new ServiceException("一次最多导出一年的名单，请缩小日期范围", 422);
        }
        if (scope != null && !scope.isEmpty() && !"active".equals(scope) && !"hired".equals(scope))
        {
            throw new ServiceException("名单范围无效", 422);
        }
        q.hiredOnly = "hired".equals(scope);
        List<StaffGrantView> grants = exportable(me, storeId);
        if (grants.isEmpty())
        {
            // 记一条被拒的导出日志（记在他点的那家或第一家授权门店上），再按矩阵给出具体缺哪一格
            StaffSupport.Ctx c = support.ctx(storeId);
            support.need(c, "jobs", StaffPerms.EXPORT, "applicant_export", "导出到店名单");
            throw new ServiceException(storeId == null ? "你在已授权的门店里都没有导出名单的权限" : "没有这家门店的名单导出权限", HttpStatus.FORBIDDEN);
        }
        for (StaffGrantView g : grants)
        {
            q.stores.add(g.getStoreId());
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("storeId", g.getStoreId());
            s.put("storeName", g.getStoreName());
            q.storeList.add(s);
        }
        return q;
    }

    /** 名单行（完整数据，只在本类里用）：按开始时间、门店、报名先后排 */
    List<Map<String, Object>> rows(Query q)
    {
        return db.list("select a.app_id, a.app_user_id, a.store_id, a.status, a.real_name, a.phone, a.stay_days, j.title job_title, s.store_name, u.phone user_phone, "
                + "date_format(coalesce(ar.arrive_date, a.arrival_date), '%Y-%m-%d') start_day, t.id_name, t.id_no_enc "
                + "from kg_application a join kg_job j on j.job_id = a.job_id left join kg_store s on s.store_id = a.store_id "
                + "left join kg_app_arrival ar on ar.app_id = a.app_id left join kg_app_user u on u.user_id = a.app_user_id "
                + "left join kg_user_travel_id t on t.user_id = a.app_user_id "
                + "where a.del_flag = '0' and a.store_id in (:ids) and coalesce(j.job_type, '') <> 'online' and a.status in (:st) "
                + "and coalesce(ar.arrive_date, a.arrival_date) >= :f and coalesce(ar.arrive_date, a.arrival_date) < :t "
                + "order by start_day, s.store_name, a.app_id limit " + (MAX_ROWS + 1),
                p("ids", q.stores, "st", q.hiredOnly ? HIRED : ACTIVE, "f", q.from.toString(), "t", q.to.plusDays(1).toString()));
    }

    static String str(Object v)
    {
        String s = KgJdbc.asStr(v);
        return s == null ? "" : s.trim();
    }

    /** 结束日 = 开始 + 停留天数 − 1（和保险、换工日报同一口径）；天数不合理时不写 */
    static String endDay(String start, Object stayDays)
    {
        Long d = KgJdbc.asLong(stayDays);
        if (start.isEmpty() || d == null || d < 1 || d > 3650)
        {
            return "";
        }
        try
        {
            return LocalDate.parse(start).plusDays(d - 1).toString();
        }
        catch (DateTimeParseException e)
        {
            return "";
        }
    }

    /** 一行名单：full = true 时证件号是完整的（只给 Excel），否则脱敏 */
    Map<String, Object> line(Map<String, Object> r, boolean full)
    {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String cipher = str(r.get("idNoEnc"));
        String id = cipher.isEmpty() ? null : travelIds.reveal(cipher);
        String idText = cipher.isEmpty() ? NOT_SUBMITTED : id == null ? RESUBMIT : full ? id : KgTravelIdService.maskId(id, null);
        String name = id != null && !str(r.get("idName")).isEmpty() ? str(r.get("idName")) : str(r.get("realName"));
        String phone = str(r.get("phone")).isEmpty() ? str(r.get("userPhone")) : str(r.get("phone"));
        String start = str(r.get("startDay"));
        m.put("appId", r.get("appId"));
        m.put("name", name);
        m.put("idNo", idText);
        m.put("idSubmitted", id != null);
        m.put("phone", full ? phone : com.ruoyi.kangaroo.util.MaskUtils.phone(phone));
        m.put("storeName", str(r.get("storeName")));
        m.put("jobTitle", str(r.get("jobTitle")));
        m.put("start", start);
        m.put("end", endDay(start, r.get("stayDays")));
        Long days = KgJdbc.asLong(r.get("stayDays"));
        m.put("days", days == null || days < 1 ? "" : String.valueOf(days));
        m.put("status", ApplicationStatus.label(str(r.get("status"))));
        m.put("storeId", r.get("storeId"));
        return m;
    }

    /** 预览：条数、没交证件的人数、前 30 行（证件号、手机号都脱敏）。不算导出，不记日志 */
    public Map<String, Object> preview(Query q)
    {
        List<Map<String, Object>> raw = rows(q);
        boolean more = raw.size() > MAX_ROWS;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        int missing = 0;
        for (int i = 0; i < raw.size() && i < MAX_ROWS; i++)
        {
            Map<String, Object> m = line(raw.get(i), false);
            if (!Boolean.TRUE.equals(m.get("idSubmitted")))
            {
                missing++;
            }
            if (list.size() < PREVIEW_ROWS)
            {
                m.remove("storeId");
                list.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("from", q.from.toString());
        out.put("to", q.to.toString());
        out.put("count", Math.min(raw.size(), MAX_ROWS));
        out.put("tooMany", more);
        out.put("missingId", missing);
        out.put("stores", q.storeList);
        out.put("rows", list);
        return out;
    }

    /** 生成 Excel，并按门店各记一条导出日志（记在被导出的那家门店上，各店的操作日志里看得到） */
    public byte[] export(AppLoginUser me, Query q) throws Exception
    {
        List<Map<String, Object>> raw = rows(q);
        if (raw.size() > MAX_ROWS)
        {
            throw new ServiceException("这段时间超过 " + MAX_ROWS + " 人，请缩小日期范围分几次导出", 422);
        }
        List<Map<String, Object>> lines = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> r : raw)
        {
            lines.add(line(r, true));
        }
        byte[] bytes = workbook(lines, q);
        String range = q.from + " 至 " + q.to + (q.hiredOnly ? "（已录用）" : "");
        for (Long sid : q.stores)
        {
            int n = 0;
            int missing = 0;
            for (Map<String, Object> m : lines)
            {
                if (sid.equals(KgJdbc.asLong(m.get("storeId"))))
                {
                    n++;
                    if (!Boolean.TRUE.equals(m.get("idSubmitted")))
                    {
                        missing++;
                    }
                }
            }
            support.op(support.ctx(sid), "applicant_export", "导出到店名单", "export", "到店名单 " + range + "，" + n + " 人（含证件号，" + missing + " 人未提交）", true, null);
        }
        return bytes;
    }

    static byte[] workbook(List<Map<String, Object>> lines, Query q) throws Exception
    {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet sh = wb.createSheet("到店名单");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            int r = 0;
            Row title = sh.createRow(r++);
            title.createCell(0).setCellValue("到店名单 " + q.from + " 至 " + q.to + (q.hiredOnly ? "（只含已录用）" : "（进行中的报名）"));
            title.getCell(0).setCellStyle(bold);
            sh.createRow(r++).createCell(0).setCellValue("按开始时间筛选：开始 = 报名人填的到店日期，没填用报名时的可到岗日期；结束 = 开始 + 停留天数 − 1。含身份证号，仅用于订票、投保，请勿外传");
            Row head = sh.createRow(r++);
            for (int c = 0; c < HEADER.length; c++)
            {
                head.createCell(c).setCellValue(HEADER[c]);
                head.getCell(c).setCellStyle(bold);
            }
            int no = 1;
            for (Map<String, Object> m : lines)
            {
                Row row = sh.createRow(r++);
                String[] v = { String.valueOf(no++), str(m.get("name")), str(m.get("idNo")), str(m.get("phone")), str(m.get("storeName")), str(m.get("jobTitle")),
                        str(m.get("start")), str(m.get("end")), str(m.get("days")), str(m.get("status")) };
                for (int c = 0; c < v.length; c++)
                {
                    // 一律按文本写：身份证号、手机号不会被 Excel 变成科学计数法，以 = 开头的内容也不会被当成公式
                    row.createCell(c).setCellValue(v[c]);
                }
            }
            int[] width = { 6, 10, 22, 15, 18, 20, 12, 12, 6, 12 };
            for (int c = 0; c < width.length; c++)
            {
                sh.setColumnWidth(c, width[c] * 256);
            }
            sh.createFreezePane(0, 3);
            wb.write(out);
            return out.toByteArray();
        }
    }
}
