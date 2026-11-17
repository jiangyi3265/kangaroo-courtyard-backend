package com.ruoyi.kangaroo.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.constant.HttpStatus;

/**
 * 报名申请状态机。
 *
 * 这是报名履约链路的唯一权威定义：任何状态变更都必须经过 {@link #assertCanTransfer}，
 * 非法流转一律拒绝。禁止在 Service 里直接 setStatus 后 update —— 否则可以通过
 * 任意改状态字段跳过审核、面试、双向确认直接到"已入职"。
 *
 * 流转图（括号内为允许发起该流转的操作方）：
 *
 *   submitted 已提交
 *     ├─ review_passed    审核通过        (staff 门店员工 / admin 后台)
 *     ├─ review_rejected  审核未通过      (staff / admin)
 *     └─ withdrawn        用户撤回        (user 本人)
 *
 *   review_passed 审核通过
 *     ├─ interview_booked 已预约面试      (user 本人选择时段)
 *     └─ withdrawn        用户撤回        (user 本人)
 *
 *   interview_booked 已预约面试
 *     ├─ interview_done   面试已完成      (user / staff / system)
 *     ├─ withdrawn        用户撤回        (user)
 *     └─ terminated       已终止          (staff / admin / system 超时)
 *
 *   interview_done 面试已完成
 *     ├─ user_confirmed   用户确认愿意前往 (user)
 *     └─ terminated       已终止（用户表示不合适 / 商家终止 / 超时）(user / staff / admin / system)
 *
 *   user_confirmed 用户已确认
 *     ├─ store_confirmed  商家确认录用    (staff / admin)
 *     └─ terminated       已终止（商家拒绝或 24 小时逾期未确认）(staff / admin / system)
 *
 *   store_confirmed 商家已确认
 *     ├─ contract_signed  已签署责任书    (user 本人签署)
 *     └─ terminated       已终止          (staff / admin)
 *
 *   contract_signed 已签署
 *     ├─ onboarded        已入职          (staff / admin)
 *     └─ terminated       已终止          (staff / admin)
 *
 *   onboarded / review_rejected / withdrawn / terminated 为终态，不可再流转。
 */
public final class ApplicationStatus
{
    /** 已提交，等待商家审核 */
    public static final String SUBMITTED = "submitted";
    /** 审核通过，等待用户选择面试时段 */
    public static final String REVIEW_PASSED = "review_passed";
    /** 审核未通过（终态） */
    public static final String REVIEW_REJECTED = "review_rejected";
    /** 用户撤回（终态） */
    public static final String WITHDRAWN = "withdrawn";
    /** 已预约面试 */
    public static final String INTERVIEW_BOOKED = "interview_booked";
    /** 面试已完成，等待用户表态 */
    public static final String INTERVIEW_DONE = "interview_done";
    /** 用户已确认愿意前往，等待商家确认 */
    public static final String USER_CONFIRMED = "user_confirmed";
    /** 商家已确认录用，等待签署责任书 */
    public static final String STORE_CONFIRMED = "store_confirmed";
    /** 已签署责任书 */
    public static final String CONTRACT_SIGNED = "contract_signed";
    /** 已入职（终态） */
    public static final String ONBOARDED = "onboarded";
    /** 已终止（终态） */
    public static final String TERMINATED = "terminated";

    /** 允许的状态流转：from -> 允许到达的 to 集合 */
    private static final Map<String, Set<String>> TRANSFERS;

    /** 每条流转允许的发起方："from>to" -> 允许的 operatorType 集合 */
    private static final Map<String, Set<String>> ACTORS;

    /** 中文名，用于消息文案与错误提示 */
    private static final Map<String, String> LABELS;

