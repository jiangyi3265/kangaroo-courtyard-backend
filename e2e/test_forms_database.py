"""V66（包 20261031-forms）的真实库验证：克隆验收库到一次性的 acceptance_forms_<hex>_guardtest（只读源库，绝不写它），补上 V64、V65，
真跑 V66 前滚、重复前滚、回滚的每道拒绝、回滚后逐项比快照、再前滚。报销（R12）的表、菜单、回滚拒绝也在这里。跑完删库。不连线上，不碰别的库。

用法：python -B -X utf8 RuoYi-Vue/e2e/test_forms_database.py
  KG_FORMS_SOURCE_DB 指定克隆来源（默认 kg_acceptance_admin_20261028）；KG_MYSQL 指定 mysql.exe。
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, sys, uuid
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password
from migration_guard_policy import validate_guard_databases, authorized_rollback_prefix
BE = ROOT / 'RuoYi-Vue'
MIG = BE / 'sql' / 'kangaroo'
SOURCE = os.environ.get('KG_FORMS_SOURCE_DB', 'kg_acceptance_admin_20261028')
GUARD = 'acceptance_forms_' + uuid.uuid4().hex[:12] + '_guardtest'
assert re.fullmatch(r'acceptance_forms_[a-f0-9]{12}_guardtest', GUARD)
validate_guard_databases(SOURCE, GUARD)
mysql_bin = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
dump_bin = str(Path(mysql_bin).with_name('mysqldump.exe'))
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)))
END = '__KG_END__'
NEW_COLUMNS = ['meal_allowance', 'meal_allowance_unit', 'pet_status', 'pet_images', 'lodging_images', 'lodging_video', 'lodging_video_cover']
OLD_NAME = '\u4e91\u8fde\u9501'
NEW_NAME = '\u6296\u97f3\u5356\u623f'
checks = []
failed = []


def check(name, ok, detail=''):
    checks.append(('ok ' if ok else 'FAIL ') + name + ((' : ' + str(detail)) if detail and not ok else ''))
    if not ok:
        failed.append(name)


def sql(text, db=None, tolerate=False):
    cmd = [mysql_bin, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '-n', '-N', '-B', '--raw']
    if db:
        cmd.append(db)
    r = subprocess.run(cmd, input=(text.rstrip().rstrip(';') + ";\nselect '%s';\n" % END).encode('utf-8'), capture_output=True, env=env, timeout=300)
    out = r.stdout.decode('utf-8', 'replace').replace('\r', '')
    err = r.stderr.decode('utf-8', 'replace')
    if r.returncode != 0:
        if tolerate:
            return None, err
        raise RuntimeError(err[:1000])
    lines = [l for l in out.split('\n') if l.strip()]
    assert lines and lines[-1].strip() == END, 'mysql 输出丢失'
    return '\n'.join(lines[:-1]), err


def script(path, prefix=''):
    return prefix + path.read_text(encoding='utf-8')


def snapshot(db):
    q = ("select 'table', table_name, table_collation, table_comment from information_schema.tables where table_schema='%(d)s' order by table_name;"
         "select 'col', table_name, column_name, column_type, is_nullable, ifnull(column_default,'<null>'), column_comment from information_schema.columns where table_schema='%(d)s' order by table_name, ordinal_position;"
         "select 'idx', table_name, index_name, seq_in_index, column_name from information_schema.statistics where table_schema='%(d)s' order by 1,2,3,4;"
         "select 'proc', routine_name from information_schema.routines where routine_schema='%(d)s' order by 1;"
         "select 'menu', menu_id, menu_name, parent_id, perms, component, status, visible, remark from sys_menu order by menu_id;"
         "select 'rolemenu', role_id, menu_id from sys_role_menu order by 1,2,3;"
         "select 'dict', dict_code, dict_value, dict_label, dict_sort, status, is_default, remark from sys_dict_data order by dict_code;"
         "select 'config', config_key, config_value from sys_config order by config_key;"
         "select 'ledger', version, name, checksum from kg_schema_version order by version;"
         "select 'phase', phase from kg_role_switch order by id;"
         "select 'grant', grant_id, app_user_id, store_id, role_code, status, del_flag from kg_staff_grant order by grant_id;"
         "select 'request', request_id, request_type, title, content, status, ifnull(update_time,'') from kg_merchant_request order by request_id;"
         "select 'msg', msg_id, title, content, biz_type from kg_message where biz_type in ('merchant_request','forms_guard') order by msg_id;"
         "select 'oplog', count(*), ifnull(max(log_id),0), (select action_label from kg_staff_op_log where operator_name='guard' limit 1) from kg_staff_op_log;"
         "select 'jobs', count(*), md5(group_concat(concat_ws('|',job_id,title,status,board_type,images,ifnull(update_time,'')) order by job_id)) from kg_job;"
         % {'d': db})
    return sql("set session group_concat_max_len = 16000000;" + q, db)[0]


def only_proc_differs(a, b):
    drop = lambda t: [l for l in t.split('\n') if not l.startswith('proc\t')]
    return drop(a) == drop(b)


created = False
try:
    assert sql('select count(*) from kg_schema_version where version=66', SOURCE)[0] == '0', '来源库已跑过 V66，不能做前滚基线'
    collation = sql("select default_collation_name from information_schema.schemata where schema_name='%s'" % SOURCE)[0]
    assert re.fullmatch(r'[a-zA-Z0-9_]+', collation)
    sql('create database %s character set utf8mb4 collate %s' % (GUARD, collation))
    created = True
    dump = subprocess.run([dump_bin, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', '--single-transaction', '--routines', '--no-tablespaces',
                           '--set-gtid-purged=OFF', '--skip-triggers', SOURCE], capture_output=True, env=env, timeout=300)
    assert dump.returncode == 0, dump.stderr[:500]
    load = subprocess.run([mysql_bin, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4', GUARD], input=dump.stdout, capture_output=True, env=env, timeout=600)
    assert load.returncode == 0, load.stderr[:500]
    checks.append('ok 克隆 %s → %s（%d 字节）' % (SOURCE, GUARD, len(dump.stdout)))
    for v, name in ((64, 'V64__role_switch.sql'), (65, 'V65__stay_comment.sql')):
        if sql('select count(*) from kg_schema_version where version=%d' % v, GUARD)[0] == '0':
            sql(script(MIG / name), GUARD)
            checks.append('ok 克隆库补 V%d' % v)
    check('基线：账本有 60、62、63、64、65，没有 61', sql('select count(*) from kg_schema_version where version in (60,62,63,64,65)', GUARD)[0] == '5' and sql('select count(*) from kg_schema_version where version=61', GUARD)[0] == '0')

    # ---- 探针数据：线上可能已有的几种行
    sql("delete from kg_message where biz_type='merchant_request'; delete from kg_merchant_request;", GUARD)
    sql("insert into kg_merchant_request (request_id, request_type, store_name, city, title, content, status, source, create_by, create_time, update_time) values "
        "(9001, 'cloud_chain', '南舍庄园', '平潭', '%(o)s · 南舍庄园', '%(o)s怎么做，商家自己写的', 'pending_verify', 'merchant', 'guard', '2026-10-30 10:00:00', '2026-10-30 11:00:00'),"
        "(9002, 'managed', '南舍庄园', '平潭', '想问%(o)s', '商家写的', 'pending_verify', 'merchant', 'guard', '2026-10-30 10:00:00', null),"
        "(9003, 'cloud_chain', '自己写标题', '平潭', '我自己起的标题', '说明', 'done', 'merchant', 'guard', '2026-10-30 10:00:00', null);" % {'o': OLD_NAME}, GUARD)
    sql("insert into kg_message (recipient_id, mode, category, title, content, biz_type, biz_id, read_flag, del_flag, create_time) values "
        "(1, 'staff', 'system', '有商家需求待核实', '「南舍庄园」提交了%(o)s「%(o)s · 南舍庄园」，请和商家沟通后确认转发', 'merchant_request', 9001, '0', '0', now()),"
        "(1, 'staff', 'system', '%(o)s已核实', '南舍庄园', 'merchant_request', 9001, '1', '0', now()),"
        "(1, 'user', 'system', '别的业务里提到%(o)s', '不能动：%(o)s', 'forms_guard', 1, '0', '0', now());" % {'o': OLD_NAME}, GUARD)
    sql("insert into kg_staff_op_log (store_id, app_user_id, operator_name, role_code, action, action_label, category, detail, ok, create_time) values (1, 1, 'guard', 'merchant', 'merchant_request', '提交%s', 'general', '南舍庄园', '1', now())" % OLD_NAME, GUARD)

    up = MIG / 'V66__forms.sql'
    down = MIG / 'V66__rollback.sql'
    before = snapshot(GUARD)
    allow = authorized_rollback_prefix(66, SOURCE, GUARD)
    check('V66 回滚授权变量已登记', 'kg_allow_forms_rollback' in allow, allow)

    # ---- 没有前置迁移时拒绝
    sql("create table kg_schema_version_guard_backup like kg_schema_version; insert into kg_schema_version_guard_backup select * from kg_schema_version; delete from kg_schema_version where version=65", GUARD)
    r, err = sql(script(up), GUARD, tolerate=True)
    check('V66 前滚：账本没有 V65 时拒绝', r is None and 'V65' in err, err[:200])
    check('V66 前滚：被拒后 kg_job 没加列', sql("select count(*) from information_schema.columns where table_schema='%s' and table_name='kg_job' and column_name='meal_allowance'" % GUARD)[0] == '0')
    sql('delete from kg_schema_version; insert into kg_schema_version select * from kg_schema_version_guard_backup; drop table kg_schema_version_guard_backup', GUARD)
    check('恢复账本后快照回到基线（只差存储过程）', only_proc_differs(snapshot(GUARD), before))
    before = snapshot(GUARD)

    # ---- 前滚
    sql(script(up), GUARD)
    once = snapshot(GUARD)
    cols = sql("select column_name, column_type, is_nullable, ifnull(column_default,'<null>') from information_schema.columns where table_schema='%s' and table_name='kg_job' and column_name in (%s) order by column_name"
               % (GUARD, ','.join("'%s'" % c for c in NEW_COLUMNS)), GUARD)[0].split('\n')
    check('V66 前滚：7 列都在', len(cols) == 7, cols)
    check('V66 前滚：只加可空列，默认 NULL（老 jar 插入不带它们）', all(c.split('\t')[2] == 'YES' and c.split('\t')[3] == '<null>' for c in cols), cols)
    widths = {c.split('\t')[0]: c.split('\t')[1] for c in cols}
    check('V66 前滚：列宽', widths == {'meal_allowance': 'varchar(12)', 'meal_allowance_unit': 'varchar(8)', 'pet_status': 'varchar(8)', 'pet_images': 'varchar(1000)', 'lodging_images': 'varchar(2000)',
                                      'lodging_video': 'varchar(255)', 'lodging_video_cover': 'varchar(255)'}, widths)
    check('V66 前滚：老行全是 NULL，不回填', sql('select count(*) from kg_job where %s' % ' or '.join(c + ' is not null' for c in NEW_COLUMNS), GUARD)[0] == '0')
    check('V66 前滚：账本 66', sql('select name from kg_schema_version where version=66', GUARD)[0] == 'forms')
    same = lambda prefix: [l for l in once.split('\n') if l.startswith(prefix)] == [l for l in before.split('\n') if l.startswith(prefix)]
    check('V66 前滚：不碰授权、参数、阶段、岗位行的原有列', all(same(p) for p in ('grant\t', 'config\t', 'phase\t', 'jobs\t')))
    check('V66 前滚：没留存储过程', sql("select count(*) from information_schema.routines where routine_schema='%s' and routine_name like 'kg\\_%%'" % GUARD)[0] == '0')

    t = lambda rid: sql('select title from kg_merchant_request where request_id=%d' % rid, GUARD)[0]
    check('改名：默认标题', t(9001) == NEW_NAME + ' · 南舍庄园', t(9001))
    check('改名：别的类型标题里出现老名字不动', t(9002) == '想问' + OLD_NAME, t(9002))
    check('改名：自己起的标题不动', t(9003) == '我自己起的标题', t(9003))
    check('改名：商家写的说明不动', sql('select content from kg_merchant_request where request_id=9001', GUARD)[0] == OLD_NAME + '怎么做，商家自己写的')
    check('改名：内部编码不变', sql("select count(*) from kg_merchant_request where request_type='cloud_chain'", GUARD)[0] == '2')
    check('改名：update_time 不动', sql('select update_time from kg_merchant_request where request_id=9001', GUARD)[0] == '2026-10-30 11:00:00')
    msg = sql("select title, content from kg_message where biz_type='merchant_request' order by msg_id", GUARD)[0]
    check('改名：已发站内信的标题和正文', OLD_NAME not in msg and NEW_NAME in msg, msg)
    check('改名：老操作日志不动', sql("select action_label from kg_staff_op_log where operator_name='guard'", GUARD)[0] == '提交' + OLD_NAME)
    check('改名：别的业务的消息不动', sql("select content from kg_message where biz_type='forms_guard'", GUARD)[0] == '不能动：' + OLD_NAME)
    check('改名：字典备注', sql("select remark from sys_dict_data where dict_type='kg_staff_role' and dict_value='merchant_service'", GUARD)[0].startswith('代运营、' + NEW_NAME))
    check('改名：菜单备注', NEW_NAME in sql("select remark from sys_menu where perms='kangaroo:merchantrequest:list'", GUARD)[0] and OLD_NAME not in sql("select remark from sys_menu where perms='kangaroo:merchantrequest:list'", GUARD)[0])

    # ---- 报销（R12）：表、菜单、角色
    rb_cols = dict(l.split('\t')[0:2] for l in sql("select column_name, column_type from information_schema.columns where table_schema='%s' and table_name='kg_reimbursement'" % GUARD, GUARD)[0].split('\n'))
    check('报销：金额 decimal(10,2)，100000.00 放得下', rb_cols.get('amount') == 'decimal(10,2)', rb_cols)
    check('报销：状态、驳回理由、审核人、审核时间、打款时间、软删都有', all(c in rb_cols for c in ('status', 'reject_reason', 'audit_by', 'audit_time', 'paid_time', 'del_flag', 'receipts', 'purchase_date', 'quantity', 'item_name', 'remark', 'store_id', 'app_user_id')), rb_cols)
    check('报销：排序规则和 kg_application 一样', sql("select count(distinct table_collation) from information_schema.tables where table_schema='%s' and table_name in ('kg_reimbursement','kg_application')" % GUARD, GUARD)[0] == '1')
    check('报销：没有数据', sql('select count(*) from kg_reimbursement', GUARD)[0] == '0')
    menu = sql("select m.menu_id, m.menu_name, m.menu_type, p.menu_name, m.component, m.route_name, m.perms from sys_menu m join sys_menu p on p.menu_id = m.parent_id where m.perms like 'kangaroo:reimburse:%' order by m.menu_id", GUARD)[0].split('\n')
    check('报销：菜单优先用固定编号，放在「旅居」下面', menu == ['2288\t采购报销\tC\t旅居\tkangaroo/reimburse/index\tKgReimburse\tkangaroo:reimburse:list', '2289\t报销审核\tF\t采购报销\t\t\tkangaroo:reimburse:audit']
          or menu == ['2288\t采购报销\tC\t旅居\tkangaroo/reimburse/index\tKgReimburse\tkangaroo:reimburse:list', '2289\t报销审核\tF\t采购报销\tNULL\t\tkangaroo:reimburse:audit'], menu)
    check('报销：只属于角色 1', sql("select group_concat(distinct rm.role_id), count(*) from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:reimburse:%'", GUARD)[0] == '1\t2')

    # ---- 重复前滚
    sql(script(up), GUARD)
    check('V66 重复前滚：快照逐项相同', snapshot(GUARD) == once)

    # ---- 回滚守卫
    r, err = sql(script(down), GUARD, tolerate=True)
    check('V66 回滚：没有授权变量时拒绝', r is None and 'explicit authorization' in err, err[:200])
    check('V66 回滚：拒绝后数据和结构都没变', only_proc_differs(snapshot(GUARD), once))
    probe_job = sql('select min(job_id) from kg_job', GUARD)[0]
    for col, value in (('meal_allowance', '30'), ('meal_allowance_unit', 'day'), ('pet_status', 'none'), ('pet_images', '["/profile/upload/a.jpg"]'), ('lodging_images', '["/profile/upload/a.jpg"]'),
                       ('lodging_video', '/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4'), ('lodging_video_cover', '/profile/upload/a.jpg')):
        sql("update kg_job set %s = '%s' where job_id = %s" % (col, value, probe_job), GUARD)
        r, err = sql(script(down, allow), GUARD, tolerate=True)
        check('V66 回滚：%s 有内容时拒绝' % col, r is None and 'has meal, pet or lodging data' in err, err[:200])
        check('V66 回滚：被拒后 %s 还在' % col, sql('select %s from kg_job where job_id = %s' % (col, probe_job), GUARD)[0] == value)
        sql('update kg_job set %s = null where job_id = %s' % (col, probe_job), GUARD)
    sql("update kg_job set pet_status = '' where job_id = %s" % probe_job, GUARD)
    r, err = sql(script(down, allow) + ";\nselect 'probe-empty-string-ok';", GUARD, tolerate=True)
    empty_ok = r is not None
    check('V66 回滚：空串不算内容（新版显式清空过的行）', empty_ok, err[:200])
    if empty_ok:
        sql(script(up), GUARD)
    else:
        sql('update kg_job set pet_status = null where job_id = %s' % probe_job, GUARD)
    sql("insert into kg_schema_version (version, name, applied_time, checksum) values (67, 'guardtest', now(), repeat('0',64))", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V66 回滚：有更高版本时拒绝', r is None and 'later migrations' in err, err[:200])
    sql('delete from kg_schema_version where version=67', GUARD)
    # 报销是财务记录：有一行就拒绝，撤回的、注销转匿名的都算
    for label, extra in (('待审核', "'pending', '0', '0'"), ('已打款', "'paid', '0', '0'"), ('已撤回（软删）', "'pending', '2', '0'"), ('注销转匿名', "'rejected', '0', '1'")):
        sql("insert into kg_reimbursement (store_id, store_name, app_user_id, submitter_name, item_name, quantity, amount, purchase_date, receipts, status, del_flag, anonymous, submit_time, create_time) "
            "values (1, '店', 1, '人', '床单', 1, 100000.00, '2026-10-31', '[]', %s, now(), now())" % extra, GUARD)
        r, err = sql(script(down, allow), GUARD, tolerate=True)
        check('V66 回滚：报销有一行（%s）时拒绝' % label, r is None and 'kg_reimbursement has data' in err, err[:200])
        check('V66 回滚：被拒后这一行还在，表也在（%s）' % label, sql("select amount from kg_reimbursement", GUARD)[0] == '100000.00')
        sql('delete from kg_reimbursement', GUARD)
    check('V66 回滚：几次拒绝之后快照和前滚后相同（只差存储过程）', only_proc_differs(snapshot(GUARD), once))

    # ---- 回滚
    sql(script(down, allow), GUARD)
    back = snapshot(GUARD)
    check('V66 回滚：表、列、过程、菜单和字典备注、需求标题、站内信、账本逐项回到前滚之前', only_proc_differs(back, before), '差异：%s' % sorted(set(back.split('\n')) ^ set(before.split('\n')))[:6])
    sql(script(down, allow), GUARD)
    check('V66 回滚：账本没有这一版时重复回滚也不出错、不多动', only_proc_differs(snapshot(GUARD), before))

    # ---- 报销菜单：固定编号被占时退给自增，别的菜单不动；回滚只删自己的
    sql("insert into sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, route_name, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark) values "
        "(2288, '占位菜单甲', 2200, 90, '', null, '', '', 1, 0, 'F', '0', '0', 'guard:occupied:a', '#', 'guard', now(), ''), (2289, '占位菜单乙', 2200, 91, '', null, '', '', 1, 0, 'F', '0', '0', 'guard:occupied:b', '#', 'guard', now(), '')", GUARD)
    sql(script(up), GUARD)
    moved = sql("select menu_id, perms, parent_id, (select menu_id from sys_menu x where x.perms = 'kangaroo:reimburse:list') from sys_menu where perms like 'kangaroo:reimburse:%' order by menu_id", GUARD)[0].split('\n')
    check('报销菜单：编号被占时退给自增，父子关系对', len(moved) == 2 and all(int(l.split('\t')[0]) not in (2288, 2289) for l in moved) and moved[1].split('\t')[2] == moved[1].split('\t')[3], moved)
    check('报销菜单：占位的两行没被动', sql("select group_concat(menu_name order by menu_id) from sys_menu where menu_id in (2288, 2289)", GUARD)[0] == '占位菜单甲,占位菜单乙')
    check('报销菜单：退到自增时还是只给角色 1', sql("select group_concat(distinct rm.role_id), count(*) from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:reimburse:%'", GUARD)[0] == '1\t2')
    sql(script(up), GUARD)
    check('报销菜单：退到自增后重复前滚不会多一行', sql("select count(*) from sys_menu where perms like 'kangaroo:reimburse:%'", GUARD)[0] == '2')
    # 运营账号被主人授了这个菜单：回滚也要清掉，不留孤儿
    sql("insert into sys_role_menu (role_id, menu_id) select 2, menu_id from sys_menu where perms = 'kangaroo:reimburse:list'", GUARD)
    sql(script(down, allow), GUARD)
    check('报销菜单：回滚删掉自己的菜单和所有角色授权，占位的两行还在', sql("select count(*) from sys_menu where perms like 'kangaroo:reimburse:%'", GUARD)[0] == '0'
          and sql("select count(*) from sys_role_menu rm left join sys_menu m on m.menu_id = rm.menu_id where m.menu_id is null", GUARD)[0] == '0'
          and sql("select count(*) from sys_menu where menu_id in (2288, 2289)", GUARD)[0] == '2')
    sql("delete from sys_menu where menu_id in (2288, 2289)", GUARD)
    check('报销菜单：探针清掉后回到基线', only_proc_differs(snapshot(GUARD), before), '差异：%s' % sorted(set(snapshot(GUARD).split('\n')) ^ set(before.split('\n')))[:6])

    # ---- 超长标题：left 截断不报错
    long_title = OLD_NAME + ' · ' + ('长' * 58)
    assert len(long_title) == 64
    sql("update kg_merchant_request set title = '%s' where request_id = 9003" % long_title, GUARD)
    sql(script(up), GUARD)
    got = sql('select title from kg_merchant_request where request_id=9003', GUARD)[0]
    check('V66 超长标题：截到 64 字，不报错', len(got) == 64 and got.startswith(NEW_NAME), got)
    sql(script(down, allow), GUARD)
    sql("update kg_merchant_request set title = '我自己起的标题' where request_id = 9003", GUARD)
    check('V66 超长探针清掉后回到基线', only_proc_differs(snapshot(GUARD), before))

    # ---- 再前滚
    sql(script(up), GUARD)
    again = snapshot(GUARD)
    text = up.read_text(encoding='utf-8')
    head = text[:text.index('-- @kg-ledger')]
    check('V66 账本 checksum 和脚本一致', sql('select checksum from kg_schema_version where version=66', GUARD)[0] == hashlib.sha256(head.encode('utf-8')).hexdigest())
    check('V66 再前滚：快照和第一次相同', again == once, '差异：%s' % sorted(set(again.split('\n')) ^ set(once.split('\n')))[:6])
finally:
    if created:
        sql('drop database if exists ' + GUARD)
        checks.append('已删除 ' + GUARD)
print(json.dumps({'database': GUARD, 'source': SOURCE, 'failed': failed, 'passed': sum(1 for c in checks if c.startswith('ok ')), 'checks': checks}, ensure_ascii=False, indent=2))
sys.exit(1 if failed else 0)
