package com.ruoyi.kangaroo.service.impl;

import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** 微信、短信与旧版未核验绑定共用的原子写入；外部核验在进入本事务前完成。 */
@Service
public class KgPhoneBindingService
{
    private final KgJdbc db;

    public KgPhoneBindingService(KgJdbc db)
    {
        this.db = db;
    }

    @Transactional(rollbackFor = Exception.class)
    public int bind(Long userId, String phone, boolean verified)
    {
        if (phone == null || !phone.matches("^1\\d{10}$"))
        {
            throw new ServiceException("请输入正确的 11 位手机号", 422);
        }
        // 所有实例先锁同一行，再锁账号；绑定是低频短事务，不在锁内调用微信、短信或 Redis。
        // 固定锁顺序避免双向换绑/接管时互锁；唯一索引还负责兜住绕过本服务的写入。
        if (db.one("select mutex_key from kg_auth_mutex where mutex_key='phone-binding' for update", null) == null)
        {
            throw new ServiceException("手机号绑定服务未就绪，请联系平台", 503);
        }
        Map<String, Object> user = db.one("select phone,status,del_flag from kg_app_user where user_id=:u for update", p("u", userId));
        if (user == null || !"0".equals(user.get("delFlag")))
        {
            throw new ServiceException("账号不存在", 401);
        }
        if (!"0".equals(user.get("status")))
        {
            throw new ServiceException("账号已被停用，请联系客服", 403);
        }
        if (verified)
        {
            // 只允许经过微信/短信核验的机主接管未核验占号；后续任一步失败都一并回滚。
            db.update("update kg_app_user set phone=null, update_time=now() where phone=:p "
                    + "and user_id<>:u and del_flag='0' and phone_verified_time is null", p("p", phone, "u", userId));
        }
        Map<String, Object> owner = db.one("select user_id from kg_app_user where active_phone=:p for update", p("p", phone));
        if (owner != null && ((Number) owner.get("userId")).longValue() != userId.longValue())
        {
            throw new ServiceException("该手机号已绑定其他账号，如确为本人号码请联系平台处理", 409);
        }
        // 在同一句 UPDATE 内保存号码与核验状态；旧版未核验换号必须撤销旧号码的核验状态。
        return db.update("update kg_app_user set phone_verified_time="
                + (verified ? "now()" : "case when phone=:p then phone_verified_time else null end")
                + ",phone=:p,update_by='app',update_time=now() where user_id=:u", p("p", phone, "u", userId));
    }
}
