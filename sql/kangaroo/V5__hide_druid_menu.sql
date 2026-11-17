-- V5：「系统监控 → 数据监控」菜单默认隐藏
--
-- 为什么：application-druid.yml 里 Druid 控制台默认关闭（DRUID_CONSOLE_ENABLED 缺省为 false，
-- 且不再内置任何默认账号口令），但 V2 补进来的「数据监控」菜单仍然可见 ——
-- 运营从侧边栏点进去是一个 404（/druid/login.html），这是后台浏览器用例 C22 抓到的。
--
-- 做法：只把显示状态改成隐藏（visible='1'），菜单行与 monitor:druid:list 权限标识都保留，
-- 不删任何数据。若依的 /getRouters 会把隐藏菜单的路由照常下发但不进侧边栏。
--
-- 需要 Druid 控制台的环境：设置 DRUID_CONSOLE_ENABLED=true，并配好
-- DRUID_CONSOLE_USER / DRUID_CONSOLE_PASSWORD，再执行 V5__rollback.sql 把菜单显示回来。
--
-- 可重复执行：条件里带了 path，重复跑只是再写一次同一个值。
update sys_menu set visible = '1', update_by = 'V5', update_time = sysdate()
 where menu_id = 111 and path = 'druid';
select count(*) as '数据监控菜单已隐藏（应为 1）' from sys_menu where menu_id = 111 and visible = '1';
