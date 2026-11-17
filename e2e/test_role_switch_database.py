"""V64 和角色切换的真实库验证（2026-10-30）：克隆验收库到一次性的 acceptance_roleswitch_<hex>_guardtest（只读源库，绝不写它），
1. 真跑 V64 前滚、重复前滚、回滚、再前滚，每一步比表、列、索引、菜单、角色菜单、账本；回滚的守卫逐条验证；
2. 跑切换探针 KgRoleSwitchIT（真实事务、行锁、触发器）；
3. 切过的库 V64 回滚必须拒绝，退回后才放行；
跑完删库。不连线上，不碰别的库。

用法：python -B -X utf8 RuoYi-Vue/e2e/test_role_switch_database.py
  KG_ROLE_SWITCH_SOURCE_DB 指定克隆来源（默认 kg_acceptance_admin_20261028）；KG_MYSQL 指定 mysql.exe。
"""
from pathlib import Path
import json, os, re, subprocess, sys, uuid
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password
from migration_guard_policy import validate_guard_databases, authorized_rollback_prefix
BE = ROOT / 'RuoYi-Vue'
MIG = BE / 'sql' / 'kangaroo'
SOURCE = os.environ.get('KG_ROLE_SWITCH_SOURCE_DB', 'kg_acceptance_admin_20261028')
GUARD = 'acceptance_roleswitch_' + uuid.uuid4().hex[:12] + '_guardtest'
assert re.fullmatch(r'acceptance_roleswitch_[a-f0-9]{12}_guardtest', GUARD)
validate_guard_databases(SOURCE, GUARD)
mysql_bin = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
dump_bin = str(Path(mysql_bin).with_name('mysqldump.exe'))
env = dict(os.environ, MYSQL_PWD=db_password(str(BE)), KG_ROLE_SWITCH_TEST_DB=GUARD)
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
         "select 'grant', grant_id, app_user_id, store_id, role_code, status, del_flag, also_service, granted_by, granted_time, update_by, update_time from kg_staff_grant order by grant_id;"
         % {'d': db})
    return sql(q, db)[0]


