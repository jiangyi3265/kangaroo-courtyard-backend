-- V5 回退：把「系统监控 → 数据监控」菜单重新显示出来。
-- 只在已经打开 Druid 控制台（DRUID_CONSOLE_ENABLED=true 且配好了账号口令）的环境执行，
-- 否则侧边栏又会出现一个点进去是 404 的菜单。
update sys_menu set visible = '0', update_by = 'V5-rollback', update_time = sysdate()
 where menu_id = 111 and path = 'druid';
