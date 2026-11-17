package com.ruoyi.kangaroo.ext.manage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;

/**
 * 经营模块的后台登记：周报（查看 / 退回）、日报明细与任务（只读）、分佣规则（增删改）、钱包流水（只读 + 人工调整）、
 * 收款账户（财务登记）、结算申请（审核 / 驳回 / 登记打款）、荣誉定义与授予、成长里程碑、生日礼物与领取（发货）、申报（处理）。
 */
@Component
public class KgManageModule implements ExtEntityContributor
{
    @Autowired
    private KgJdbc db;

    /** 结算的四眼原则（与 KgWalletService 同一开关）：开着时审批人不能登记打款 */
    @org.springframework.beans.factory.annotation.Value("${kangaroo.wallet.four-eyes:true}")
    private boolean fourEyes;

    /**
     * 分佣规则保存前校验（2026-09-28 复核发现的三个洞）：
     *  · 阶梯格式：写错一条规则会拖垮全平台的分佣入账，保存时就拦下并归一化；
     *  · 同一门店、同一来源同时只能有一条生效规则：重叠的规则会让同一笔订单给多个收款人各计一次；
     *  · 规则已经产生过流水后，收款人 / 门店 / 来源 / 生效日 / 费率 / 阶梯都不能再改，只能停用后新建。
     */
    private void checkRule(java.util.Map<String, Object> cols, Boolean creating)
    {
        Object tiers = cols.get("tiers");
        if (tiers != null)
        {
            cols.put("tiers", com.ruoyi.kangaroo.ext.manage.KgWalletService.normalizeTiers(String.valueOf(tiers)));
        }
        Object pk = cols.remove("_pk");
        if (!creating && pk != null)
        {
            long n = db.count("select count(*) from kg_wallet_entry where rule_id = :id", KgJdbc.p("id", pk));
            if (n > 0)
            {
                for (String k : new String[] { "payee_user_id", "store_id", "source_type", "effective_from", "rate_bp", "tiers" })
                {
                    if (cols.containsKey(k))
                    {
                        java.util.Map<String, Object> cur = db.one("select * from kg_commission_rule where rule_id = :id", KgJdbc.p("id", pk));
                        String a = String.valueOf(cols.get(k) == null ? "" : cols.get(k));
                        String b = String.valueOf(cur == null || cur.get(KgJdbc.toCamel(k)) == null ? "" : cur.get(KgJdbc.toCamel(k)));
                        if (b.length() >= 10 && "effective_from".equals(k))
                        {
                            b = b.substring(0, 10);
                        }
                        if (!a.equals(b) && !(("rate_bp".equals(k) || "tiers".equals(k)) && a.equals("0") && b.isEmpty()))
                        {
                            throw new ServiceException("这条规则已经产生过分佣流水，不能再改收款人、门店、来源、生效日和费率；请停用后新建一条", 409);
                        }
                    }
                }
            }
        }
        Object status = cols.get("status");
        boolean active = status == null || "0".equals(String.valueOf(status));
        if (active && cols.get("store_id") != null && cols.get("source_type") != null)
        {
            long dup = db.count("select count(*) from kg_commission_rule where store_id = :s and source_type = :t and status = '0' and del_flag = '0'"
                    + (pk != null ? " and rule_id <> :id" : ""), KgJdbc.p("s", cols.get("store_id"), "t", cols.get("source_type"), "id", pk == null ? 0L : pk));
            if (dup > 0)
            {
                throw new ServiceException("这家门店的这个来源已经有一条生效的分佣规则，请先停用旧规则再新建", 409);
            }
        }
    }