created = False
try:
    assert sql("select count(*) from kg_schema_version where version=63", SOURCE)[0] == '1', '来源库不是 schema 63'
    assert sql("select count(*) from kg_schema_version where version=64", SOURCE)[0] == '0', '来源库已跑过 V64，不能做前滚基线'
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

    up = MIG / 'V64__role_switch.sql'
    down = MIG / 'V64__rollback.sql'
    before = snapshot(GUARD)

    # ---- 前滚
    sql(script(up), GUARD)
    once = snapshot(GUARD)
    check('V64 前滚：三张新表', sql("select count(*) from information_schema.tables where table_schema='%s' and table_name in ('kg_role_switch','kg_role_switch_backup','kg_staff_client_seen')" % GUARD)[0] == '3')
    check('V64 前滚：状态行 legacy', sql('select phase from kg_role_switch where id=1', GUARD)[0] == 'legacy')
    check('V64 前滚：备份表空', sql('select count(*) from kg_role_switch_backup', GUARD)[0] == '0')
    check('V64 前滚：四个菜单只给超级管理员', sql("select count(*) from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms like 'kangaroo:roleswitch:%' and rm.role_id=1", GUARD)[0] == '4'
          and sql("select count(*) from sys_role_menu rm join sys_menu m on m.menu_id=rm.menu_id where m.perms like 'kangaroo:roleswitch:%' and rm.role_id<>1", GUARD)[0] == '0')
    check('V64 前滚：账本 64', sql("select name from kg_schema_version where version=64", GUARD)[0] == 'role_switch')
    def body(s):
        return '\n'.join(l for l in s.split('\n') if not re.match(r"(menu\t\d+\t(角色切换|执行切换|退回|打开派单)\t|rolemenu\t1\t\d+$|ledger\t64\t|table\tkg_role_switch|table\tkg_staff_client_seen|col\tkg_role_switch|col\tkg_staff_client_seen|idx\tkg_role_switch|idx\tkg_staff_client_seen)", l))
    check('V64 前滚：不碰 kg_staff_grant、字典、参数', [l for l in once.split('\n') if l.startswith(('grant\t', 'dict\t', 'config\t'))] == [l for l in before.split('\n') if l.startswith(('grant\t', 'dict\t', 'config\t'))])

    # ---- 重复前滚
    sql(script(up), GUARD)
    check('V64 重复前滚：快照逐项相同', snapshot(GUARD) == once)

    # ---- 菜单编号被占时退让
    # 见下面第 4 段：回滚后占住 3970，再前滚

    # ---- 回滚守卫
    r, err = sql(script(down), GUARD, tolerate=True)
    check('V64 回滚：没有授权变量时拒绝', r is None and 'explicit authorization' in err, err[:200])
    # mysql 客户端遇错就停，脚本尾部清理存储过程的语句没跑（V63 回滚也一样），下次跑脚本开头会先 drop；数据和结构不能变
    def no_proc(text):
        return [l for l in text.split(chr(10)) if not l.startswith('proc' + chr(9))]
    check('V64 回滚：拒绝后数据和结构都没变', no_proc(snapshot(GUARD)) == no_proc(once))
    allow = authorized_rollback_prefix(64, SOURCE, GUARD)
    check('V64 回滚授权变量已登记', 'kg_allow_role_switch_rollback' in allow, allow)
    sql("update kg_role_switch set phase='new' where id=1", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V64 回滚：阶段不是 legacy 时拒绝', r is None and 'phase is not legacy' in err, err[:200])
    sql("update kg_role_switch set phase='legacy' where id=1; insert into kg_role_switch_backup (kind, grant_id, create_time) values ('grant', 1, now())", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V64 回滚：备份表非空时拒绝', r is None and 'backup is not empty' in err, err[:200])
    sql("delete from kg_role_switch_backup; insert into kg_schema_version (version, name, applied_time, checksum) values (65, 'guardtest', now(), repeat('0',64))", GUARD)
    r, err = sql(script(down, allow), GUARD, tolerate=True)
    check('V64 回滚：有更高版本时拒绝', r is None and 'later migrations' in err, err[:200])
    sql('delete from kg_schema_version where version=65', GUARD)

    # ---- 回滚
    sql(script(down, allow), GUARD)
    back = snapshot(GUARD)
    check('V64 回滚：表、列、索引、菜单、角色菜单、字典、参数、账本、授权逐项回到前滚之前', back == before, '差异行数 %d' % len(set(back.split('\n')) ^ set(before.split('\n'))))

    # ---- 菜单编号被占时退让：占住 3970 和 3972，自增计数紧贴
    sql("insert into sys_menu (menu_id, menu_name, parent_id, order_num, menu_type, perms, create_by, create_time) values (3970, '占位一', 2000, 1, 'F', 'guard:occupied:1', 'guardtest', now()), (3972, '占位二', 2000, 2, 'F', 'guard:occupied:2', 'guardtest', now())", GUARD)
    sql(script(up), GUARD)
    rows = sql("select menu_id, perms, parent_id from sys_menu where perms like 'kangaroo:roleswitch:%' order by menu_id", GUARD)[0].split('\n')
    ids = {l.split('\t')[1]: int(l.split('\t')[0]) for l in rows}
    check('V64 菜单编号被占时退让', len(ids) == 4 and ids['kangaroo:roleswitch:list'] != 3970 and ids['kangaroo:roleswitch:rollback'] != 3972 and ids['kangaroo:roleswitch:switch'] == 3971, ids)
    check('V64 占位菜单没被动', sql("select count(*) from sys_menu where perms like 'guard:occupied:%'", GUARD)[0] == '2')
    sql(script(down, allow), GUARD)
    sql("delete from sys_menu where perms like 'guard:occupied:%'", GUARD)
    check('V64 退让之后回滚也干净', snapshot(GUARD) == before)

    # ---- 再前滚（后面的探针跑在这个状态上）
    sql(script(up), GUARD)
    check('V64 再前滚', snapshot(GUARD) == once)

    # ---- 切换探针
    sql('create table kg_staff_grant_probe_orig like kg_staff_grant; insert into kg_staff_grant_probe_orig select * from kg_staff_grant', GUARD)
    log = BE / 'ruoyi-kangaroo' / 'target' / 'role-switch-it.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('w', encoding='utf-8') as out:
        r = subprocess.run(['mvn.cmd', '-o', '-q', '-pl', 'ruoyi-kangaroo', '-am', '-Dtest=KgRoleSwitchIT', '-Dsurefire.failIfNoSpecifiedTests=false', '-Duser.timezone=Asia/Shanghai', 'test'],
                           cwd=BE, env=env, stdout=out, stderr=subprocess.STDOUT)
    report = BE / 'ruoyi-kangaroo' / 'target' / 'surefire-reports' / 'TEST-com.ruoyi.kangaroo.ext.staff.KgRoleSwitchIT.xml'
    head = report.read_text(encoding='utf-8')[:800] if report.exists() else ''
    m = re.search(r'tests="(\d+)" errors="(\d+)" skipped="(\d+)" failures="(\d+)"', head)
    check('切换探针 KgRoleSwitchIT %s' % (m.group(0) if m else '没有报告'), r.returncode == 0 and bool(m) and m.group(1) != '0' and m.groups()[1:] == ('0', '0', '0'), str(log))
    if m:
        checks.append('   探针详情：' + m.group(0))
finally:
    if created:
        sql('drop database if exists ' + GUARD)
        checks.append('已删除 ' + GUARD)
print(json.dumps({'database': GUARD, 'source': SOURCE, 'failed': failed, 'checks': checks}, ensure_ascii=False, indent=2))
sys.exit(1 if failed else 0)
