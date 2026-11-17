package com.ruoyi.kangaroo.ext.staff;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 写授权的事务开头调用（后台授予、改角色，小程序授予）：角色编码的意思跟着阶段变，manager 切换前是带资金权的老主理人，切换后是换工主理人。
 * 切换、退回时正等着授权行锁的写请求，校验时看的是旧阶段，提交后才写进去，就会写出切换后的 staff 行，或者退回后带资金权的 manager 行。
 *
 * 在状态行上拿共享锁，和切换、退回排好队（它们先锁状态行，所以不会死锁）；等到之后再比：进来时的阶段和库里的不一样，说明等的时候阶段变了，这次不写。
 * 拿到锁之后到事务结束，切换和退回都要等，阶段不会再变，后面的校验看到的就是真的。
 */
@Component
public class RolePhaseGuard
{
    @Autowired
    private KgJdbc db;

    public void beforeGrantWrite()
    {
        String seen = RolePhase.code();
        Map<String, Object> row = db.one("select phase from kg_role_switch where id = 1 lock in share mode", p());
        if (row != null && !seen.equals(StaffSupport.str(row.get("phase"))))
        {
            throw new ServiceException("角色刚刚切换或退回了，这次没有改授权。请刷新页面后重新操作", HttpStatus.CONFLICT);
        }
    }
}
