package com.ruoyi.kangaroo.ext.core;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 门店来源类型（kg_store.op_type）单独改（2026-11-03 主人：「来源类型，分成直营，加盟，代运营，合作」，V70 字典 kg_store_op_type 加了 partner）。
 *
 * 为什么不走门店通用编辑接口：线上还是旧版 KgStoreServiceImpl（V61 线上没有 kg_store.images 列，门店服务 / 实体 / Mapper XML 都不能随包发），
 * 旧版校验只认 直营 / 加盟 / 代运营，选「合作」保存会被拦下来。这里只用 KgJdbc 改 op_type、update_by、update_time 三列，
 * 不碰门店实体和 Mapper，能单独发布。后台「门店管理」选「合作」时主表单不带 partner，保存完再调这里。
 *
 * 「直营 / 非直营」的口径不变：别处只判 "direct".equals(opType)（活动代收、报销、员工端配色），合作和加盟、代运营一样按非直营处理。
 */
@Service
public class KgStoreOpTypes
{
    /** 允许的代码，顺序同字典 kg_store_op_type：直营、加盟、代运营、合作 */
    public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList("direct", "franchise", "managed", "partner"));

    static final String LABELS = "直营 / 加盟 / 代运营 / 合作";

    /** 只查这几列、只改这几列（不能出现 images：线上没有这一列） */
    static final String FIND_SQL = "select store_id, store_name, op_type from kg_store where store_id = :id and del_flag = '0'";

    static final String UPDATE_SQL = "update kg_store set op_type = :op, update_by = :by, update_time = now() where store_id = :id and del_flag = '0'";

    @Autowired
    private KgJdbc db;

    static String str(Object v)
    {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 校验并规整来源类型代码；不认识的抛业务异常（单测直接调） */
    public static String normalize(Object opType)
    {
        String op = str(opType);
        if (!TYPES.contains(op))
        {
            throw new ServiceException("来源类型只能是 " + LABELS);
        }
        return op;
    }

    /**
     * 把门店来源类型改成 opType。by = 后台账号名（记到 update_by）。
     * 返回 {storeId, storeName, opType, changed}：changed = false 表示本来就是这个类型，没动库。
     */
    public Map<String, Object> set(Long storeId, Object opType, String by)
    {
        if (storeId == null)
        {
            throw new ServiceException("门店ID不能为空");
        }
        String op = normalize(opType);
        Map<String, Object> store = db.one(FIND_SQL, p("id", storeId));
        if (store == null)
        {
            throw new ServiceException("门店不存在或已删除");
        }
        boolean changed = !op.equals(str(store.get("opType")));
        if (changed)
        {
            db.update(UPDATE_SQL, p("op", op, "by", str(by), "id", storeId));
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("storeId", storeId);
        out.put("storeName", str(store.get("storeName")));
        out.put("opType", op);
        out.put("changed", Boolean.valueOf(changed));
        return out;
    }
}
