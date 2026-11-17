-- =============================================================================
-- V43__inbox_interactions（由 .dev/tools/make_migration.py 组装：头尾片段原样复制，账本行自动计算）
-- =============================================================================
-- @kg-header-begin
-- ---- 迁移头部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
-- 用 mysql 客户端跑脚本时会话时区是 SYSTEM；钉到 +08:00，now() 写进审计列才和应用写的对得上
set time_zone = '+08:00';

drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;

delimiter $$

-- 库默认字符集必须是 utf8mb4；p_requires 列出的前置迁移版本（逗号分隔）必须都已记在账本里
create procedure kg_assert_env(in p_requires varchar(255))
begin
    declare v_left varchar(255) default trim(ifnull(p_requires, ''));
    declare v_ver varchar(16);
    declare v_pos int;
    declare v_msg varchar(255);
    if @@character_set_database <> 'utf8mb4' then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 库默认字符集不是 utf8mb4，已中止';
    end if;
    if v_left <> '' and not exists (select 1 from information_schema.tables
                                    where table_schema = database() and table_name = 'kg_schema_version') then
        signal sqlstate '45000' set message_text = '[kg_assert_env] 没有迁移账本 kg_schema_version（V6 未执行），已中止';
    end if;
    while v_left <> '' do
        set v_pos = locate(',', v_left);
        if v_pos = 0 then
            set v_ver = v_left;
            set v_left = '';
        else
            set v_ver = trim(substring(v_left, 1, v_pos - 1));
            set v_left = trim(substring(v_left, v_pos + 1));
        end if;
        if v_ver <> '' and not exists (select 1 from kg_schema_version where version = cast(v_ver as unsigned)) then
            set v_msg = concat('[kg_assert_env] 前置迁移 V', v_ver, ' 未执行（账本里没有），已中止');
            signal sqlstate '45000' set message_text = v_msg;
        end if;
    end while;
end$$

-- 新表的排序规则必须与 kg_application 相同，否则 join 时报 Illegal mix of collations
create procedure kg_assert_same_collation(in p_table varchar(64))
begin
    declare v_ref varchar(64);
    declare v_got varchar(64);
    declare v_msg varchar(255);
    set v_ref = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = 'kg_application');
    set v_got = (select table_collation from information_schema.tables
                  where table_schema = database() and table_name = p_table);
    if v_got is null or v_ref is null or v_got <> v_ref then
        -- SIGNAL 的 message_text 上限 128 个字符，这里控制在 100 以内
        set v_msg = left(concat('[kg_assert_same_collation] ', p_table, ' 排序规则 ', ifnull(v_got, '(无此表)'),
                                ' ≠ kg_application 的 ', ifnull(v_ref, '(无此表)')), 120);
        signal sqlstate '45000' set message_text = v_msg;
    end if;
end$$

