package com.ruoyi.kangaroo.ext.user;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtEntityContributor;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 后台「袋鼠小院 → 商家入驻申请」（V56）：小程序「成为商家」交上来的申请。
 * 用户填的内容只读（改了就不是对方说的了）；后台只改跟进状态和跟进备注（通用「修改」，权限 kangaroo:merchantapply:edit）。
 * 不能新增（申请只从小程序来），也不能删除（跟进记录要留着；不合作改成「暂不合作」）。
 * 改跟进状态有联动（保存前钩子，同一事务，KgMerchantApplyService.adminFollow）：给用户发站内消息；改成「已入驻」时开通门店和合作商家身份，开不了就不让保存。
 */
@Component
public class KgMerchantModule implements ExtEntityContributor
{
    static final String STATUS_OPTIONS = "new:待审核,contacted:核实中,settled:审核通过并开通,rejected:审核未通过";

    @Lazy
    @Autowired(required = false)
    private KgMerchantApplyService applies;

    @Override
    public List<ExtEntity> entities()
    {
        ExtEntity apply = new ExtEntity("merchantapply", "商家入驻申请", "kg_merchant_apply", "apply_id", "kangaroo:merchantapply")
                .field(ExtField.of("storeName", "店铺名称", "text").search("like").readOnly())
                .field(ExtField.of("storeType", "店铺类型", "select").options("homestay:民宿,hotel:酒店,cafe:咖啡餐饮,bar:酒吧,other:其他").search("eq").readOnly())
                .field(ExtField.of("city", "城市", "text").search("like").readOnly())
                .field(ExtField.of("contactName", "联系人", "text").search("like").readOnly())
                .field(ExtField.of("phone", "手机号", "text").search("like").readOnly())
                .field(ExtField.of("wantTypes", "想招的人（老版本才填）", "text").hideInList().readOnly())
                .field(ExtField.of("storeId", "开通门店ID", "int").search("eq").readOnly())
                .field(ExtField.of("grantId", "开通授权ID", "int").hideInList().readOnly())
                .field(ExtField.of("intro", "简单介绍", "textarea").hideInList().readOnly())
                .field(ExtField.of("status", "审核状态", "select").options(STATUS_OPTIONS).required().search("eq"))
                .field(ExtField.of("followNote", "跟进备注", "textarea").max(500))
                .field(ExtField.of("appUserId", "提交账号ID", "int").search("eq").hideInList().readOnly())
                .field(ExtField.of("createTime", "提交时间", "datetime").search("range").readOnly())
                .field(ExtField.of("updateBy", "最后跟进人", "text").hideInList().readOnly())
                .field(ExtField.of("updateTime", "最后跟进时间", "datetime").hideInList().readOnly())
                .noAdd()
                .noDelete()
                // 修改时 cols 里带 _pk（申请 ID）；store_id、grant_id 由钩子加进 cols 一起保存
                .validate((cols, creating) -> {
                    if (!creating && applies != null)
                    {
                        applies.adminFollow(KgJdbc.asLong(cols.get("_pk")), cols, SecurityUtils.getUsername());
                    }
                })
                .order("field(status, 'new', 'contacted', 'settled', 'rejected'), apply_id desc");
        return Collections.singletonList(apply);
    }
}
