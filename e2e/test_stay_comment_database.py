"""V65（小院评论）的真实库验证（2026-10-31）：克隆验收库到一次性的 acceptance_staycomment_<hex>_guardtest（只读源库，绝不写它），补上 V64，
真跑 V65 前滚、重复前滚、回滚的每道拒绝、回滚后逐项比快照、菜单编号被占时退让、再前滚。跑完删库。不连线上，不碰别的库。

用法：python -B -X utf8 RuoYi-Vue/e2e/test_stay_comment_database.py
  KG_STAY_COMMENT_SOURCE_DB 指定克隆来源（默认 kg_acceptance_admin_20261028）；KG_MYSQL 指定 mysql.exe。
"""
from pathlib import Path
import json, os, re, subprocess, sys, uuid
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password
from migration_guard_policy import validate_guard_databases, authorized_rollback_prefix
BE = ROOT / 'RuoYi-Vue'
MIG = BE / 'sql' / 'kangaroo'
SOURCE = os.environ.get('KG_STAY_COMMENT_SOURCE_DB', 'kg_acceptance_admin_20261028')
GUARD = 'acceptance_staycomment_' + uuid.uuid4().hex[:12] + '_guardtest'
assert re.fullmatch(r'acceptance_staycomment_[a-f0-9]{12}_guardtest', GUARD)
validate_guard_databases(SOURCE, GUARD)
mysql_bin = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
dump_bin = str(Path(mysql_bin).with_name('mysqldump.exe'))
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)))
END = '__KG_END__'
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
         "select 'menu', menu_id, menu_name, parent_id, perms, component, status, visible from sys_menu order by menu_id;"
         "select 'rolemenu', role_id, menu_id from sys_role_menu order by 1,2,3;"
         "select 'dict', dict_code, dict_value, dict_label, dict_sort, status, is_default from sys_dict_data order by dict_code;"
         "select 'config', config_key, config_value from sys_config order by config_key;"
         "select 'ledger', version, name, checksum from kg_schema_version order by version;"
         "select 'phase', phase from kg_role_switch order by id;"
         "select 'review', count(*), ifnull(sum(rating),0), ifnull(max(update_time),'') from kg_review;"
         "select 'diary', count(*), ifnull(max(update_time),'') from kg_stay_diary;"
         "select 'grant', grant_id, app_user_id, store_id, role_code, status, del_flag from kg_staff_grant order by grant_id;"
         % {'d': db})
    return sql(q, db)[0]


def only_proc_differs(a, b):
    drop = lambda t: [l for l in t.split('\n') if not l.startswith('proc\t')]
    return drop(a) == drop(b)


