package com.ruoyi.kangaroo.ext.user;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;

/**
 * 用户服务的后台登记：城市字典（增删改）、客服工单（回复）、新机会订阅（只读）、技能证书（登记 / 修改 / 作废）、
 * 责任书签署存证（只读）、到店信息（只读）、旅行体验官申请（通过 / 驳回）。
 */
@Component
public class KgUserModule implements ExtEntityContributor
{
    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity city = new ExtEntity("city", "城市字典", "kg_city", "city_id", "kangaroo:city")
                .field(ExtField.of("name", "简称", "text").required().max(32).search("like"))
                .field(ExtField.of("fullName", "全称", "text").max(32))
                .field(ExtField.of("province", "省份", "text").max(16).search("eq"))
                .field(ExtField.of("parentName", "所属城市", "text").max(32).search("like"))
                .field(ExtField.of("adcode", "行政区代码", "text").max(6).readOnly().search("eq"))
                .field(ExtField.of("level", "层级", "select").options("city:城市,district:区县,destination:常用目的地").search("eq"))
                .field(ExtField.of("searchAliases", "搜索别名", "text").max(128).hideInList())
                .field(ExtField.of("region", "城市群", "text").max(32).search("eq"))
                .field(ExtField.of("pinyin", "拼音", "text").max(64))
                .field(ExtField.of("initial", "首字母", "text").max(1))
                .field(ExtField.of("lat", "纬度", "text").hideInList())
                .field(ExtField.of("lng", "经度", "text").hideInList())
                .field(ExtField.of("hot", "热门", "select").options("1:是,0:否").search("eq"))
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").search("eq"))
                .field(ExtField.of("coordinatorName", "负责人称呼", "text").max(32).hideInList())
                .field(ExtField.of("coordinatorAvatar", "负责人头像", "image").max(500).hideInList())
                .field(ExtField.of("coordinatorIntro", "负责人简介", "textarea").max(240).hideInList())
                .field(ExtField.of("campLocalQr", "青年营地·同城群活码", "image").max(500).hideInList())
                .field(ExtField.of("campDestinationQr", "青年营地·目的地群活码", "image").max(500).hideInList())
                .field(ExtField.of("regionGroupName", "地区换工群名称", "text").max(64).hideInList())
                .field(ExtField.of("regionGroupQr", "地区换工群二维码", "image").max(500).hideInList())
                .field(ExtField.of("regionGroupExpire", "地区群码有效期至", "date").hideInList())
                .field(ExtField.of("regionGroupNote", "地区群入群说明", "textarea").max(240).hideInList())
                .validate((cols, creating) -> {
                    KgRegionGroupService.validate(cols);
                    KgCampService.validate(cols);
                    Object avatar = cols.get("coordinator_avatar");
                    if (avatar != null && !String.valueOf(avatar).trim().isEmpty()) {
                        List<String> images;
                        try { images = com.ruoyi.kangaroo.domain.JobImages.require(avatar); }
                        catch (com.ruoyi.common.exception.ServiceException e) { throw new com.ruoyi.common.exception.ServiceException("负责人头像无效或尚未上传成功，请重新上传", 422); }
                        if (images.size() != 1) throw new com.ruoyi.common.exception.ServiceException("请上传一张有效的负责人头像", 422);
                        cols.put("coordinator_avatar", images.get(0));
                    }
                })
                .hardDelete();

        ExtEntity faq = new ExtEntity("faq", "常见问题", "kg_support_faq", "faq_id", "kangaroo:faq")
                .field(ExtField.of("question", "问题", "text").required().max(80).search("like"))
                .field(ExtField.of("category", "分类", "select").required().options("apply:报名审核,interview:面试流程,contract:签约与责任书,insurance:保险说明,arrival:到店与交通,refund:费用与退款,other:其他").search("eq"))
                .field(ExtField.of("answer", "解答", "textarea").required().max(2000).hideInList())
                .field(ExtField.of("sort", "排序", "int").range(0L, 9999L))
                .field(ExtField.of("status", "状态", "select").required().options("0:展示,1:停用").search("eq"))
                .order("sort, faq_id");

