package com.ruoyi.kangaroo.security.admin;

import java.util.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import net.sf.jsqlparser.expression.JdbcParameter;

/** SQL is scoped before cache-key creation; disable shared/local result reuse for restricted requests. */
@Component
@Intercepts({
 @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
 @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class,CacheKey.class,BoundSql.class}),
 @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
})
public class AdminCityMybatisInterceptor implements Interceptor {
    private final AdminCitySqlGuard guard;
    public AdminCityMybatisInterceptor(AdminCitySqlGuard guard) {this.guard=guard;}
    @Override public Object intercept(Invocation invocation) throws Throwable {
        if(!AdminCityContext.limited())return invocation.proceed();
        Object[] args=invocation.getArgs();
        MappedStatement ms=(MappedStatement)args[0];Object parameter=args[1];
        BoundSql old=args.length==6?(BoundSql)args[5]:ms.getBoundSql(parameter);
        Configuration c=ms.getConfiguration();
        String scoped=guard.sql(old.getSql(), expression->{
            int index=((JdbcParameter)expression).getIndex()-1;
            String name=old.getParameterMappings().get(index).getProperty();
            if(old.hasAdditionalParameter(name))return old.getAdditionalParameter(name);
            if(parameter==null)return null;
            if(c.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass()))return parameter;
            return c.newMetaObject(parameter).getValue(name);
        });
        BoundSql next=new BoundSql(c,scoped,old.getParameterMappings(),parameter);
        MetaObject oldMeta=c.newMetaObject(old);
        @SuppressWarnings("unchecked") Map<String,Object> extras=(Map<String,Object>)oldMeta.getValue("additionalParameters");
        extras.forEach(next::setAdditionalParameter);
        MappedStatement.Builder b=new MappedStatement.Builder(c,ms.getId(),p->next,ms.getSqlCommandType())
            .resource(ms.getResource()).fetchSize(ms.getFetchSize()).timeout(ms.getTimeout())
            .statementType(ms.getStatementType()).resultSetType(ms.getResultSetType())
            .parameterMap(ms.getParameterMap()).resultMaps(ms.getResultMaps()).keyGenerator(ms.getKeyGenerator())
            .databaseId(ms.getDatabaseId()).lang(ms.getLang()).resultOrdered(ms.isResultOrdered())
            .useCache(false).flushCacheRequired(true);
        if(ms.getKeyProperties()!=null)b.keyProperty(String.join(",",ms.getKeyProperties()));
        if(ms.getKeyColumns()!=null)b.keyColumn(String.join(",",ms.getKeyColumns()));
        if(ms.getResultSets()!=null)b.resultSets(String.join(",",ms.getResultSets()));
        args[0]=b.build();
        Executor executor=(Executor)invocation.getTarget();executor.clearLocalCache();
        if(args.length==6) {args[5]=next;args[4]=executor.createCacheKey((MappedStatement)args[0],parameter,(RowBounds)args[2],next);}
        try {return invocation.proceed();} finally {executor.clearLocalCache();}
    }
    @Override public Object plugin(Object target) {return Plugin.wrap(target,this);}
    @Override public void setProperties(Properties properties) {}
}