    static
    {
        Map<String, Set<String>> t = new HashMap<String, Set<String>>();
        t.put(SUBMITTED, setOf(REVIEW_PASSED, REVIEW_REJECTED, WITHDRAWN));
        // 审核通过后用户迟迟不约面试时，门店需要一个出口（否则这条报名永远挂在待约面试里）
        t.put(REVIEW_PASSED, setOf(INTERVIEW_BOOKED, WITHDRAWN, TERMINATED));
        t.put(INTERVIEW_BOOKED, setOf(INTERVIEW_DONE, WITHDRAWN, TERMINATED));
        t.put(INTERVIEW_DONE, setOf(USER_CONFIRMED, TERMINATED));
        t.put(USER_CONFIRMED, setOf(STORE_CONFIRMED, TERMINATED));
        t.put(STORE_CONFIRMED, setOf(CONTRACT_SIGNED, TERMINATED));
        t.put(CONTRACT_SIGNED, setOf(ONBOARDED, TERMINATED));
        t.put(REVIEW_REJECTED, Collections.<String>emptySet());
        t.put(WITHDRAWN, Collections.<String>emptySet());
        t.put(ONBOARDED, Collections.<String>emptySet());
        t.put(TERMINATED, Collections.<String>emptySet());
        TRANSFERS = Collections.unmodifiableMap(t);

        Map<String, Set<String>> a = new HashMap<String, Set<String>>();
        a.put(key(SUBMITTED, REVIEW_PASSED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        a.put(key(SUBMITTED, REVIEW_REJECTED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        a.put(key(SUBMITTED, WITHDRAWN), setOf(OperatorType.USER));
        a.put(key(REVIEW_PASSED, INTERVIEW_BOOKED), setOf(OperatorType.USER));
        a.put(key(REVIEW_PASSED, WITHDRAWN), setOf(OperatorType.USER));
        a.put(key(REVIEW_PASSED, TERMINATED), setOf(OperatorType.STAFF, OperatorType.ADMIN, OperatorType.SYSTEM));
        a.put(key(INTERVIEW_BOOKED, INTERVIEW_DONE), setOf(OperatorType.USER, OperatorType.STAFF, OperatorType.SYSTEM));
        a.put(key(INTERVIEW_BOOKED, WITHDRAWN), setOf(OperatorType.USER));
        a.put(key(INTERVIEW_BOOKED, TERMINATED), setOf(OperatorType.STAFF, OperatorType.ADMIN, OperatorType.SYSTEM));
        a.put(key(INTERVIEW_DONE, USER_CONFIRMED), setOf(OperatorType.USER));
        a.put(key(INTERVIEW_DONE, TERMINATED), setOf(OperatorType.USER, OperatorType.STAFF, OperatorType.ADMIN, OperatorType.SYSTEM));
        a.put(key(USER_CONFIRMED, STORE_CONFIRMED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        a.put(key(USER_CONFIRMED, TERMINATED), setOf(OperatorType.STAFF, OperatorType.ADMIN, OperatorType.SYSTEM));
        a.put(key(STORE_CONFIRMED, CONTRACT_SIGNED), setOf(OperatorType.USER));
        a.put(key(STORE_CONFIRMED, TERMINATED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        a.put(key(CONTRACT_SIGNED, ONBOARDED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        a.put(key(CONTRACT_SIGNED, TERMINATED), setOf(OperatorType.STAFF, OperatorType.ADMIN));
        ACTORS = Collections.unmodifiableMap(a);

        Map<String, String> l = new HashMap<String, String>();
        l.put(SUBMITTED, "已提交");
        l.put(REVIEW_PASSED, "审核通过");
        l.put(REVIEW_REJECTED, "审核未通过");
        l.put(WITHDRAWN, "已撤回");
        l.put(INTERVIEW_BOOKED, "已预约面试");
        l.put(INTERVIEW_DONE, "面试已完成");
        l.put(USER_CONFIRMED, "用户已确认");
        l.put(STORE_CONFIRMED, "商家已确认");
        l.put(CONTRACT_SIGNED, "已签署责任书");
        l.put(ONBOARDED, "已入职");
        l.put(TERMINATED, "已终止");
        LABELS = Collections.unmodifiableMap(l);
    }

    private ApplicationStatus()
    {
    }

    private static Set<String> setOf(String... values)
    {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    private static String key(String from, String to)
    {
        return from + ">" + to;
    }

    /** 状态是否存在 */
    public static boolean exists(String status)
    {
        return status != null && TRANSFERS.containsKey(status);
    }

    /** 是否终态 */
    public static boolean isFinal(String status)
    {
        Set<String> next = TRANSFERS.get(status);
        return next != null && next.isEmpty();
    }

    /** 中文名 */
    public static String label(String status)
    {
        String l = LABELS.get(status);
        return l == null ? String.valueOf(status) : l;
    }

    /** 该流转是否合法（不考虑操作方） */
    public static boolean canTransfer(String from, String to)
    {
        Set<String> next = TRANSFERS.get(from);
        return next != null && next.contains(to);
    }

    /** 该操作方是否被允许发起该流转 */
    public static boolean allows(String from, String to, String operatorType)
    {
        if (!canTransfer(from, to))
        {
            return false;
        }
        Set<String> actors = ACTORS.get(key(from, to));
        return actors != null && actors.contains(operatorType);
    }

    /**
     * 断言流转合法，否则抛业务异常。
     *
     * @param from         当前状态（从数据库读出的真实状态，不能用请求参数）
     * @param to           目标状态
     * @param operatorType 发起方，见 {@link OperatorType}
     */
    public static void assertCanTransfer(String from, String to, String operatorType)
    {
        if (!exists(from))
        {
            throw new ServiceException("报名状态异常：" + from);
        }
        if (!exists(to))
        {
            throw new ServiceException("目标状态不存在：" + to);
        }
        if (!canTransfer(from, to))
        {
            throw new ServiceException("当前状态【" + label(from) + "】不能变更为【" + label(to) + "】");
        }
        if (!allows(from, to, operatorType))
        {
            throw new ServiceException("没有权限执行该操作：" + label(from) + " -> " + label(to),
                    Integer.valueOf(HttpStatus.FORBIDDEN));
        }
    }
}
