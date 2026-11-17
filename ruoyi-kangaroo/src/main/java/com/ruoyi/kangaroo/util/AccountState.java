package com.ruoyi.kangaroo.util;

import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;

/** Call inside the business transaction before locking or writing owned resources. */
public final class AccountState
{
    private AccountState() { }
    public static void requireActive(KgJdbc db, Long userId)
    {
        Map<String, Object> row = db.one("select status, del_flag from kg_app_user where user_id=:u for update", p("u", userId));
        if (row == null || !"0".equals(KgJdbc.asStr(row.get("status"))) || !"0".equals(KgJdbc.asStr(row.get("delFlag"))))
            throw new ServiceException("账号已注销或不可用", 409);
    }
}
