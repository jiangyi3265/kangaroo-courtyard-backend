package com.ruoyi.kangaroo.security.admin;

import java.util.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;

@Service
public class AdminCityScopeService {
    private final JdbcTemplate jdbc;
    public AdminCityScopeService(DataSource ds) { jdbc = new JdbcTemplate(ds); }
    public boolean all(long user) {
        return user == 1L || jdbc.queryForObject("select count(*) from kg_admin_city_scope where user_id=? and all_cities=1", Long.class, user) > 0;
    }
    public List<Long> cities(long user) {
        return jdbc.queryForList("select city_id from kg_admin_city_grant where user_id=? order by city_id", Long.class, user);
    }
    private void requireAdmin() {
        if (!SecurityUtils.isAdmin(SecurityUtils.getUserId())) throw new ServiceException("仅超级管理员可配置后台城市授权", 403);
    }
    public Map<String,Object> get(long user) {
        requireAdmin();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("allCities", all(user)); out.put("cityIds", cities(user));
        out.put("cities", jdbc.queryForList("select city_id cityId, name, full_name fullName from kg_city where status='0' order by sort,city_id"));
        return out;
    }
    @Transactional(rollbackFor=Exception.class)
    public void save(long user, boolean all, List<Long> cities) {
        requireAdmin();
        if (user == 1L) throw new ServiceException("超级管理员固定拥有全部城市权限", 422);
        if (jdbc.queryForObject("select count(*) from sys_user where user_id=? and del_flag='0'", Long.class, user) != 1) throw new ServiceException("账号不存在", 404);
        Set<Long> ids = new LinkedHashSet<>(cities == null ? Collections.emptyList() : cities);
        if (ids.size()>500) throw new ServiceException("授权城市过多",422);
        for (Long id:ids) if(id==null || jdbc.queryForObject("select count(*) from kg_city where city_id=? and status='0'",Long.class,id)!=1) throw new ServiceException("授权城市不存在或已停用",422);
        jdbc.update("insert into kg_admin_city_scope(user_id,all_cities,update_by,update_time) values(?,?,?,now()) on duplicate key update all_cities=values(all_cities),update_by=values(update_by),update_time=now()",user,all?1:0,SecurityUtils.getUsername());
        jdbc.update("delete from kg_admin_city_grant where user_id=?",user);
        if(!all) for(Long city:ids) jdbc.update("insert into kg_admin_city_grant(user_id,city_id) values(?,?)",user,city);
    }
}
