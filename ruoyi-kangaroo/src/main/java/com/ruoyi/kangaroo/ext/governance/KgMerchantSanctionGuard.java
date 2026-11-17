package com.ruoyi.kangaroo.ext.governance;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.ruoyi.kangaroo.domain.KgStore;
@Aspect @Component public class KgMerchantSanctionGuard {
    @Autowired private KgMerchantSanctions sanctions;
    @Autowired private PlatformTransactionManager manager;
    @Around("execution(* com.ruoyi.kangaroo.service.impl.KgStoreServiceImpl.updateKgStore(..))")
    public Object update(ProceedingJoinPoint p) throws Throwable {
        KgStore store=(KgStore)p.getArgs()[0];
        if(!"0".equals(store.getStatus()))return p.proceed();
        final Throwable[] failure={null};Object result=new TransactionTemplate(manager).execute(tx->{
            sanctions.assertCanEnable(store.getStoreId());
            try{return p.proceed();}catch(Throwable e){failure[0]=e;tx.setRollbackOnly();return null;}
        });if(failure[0]!=null)throw failure[0];return result;
    }
}
