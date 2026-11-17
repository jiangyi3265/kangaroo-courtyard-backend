-- =============================================================================
-- 清理端到端测试产生的数据。
--
-- 只删除 test_kangaroo_flow.py 造出来的记录：
--   门店名以 'E2E测试小院-' 或 '别人家小院-' 开头
--   岗位标题以 'E2E' 开头
--   账号 openid 以 'mock_e2e-' 开头
-- 演示数据（seed_demo_data.py 创建的门店与岗位）不受影响。
--
-- 用法：mysql -uroot -p dkxy < e2e/cleanup.sql
-- =============================================================================

set @e2e_stores = (select group_concat(store_id) from kg_store
                   where store_name like 'E2E测试小院-%' or store_name like '别人家小院-%');

-- 1) 面试与时段
delete iv from kg_interview iv
  join kg_application a on a.app_id = iv.app_id
  join kg_store s on s.store_id = a.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

delete sl from kg_interview_slot sl
  join kg_store s on s.store_id = sl.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

-- 2) 报名与日志
delete l from kg_application_log l
  join kg_application a on a.app_id = l.app_id
  join kg_store s on s.store_id = a.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

delete a from kg_application a
  join kg_store s on s.store_id = a.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

-- 3) 收藏与岗位
delete f from kg_job_favorite f
  join kg_job j on j.job_id = f.job_id
 where j.title like 'E2E%';

delete j from kg_job j
  join kg_store s on s.store_id = j.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

delete from kg_job where title like 'E2E%';

-- 4) 员工授权与门店
delete g from kg_staff_grant g
  join kg_store s on s.store_id = g.store_id
 where s.store_name like 'E2E测试小院-%' or s.store_name like '别人家小院-%';

delete from kg_store where store_name like 'E2E测试小院-%' or store_name like '别人家小院-%';

-- 5) 测试账号及其消息
--    覆盖两类：
--      mock_e2e-%    test_kangaroo_flow.py 造的账号
--      mock_h5-dev-% H5 页面联调时经登录流程真实创建的账号（每个浏览器上下文一个）
delete iv2 from kg_interview iv2
  join kg_application a2 on a2.app_id = iv2.app_id
  join kg_app_user u2 on u2.user_id = a2.app_user_id
 where u2.openid like 'mock_e2e-%' or u2.openid like 'mock_h5-dev-%';

update kg_interview_slot sl
  join kg_application a3 on a3.app_id = sl.booked_app_id
  join kg_app_user u3 on u3.user_id = a3.app_user_id
   set sl.status = 'open', sl.booked_app_id = null
 where u3.openid like 'mock_e2e-%' or u3.openid like 'mock_h5-dev-%';

delete l2 from kg_application_log l2
  join kg_application a4 on a4.app_id = l2.app_id
  join kg_app_user u4 on u4.user_id = a4.app_user_id
 where u4.openid like 'mock_e2e-%' or u4.openid like 'mock_h5-dev-%';

delete a5 from kg_application a5
  join kg_app_user u5 on u5.user_id = a5.app_user_id
 where u5.openid like 'mock_e2e-%' or u5.openid like 'mock_h5-dev-%';

delete f2 from kg_job_favorite f2
  join kg_app_user u6 on u6.user_id = f2.app_user_id
 where u6.openid like 'mock_e2e-%' or u6.openid like 'mock_h5-dev-%';

delete g2 from kg_staff_grant g2
  join kg_app_user u7 on u7.user_id = g2.app_user_id
 where u7.openid like 'mock_e2e-%' or u7.openid like 'mock_h5-dev-%';

delete m from kg_message m
  join kg_app_user u on u.user_id = m.recipient_id
 where u.openid like 'mock_e2e-%' or u.openid like 'mock_h5-dev-%';

delete from kg_app_user where openid like 'mock_e2e-%' or openid like 'mock_h5-dev-%';

-- 6) 岗位上的报名/录用计数归零重算
--    这两个是冗余统计列，删掉报名记录后必须重算，否则后台会显示"名额 2/3"
--    但实际一条报名都没有，运营会以为系统算错了。
update kg_job j
   set j.applied_count = (select count(*) from kg_application a
                           where a.job_id = j.job_id and a.del_flag = '0'),
       j.hired_count   = (select count(*) from kg_application a
                           where a.job_id = j.job_id and a.del_flag = '0'
                             and a.status in ('store_confirmed', 'contract_signed', 'onboarded'));

-- 7) 幂等键
delete from kg_idempotency where idem_key like 'e2e-%';

select '清理完成' as result,
       (select count(*) from kg_store) as stores,
       (select count(*) from kg_job) as jobs,
       (select count(*) from kg_application) as applications,
       (select count(*) from kg_app_user) as app_users;