    /** 人工调整：只能是「调整」类型（入账 / 冲回由系统按订单生成，后台不能手工造）、金额不能为 0、必须写明原因、加钱的调整不能倒填到已过去的结算日 */
    private void checkAdjust(java.util.Map<String, Object> cols)
    {
        if (!"adjust".equals(String.valueOf(cols.get("entry_type"))) || !"adjust".equals(String.valueOf(cols.get("source_type"))))
        {
            throw new ServiceException("后台只能新增「人工调整」流水（类型和来源都选调整）；入账与冲回由系统按订单自动生成", 422);
        }
        Object amt = cols.get("amount_fen");
        if (amt == null || ((Number) amt).longValue() == 0L)
        {
            throw new ServiceException("调整金额不能为 0（扣减请填负数）", 422);
        }
        Object remark = cols.get("remark");
        if (remark == null || String.valueOf(remark).trim().isEmpty())
        {
            throw new ServiceException("人工调整必须填写说明（原因）", 422);
        }
        Object d = cols.get("available_date");
        if (((Number) amt).longValue() > 0 && d != null && java.time.LocalDate.parse(String.valueOf(d)).isBefore(java.time.LocalDate.now()))
        {
            throw new ServiceException("增加余额的调整不能倒填已经过去的可结算日期", 422);
        }
    }

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity weekly = new ExtEntity("weeklyreport", "周报", "kg_weekly_report", "report_id", "kangaroo:weeklyreport")
                .field(ExtField.of("reportType", "类型", "select").options("ops:代运营周报,recruit:换工招募情况周报").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("weekStart", "周一", "date").search("range"))
                .field(ExtField.of("status", "状态", "select").options("draft:草稿,returned:已退回,submitted:已提交").search("eq"))
                .field(ExtField.of("note", "员工补充说明", "textarea").hideInList())
                .field(ExtField.of("risk", "风险与问题", "textarea").hideInList())
                .field(ExtField.of("proofs", "凭证", "images").hideInList())
                .field(ExtField.of("snapshot", "系统汇总（提交时冻结）", "textarea").hideInList())
                .field(ExtField.of("returnReason", "退回原因", "text").hideInList())
                .field(ExtField.of("submittedName", "提交人", "text"))
                .field(ExtField.of("submittedTime", "提交时间", "datetime").search("range"))
                .readOnly()
                .hardDelete()
                .action("return", "退回", "put", "/kangaroo/manage/weekly/{reportId}/return", "kangaroo:weeklyreport:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("submitted")))
                .promptLabel("退回原因（会发到本店负责写周报的人的消息中心）")
                // 费用支出、待协同事项存在 kg_weekly_item 里，之前后台看不到；审核前先点这里看全
                .action("items", "费用与协同", "get", "/kangaroo/manage/weekly/{reportId}/review-context", "kangaroo:weeklyreport:query",
                        null, false, null);

        ExtEntity daily = new ExtEntity("dailyentry", "日报明细", "kg_daily_entry", "entry_id", "kangaroo:dailyentry")
                .field(ExtField.of("ledger", "账本", "select").options("stay:旅居经营,recruit:换工招募").search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("bizDate", "经营日期", "date").search("range"))
                .field(ExtField.of("direction", "方向", "select").options("income:收入,expense:支出").search("eq"))
                .field(ExtField.of("category", "分类", "text").search("eq"))
                .field(ExtField.of("amountFen", "金额", "money"))
                .field(ExtField.of("payMethod", "收付方式", "text"))
                .field(ExtField.of("remark", "说明", "text"))
                .field(ExtField.of("proofs", "凭证", "images").hideInList())
                .field(ExtField.of("status", "状态", "select").options("0:有效,1:作废").search("eq"))
                .field(ExtField.of("creatorName", "录入人", "text"))
                .field(ExtField.of("createTime", "录入时间", "datetime"))
                .readOnly()
                .hardDelete();

        ExtEntity task = new ExtEntity("task", "门店任务", "kg_task", "task_id", "kangaroo:task")
                .field(ExtField.of("storeId", "门店ID", "int").search("eq"))
                .field(ExtField.of("title", "标题", "text").search("like"))
                .field(ExtField.of("taskDate", "日期", "date").search("range"))
                .field(ExtField.of("startTime", "开始", "text"))
                .field(ExtField.of("endTime", "结束", "text"))
                .field(ExtField.of("assignMode", "分配方式", "select").options("staff:指定员工,volunteer:开放给换工").search("eq"))
                .field(ExtField.of("quota", "名额", "int"))
                .field(ExtField.of("status", "状态", "select").options("draft:草稿,published:已发布,cancelled:已取消").search("eq"))
                .field(ExtField.of("creatorName", "发布人", "text"))
                .field(ExtField.of("description", "说明", "textarea").hideInList())
                .field(ExtField.of("acceptRule", "验收标准", "textarea").hideInList())
                .readOnly()
                .hardDelete();

        ExtEntity assignee = new ExtEntity("taskassignee", "任务执行记录", "kg_task_assignee", "id", "kangaroo:taskassignee")
                .field(ExtField.of("taskId", "任务ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "执行人ID", "int").search("eq"))
                .field(ExtField.of("nickname", "执行人", "text"))
                .field(ExtField.of("source", "来源", "select").options("assigned:指定,claimed:认领"))
                .field(ExtField.of("status", "状态", "select").options("pending:待接受,accepted:进行中,done:已验收,cancelled:已取消").search("eq"))
                .field(ExtField.of("actualHours", "实际工时", "text"))
                .field(ExtField.of("rating", "评分", "int"))
                .field(ExtField.of("points", "积分", "int"))
                .field(ExtField.of("checkedName", "验收人", "text"))
                .field(ExtField.of("checkedTime", "验收时间", "datetime"))
                .readOnly()
                .hardDelete();

        ExtEntity rule = new ExtEntity("commission", "分佣规则", "kg_commission_rule", "rule_id", "kangaroo:commission")
                .field(ExtField.of("payeeUserId", "收款人（小程序用户ID）", "int").required().search("eq").ref("kg_app_user", "user_id"))
                .field(ExtField.of("storeId", "门店ID", "int").required().search("eq").ref("kg_store", "store_id"))
                .field(ExtField.of("sourceType", "计佣来源", "select").options("stay:房源营业额,shop:二消服务,activity:活动报名").required().search("eq"))
                .field(ExtField.of("rateBp", "固定费率（基点，200=2%）", "int").range(0L, 3000L))
                .field(ExtField.of("tiers", "阶梯（上限元:基点;… 最后一档上限写 0）", "text").max(300))
                .field(ExtField.of("effectiveFrom", "生效日期", "date").required())
                .field(ExtField.of("status", "状态", "select").options("0:生效,1:停用").search("eq"))
                .field(ExtField.of("remark", "说明（结算协议编号等）", "text").max(200))
                .validate((cols, creating) -> checkRule(cols, creating));

        ExtEntity entry = new ExtEntity("walletentry", "钱包流水", "kg_wallet_entry", "entry_id", "kangaroo:walletentry")
                .field(ExtField.of("appUserId", "收款人ID", "int").required().search("eq").ref("kg_app_user", "user_id"))
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").ref("kg_store", "store_id"))
                .field(ExtField.of("entryType", "类型", "select").options("credit:入账,reverse:退款冲回,adjust:人工调整").required().search("eq"))
                .field(ExtField.of("sourceType", "来源", "select").options("stay:营业,shop:二消,activity:活动,adjust:调整").required().search("eq"))
                .field(ExtField.of("bizNo", "单号", "text").required().max(64).search("like"))
                .field(ExtField.of("bizTitle", "摘要", "text").max(64))
                .field(ExtField.of("baseFen", "计佣基数", "money"))
                .field(ExtField.of("rateBp", "费率（基点）", "int"))
                .field(ExtField.of("amountFen", "金额（冲回 / 扣减为负）", "money").required().range(-100000000L, 100000000L))
                .field(ExtField.of("availableDate", "可结算日期", "date").required())
                .field(ExtField.of("remark", "说明（人工调整必须写明原因）", "text").max(200))
                .field(ExtField.of("createTime", "入账时间", "datetime").search("range").readOnly())
                .hardDelete()
                .noDelete()
                .validate((cols, creating) -> checkAdjust(cols));

        // 流水只能追加人工调整，不能改已有记录
        entry.allowEdit = false;

        ExtEntity payee = new ExtEntity("payee", "收款账户", "kg_wallet_payee", "payee_id", "kangaroo:payee")
                .field(ExtField.of("appUserId", "小程序用户ID", "int").required().search("eq").ref("kg_app_user", "user_id"))
                .field(ExtField.of("payeeName", "收款人姓名", "text").required().max(32).search("like"))
                .field(ExtField.of("bankName", "开户行 / 收款方式", "text").required().max(32))
                .field(ExtField.of("accountMasked", "账号（只填脱敏尾号，如 尾号 0001）", "text").required().max(32))
                .field(ExtField.of("remark", "结算协议说明", "text").max(200))
                .hardDelete()
                .noDelete();

        ExtEntity withdraw = new ExtEntity("withdraw", "结算申请", "kg_wallet_withdraw", "withdraw_id", "kangaroo:withdraw")
                .field(ExtField.of("withdrawNo", "申请单号", "text").search("eq"))
                .field(ExtField.of("appUserId", "申请人ID", "int").search("eq"))
                .field(ExtField.of("amountFen", "金额", "money"))
                .field(ExtField.of("payeeSnapshot", "收款账户", "text"))
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,approved:待打款,paid:已打款,rejected:已驳回").search("eq"))
                .field(ExtField.of("auditNote", "审核说明", "text").hideInList())
                // 列表显示审批人：审批与登记打款必须是两个后台账号（四眼原则），看一眼就知道这单该谁来登记打款
                .field(ExtField.of("auditedBy", "审批人", "text"))
                .field(ExtField.of("paidVoucher", "打款凭证", "text").hideInList())
                .field(ExtField.of("paidBy", "打款登记人", "text").hideInList())
                .field(ExtField.of("createTime", "申请时间", "datetime").search("range"))
                .readOnly()
                .hardDelete()
                .action("approve", "通过", "put", "/kangaroo/manage/withdraw/{withdrawId}/approve", "kangaroo:withdraw:audit",
                        fourEyes ? "确认通过这笔结算申请？通过后需线下打款，并由另一位有结算审核权限的后台账号登记打款凭证（审批人不能自己登记）"
                                : "确认通过这笔结算申请？通过后需线下打款并登记凭证",
                        false, Collections.singletonMap("status", Arrays.asList("pending")))
                // 只有待审核的能驳回：通过后财务可能已线下付款，驳回会让金额回到余额再提一次（后端同样拒绝，按钮不要再给）
                .action("reject", "驳回", "put", "/kangaroo/manage/withdraw/{withdrawId}/reject", "kangaroo:withdraw:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .promptLabel("驳回原因（会发到申请人的消息中心）")
                .action("paid", "登记打款", "put", "/kangaroo/manage/withdraw/{withdrawId}/paid", "kangaroo:withdraw:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("approved")))
                .promptLabel(fourEyes ? "打款凭证（银行流水号等）；须由审批人以外的后台账号登记" : "打款凭证（银行流水号等）")
                // 已通过、还没打款的可以退回（2026-11-09 主人拍板，如通过后订单退款冲回、余额不足）：仍记「已驳回」，不加状态
                .action("revoke", "退回", "put", "/kangaroo/manage/withdraw/{withdrawId}/revoke", "kangaroo:withdraw:audit",
                        "只在还没有线下打款时退回：退回后金额回到申请人的可结算余额，并通知本人。", true, Collections.singletonMap("status", Arrays.asList("approved")))
                .promptLabel("退回原因（会发到申请人的消息中心）");
        if (fourEyes)
        {
            // 「登记打款」对这单的审批人本人置灰并说明原因：后台通用列表（ExtCrud 的 notSelf）按行里的 auditedBy 与当前后台账号（admin:用户名）比较
            for (java.util.Map<String, Object> a : withdraw.actions)
            {
                if ("paid".equals(a.get("key")))
                {
                    a.put("notSelf", "auditedBy");
                    a.put("notSelfTip", "这笔申请是你审批通过的，请由另一位有结算审核权限的后台账号登记打款");
                }
            }
        }

        ExtEntity honor = new ExtEntity("honordef", "荣誉定义", "kg_honor_def", "honor_id", "kangaroo:honordef")
                .field(ExtField.of("name", "名称", "text").required().max(32).search("like"))
                .field(ExtField.of("badge", "徽章", "image"))
                .field(ExtField.of("basis", "评选依据", "text").max(200))
                .field(ExtField.of("ruleMetric", "解锁指标", "select").options("good_rate_90d:近90天好评率（基点）,activities_90d:近90天举办活动场次,revenue_month:本月营收（分）,tasks_done_90d:近90天验收完成任务人次"))
                .field(ExtField.of("ruleThreshold", "门槛", "int"))
                .field(ExtField.of("ruleText", "解锁条件文案", "text").max(100))
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .hardDelete()
                .noDelete();

        ExtEntity award = new ExtEntity("honoraward", "荣誉授予", "kg_honor_award", "award_id", "kangaroo:honoraward")
                .field(ExtField.of("appUserId", "获得者（小程序用户ID）", "int").required().search("eq"))
                .field(ExtField.of("honorId", "荣誉ID", "int").required().search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int"))
                .field(ExtField.of("awardedDate", "获得日期", "date").required())
                .field(ExtField.of("basis", "评选依据", "text").max(200));

        ExtEntity milestone = new ExtEntity("milestone", "成长里程碑", "kg_growth_milestone", "milestone_id", "kangaroo:milestone")
                .field(ExtField.of("appUserId", "主理人（小程序用户ID）", "int").required().search("eq"))
                .field(ExtField.of("storeId", "门店ID", "int"))
                .field(ExtField.of("happenedOn", "年月（yyyy.MM）", "text").required().max(7))
                .field(ExtField.of("title", "内容", "text").required().max(64))
                .field(ExtField.of("source", "来源", "select").options("system:系统写入,admin:后台补录").readOnly());

        ExtEntity gift = new ExtEntity("giftoption", "生日礼物", "kg_gift_option", "gift_id", "kangaroo:giftoption")
                .field(ExtField.of("name", "名称", "text").required().max(32))
                .field(ExtField.of("subtitle", "副标题", "text").max(32))
                .field(ExtField.of("image", "图片", "image"))
                .field(ExtField.of("stock", "库存", "int").range(0L, 99999L))
                .field(ExtField.of("status", "状态", "select").options("0:上架,1:下架").search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .hardDelete()
                .noDelete();

        ExtEntity claim = new ExtEntity("giftclaim", "礼物领取", "kg_gift_claim", "claim_id", "kangaroo:giftclaim")
                .field(ExtField.of("appUserId", "主理人ID", "int").search("eq"))
                .field(ExtField.of("giftName", "礼物", "text"))
                .field(ExtField.of("claimYear", "年份", "int").search("eq"))
                .field(ExtField.of("receiver", "收货人", "text"))
                .field(ExtField.of("phone", "手机号", "text").hideInList())
                .field(ExtField.of("address", "地址", "text").hideInList())
                .field(ExtField.of("status", "状态", "select").options("pending:待发货,shipped:已发货").search("eq"))
                .field(ExtField.of("expressNo", "快递单号", "text"))
                .field(ExtField.of("createTime", "领取时间", "datetime"))
                .readOnly()
                .hardDelete()
                .action("ship", "登记发货", "put", "/kangaroo/manage/giftclaim/{claimId}/ship", "kangaroo:giftclaim:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .promptLabel("快递公司与单号");

        ExtEntity appeal = new ExtEntity("appeal", "申报处理", "kg_appeal", "appeal_id", "kangaroo:appeal")
                .field(ExtField.of("appUserId", "申报人ID", "int").search("eq"))
                .field(ExtField.of("appealType", "事件类型", "text").search("eq"))
                .field(ExtField.of("bizLabel", "关联", "text"))
                .field(ExtField.of("content", "事情经过", "textarea").hideInList())
                .field(ExtField.of("proofs", "凭证", "images").hideInList())
                .field(ExtField.of("helpWanted", "希望获得的帮助", "text").hideInList())
                .field(ExtField.of("status", "状态", "select").options("pending:待处理,resolved:已处理,rejected:不予受理").search("eq"))
                .field(ExtField.of("reply", "处理结果", "textarea").hideInList())
                .field(ExtField.of("handledBy", "处理人", "text").hideInList())
                .field(ExtField.of("createTime", "申报时间", "datetime").search("range"))
                .readOnly()
                .hardDelete()
                .action("resolve", "处理完成", "put", "/kangaroo/manage/appeal/{appealId}/resolve", "kangaroo:appeal:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "不予受理", "put", "/kangaroo/manage/appeal/{appealId}/reject", "kangaroo:appeal:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")))
                // 两个动作都要写清楚这段话会原样发给谁：之前只有「不予受理」有说明，「处理完成」用的是通用的「请填写原因/说明」，
                // 可线上任务的收款 / 验收申诉处理完，这段话会以「平台处理结果：…」原样发给接单人和门店
                .promptLabel("resolve", "处理结果（会原样发到申报人的消息中心；线上任务的收款 / 验收申诉，门店也会收到这段话）")
                .promptLabel("reject", "不予受理的原因（会原样发到申报人的消息中心；线上任务的收款 / 验收申诉，门店也会收到这段话）");

        return Arrays.asList(weekly, daily, task, assignee, rule, entry, payee, withdraw, honor, award, milestone, gift, claim, appeal);
    }
}
