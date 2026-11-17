package com.ruoyi.kangaroo.ext.staff;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 后台「商家需求」（V63）：老板随时能看能改。内容、联系方式、备注能改；类型、门店、状态只读，状态只能经业务流转（「重新派单 / 改派」「关闭」「标记完成」、岗位管理上架）。
 * 有岗位的需求，标题和说明只读（validate）：上架和用户端用的是岗位，要到岗位管理改，保存时会同步回这里。
 * 不能新增（需求只从小程序来），也不能删除（进度要留着）。
 */
@Component
public class KgMerchantRequestModule implements ExtEntityContributor
{
    static final String TYPE_OPTIONS = "volunteer:招募换工,parttime:招募兼职,fulltime:招募全职,online_task:线上任务,managed:代运营,cloud_chain:抖音卖房";
    static final String STATUS_OPTIONS = "unassigned:等待平台安排,dispatched:换工主理人完善中,pending_verify:待换工主理人核实,forwarded:已转商家服务人,accepted:商家服务人处理中,"
            + "returned:已退回,published:已上架,done:已完成,closed:已关闭";

    @Autowired(required = false)
    private KgJdbc db;

    private static String norm(Object v)
    {
        return v == null ? "" : String.valueOf(v).replace("\r\n", "\n").trim();
    }

    /** 有岗位的需求不能在这里改标题和说明（改了用户端也看不到，后台和商家看到的却是改过的） */
    void requireJobTextUnchanged(Map<String, Object> cols)
    {
        Long id = KgJdbc.asLong(cols.get("_pk"));
        if (db == null || id == null)
        {
            return;
        }
        Map<String, Object> r = db.one("select job_id, title, content from kg_merchant_request where request_id = :id and del_flag = '0'", KgJdbc.p("id", id));
        if (r == null || r.get("jobId") == null)
        {
            return;
        }
        if ((cols.containsKey("title") && !norm(cols.get("title")).equals(norm(r.get("title"))))
                || (cols.containsKey("content") && !norm(cols.get("content")).equals(norm(r.get("content")))))
        {
            throw new ServiceException("这条需求已经有岗位，标题和说明请到「岗位管理」修改", 422);
        }
    }

    /** 标记完成只给代运营、抖音卖房：线上任务走上架和交付，招募走岗位管理 */
    private static Map<String, List<String>> doneShowIf()
    {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        m.put("requestType", Arrays.asList("managed", "cloud_chain"));
        m.put("status", Arrays.asList("unassigned", "pending_verify", "forwarded", "accepted", "returned"));
        return m;
    }

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity e = new ExtEntity("merchantrequest", "商家需求", "kg_merchant_request", "request_id", "kangaroo:merchantrequest")
                .field(ExtField.of("requestType", "类型", "select").options(TYPE_OPTIONS).search("eq").readOnly())
                .field(ExtField.of("title", "标题", "text").search("like").required().max(64))
                .field(ExtField.of("storeName", "门店", "text").search("like").readOnly())
                .field(ExtField.of("storeId", "门店ID", "int").search("eq").hideInList().readOnly())
                .field(ExtField.of("city", "城市", "text").search("like").readOnly())
                .field(ExtField.of("status", "状态", "select").options(STATUS_OPTIONS).search("eq").readOnly())
                .field(ExtField.of("source", "来源", "select").options("merchant:商家提交,manager:换工主理人代提交").search("eq").hideInList().readOnly())
                .field(ExtField.of("contactName", "联系人", "text").search("like").max(32))
                .field(ExtField.of("contactPhone", "联系电话", "text").search("like").max(20))
                .field(ExtField.of("content", "需求说明", "textarea").hideInList().max(2000))
                .field(ExtField.of("images", "图片", "images").hideInList().max(2000))
                .field(ExtField.of("verifyNote", "核实备注", "textarea").hideInList().max(500))
                .field(ExtField.of("returnReason", "退回 / 关闭原因", "textarea").hideInList().readOnly())
                .field(ExtField.of("jobId", "岗位ID", "int").search("eq").readOnly())
                .field(ExtField.of("merchantUserId", "商家账号ID", "int").hideInList().readOnly())
                .field(ExtField.of("verifierUserId", "核实人账号ID", "int").hideInList().readOnly())
                .field(ExtField.of("serviceUserId", "商家服务人账号ID", "int").hideInList().readOnly())
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range").readOnly())
                .field(ExtField.of("handleTime", "最近处理", "datetime").readOnly())
                .field(ExtField.of("updateBy", "最后修改人", "text").hideInList().readOnly())
                .noAdd()
                .noDelete()
                .order("field(status, 'unassigned', 'pending_verify', 'dispatched', 'forwarded', 'accepted', 'returned', 'published', 'done', 'closed'), request_id desc")
                .validate((cols, creating) -> requireJobTextUnchanged(cols))
                .action("redispatch", "重新派单", "put", "/kangaroo/merchant-request/{requestId}/redispatch", "kangaroo:merchantrequest:edit",
                        "按这条需求所在城市重新找人（原来的接手人会清掉）：有换工主理人或商家服务人就派过去；已派出去的没人接时退回等待平台安排。确定吗？", false,
                        Collections.singletonMap("status", Arrays.asList("unassigned", "dispatched", "pending_verify", "forwarded", "accepted")))
                .action("closeRequest", "关闭", "put", "/kangaroo/merchant-request/{requestId}/close", "kangaroo:merchantrequest:edit",
                        "关闭后这条需求结束，原因会发给商家；岗位还是草稿的一并关闭。", true,
                        Collections.singletonMap("status", Arrays.asList("unassigned", "dispatched", "pending_verify", "forwarded", "accepted", "returned")))
                .promptLabel("请填写关闭原因（会发给商家）")
                .action("markDone", "标记完成", "put", "/kangaroo/merchant-request/{requestId}/done", "kangaroo:merchantrequest:edit",
                        "线下已经办完了？标记后商家会收到「已完成」。", false, doneShowIf());
        return Collections.singletonList(e);
    }
}