        ExtEntity ticket = new ExtEntity("ticket", "客服工单", "kg_support_ticket", "ticket_id", "kangaroo:ticket")
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq").readOnly())
                .field(ExtField.of("context", "来源", "select").options("home:首页,city:城市页,profile:我的,order:订单,staff:员工身份").readOnly())
                .field(ExtField.of("question", "问题", "text").search("like").readOnly())
                .field(ExtField.of("summary", "摘要", "text").hideInList().readOnly())
                .field(ExtField.of("related", "授权查看的信息", "text").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("open:待处理,replied:已回复,closed:已关闭").search("eq").readOnly())
                .field(ExtField.of("reply", "回复", "textarea").hideInList().readOnly())
                .field(ExtField.of("repliedBy", "回复人", "text").hideInList().readOnly())
                .field(ExtField.of("satisfaction", "满意度", "int").readOnly())
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range").readOnly())
                // 与菜单设计一致：只查询 + 审核动作，不开放通用修改 / 删除（改用户内容会绕过内容审核，删除应走隐藏 / 驳回）
                .readOnly()
                .action("reply", "回复", "put", "/kangaroo/usersvc/ticket/{ticketId}/reply", "kangaroo:ticket:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("open", "replied")))
                .promptLabel("回复内容（会发到用户的消息中心）");

        // 主人（2026-11-03）：「历史聊天记录，永久保存，不能删除」。AI袋鼠聊天记录（V72）：客服按用户ID查，只读，没有新增 / 修改 / 删除；
        // 卡片和按钮的 JSON（extra）不登记，后台只看文字和信息来源
        ExtEntity chat = new ExtEntity("supportchat", "AI客服聊天记录", "kg_support_chat", "chat_id", "kangaroo:supportchat")
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq").readOnly())
                .field(ExtField.of("role", "谁说的", "select").options("user:用户,bot:AI袋鼠").search("eq").readOnly())
                .field(ExtField.of("content", "内容", "textarea").search("like").readOnly())
                .field(ExtField.of("source", "信息来源", "text").readOnly())
                .field(ExtField.of("context", "入口", "select").options("home:首页,city:城市页,profile:我的,order:订单").readOnly())
                .field(ExtField.of("intent", "识别意图", "text").hideInList().readOnly())
                .field(ExtField.of("createTime", "时间", "datetime").search("range").readOnly())
                .readOnly()
                .hardDelete();

        ExtEntity sub = new ExtEntity("jobsub", "新机会订阅", "kg_job_subscribe", "sub_id", "kangaroo:jobsub")
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq"))
                .field(ExtField.of("cities", "城市", "text").search("like"))
                .field(ExtField.of("jobTypes", "岗位类型", "text"))
                .field(ExtField.of("storeId", "关注门店", "int").search("eq"))
                .field(ExtField.of("freq", "频率", "select").options("instant:即时,daily:每日汇总"))
                .field(ExtField.of("status", "状态", "select").options("0:生效,1:暂停").search("eq"))
                .field(ExtField.of("lastNotifiedTime", "上次推送", "datetime"))
                .readOnly();

        ExtEntity cert = new ExtEntity("cert", "技能证书", "kg_cert", "cert_id", "kangaroo:cert")
                .field(ExtField.of("appUserId", "用户ID", "int").required().search("eq"))
                .field(ExtField.of("name", "证书名称", "text").required().max(64).search("like"))
                .field(ExtField.of("category", "技能方向", "text").max(32))
                .field(ExtField.of("level", "等级", "text").max(16))
                .field(ExtField.of("grade", "评定", "text").max(16))
                .field(ExtField.of("score", "综合评分", "int").range(0L, 100L))
                .field(ExtField.of("certNo", "证书编号", "text").required().max(32).search("eq"))
                .field(ExtField.of("issuer", "颁发机构", "text").required().max(64))
                .field(ExtField.of("issuedDate", "颁发日期", "date").required())
                .field(ExtField.of("status", "状态", "select").options("0:有效,1:作废").search("eq"))
                .noDelete();

        ExtEntity sign = new ExtEntity("contractsign", "责任书签署", "kg_contract_sign", "sign_id", "kangaroo:contractsign")
                .field(ExtField.of("appId", "报名ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq"))
                .field(ExtField.of("version", "版本", "text"))
                .field(ExtField.of("agreementNo", "协议编号", "text"))
                .field(ExtField.of("merchantSigner", "店家确认人", "text"))
                .field(ExtField.of("merchantSignedTime", "店家确认时间", "datetime"))
                .field(ExtField.of("contentSnapshot", "签署原文（身份证脱敏）", "textarea").hideInList())
                .field(ExtField.of("signerName", "签署姓名", "text").search("like"))
                .field(ExtField.of("idLast4", "证件后四位", "text"))
                .field(ExtField.of("contentHash", "条款摘要（SHA-256）", "text").hideInList())
                .field(ExtField.of("signIp", "签署IP", "text").hideInList())
                .field(ExtField.of("signedTime", "签署时间", "datetime").search("range"))
                .readOnly();

        ExtEntity arrival = new ExtEntity("arrival", "到店信息", "kg_app_arrival", "app_id", "kangaroo:arrival")
                .field(ExtField.of("appId", "报名ID", "int").search("eq"))
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq"))
                .field(ExtField.of("arriveDate", "到达日期", "date").search("range"))
                .field(ExtField.of("arriveTime", "到达时间", "text"))
                .field(ExtField.of("transport", "交通方式", "text"))
                .field(ExtField.of("transportNo", "车次/航班", "text"))
                // 「需要接站」已下线（门店不提供接站）：列表、详情、导出都不再出现；库里 need_pickup 列和旧数据保留
                .field(ExtField.of("tickets", "票据", "images").hideInList())
                .field(ExtField.of("note", "备注", "text").hideInList())
                .field(ExtField.of("updateTime", "更新时间", "datetime"))
                .readOnly();

        ExtEntity guide = new ExtEntity("guideapply", "体验官申请", "kg_guide_apply", "apply_id", "kangaroo:guideapply")
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq").readOnly())
                .field(ExtField.of("cities", "申请城市", "text").search("like").readOnly())
                .field(ExtField.of("plan", "创作计划", "textarea").readOnly())
                .field(ExtField.of("works", "代表作品", "textarea").hideInList().readOnly())
                .field(ExtField.of("status", "状态", "select").options("pending:待审核,approved:已通过,rejected:未通过").search("eq").readOnly())
                .field(ExtField.of("auditNote", "审核说明", "text").hideInList().readOnly())
                .field(ExtField.of("createTime", "申请时间", "datetime").search("range").readOnly())
                // 与菜单设计一致：只查询 + 审核动作，不开放通用修改 / 删除（改用户内容会绕过内容审核，删除应走隐藏 / 驳回）
                .readOnly()
                .action("approve", "通过", "put", "/kangaroo/usersvc/guide/{applyId}/approve", "kangaroo:guideapply:audit",
                        "确认通过这份体验官申请？", false, Collections.singletonMap("status", Arrays.asList("pending")))
                .action("reject", "驳回", "put", "/kangaroo/usersvc/guide/{applyId}/reject", "kangaroo:guideapply:audit",
                        null, true, Collections.singletonMap("status", Arrays.asList("pending")));

        ExtEntity accountdel = new ExtEntity("accountdel", "账号注销记录", "kg_account_deletion", "id", "kangaroo:accountdel")
                .field(ExtField.of("appUserId", "账号ID", "int").search("eq"))
                .field(ExtField.of("nickname", "注销前昵称", "text").search("like"))
                .field(ExtField.of("reason", "注销原因", "text").search("like"))
                .field(ExtField.of("detail", "处理说明", "textarea").hideInList())
                .field(ExtField.of("createTime", "注销时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        ExtEntity forward = new ExtEntity("resumeforward", "简历转发记录", "kg_resume_forward", "forward_id", "kangaroo:resumeforward")
                .field(ExtField.of("appId", "报名ID", "int").search("eq"))
                .field(ExtField.of("storeId", "门店", "int").search("eq"))
                .field(ExtField.of("fromUserId", "转发人ID", "int").search("eq"))
                .field(ExtField.of("fromName", "转发人", "text").search("like"))
                .field(ExtField.of("target", "转发对象", "select").options("manager:主理人,store:门店群").search("eq"))
                .field(ExtField.of("suggestion", "审核建议", "text"))
                .field(ExtField.of("track", "记录已读", "select").options("1:是,0:否"))
                .field(ExtField.of("msgIds", "站内信ID", "text").hideInList())
                .field(ExtField.of("createTime", "转发时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        ExtEntity substpl = new ExtEntity("substpl", "订阅消息模板", "kg_subscribe_template", "tpl_id", "kangaroo:substpl")
                .field(ExtField.of("bizKey", "业务类型", "select").required()
                        .options("job_new:岗位订阅匹配,apply_progress:报名、面试与到离岗,online_task:线上任务进度,finance:结算、提现与报销,member:会员权益与积分,order:旅居与服务订单,activity:活动进度,review:服务评价,community:聊天、留言与回复,insurance:保险办理,wish:心愿互助进度,mediation:纠纷调解,mediation_jury:大众评审,governance_case:权益与合作复核,account:审核与身份变更,ops_daily:日常工作日报,ops_weekly:每周工作报告,ops_monthly:月度目标与总结,work_task:工作任务与待办").search("eq"))
                .field(ExtField.of("title", "展示名", "text").required().max(64))
                .field(ExtField.of("templateId", "微信模板ID", "text").required().max(64).search("like"))
                .field(ExtField.of("page", "安全跳转入口", "text").max(200).readOnly().hideInList())
                .field(ExtField.of("fields", "字段映射", "text").max(500).hideInList())
                .field(ExtField.of("status", "状态", "select").options("0:启用,1:停用").search("eq"))
                .field(ExtField.of("remark", "备注", "text").max(200).hideInList())
                .field(ExtField.of("updateTime", "更新时间", "datetime"))
                .hardDelete();

        ExtEntity sublog = new ExtEntity("sublog", "订阅发送记录", "kg_subscribe_log", "log_id", "kangaroo:sublog")
                .field(ExtField.of("appUserId", "用户ID", "int").search("eq"))
                .field(ExtField.of("bizKey", "业务类型", "text").search("eq"))
                .field(ExtField.of("templateId", "模板ID", "text").hideInList())
                .field(ExtField.of("msgId", "站内信ID", "int"))
                .field(ExtField.of("status", "结果", "select").options("sent:已发送,skipped:未发送,failed:失败,unknown:结果待核对").search("eq"))
                .field(ExtField.of("detail", "说明", "text"))
                .field(ExtField.of("createTime", "时间", "datetime").search("range"))
                .readOnly()
                .hardDelete();

        return Arrays.asList(city, faq, ticket, chat, sub, cert, sign, arrival, guide, accountdel, forward, substpl, sublog);
    }
}
