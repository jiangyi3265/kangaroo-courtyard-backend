package com.ruoyi.kangaroo.security.admin;

import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** RuoYi creates its own SqlSessionFactoryBean, so a component interceptor alone is not registered. */
@Component
public class AdminCityMybatisRegistration implements SmartInitializingSingleton {
    private final List<SqlSessionFactory> factories;
    private final AdminCityMybatisInterceptor interceptor;
    public AdminCityMybatisRegistration(List<SqlSessionFactory> factories,AdminCityMybatisInterceptor interceptor) {this.factories=factories;this.interceptor=interceptor;}
    @Override public void afterSingletonsInstantiated() {
        for(SqlSessionFactory factory:factories)
            if(!factory.getConfiguration().getInterceptors().contains(interceptor))factory.getConfiguration().addInterceptor(interceptor);
    }
}