create procedure kg_add_col_if_absent(in p_table varchar(64), in p_column varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.columns
                   where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' add column ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_add_idx_if_absent(in p_table varchar(64), in p_index varchar(64), in p_ddl varchar(1024))
begin
    if not exists (select 1 from information_schema.statistics
                   where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' add ', p_ddl);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_col_if_present(in p_table varchar(64), in p_column varchar(64))
begin
    if exists (select 1 from information_schema.columns
               where table_schema = database() and table_name = p_table and column_name = p_column) then
        set @kg_s = concat('alter table ', p_table, ' drop column ', p_column);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

create procedure kg_drop_idx_if_present(in p_table varchar(64), in p_index varchar(64))
begin
    if exists (select 1 from information_schema.statistics
               where table_schema = database() and table_name = p_table and index_name = p_index) then
        set @kg_s = concat('alter table ', p_table, ' drop index ', p_index);
        prepare kg_st from @kg_s; execute kg_st; deallocate prepare kg_st;
    end if;
end$$

delimiter ;
-- ---- 迁移头部标准片段结束 ----
-- @kg-header-end
call kg_assert_env('42');

-- V43（2026-10-15）：消息页改版（主人第 12 条「为啥没有点赞和评论」）：站内信新增「评论」「赞」两类。
--   一、kg_message 新增 actor_id：这条通知是谁触发的（评论人 / 最近点赞的人，kg_app_user.user_id），空 = 系统或业务流程发的。
--       消息页「收到的评论」「收到的赞」靠它显示对方昵称和头像；双方任一方拉黑对方后，这类通知自动不显示。
--   二、字典 kg_msg_category 加「评论 comment」「点赞 like」：后台「站内消息」列表能显示分类名。
--   三、老的评论通知（「你的动态有新评论」「有人回复了你的评论」）从「系统」改归「评论」，并按评论记录补上触发人：
--       之前评论通知混在系统通知里；改归后出现在新消息页的「评论」里。标题、正文、跳转都不动。
-- 可重复执行。后端要在本段执行之后再发布：新代码读写 actor_id，缺了拒绝启动（InboxInteractionsSchema）。
-- 老版本小程序不受影响：它不认识的分类一律归到「系统」页签。

-- =============================================================================
-- 一、触发人
-- =============================================================================
-- 之前：消息只记收件人，不知道是谁评论的，列表只能写「有人回复了你的评论」。
-- 现在：评论通知一条评论一行，actor_id = 评论人；点赞通知同一条动态 / 评论只占一行（「晨光 等 3 人赞了你的动态」），actor_id = 最近点赞的人。
call kg_add_col_if_absent('kg_message', 'actor_id', 'actor_id bigint null comment ''触发人（评论人 / 最近点赞的人，kg_app_user.user_id；空 = 系统或业务流程发的）'' after biz_id');
-- 注销账号时按 actor_id 清掉此人触发的通知（KgAccountService），加索引免得整表扫描、长时间锁行
call kg_add_idx_if_absent('kg_message', 'idx_msg_actor', 'key idx_msg_actor (actor_id)');

-- =============================================================================
-- 二、消息分类字典
-- =============================================================================
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 9, '评论', 'comment', 'kg_msg_category', '', 'success', 'N', '0', 'admin', sysdate(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_msg_category' and dict_value = 'comment');
insert into sys_dict_data (dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default, status, create_by, create_time, remark)
select 10, '点赞', 'like', 'kg_msg_category', '', 'danger', 'N', '0', 'admin', sysdate(), ''
from dual where not exists (select 1 from sys_dict_data where dict_type = 'kg_msg_category' and dict_value = 'like');

-- =============================================================================
-- 三、老评论通知改归「评论」
-- =============================================================================
-- 只改这两种标题（审核结果「你的动态已通过审核」「你的评论未通过审核」等仍是系统通知）。
update kg_message set category = 'comment'
 where mode = 'user' and category = 'system' and biz_type = 'post'
   and title in ('你的动态有新评论', '有人回复了你的评论');

-- 老评论通知补上触发人，消息页才能显示是谁评论的：老代码发通知时正文 = 评论前 60 字、跳转到这条动态；
-- 「你的动态有新评论」= 一级评论（没有回复对象），「有人回复了你的评论」= 回复收件人。按同一条动态、正文、回复对象找评论，
-- 取通知发出前（审核通过后才发的，评论时间早于通知）最近的一条。找不到的留空，消息页照旧显示原标题。只填新列，不改任何已有列。
update kg_message m
   set m.actor_id = (
       select c.app_user_id from kg_post_comment c
        where c.post_id = m.biz_id and c.app_user_id <> m.recipient_id
          and left(c.content, 60) = m.content
          and ((m.title = '有人回复了你的评论' and c.reply_user_id = m.recipient_id)
               or (m.title = '你的动态有新评论' and c.reply_user_id is null))
          and c.create_time <= date_add(m.create_time, interval 5 second)
        order by c.create_time desc, c.comment_id desc limit 1)
 where m.mode = 'user' and m.category = 'comment' and m.biz_type = 'post' and m.actor_id is null
   and m.title in ('你的动态有新评论', '有人回复了你的评论');

-- 回退过再重新执行时，把回退时改回「系统」的新式评论 / 点赞通知归位（第一次执行时库里没有这三种 biz_type，不影响任何行）
update kg_message set category = 'comment' where mode = 'user' and category = 'system' and biz_type = 'comment';
update kg_message set category = 'like' where mode = 'user' and category = 'system' and biz_type in ('post_like', 'comment_like');
-- 回退时 actor_id 列连同触发人一起删掉了，归位的同时补回来（第一次执行时同样一行都不影响）：
-- 不补的话这些通知在消息页没有对方昵称和头像，双方拉黑后也不会隐藏。评论通知 = 这条评论的评论人；点赞通知 = 现在最近点赞的人（不含收件人自己）
update kg_message m join kg_post_comment c on c.comment_id = m.biz_id
   set m.actor_id = c.app_user_id
 where m.mode = 'user' and m.category = 'comment' and m.biz_type = 'comment' and m.actor_id is null;
update kg_message m
   set m.actor_id = (select l.app_user_id from kg_post_like l where l.post_id = m.biz_id and l.app_user_id <> m.recipient_id
                      order by l.create_time desc, l.like_id desc limit 1)
 where m.mode = 'user' and m.category = 'like' and m.biz_type = 'post_like' and m.actor_id is null;
update kg_message m
   set m.actor_id = (select l.app_user_id from kg_post_comment_like l where l.comment_id = m.biz_id and l.app_user_id <> m.recipient_id
                      order by l.create_time desc, l.id desc limit 1)
 where m.mode = 'user' and m.category = 'like' and m.biz_type = 'comment_like' and m.actor_id is null;

-- @kg-footer-begin
-- ---- 迁移尾部标准片段（原样复制自 sql/kangaroo/_snippets/header.sql，勿手改）----
drop procedure if exists kg_assert_env;
drop procedure if exists kg_assert_same_collation;
drop procedure if exists kg_add_col_if_absent;
drop procedure if exists kg_add_idx_if_absent;
drop procedure if exists kg_drop_col_if_present;
drop procedure if exists kg_drop_idx_if_present;
-- ---- 迁移尾部标准片段结束 ----
-- @kg-footer-end
-- @kg-ledger（本行起不计入 checksum：checksum = 本行之前全部内容的 sha256）
insert into kg_schema_version (version, name, applied_time, checksum)
values (43, 'inbox_interactions', now(), '2bd5ba8dd67dea34ea98d8dc8056232201818e38aab99fc16001e457a0bb76b1')
on duplicate key update name = 'inbox_interactions', applied_time = now(), checksum = '2bd5ba8dd67dea34ea98d8dc8056232201818e38aab99fc16001e457a0bb76b1';
