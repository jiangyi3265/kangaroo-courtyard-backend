package com.ruoyi.kangaroo.enums;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ruoyi.common.exception.ServiceException;

/**
 * 报名状态机测试。
 *
 * 这里覆盖的是"改状态字段就能跳过审核/面试/双向确认"这一类越权风险，
 * 每条断言都对应一个具体的攻击面，而不是为了覆盖率凑数。
 */
public class ApplicationStatusTest
{
    // ---------- 正常路径 ----------

    @Test
    @DisplayName("完整履约链路的每一步流转都合法")
    public void happyPath()
    {
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, OperatorType.STAFF));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.INTERVIEW_BOOKED, OperatorType.USER));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.INTERVIEW_BOOKED, ApplicationStatus.INTERVIEW_DONE, OperatorType.USER));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.INTERVIEW_DONE, ApplicationStatus.USER_CONFIRMED, OperatorType.USER));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.USER_CONFIRMED, ApplicationStatus.STORE_CONFIRMED, OperatorType.STAFF));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED, OperatorType.USER));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.CONTRACT_SIGNED, ApplicationStatus.ONBOARDED, OperatorType.STAFF));
    }

    // ---------- 跳步（核心越权风险） ----------

    @Test
    @DisplayName("不能从已提交直接跳到已入职")
    public void cannotSkipStraightToOnboarded()
    {
        ServiceException e = assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.SUBMITTED, ApplicationStatus.ONBOARDED, OperatorType.ADMIN));
        assertTrue(e.getMessage().contains("不能变更为"));
    }

    @Test
    @DisplayName("不能跳过面试直接确认录用")
    public void cannotSkipInterview()
    {
        assertFalse(ApplicationStatus.canTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.STORE_CONFIRMED));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.STORE_CONFIRMED, OperatorType.STAFF));
    }

    @Test
    @DisplayName("不能跳过商家确认直接签署责任书")
    public void cannotSkipStoreConfirm()
    {
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.USER_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED, OperatorType.USER));
    }

    // ---------- 操作方越权 ----------

    @Test
    @DisplayName("用户不能自己审核通过自己的报名")
    public void userCannotAuditOwnApplication()
    {
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, OperatorType.USER));
        ServiceException e = assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, OperatorType.USER));
        assertTrue(e.getMessage().contains("没有权限"));
        assertEquals(Integer.valueOf(403), e.getCode());
    }

    @Test
    @DisplayName("用户不能自己确认录用自己")
    public void userCannotConfirmHire()
    {
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.USER_CONFIRMED, ApplicationStatus.STORE_CONFIRMED, OperatorType.USER));
    }

    @Test
    @DisplayName("商家不能替用户撤回报名")
    public void staffCannotWithdrawForUser()
    {
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.SUBMITTED, ApplicationStatus.WITHDRAWN, OperatorType.STAFF));
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.SUBMITTED, ApplicationStatus.WITHDRAWN, OperatorType.ADMIN));
    }

    @Test
    @DisplayName("商家不能替用户签署责任书")
    public void staffCannotSignForUser()
    {
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED, OperatorType.STAFF));
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.STORE_CONFIRMED, ApplicationStatus.CONTRACT_SIGNED, OperatorType.ADMIN));
    }

    // ---------- 终态 ----------

    @Test
    @DisplayName("四个终态不能再流转")
    public void finalStatesAreFinal()
    {
        assertTrue(ApplicationStatus.isFinal(ApplicationStatus.ONBOARDED));
        assertTrue(ApplicationStatus.isFinal(ApplicationStatus.TERMINATED));
        assertTrue(ApplicationStatus.isFinal(ApplicationStatus.WITHDRAWN));
        assertTrue(ApplicationStatus.isFinal(ApplicationStatus.REVIEW_REJECTED));

        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.WITHDRAWN, ApplicationStatus.REVIEW_PASSED, OperatorType.STAFF));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_REJECTED, ApplicationStatus.INTERVIEW_BOOKED, OperatorType.USER));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.ONBOARDED, ApplicationStatus.TERMINATED, OperatorType.ADMIN));
    }

    @Test
    @DisplayName("审核通过后用户一直不约面试：门店 / 后台 / 系统可以终止，用户不能借此绕过撤回")
    public void reviewPassedCanBeTerminatedByStoreSide()
    {
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.TERMINATED, OperatorType.STAFF));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.TERMINATED, OperatorType.ADMIN));
        assertDoesNotThrow(() -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.TERMINATED, OperatorType.SYSTEM));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.REVIEW_PASSED, ApplicationStatus.TERMINATED, OperatorType.USER));
    }

    @Test
    @DisplayName("非终态不会被误判为终态")
    public void nonFinalStates()
    {
        assertFalse(ApplicationStatus.isFinal(ApplicationStatus.SUBMITTED));
        assertFalse(ApplicationStatus.isFinal(ApplicationStatus.REVIEW_PASSED));
        assertFalse(ApplicationStatus.isFinal(ApplicationStatus.CONTRACT_SIGNED));
    }

    // ---------- 非法输入 ----------

    @Test
    @DisplayName("不存在的状态被拒绝，而不是静默通过")
    public void unknownStatusRejected()
    {
        assertFalse(ApplicationStatus.exists("hacked"));
        assertFalse(ApplicationStatus.exists(null));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                "hacked", ApplicationStatus.ONBOARDED, OperatorType.ADMIN));
        assertThrows(ServiceException.class, () -> ApplicationStatus.assertCanTransfer(
                ApplicationStatus.SUBMITTED, "hacked", OperatorType.ADMIN));
    }

    @Test
    @DisplayName("未知操作方一律拒绝")
    public void unknownOperatorRejected()
    {
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, "anonymous"));
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.SUBMITTED, ApplicationStatus.REVIEW_PASSED, null));
    }

    // ---------- 终止路径 ----------

    @Test
    @DisplayName("系统可以在超时场景下终止，但不能替用户表态")
    public void systemCanTerminateButNotConfirm()
    {
        assertTrue(ApplicationStatus.allows(
                ApplicationStatus.USER_CONFIRMED, ApplicationStatus.TERMINATED, OperatorType.SYSTEM));
        assertFalse(ApplicationStatus.allows(
                ApplicationStatus.INTERVIEW_DONE, ApplicationStatus.USER_CONFIRMED, OperatorType.SYSTEM));
    }

    @Test
    @DisplayName("状态中文名可用于消息文案")
    public void labels()
    {
        assertEquals("已提交", ApplicationStatus.label(ApplicationStatus.SUBMITTED));
        assertEquals("已入职", ApplicationStatus.label(ApplicationStatus.ONBOARDED));
        assertEquals("unknown", ApplicationStatus.label("unknown"));
    }
}
