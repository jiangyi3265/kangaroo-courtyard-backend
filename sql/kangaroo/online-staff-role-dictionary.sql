-- Role metadata only. No schema changes or grants to accounts.
-- The backend refreshes dictionary caches at startup after this release is activated.
insert into sys_dict_data(dict_sort,dict_label,dict_value,dict_type,css_class,list_class,is_default,status,create_by,create_time,remark)
select 4,'线上任务专员','online_staff','kg_staff_role','','success','N','0','online_staff_release',now(),'仅处理获授权门店的线上任务：回复消息、审核报名、验收成果'
where not exists(select 1 from sys_dict_data where dict_type='kg_staff_role' and dict_value='online_staff');