created = False
try:
    assert sql("select count(*) from kg_schema_version where version=65", SOURCE)[0] == '0', '来源库已跑过 V65，不能做前滚基线'
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
    if sql("select count(*) from kg_schema_version where version=64", GUARD)[0] == '0':
        sql(script(MIG / 'V64__role_switch.sql'), GUARD)
        checks.append('ok 克隆库补 V64')
    check('基线：账本有 60、62、63、64', sql("select count(*) from kg_schema_version where version in (60,62,63,64)", GUARD)[0] == '4')

    up = MIG / 'V65__stay_comment.sql'
    down = MIG / 'V65__rollback.sql'
    # 跑过一次 V64 留下的会话无关对象都在快照里：这里之后才开始比
    before = snapshot(GUARD)
    allow = authorized_rollback_prefix(65, SOURCE, GUARD)
    check('V65 回滚授权变量已登记', 'kg_allow_stay_comment_rollback' in allow, allow)

    # ---- 没有前置迁移时拒绝（在另一个空壳状态下验证：临时去掉账本 64）
    sql("create table kg_schema_version_guard_backup like kg_schema_version; insert into kg_schema_version_guard_backup select * from kg_schema_version; delete from kg_schema_version where version=64", GUARD)
    r, err = sql(script(up), GUARD, tolerate=True)
    check('V65 前滚：账本没有 V64 时拒绝', r is None and 'V64' in err, err[:200])
    check('V65 前滚：被拒后没有建表', sql("select count(*) from information_schema.tables where table_schema='%s' and table_name='kg_stay_comment'" % GUARD)[0] == '0')
    sql("delete from kg_schema_version; insert into kg_schema_version select * from kg_schema_version_guard_backup; drop table kg_schema_version_guard_backup", GUARD)
    check('恢复账本后快照回到基线（只差存储过程）', only_proc_differs(snapshot(GUARD), before))
    before = snapshot(GUARD)

    # ---- 不依赖 V61（生产账本没有）
    check('克隆库没有 V61，也能前滚（kg_store.images 不在）', sql("select count(*) from kg_schema_version where version=61", GUARD)[0] == '0')

    # ---- 前滚
    sql(script(up), GUARD)
    once = snapshot(GUARD)
    check('V65 前滚：kg_stay_comment 建好，排序规则和 kg_application 一致',
          sql("select count(*) from information_schema.tables a join information_schema.tables b on a.table_schema=b.table_schema and a.table_collation=b.table_collation "
              "where a.table_schema='%s' and a.table_name='kg_stay_comment' and b.table_name='kg_application'" % GUARD)[0] == '1')
    check('V65 前滚：三个菜单用固定编号 2285–2287',
          sql("select group_concat(menu_id order by menu_id) from sys_menu where perms like 'kangaroo:staycomment:%'", GUARD)[0] == '2285,2286,2287',
          sql("select group_concat(menu_id order by menu_id) from sys_menu where perms like 'kangaroo:staycomment:%'", GUARD)[0])
    vlog_parent = sql("select parent_id from sys_menu where perms='kangaroo:stayvlog:list'", GUARD)[0]
    check('V65 前滚：菜单挂在「旅居」目录（和 Vlog 审核同一个上级）', sql("select parent_id from sys_menu where perms='kangaroo:staycomment:list'", GUARD)[0] == vlog_parent)
    check('V65 前滚：菜单只属于角色 1',
          sql("select count(*) from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms like 'kangaroo:staycomment:%' and rm.role_id=1", GUARD)[0] == '3'
          and sql("select count(*) from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms like 'kangaroo:staycomment:%' and rm.role_id<>1", GUARD)[0] == '0')
    check('V65 前滚：账本 65', sql("select name from kg_schema_version where version=65", GUARD)[0] == 'stay_comment')
    same = lambda prefix: [l for l in once.split('\n') if l.startswith(prefix)] == [l for l in before.split('\n') if l.startswith(prefix)]
    check('V65 前滚：不碰 kg_review、kg_stay_diary、授权、字典、参数、阶段', all(same(p) for p in ('review\t', 'diary\t', 'grant\t', 'dict\t', 'config\t', 'phase\t')))
    check('V65 前滚：kg_review、kg_stay_diary 表结构不变', all([l for l in once.split('\n') if l.startswith(('col\t' + t + '\t', 'idx\t' + t + '\t'))] == [l for l in before.split('\n') if l.startswith(('col\t' + t + '\t', 'idx\t' + t + '\t'))] for t in ('kg_review', 'kg_stay_diary')))
    check('V65 前滚：没留存储过程', sql("select count(*) from information_schema.routines where routine_schema='%s' and routine_name like 'kg_v65%%'" % GUARD)[0] == '0')

    # ---- 重复前滚
    sql(script(up), GUARD)
    check('V65 重复前滚：快照逐项相同', snapshot(GUARD) == once)

    # ---- 回滚守卫
    r, err = sql(script(down), GUARD, tolerate=True)
    check('V65 回滚：没有授权变量时拒绝', r is None and 'explicit authorization' in err, err[:200])
    check('V65 回滚：拒绝后数据和结构都没变', only_proc_differs(snapshot(GUARD), once))
    sql("insert into kg_stay_comment (stay_id, store_id, app_user_id, content, create_time) values (1, 110, 1, '有数据', now())", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V65 回滚：表里有数据时拒绝', r is None and 'has data' in err, err[:200])
    sql("update kg_stay_comment set del_flag='2'", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V65 回滚：只剩已删除的行也拒绝', r is None and 'has data' in err, err[:200])
    check('V65 回滚：拒绝时表和数据都还在', sql("select count(*) from kg_stay_comment", GUARD)[0] == '1')
    sql("delete from kg_stay_comment; alter table kg_stay_comment auto_increment = 1; insert into kg_report (target_type, target_id, reporter_id, reason, status, create_time) values ('stay_comment', 1, 1, '探针', 'pending', now())", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V65 回滚：有这类举报时拒绝', r is None and 'kg_report' in err, err[:200])
    sql("delete from kg_report where target_type='stay_comment' and reason='探针'; insert into kg_schema_version (version, name, applied_time, checksum) values (66, 'guardtest', now(), repeat('0',64))", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V65 回滚：有更高版本时拒绝', r is None and 'later migrations' in err, err[:200])
    sql('delete from kg_schema_version where version=66', GUARD)
    check('V65 回滚：几次拒绝之后快照和前滚后相同（只差存储过程）', only_proc_differs(snapshot(GUARD), once))

    # ---- 回滚
    sql(script(down, allow), GUARD)
    back = snapshot(GUARD)
    check('V65 回滚：表、列、索引、过程、菜单、角色菜单、字典、参数、账本、阶段、评价、碎片、授权逐项回到前滚之前', only_proc_differs(back, before), '差异行数 %d' % len(set(back.split('\n')) ^ set(before.split('\n'))))
    check('V65 回滚：kg_stay_comment 已删', sql("select count(*) from information_schema.tables where table_schema='%s' and table_name='kg_stay_comment'" % GUARD)[0] == '0')

    # ---- 菜单编号被占时退让：占住 2285 和 2287
    sql("insert into sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, perms, create_by, create_time) values (2285, '占位一', 2000, 1, 'F', 'guard:occupied:1', 'guardtest', now()), (2287, '占位二', 2000, 2, 'F', 'guard:occupied:2', 'guardtest', now())", GUARD)
    sql(script(up), GUARD)
    rows = sql("select menu_id, perms, parent_id from sys_menu where perms like 'kangaroo:staycomment:%' order by menu_id", GUARD)[0].split('\n')
    ids = {l.split('\t')[1]: int(l.split('\t')[0]) for l in rows}
    check('V65 菜单编号被占时退让', len(ids) == 3 and ids['kangaroo:staycomment:list'] != 2285 and ids['kangaroo:staycomment:audit'] != 2287 and ids['kangaroo:staycomment:query'] == 2286, ids)
    check('V65 退让后子菜单仍挂在新的页面菜单下', sql("select count(*) from sys_menu m where m.perms in ('kangaroo:staycomment:query','kangaroo:staycomment:audit') and m.parent_id=%d" % ids['kangaroo:staycomment:list'], GUARD)[0] == '2')
    check('V65 退让后也只属于角色 1', sql("select count(*) from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms like 'kangaroo:staycomment:%' and rm.role_id=1", GUARD)[0] == '3')
    check('V65 占位菜单没被动', sql("select group_concat(menu_name order by menu_id) from sys_menu where perms like 'guard:occupied:%'", GUARD)[0] == '占位一,占位二')
    sql(script(down, allow), GUARD)
    sql("delete from sys_menu where perms like 'guard:occupied:%'", GUARD)
    check('V65 退让之后回滚也干净', only_proc_differs(snapshot(GUARD), before))

    # ---- 再前滚
    sql(script(up), GUARD)
    again = snapshot(GUARD)
    check('V65 再前滚：菜单回到固定编号', sql("select group_concat(menu_id order by menu_id) from sys_menu where perms like 'kangaroo:staycomment:%'", GUARD)[0] == '2285,2286,2287')
    # applied_time 不在快照里；checksum 必须和脚本一致
    text = (up).read_text(encoding='utf-8')
    import hashlib
    head = text[:text.index('-- @kg-ledger')]
    check('V65 账本 checksum 和脚本一致', sql("select checksum from kg_schema_version where version=65", GUARD)[0] == hashlib.sha256(head.encode('utf-8')).hexdigest())
    check('V65 再前滚：快照和第一次相同（菜单行除创建时间外逐项比）', again == once)
finally:
    if created:
        sql('drop database if exists ' + GUARD)
        checks.append('已删除 ' + GUARD)
print(json.dumps({'database': GUARD, 'source': SOURCE, 'failed': failed, 'passed': sum(1 for c in checks if c.startswith('ok ')), 'checks': checks}, ensure_ascii=False, indent=2))
sys.exit(1 if failed else 0)
