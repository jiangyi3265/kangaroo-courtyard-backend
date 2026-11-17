package com.ruoyi.kangaroo.dto;

import java.io.Serializable;

/**
 * 小程序报名提交请求体。
 *
 * 字段与 /pages/jobs/apply.vue 的三步表单一一对应：
 *   step1 报名资料：name / age / phone / skills
 *   step2 到岗安排：arrival / days / note
 * 前端字段名为 name / arrival / days，这里保留同名以便请求层直接透传，
 * 不需要改动页面里的表单绑定。
 *
 * 注意：请求体里不接受 appUserId —— 报名人一律取自登录态。
 *
 * 2026-10-18 起新版报名页「用简历投递」：name / age / phone / skills 可以不传，服务端从「我的简历」取
 * （简历没留手机号时用账号绑定的手机号，见 KgApplicationServiceImpl.resumeFallback）；老版本传了的照旧以传的为准。
 */
public class ApplicationSubmitDTO implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 岗位ID */
    private Long jobId;

    /** 姓名 */
    private String name;

    /** 年龄 */
    private Integer age;

    /** 联系手机号 */
    private String phone;

    /** 技能与经历（选填） */
    private String skills;

    /** 预计到岗日期 yyyy-MM-dd */
    private String arrival;

    /** 可停留天数 */
    private Integer days;

    /** 补充说明（选填） */
    private String note;

    /** 幂等键：同一次提交重复请求只产生一条报名。由小程序在进入确认页时生成一次。 */
    private String idemKey;

    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getSkills() { return skills; }
    public void setSkills(String skills) { this.skills = skills; }
    public String getArrival() { return arrival; }
    public void setArrival(String arrival) { this.arrival = arrival; }
    public Integer getDays() { return days; }
    public void setDays(Integer days) { this.days = days; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getIdemKey() { return idemKey; }
    public void setIdemKey(String idemKey) { this.idemKey = idemKey; }
}
