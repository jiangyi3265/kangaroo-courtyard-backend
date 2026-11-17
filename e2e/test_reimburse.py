# -*- coding: utf-8 -*-
"""采购报销（R12，V66，包 20261031-forms）的真实接口回归。全部在本机：一次性克隆库 + Redis db 12 + 自己挑的空闲端口，跑完只停自己启动的进程、删库。不连线上。

  1. 克隆验收库，补 V64、V65，跑 V66。
  2. 线上 jar（4bd5，不认识新表）在 V66 库上启动、走一遍：不碰 kg_reimbursement（performance_schema 计数 = 0）。
  3. 新 jar + legacy：谁能交、金额等边界、并发、每小时条数、再交、撤回、后台列表合计筛选导出、审核、并发审核只成功一次、通知、不能审自己、注销、数据导出。
  4. 库里改成 new 阶段重启新 jar：小院主理人含义不变，能交能审；义工主理人（manager）、老员工（staff）、城市负责人还是不能交。
  5. 老 jar 在有报销数据的库上再启动一次，还是不碰新表。

用法（项目根目录）：
    set KG_REIMBURSE_JAR=<新 jar：类补丁包，不带 V61>
    python -B -X utf8 RuoYi-Vue/e2e/test_reimburse.py
可选：KG_REIMBURSE_OLD_JAR 老 jar；KG_REIMBURSE_SOURCE_DB 克隆来源（默认 kg_acceptance_admin_20261028）；KG_REIMBURSE_WORK 上传目录和日志放哪；KG_MYSQL mysql.exe；KG_REDIS_DB 默认 12。
不清 Redis：克隆库的账号自增起点每次随机，限流键撞不上上一轮（别的 jar 开着也能跑）。
"""
import base64
import concurrent.futures as futures
import io
import json
import os
import random
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BE = ROOT / 'RuoYi-Vue'
sys.path.insert(0, str(Path(__file__).resolve().parent))
from dbcred import db_password  # noqa: E402
from migration_guard_policy import validate_guard_databases  # noqa: E402

sys.stdout.reconfigure(encoding='utf-8')
MIG = BE / 'sql' / 'kangaroo'
SOURCE = os.environ.get('KG_REIMBURSE_SOURCE_DB', 'kg_acceptance_admin_20261028')
NONCE = uuid.uuid4().hex[:8]
GUARD = 'acceptance_reimburse_' + NONCE + '_guardtest'
validate_guard_databases(SOURCE, GUARD)
NEW_JAR = os.environ['KG_REIMBURSE_JAR']
OLD_JAR = os.environ.get('KG_REIMBURSE_OLD_JAR', str(ROOT / 'artifacts/release-20261031-stay-comment/backend/ruoyi-admin.jar'))
OLD_BUILD = os.environ.get('KG_REIMBURSE_OLD_BUILD', '4bd5a81d08ad')
WORK = Path(os.environ.get('KG_REIMBURSE_WORK') or tempfile.mkdtemp(prefix='kg-reimburse-'))
WORK.mkdir(parents=True, exist_ok=True)
MYSQL = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
DUMP = str(Path(MYSQL).with_name('mysqldump.exe'))
PW = db_password(str(BE))
ENV = dict(os.environ, MYSQL_PWD=PW)
ARGS = ['--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-uroot', '--default-character-set=utf8mb4']
ADMIN_HASH = '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2'  # 若依默认 admin123，只写进这个一次性克隆库
REDIS_DB = os.environ.get('KG_REDIS_DB', '12')
SECRET = secrets.token_urlsafe(64)  # 这次跑现生成，不落盘
PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6l0sAAAAASUVORK5CYII=')
results, fails, started = [], [], []


def check(name, ok, extra=None):
    results.append(name)
    print(('PASS ' if ok else 'FAIL ') + name + ('' if ok else '  ' + json.dumps(extra, ensure_ascii=False, default=str)[:700]), flush=True)
    if not ok:
        fails.append(name)
    return bool(ok)


def sql(text, db=GUARD, tolerate=False):
    r = subprocess.run([MYSQL, *ARGS, '-n', '-N', '-B', '--raw', db], input=("set time_zone='+08:00';\n" + text).encode('utf-8'), capture_output=True, env=ENV, timeout=600)
    if r.returncode != 0:
        if tolerate:
            return None
        raise RuntimeError(r.stderr.decode('utf-8', 'replace')[:800] + ' :: ' + text[:200])
    return r.stdout.decode('utf-8', 'replace').replace('\r', '').strip()


def script(path):
    return sql(Path(path).read_text(encoding='utf-8'))


def clone():
    subprocess.run([MYSQL, *ARGS], input=('create database %s character set utf8mb4 collate utf8mb4_unicode_ci;' % GUARD).encode(), capture_output=True, env=ENV, timeout=60, check=True)
    d = subprocess.run([DUMP, *ARGS, '--single-transaction', '--routines', '--no-tablespaces', '--set-gtid-purged=OFF', '--hex-blob', SOURCE], capture_output=True, env=ENV, timeout=600)
    assert d.returncode == 0, d.stderr[:500]
    r = subprocess.run([MYSQL, *ARGS, GUARD], input=d.stdout, capture_output=True, env=ENV, timeout=600)
    assert r.returncode == 0, r.stderr[:500]


def drop_db():
    subprocess.run([MYSQL, *ARGS], input=('drop database if exists %s;' % GUARD).encode(), capture_output=True, env=ENV, timeout=60, check=True)


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def today(days=0):
    # 和后端同一个时区算：跑的这台机器不一定在东八区
    return time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + days * 86400))


class Jar:
    """只停自己启动的进程：停之前核对命令行里有这个克隆库"""

    def __init__(self, jar, name):
        self.port = free_port()
        self.name = name
        url = ('jdbc:mysql://127.0.0.1:3306/%s?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true'
               '&serverTimezone=GMT%%2B8&sessionVariables=time_zone%%3D%%27%%2B08%%3A00%%27' % GUARD)
        self.work = WORK / name
        (self.work / 'upload').mkdir(parents=True, exist_ok=True)
        log = open(WORK / ('backend-%s.log' % name), 'w', encoding='utf-8')
        self.proc = subprocess.Popen(['java', '-Dfile.encoding=UTF-8', '-Dkangaroo.wx.mock-enabled=true', '-Dserver.port=%d' % self.port, '-Dkangaroo.pay.mode=mock', '-Dkangaroo.content.review-mode=mock',
                                      '-Dkangaroo.sms.mode=mock', '-Dkangaroo.realname.mode=mock', '-Dkangaroo.subscribe.mode=mock', '-Dspring.datasource.druid.master.url=' + url,
                                      '-Dspring.redis.database=' + REDIS_DB, '-Druoyi.profile=' + str(self.work), '-jar', str(jar)], cwd=str(self.work), stdout=log, stderr=subprocess.STDOUT,
                                     env=dict(os.environ, DB_USERNAME='root', DB_PASSWORD=PW, JWT_SECRET=SECRET, DRUID_USERNAME='guardtest', DRUID_PASSWORD=secrets.token_hex(8)),
                                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS)
        started.append(self)
        self.api = Api(self.port)
        for _ in range(240):
            try:
                if self.api.get('/app/auth/ping').get('code') == 200:
                    return
            except Exception:
                pass
            assert self.proc.poll() is None, 'jar 退出了，看 %s' % (WORK / ('backend-%s.log' % name))
            time.sleep(1)
        raise RuntimeError('jar 没起来 ' + name)

    def stop(self):
        if self.proc.poll() is not None:
            return
        cl = subprocess.run(['powershell.exe', '-NoProfile', '-Command', "(Get-CimInstance Win32_Process -Filter 'ProcessId=%d').CommandLine" % self.proc.pid], capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
        if GUARD in cl and 'ruoyi-admin.jar' in cl:
            subprocess.run(['taskkill', '/PID', str(self.proc.pid), '/F'], capture_output=True)
            self.proc.wait(timeout=30)


class Api:
    def __init__(self, port):
        self.port = port

    def request(self, method, path, body=None, token=None, headers=None, data=None, content_type='application/json'):
        payload = data if data is not None else (None if body is None else json.dumps(body).encode('utf-8'))
        req = urllib.request.Request('http://127.0.0.1:%d%s' % (self.port, path), method=method, data=payload)
        req.add_header('Content-Type', content_type)
        if token:
            req.add_header('X-App-Token' if path.startswith('/app/') else 'Authorization', token if path.startswith('/app/') else 'Bearer ' + token)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return r.status, r.read(), r.headers.get('Content-Type', '')
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers.get('Content-Type', '')

    def raw(self, method, path, body=None, token=None, **kw):
        status, blob, _ = self.request(method, path, body, token, **kw)
        try:
            out = json.loads(blob.decode('utf-8'))
        except Exception:
            out = {'code': status, 'raw': blob[:200].decode('utf-8', 'replace')}
        if isinstance(out, dict):
            out.setdefault('_http', status)
        return out

    def get(self, path, token=None, **kw):
        return self.raw('GET', path, token=token, **kw)

    def post(self, path, body=None, token=None, **kw):
        return self.raw('POST', path, body if body is not None else {}, token=token, **kw)

    def put(self, path, body=None, token=None, **kw):
        return self.raw('PUT', path, body if body is not None else {}, token=token, **kw)

    def delete(self, path, token=None):
        return self.raw('DELETE', path, token=token)

    def login(self, code, nickname):
        b = self.post('/app/auth/wx-login', {'code': code, 'nickname': nickname})
        assert b.get('code') == 200 and b.get('token'), b
        return b['token'], int(b['account']['id'])

    def admin(self, username='admin'):
        b = self.post('/login', {'username': username, 'password': 'admin123', 'code': '', 'uuid': ''})
        assert b.get('token'), b
        return b['token']

    def upload(self, token, blob=PNG, name='x.png'):
        boundary = 'kgreimburse' + uuid.uuid4().hex
        body = (('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: image/png\r\n\r\n' % (boundary, name)).encode() + blob + ('\r\n--%s--\r\n' % boundary).encode())
        r = self.raw('POST', '/app/upload/image', data=body, token=token, headers={'X-App-Upload-Version': '2'}, content_type='multipart/form-data; boundary=' + boundary)
        assert r.get('code') == 200, r
        return r['data']['url']


def io_count():
    return int(sql("select ifnull(sum(count_star),0) from performance_schema.table_io_waits_summary_by_table where object_schema='%s' and object_name='kg_reimbursement'" % GUARD))


def codes(*resps):
    return [r.get('code') for r in resps]


def parallel(fns):
    with futures.ThreadPoolExecutor(max_workers=len(fns)) as ex:
        return [f.result() for f in [ex.submit(fn) for fn in fns]]


def xlsx_parts(blob):
    z = zipfile.ZipFile(io.BytesIO(blob))
    return {n: z.read(n).decode('utf-8', 'replace') for n in z.namelist() if n.endswith('.xml')}


# ------------------------------------------------------------------ 第一段：老 jar 在 V66 空表上

def old_jar_pass(label, tok_name):
    before = io_count()
    jar = Jar(OLD_JAR, 'old-' + tok_name)
    api = jar.api
    ping = api.get('/app/auth/ping')['data']
    check('%s：老 jar 在 V66 库上启动（build %s）' % (label, OLD_BUILD), ping.get('build') == OLD_BUILD, ping)
    atok = api.admin()
    routers = json.dumps(api.get('/getRouters', token=atok), ensure_ascii=False)
    check('%s：老 jar 后台能登录、菜单能取' % label, '"code": 200' in routers or '"code":200' in routers, routers[:200])
    check('%s：老 jar 没有报销接口' % label, api.raw('GET', '/kangaroo/reimburse/list', token=atok).get('code') in (404, None) or api.raw('GET', '/kangaroo/reimburse/list', token=atok).get('_http') == 404)
    tok, _ = api.login('reimburse-old-%s-%s' % (NONCE, tok_name), '老jar走查')
    check('%s：老 jar 招募列表、消息、注销检查、个人数据导出照常' % label, codes(api.get('/app/job/list?type=volunteer&pageSize=5'), api.get('/app/message/list?mode=user', token=tok), api.get('/app/me/deactivation', token=tok)) == [200, 200, 200]
          and api.request('GET', '/app/me/export.xlsx', token=tok)[0] == 200)
    check('%s：老 jar 后台岗位、消息列表照常' % label, codes(api.raw('GET', '/kangaroo/job/list?pageNum=1&pageSize=5', token=atok), api.raw('GET', '/kangaroo/message/list?pageNum=1&pageSize=5', token=atok)) == [200, 200])
    jar.stop()
    check('%s：老 jar 没碰 kg_reimbursement（performance_schema 计数没变）' % label, io_count() == before, (before, io_count()))


# ------------------------------------------------------------------ 第二段、第三段：新 jar

def setup_people(api):
    def store(name, op='direct'):
        full = name + NONCE
        sql("insert into kg_store(store_name,city,address,status,del_flag,op_type,create_by,create_time) values('%s','平潭','路','0','0','%s','reimburse',now())" % (full, op))
        return int(sql("select store_id from kg_store where store_name='%s' order by store_id desc limit 1" % full))

    def user(tag, nick, phone=None):
        tok, uid = api.login('reimburse-%s-%s' % (NONCE, tag), nick)
        sql("update kg_app_user set nickname='%s'%s where user_id=%d" % (nick, ", phone='%s', phone_verified_time=now()" % phone if phone else '', uid))
        return {'tok': tok, 'id': uid, 'name': nick, 'phone': phone}

    def grant(u, sid, role):
        sql("insert into kg_staff_grant(app_user_id,store_id,role_code,status,granted_by,granted_time,del_flag,create_by,create_time) values(%d,%d,'%s','0','platform',now(),'0','reimburse',now())" % (u['id'], sid, role))

    s = {k: store('报销走查' + k, op) for k, op in (('A', 'direct'), ('B', 'direct'), ('C', 'managed'), ('D', 'franchise'), ('E', 'direct'), ('F', 'direct'), ('G', 'direct'), ('H', 'direct'), ('I', 'direct'), ('J', 'direct'), ('K', 'direct'), ('L', 'direct'), ('M', 'direct'))}
    u = {}
    phone = 13600000000 + random.randint(0, 9000000)
    for i, (tag, nick) in enumerate((('k1', '小院阿杨'), ('k2', '小院二号'), ('kb', '别家主理人'), ('km', '代运营店主理人'), ('kf', '加盟店主理人'), ('mgr', '义工主理人甲'), ('st', '老员工甲'), ('ol', '线上专员甲'),
                                     ('mer', '合作商家甲'), ('svc', '商家服务人甲'), ('lead', '城市负责人甲'), ('plain', '普通用户'), ('mix', '两头身份'), ('multi', '管两家'), ('kol', '带专员授权'), ('ban', '被封的'),
                                     ('lim', '限额的'), ('con', '并发的'), ('dup', '连点的'), ('gone', '要注销的'), ('rev', '被撤授权的'), ('new', '新阶段主理人'))):
        u[tag] = user(tag, nick, str(phone + i))
    grant(u['k1'], s['A'], 'keeper')
    grant(u['k2'], s['A'], 'keeper')
    grant(u['kb'], s['B'], 'keeper')
    grant(u['km'], s['C'], 'keeper')
    grant(u['kf'], s['D'], 'keeper')
    grant(u['mgr'], s['A'], 'manager')
    grant(u['st'], s['A'], 'staff')
    grant(u['ol'], s['A'], 'online_staff')
    grant(u['mer'], s['D'], 'merchant')
    grant(u['svc'], s['A'], 'merchant_service')
    grant(u['lead'], s['A'], 'city_lead')
    grant(u['mix'], s['B'], 'manager')  # 第一条授权不是 keeper
    grant(u['mix'], s['E'], 'keeper')
    grant(u['multi'], s['F'], 'keeper')
    grant(u['multi'], s['G'], 'keeper')
    grant(u['kol'], s['C'], 'online_staff')  # 账号带专员授权，消息列表会收窄
    grant(u['kol'], s['H'], 'keeper')
    grant(u['ban'], s['H'], 'keeper')
    grant(u['lim'], s['I'], 'keeper')
    grant(u['con'], s['J'], 'keeper')
    grant(u['dup'], s['J'], 'keeper')
    grant(u['gone'], s['K'], 'keeper')
    grant(u['rev'], s['L'], 'keeper')
    grant(u['new'], s['M'], 'keeper')
    return s, u


def age(uid):
    """把这个人的提交时间往前挪两小时：每小时条数的名额回来，别的测试不用凑 20 条"""
    sql("update kg_reimbursement set submit_time = date_sub(submit_time, interval 2 hour) where app_user_id = %d" % uid)


def run_new_legacy():
    jar = Jar(NEW_JAR, 'new-legacy')
    api = jar.api
    ping = api.get('/app/auth/ping')['data']
    check('新 jar 在 V66 库上启动（legacy）', ping.get('rolePhase') == 'legacy', ping)
    atok = api.admin()
    s, u = setup_people(api)

    routers = json.dumps(api.get('/getRouters', token=atok), ensure_ascii=False)
    check('超管菜单里有「采购报销」', '采购报销' in routers and 'kangaroo/reimburse/index' in routers and 'KgReimburse' in routers, routers[:300])
    check('菜单只属于角色 1', sql("select group_concat(distinct rm.role_id) from sys_role_menu rm join sys_menu m on m.menu_id = rm.menu_id where m.perms like 'kangaroo:reimburse:%'") == '1')
    check('菜单在「旅居」目录', sql("select p.menu_name from sys_menu m join sys_menu p on p.menu_id = m.parent_id where m.perms='kangaroo:reimburse:list'") == '旅居')

    def img(who='k1'):
        return api.upload(u[who]['tok'])

    def good(**kw):
        b = {'itemName': '床单' + uuid.uuid4().hex[:4], 'quantity': 2, 'amount': '128.5', 'purchaseDate': today(), 'receipts': [IMG], 'remark': '买给二楼'}
        b.update(kw)
        return b

    IMG = img()

    def sub(who, body=None, store='A', qs=None):
        return api.post('/app/staff/reimbursements' + (qs if qs is not None else '?storeId=%d' % s[store]), body if body is not None else good(), token=u[who]['tok'] if who else None)

    # ---------------------------------------------------------------- 谁能交
    print('\n=== 谁能交 ===')
    r = sub('k1', good(itemName='床单', receipts=[IMG], remark='  买给二楼  '))
    check('直营店的小院主理人能交', r.get('code') == 200 and r.get('msg') == '已提交，等平台审核', r)
    first = r['data']
    fid = first['reimburseId']
    check('回来的是两位小数字符串', first['amount'] == '128.50' and first['quantity'] == 2 and first['status'] == 'pending' and first['statusText'] == '待审核', first)
    check('回来的权限位：本人能撤、不能改', first['isMine'] and first['canWithdraw'] and not first['canEdit'], first)
    check('不带后台账号名', 'auditBy' not in first and 'paidBy' not in first, first)
    row = sql("select concat_ws('|',store_id,app_user_id,submitter_name,item_name,quantity,amount,purchase_date,remark,status,revision,del_flag,anonymous) from kg_reimbursement where reimburse_id=%d" % fid)
    check('落库', row == '|'.join(str(x) for x in (s['A'], u['k1']['id'], '小院阿杨', '床单', 2, '128.50', today(), '买给二楼', 'pending', 1, '0', '0')), row)
    check('凭证存 JSON 数组', sql('select receipts from kg_reimbursement where reimburse_id=%d' % fid) == json.dumps([IMG]))
    check('门店名存快照', sql('select store_name from kg_reimbursement where reimburse_id=%d' % fid) == '报销走查A' + NONCE)
    check('记操作日志（资金类）', sql("select count(*) from kg_staff_op_log where app_user_id=%d and action='reimburse_submit' and category='fund' and role_code='keeper' and detail like '报销#%d %%'" % (u['k1']['id'], fid)) == '1')

    r = api.post('/app/staff/reimbursements?storeId=%d' % s['A'], good())
    check('游客 401', r.get('code') == 401, r)
    for who, label in (('plain', '普通用户'), ('mgr', '同店义工主理人'), ('st', '同店老员工'), ('ol', '同店线上专员'), ('svc', '同店商家服务人'), ('lead', '同店城市负责人')):
        r = sub(who)
        check('%s不能交（403）' % label, r.get('code') == 403, r)
    r = sub('mer', store='D')
    check('合作商家不能交（403）', r.get('code') == 403, r)
    r = sub('km', store='C')
    check('代运营店的小院主理人不能交（403）', r.get('code') == 403 and '直营' in str(r.get('msg')), r)
    r = sub('kf', store='D')
    check('加盟店的小院主理人不能交（403）', r.get('code') == 403 and '直营' in str(r.get('msg')), r)
    r = sub('kb', store='A')
    check('别家门店的小院主理人不能交到这家（403）', r.get('code') == 403, r)
    r = sub('mix', store='B')
    check('在 B 店是义工主理人的不能交到 B（403）', r.get('code') == 403, r)
    r = sub('mix', store='E', body=good(receipts=[img('mix')]))
    check('同一个人在 E 店是小院主理人能交到 E', r.get('code') == 200 and r['data']['storeId'] == s['E'], r)
    r = api.post('/app/staff/reimbursements', good(receipts=[img('mix')]), token=u['mix']['tok'])
    check('不传门店：第一条授权不是 keeper 也落到 keeper 那家', r.get('code') == 200 and r['data']['storeId'] == s['E'], r)
    r = api.post('/app/staff/reimbursements', good(receipts=[img('multi')]), token=u['multi']['tok'])
    check('管两家不传门店：422，不能记错店', r.get('code') == 422 and '门店' in str(r.get('msg')), r)
    check('管两家没传门店：一笔都没进库', sql('select count(*) from kg_reimbursement where app_user_id=%d' % u['multi']['id']) == '0')
    r = api.post('/app/staff/reimbursements?storeId=%d' % s['G'], good(receipts=[img('multi')]), token=u['multi']['tok'])
    check('管两家传了门店：落到传的那家', r.get('code') == 200 and r['data']['storeId'] == s['G'], r)

    # 封禁、撤授权
    sql("update kg_app_user set status='1' where user_id=%d" % u['ban']['id'])
    r = sub('ban', store='H')
    check('封禁的账号不能交（403）', r.get('code') == 403 and '停用' in str(r.get('msg')), r)
    sql("update kg_app_user set status='0' where user_id=%d" % u['ban']['id'])
    tmp = sub('rev', store='L', body=good(receipts=[img('rev')]))
    check('撤授权之前能交', tmp.get('code') == 200, tmp)
    sql("update kg_staff_grant set status='1' where app_user_id=%d" % u['rev']['id'])
    rid = tmp['data']['reimburseId']
    check('撤授权之后交、看、撤都 403（每次请求重读授权，不用重新登录）', codes(sub('rev', store='L'), api.get('/app/staff/reimbursements/%d' % rid, token=u['rev']['tok']), api.delete('/app/staff/reimbursements/%d' % rid, token=u['rev']['tok'])) == [403, 403, 403])
    sql("update kg_store set status='1' where store_id=%d" % s['L'])
    sql("update kg_staff_grant set status='0' where app_user_id=%d" % u['rev']['id'])
    check('门店停用：交、看都 403', codes(sub('rev', store='L'), api.get('/app/staff/reimbursements?storeId=%d' % s['L'], token=u['rev']['tok'])) == [403, 403])
    sql("update kg_store set status='0' where store_id=%d; update kg_staff_grant set status='1' where app_user_id=%d" % (s['L'], u['rev']['id']))

    # ---------------------------------------------------------------- 边界
    print('\n=== 金额等边界 ===')
    age(u['k1']['id'])

    def bad(field, value, word=None, drop=False):
        b = good()
        if drop:
            del b[field]
        else:
            b[field] = value
        r = sub('k1', b)
        check('%s=%r → 422' % (field, value if not drop else '(没带)'), r.get('code') == 422 and (word is None or word in str(r.get('msg'))), r)

    def ok(field, value, stored=None, sql_col=None):
        r = sub('k1', good(**{field: value}))
        passed = r.get('code') == 200
        if passed and stored is not None:
            got = sql('select %s from kg_reimbursement where reimburse_id=%d' % (sql_col, r['data']['reimburseId']))
            passed = got == stored
        check('%s=%r 通过' % (field, value if len(str(value)) < 40 else str(value)[:20] + '…'), passed, r)
        age(u['k1']['id'])
        return r

    for v in ('0', 0, '0.00', '-1', -1, '0.001', '1.234', 1.234, '100000.01', 100000.01, '100001', 100001, '1000000', 'abc', '1e3', '', '   ', '１２', '12,5', None, True, [1], {'a': 1}, '.5', '5.', '+5', '1.230', '99999999999999999999999'):
        bad('amount', v)
    bad('amount', None, drop=True)
    ok('amount', '0.01', '0.01', 'amount')
    ok('amount', '100000', '100000.00', 'amount')
    ok('amount', '100000.00', '100000.00', 'amount')
    ok('amount', 100000, '100000.00', 'amount')
    ok('amount', 12.5, '12.50', 'amount')
    ok('amount', 7, '7.00', 'amount')
    ok('amount', ' 8.8 ', '8.80', 'amount')
    ok('amount', '015', '15.00', 'amount')

    for v in (0, '0', -1, 1.5, '1.5', '2.0', 'a', '', None, True, [1], 1000000, '1000000', 12345678901):
        bad('quantity', v)
    bad('quantity', None, drop=True)
    ok('quantity', 1, '1', 'quantity')
    ok('quantity', '3', '3', 'quantity')
    ok('quantity', 999999, '999999', 'quantity')

    for v in ('', '   ', '\u3000\u200b\ufe0f', '字' * 61, None, ['x'], {'a': 1}):
        bad('itemName', v, '品名')
    bad('itemName', None, '品名', drop=True)
    ok('itemName', '字' * 60, '字' * 60, 'item_name')
    ok('itemName', '\U0001F6CF' * 60, '\U0001F6CF' * 60, 'item_name')  # 60 个表情：按字算，不按 UTF-16 算
    ok('itemName', '=1+1', '=1+1', 'item_name')

    for v, word in ((today(1), '不能晚于今天'), (today(400), '不能晚于今天'), ('2026-02-30', None), ('2026/10/01', None), ('20261001', None), ('', None), ('1999-12-31', None), ('0001-01-01', None), ('+12026-01-01', None), ('abc', None), (20261001, None)):
        bad('purchaseDate', v, word)
    bad('purchaseDate', None, drop=True)
    ok('purchaseDate', today(), today(), 'purchase_date')
    ok('purchaseDate', today(-400), today(-400), 'purchase_date')

    nine = [img() for _ in range(9)]
    ten = nine + [img()]
    # 不是图片的文件：直接放进上传目录，路径合格，内容不合格
    fake_dir = jar.work / 'upload' / 'guardtest'
    fake_dir.mkdir(parents=True, exist_ok=True)
    (fake_dir / 'not-image.png').write_bytes(b'this is not an image, really not')
    video_path = '/profile/upload/videos/2026/10/31/0123456789abcdef0123456789abcdef.mp4'
    for v, word in (([], '至少上传 1 张'), ('', '至少上传 1 张'), (None, '至少上传 1 张'), (ten, '最多 9 张'), (['https://evil.example/a.png'], '本平台'), (['/profile/upload/../../etc/passwd.png'], '本平台'),
                    (['/profile/avatar/a.png'], '本平台'), ([video_path], '本平台'), (['/profile/upload/guardtest/missing.png'], '不存在'), (['/profile/upload/guardtest/not-image.png'], '不是有效的图片'),
                    ([123], '格式不对'), ({'a': 1}, '格式不对'), (123, '格式不对')):
        bad('receipts', v, word)
    bad('receipts', None, '至少上传 1 张', drop=True)
    ok('receipts', nine)
    ok('receipts', ','.join(nine[:2]))  # 后台图片组件给的逗号分隔也认
    ok('receipts', json.dumps(nine[:2]))
    dup_img = sub('k1', good(receipts=[IMG, IMG]))
    check('重复的凭证只留一张', dup_img.get('code') == 200 and dup_img['data']['receipts'] == [IMG], dup_img)
    age(u['k1']['id'])

    bad('remark', '字' * 201, '备注')
    bad('remark', {'a': 1}, '备注')
    ok('remark', '字' * 200, '字' * 200, 'remark')
    r = sub('k1', {k: v for k, v in good().items() if k != 'remark'})
    check('备注可以不填', r.get('code') == 200 and r['data']['remark'] == '', r)
    age(u['k1']['id'])
    r = api.post('/app/staff/reimbursements?storeId=%d' % s['A'], {}, token=u['k1']['tok'])
    check('空表单 422', r.get('code') == 422, r)
    before = sql('select count(*) from kg_reimbursement where app_user_id=%d' % u['k1']['id'])
    for _ in range(3):
        sub('k1', good(amount='0'))
    check('校验没过不落库', sql('select count(*) from kg_reimbursement where app_user_id=%d' % u['k1']['id']) == before)

    # ---------------------------------------------------------------- 每小时条数、并发
    print('\n=== 每小时 20 条、并发 ===')
    pic = img('lim')
    got = [sub('lim', good(receipts=[pic]), store='I') for _ in range(20)]
    check('一小时内前 20 条都能交', all(x.get('code') == 200 for x in got), [x.get('code') for x in got])
    r = sub('lim', good(receipts=[pic]), store='I')
    check('第 21 条 429，提示一小时最多 20 条', r.get('code') == 429 and '20' in str(r.get('msg')), r)
    w = api.delete('/app/staff/reimbursements/%d' % got[0]['data']['reimburseId'], token=u['lim']['tok'])
    check('撤回一条', w.get('code') == 200, w)
    check('撤回不还名额：还是 429', sub('lim', good(receipts=[pic]), store='I').get('code') == 429)
    for _ in range(3):
        bad_r = sub('lim', good(amount='0'), store='I')
    check('超限时填错也是先看校验（422），填错不占名额', bad_r.get('code') == 422, bad_r)
    age(u['lim']['id'])
    check('过了一小时又能交', sub('lim', good(receipts=[pic]), store='I').get('code') == 200)

    pic = img('con')
    res = parallel([lambda: sub('con', good(receipts=[pic]), store='J') for _ in range(32)])
    oks = [x for x in res if x.get('code') == 200]
    check('32 个并发：正好 20 个成功，其余 429（锁在账号行，超不了）', len(oks) == 20 and all(x.get('code') in (200, 429) for x in res), sorted(str(x.get('code')) for x in res))
    check('库里正好 20 行', sql('select count(*) from kg_reimbursement where app_user_id=%d' % u['con']['id']) == '20')

    pic = img('dup')
    same = good(receipts=[pic], itemName='连点两下')
    res = parallel([lambda: sub('dup', same, store='J') for _ in range(8)])
    check('连点 8 下同样的内容：只进一笔，其余 409', sorted(x.get('code') for x in res) == [200] + [409] * 7, [x.get('code') for x in res])
    check('库里只有一行', sql("select count(*) from kg_reimbursement where app_user_id=%d and item_name='连点两下'" % u['dup']['id']) == '1')
    check('重新选一次照片（新文件）不算重复', sub('dup', dict(same, receipts=[img('dup')]), store='J').get('code') == 200)

    # ---------------------------------------------------------------- 小程序：列表、详情、撤回、再交
    print('\n=== 小程序看、撤、改 ===')
    age(u['k1']['id'])
    a1 = sub('k1', good(itemName='枕头', amount='10.10'))['data']['reimburseId']
    a2 = sub('k2', good(itemName='二号交的', amount='20.20', receipts=[img('k2')]))['data']['reimburseId']
    lst = api.get('/app/staff/reimbursements?storeId=%d' % s['A'], token=u['k2']['tok'])
    check('同店另一位小院主理人能看表格', lst.get('code') == 200 and any(x['reimburseId'] == a1 for x in lst['data']['rows']), lst)
    row = next(x for x in lst['data']['rows'] if x['reimburseId'] == a1)
    check('别人的行：不是本人、不能改不能撤', not row['isMine'] and not row['canWithdraw'] and not row['canEdit'], row)
    check('自己的行：能撤', next(x for x in lst['data']['rows'] if x['reimburseId'] == a2)['canWithdraw'])
    check('表头：门店和能不能交', lst['data']['store'] == {'storeId': s['A'], 'storeName': '报销走查A' + NONCE, 'canSubmit': True}, lst['data']['store'])
    check('状态计数', lst['data']['counts']['pending'] == int(sql("select count(*) from kg_reimbursement where store_id=%d and del_flag='0' and status='pending'" % s['A'])), lst['data']['counts'])
    check('翻页：一页 20 条、有下一页', len(lst['data']['rows']) == 20 and lst['data']['hasMore'] is True and api.get('/app/staff/reimbursements?storeId=%d&page=2' % s['A'], token=u['k2']['tok'])['data']['pageNum'] == 2)
    only = api.get('/app/staff/reimbursements?storeId=%d&status=approved' % s['A'], token=u['k1']['tok'])
    check('按状态筛', only.get('code') == 200 and only['data']['total'] == 0, only)
    check('状态乱写 422', api.get('/app/staff/reimbursements?storeId=%d&status=xx' % s['A'], token=u['k1']['tok']).get('code') == 422)
    check('不传门店：取 keeper 那家', api.get('/app/staff/reimbursements', token=u['mix']['tok'])['data']['store']['storeId'] == s['E'])
    d = api.get('/app/staff/reimbursements/%d' % a1, token=u['k1']['tok'])
    check('详情', d.get('code') == 200 and d['data']['itemName'] == '枕头' and d['data']['amount'] == '10.10' and d['data']['receipts'] == [IMG], d)
    for who, label in (('kb', '别家小院主理人'), ('mgr', '同店义工主理人'), ('st', '同店老员工'), ('plain', '普通用户'), ('ol', '同店专员'), ('lead', '同店城市负责人')):
        check('%s看不到列表、详情（403）' % label, codes(api.get('/app/staff/reimbursements?storeId=%d' % s['A'], token=u[who]['tok']), api.get('/app/staff/reimbursements/%d' % a1, token=u[who]['tok'])) == [403, 403])
    check('游客看不到（401）', codes(api.get('/app/staff/reimbursements?storeId=%d' % s['A']), api.get('/app/staff/reimbursements/%d' % a1)) == [401, 401])
    check('不存在的 404', api.get('/app/staff/reimbursements/999999999', token=u['k1']['tok']).get('code') == 404)

    acc = api.get('/app/staff/reimbursements/access?storeId=%d' % s['A'], token=u['k1']['tok'])['data']
    check('access：直营 keeper 两个 true', acc['visible'] is True and acc['canSubmit'] is True and acc['storeId'] == s['A'], acc)
    check('access：代运营 keeper 没老记录，都是 false', api.get('/app/staff/reimbursements/access?storeId=%d' % s['C'], token=u['km']['tok'])['data']['visible'] is False)
    acc = api.get('/app/staff/reimbursements/access?storeId=%d' % s['A'], token=u['mgr']['tok'])
    check('access：义工主理人 200 + 两个 false（不弹错）', acc.get('code') == 200 and acc['data']['visible'] is False and acc['data']['canSubmit'] is False, acc)
    acc = api.get('/app/staff/reimbursements/access?storeId=%d' % s['A'], token=u['kb']['tok'])
    check('access：别家门店 200 + 两个 false', acc.get('code') == 200 and acc['data']['visible'] is False, acc)
    check('access：不传门店取 keeper 那家', api.get('/app/staff/reimbursements/access', token=u['mix']['tok'])['data']['storeId'] == s['E'])

    check('别人不能撤（403）', api.delete('/app/staff/reimbursements/%d' % a1, token=u['k2']['tok']).get('code') == 403)
    check('别人不能改（403）', api.put('/app/staff/reimbursements/%d' % a1, good(), token=u['k2']['tok']).get('code') == 403)
    check('待审的不能改（409，只有驳回后才能改）', api.put('/app/staff/reimbursements/%d' % a1, good(), token=u['k1']['tok']).get('code') == 409)
    wid = sub('k1', good(itemName='要撤回的'))['data']['reimburseId']
    w = api.delete('/app/staff/reimbursements/%d' % wid, token=u['k1']['tok'])
    check('撤回待审的', w.get('code') == 200 and w.get('msg') == '已撤回', w)
    check('软删：行还在，del_flag=2', sql('select del_flag from kg_reimbursement where reimburse_id=%d' % wid) == '2')
    check('撤回后看不到（详情 404、再撤 404、后台详情 404）', codes(api.get('/app/staff/reimbursements/%d' % wid, token=u['k1']['tok']), api.delete('/app/staff/reimbursements/%d' % wid, token=u['k1']['tok']), api.raw('GET', '/kangaroo/reimburse/%d' % wid, token=atok)) == [404, 404, 404])
    check('撤回后后台也审不了（404）', api.put('/kangaroo/reimburse/%d/approve' % wid, {}, token=atok).get('code') == 404)
    check('撤回后不在列表里', all(x['reimburseId'] != wid for x in api.get('/app/staff/reimbursements?storeId=%d' % s['A'], token=u['k1']['tok'])['data']['rows']))

    # ---------------------------------------------------------------- 后台权限
    print('\n=== 后台 ===')
    sql("insert into sys_user (dept_id, user_name, nick_name, password, status, del_flag, create_by, create_time) values (103,'rb_noperm','无权限','%(h)s','0','0','guardtest',now()),(103,'rb_list','只能看','%(h)s','0','0','guardtest',now());"
        "insert into sys_role (role_name, role_key, role_sort, status, del_flag, create_by, create_time) values ('报销只读','rb_list',99,'0','0','guardtest',now());"
        "insert into sys_user_role (user_id, role_id) select u.user_id, r.role_id from sys_user u join sys_role r on r.role_key='rb_list' where u.user_name='rb_list';"
        "insert into sys_role_menu (role_id, menu_id) select r.role_id, m.menu_id from sys_role r join sys_menu m on m.perms='kangaroo:reimburse:list' where r.role_key='rb_list';" % {'h': ADMIN_HASH})
    noperm, listonly = api.admin('rb_noperm'), api.admin('rb_list')
    for path in ('/kangaroo/reimburse/list', '/kangaroo/reimburse/stores', '/kangaroo/reimburse/%d' % a1):
        check('没有菜单的后台账号看不了 %s（403）' % path.split('/')[-1], api.raw('GET', path, token=noperm).get('code') == 403)
    check('没有菜单的后台账号不能导出', api.raw('POST', '/kangaroo/reimburse/export', token=noperm, data=b'', content_type='application/x-www-form-urlencoded').get('code') == 403)
    for act in ('approve', 'reject', 'paid'):
        check('没有菜单的后台账号不能 %s（403）' % act, api.put('/kangaroo/reimburse/%d/%s' % (a1, act), {'reason': 'x'}, token=noperm).get('code') == 403)
        check('只有 list 权限的不能 %s（403）' % act, api.put('/kangaroo/reimburse/%d/%s' % (a1, act), {'reason': 'x'}, token=listonly).get('code') == 403)
    check('只有 list 权限的能看表格', api.raw('GET', '/kangaroo/reimburse/list', token=listonly).get('code') == 200)
    check('小程序令牌进不了后台（401）', api.raw('GET', '/kangaroo/reimburse/list', headers={'Authorization': 'Bearer ' + u['k1']['tok']}).get('code') == 401)
    check('后台接口没登录（401）', api.raw('GET', '/kangaroo/reimburse/list').get('code') == 401)

    def admin_list(query=''):
        return api.raw('GET', '/kangaroo/reimburse/list?' + query, token=atok)

    # ---------------------------------------------------------------- 审核流转 + 通知
    def msgs(who):
        return api.get('/app/message/list?mode=staff', token=u[who]['tok']).get('rows') or []

    def notices(rid):
        return sql("select count(*) from kg_message where biz_type='reimburse' and biz_id=%d" % rid)

    check('提交时不发消息', notices(a1) == '0')
    rj = api.put('/kangaroo/reimburse/%d/reject' % a1, {}, token=atok)
    check('驳回没写理由 422', rj.get('code') == 422 and '理由' in str(rj.get('msg')), rj)
    check('驳回理由全是空白 422', api.put('/kangaroo/reimburse/%d/reject' % a1, {'reason': ' \u3000\u200b '}, token=atok).get('code') == 422)
    check('驳回理由太长 422', api.put('/kangaroo/reimburse/%d/reject' % a1, {'reason': '字' * 256}, token=atok).get('code') == 422)
    check('上面这些都没改状态', sql('select status from kg_reimbursement where reimburse_id=%d' % a1) == 'pending')
    check('待审的不能标打款（409）', api.put('/kangaroo/reimburse/%d/paid' % a1, token=atok).get('code') == 409)
    rv = api.put('/kangaroo/reimburse/%d/approve' % a1, {'revision': 99}, token=atok)
    check('带旧的 revision 通过：409，提示刷新', rv.get('code') == 409 and '刷新' in str(rv.get('msg')), rv)
    check('revision 乱写 422', api.put('/kangaroo/reimburse/%d/approve' % a1, {'revision': 'x'}, token=atok).get('code') == 422)
    rj = api.put('/kangaroo/reimburse/%d/reject' % a1, {'reason': '  小票看不清  ', 'revision': 1}, token=atok)
    check('驳回', rj.get('code') == 200, rj)
    row = sql("select concat_ws('|',status,reject_reason,audit_by,audit_time is not null) from kg_reimbursement where reimburse_id=%d" % a1)
    check('驳回落库：理由、审核人、审核时间', row == 'rejected|小票看不清|admin|1', row)
    m = [x for x in msgs('k1') if x.get('bizType') == 'reimburse' and int(x.get('bizId')) == a1]
    check('通知提交人：一条，标题、理由都在', len(m) == 1 and m[0]['title'] == '采购报销被驳回' and '小票看不清' in m[0]['content'] and '枕头' in m[0]['content'], m)
    check('点开回到报销页', m and m[0]['route'] == '/pages/staff/reimburse?id=%d&storeId=%d' % (a1, s['A']), m)
    check('同店别人没收到', not [x for x in msgs('k2') if x.get('bizType') == 'reimburse' and int(x.get('bizId')) == a1])
    page = api.get('/app/message/list?mode=staff&bucket=system', token=u['k1']['tok'])
    check('进员工身份的「系统通知」这一组，算未读', any(x.get('bizType') == 'reimburse' and int(x.get('bizId')) == a1 for x in page.get('rows') or []) and page.get('unread', 0) >= 1, str(page)[:300])
    d = api.get('/app/staff/reimbursements/%d' % a1, token=u['k1']['tok'])['data']
    check('小程序看到理由，本人能改', d['status'] == 'rejected' and d['rejectReason'] == '小票看不清' and d['canEdit'] is True and d['canWithdraw'] is False, d)
    check('同店别人看得到理由，不能改', api.get('/app/staff/reimbursements/%d' % a1, token=u['k2']['tok'])['data']['canEdit'] is False)
    check('已驳回的不能再通过、再驳回、标打款（409）', codes(api.put('/kangaroo/reimburse/%d/approve' % a1, {}, token=atok), api.put('/kangaroo/reimburse/%d/reject' % a1, {'reason': 'x'}, token=atok), api.put('/kangaroo/reimburse/%d/paid' % a1, token=atok)) == [409, 409, 409])
    check('已驳回的不能撤回（409）', api.delete('/app/staff/reimbursements/%d' % a1, token=u['k1']['tok']).get('code') == 409)

    # 再交
    r = api.put('/app/staff/reimbursements/%d' % a1, good(amount='0'), token=u['k1']['tok'])
    check('再交也校验：金额 0 → 422，行没变', r.get('code') == 422 and sql('select concat(status,amount) from kg_reimbursement where reimburse_id=%d' % a1) == 'rejected10.10', r)
    before = sql('select submit_time from kg_reimbursement where reimburse_id=%d' % a1)
    time.sleep(1.1)
    r = api.put('/app/staff/reimbursements/%d' % a1, good(itemName='枕头改', amount='11.11', quantity=3, remark='重拍了'), token=u['k1']['tok'])
    check('修改再交', r.get('code') == 200 and r['data']['status'] == 'pending' and r['data']['revision'] == 2 and r['data']['amount'] == '11.11' and r['data']['rejectReason'] == '', r)
    row = sql("select concat_ws('|',item_name,quantity,amount,remark,status,revision,ifnull(reject_reason,'-'),ifnull(audit_by,'-'),ifnull(audit_time,'-')) from kg_reimbursement where reimburse_id=%d" % a1)
    check('再交落库：内容换了，第 2 次，审核信息清空', row == '枕头改|3|11.11|重拍了|pending|2|-|-|-', row)
    check('再交更新提交时间', sql('select submit_time from kg_reimbursement where reimburse_id=%d' % a1) != before)
    check('再交记日志', sql("select count(*) from kg_staff_op_log where app_user_id=%d and action='reimburse_resubmit' and detail like '报销#%d %%'" % (u['k1']['id'], a1)) == '1')
    r = api.put('/kangaroo/reimburse/%d/approve' % a1, {'revision': 1}, token=atok)
    check('旧页面（第 1 次的内容）审不过去', r.get('code') == 409 and sql('select status from kg_reimbursement where reimburse_id=%d' % a1) == 'pending', r)
    r = api.put('/kangaroo/reimburse/%d/approve' % a1, {'revision': 2}, token=atok)
    check('带对的 revision 能通过', r.get('code') == 200 and r.get('msg') == '已通过', r)
    row = sql("select concat_ws('|',status,audit_by,audit_time is not null) from kg_reimbursement where reimburse_id=%d" % a1)
    check('通过落库', row == 'approved|admin|1', row)
    check('通过后通知 +1', notices(a1) == '2')
    check('已通过的不能撤、不能改、不能再审（409）', codes(api.delete('/app/staff/reimbursements/%d' % a1, token=u['k1']['tok']), api.put('/app/staff/reimbursements/%d' % a1, good(), token=u['k1']['tok']),
                                                      api.put('/kangaroo/reimburse/%d/approve' % a1, {}, token=atok), api.put('/kangaroo/reimburse/%d/reject' % a1, {'reason': 'x'}, token=atok)) == [409] * 4)
    r = api.put('/kangaroo/reimburse/%d/paid' % a1, token=atok)
    check('标记已打款', r.get('code') == 200, r)
    row = sql("select concat_ws('|',status,paid_by,paid_time is not null) from kg_reimbursement where reimburse_id=%d" % a1)
    check('打款落库', row == 'paid|admin|1', row)
    m = [x for x in msgs('k1') if x.get('bizType') == 'reimburse' and int(x.get('bizId')) == a1]
    check('三条通知都在（驳回、通过、打款）', sorted(x['title'] for x in m) == sorted(['采购报销被驳回', '采购报销已通过', '采购报销已打款']), m)
    check('打过款的不能再标（409）', api.put('/kangaroo/reimburse/%d/paid' % a1, token=atok).get('code') == 409)
    check('不存在的审核 404', codes(api.put('/kangaroo/reimburse/999999999/approve', {}, token=atok), api.put('/kangaroo/reimburse/999999999/paid', token=atok)) == [404, 404])

    # 账号带专员授权：消息列表收窄，这一类要放行
    kol = sub('kol', good(receipts=[img('kol')]), store='H')['data']['reimburseId']
    api.put('/kangaroo/reimburse/%d/approve' % kol, {}, token=atok)
    check('账号带线上专员授权也看得到审核结果', [x for x in msgs('kol') if x.get('bizType') == 'reimburse' and int(x.get('bizId')) == kol], msgs('kol')[:3])

    # 不能审自己的
    k2_phone = u['k2']['phone']
    original = sql("select ifnull(phonenumber,'') from sys_user where user_name='admin'")
    sql("update sys_user set phonenumber='%s' where user_name='admin'" % k2_phone)
    r = api.put('/kangaroo/reimburse/%d/approve' % a2, {}, token=atok)
    check('后台账号手机号和提交人一样：通过 403', r.get('code') == 403 and '自己' in str(r.get('msg')), r)
    check('驳回也 403', api.put('/kangaroo/reimburse/%d/reject' % a2, {'reason': 'x'}, token=atok).get('code') == 403)
    check('状态没动', sql('select status from kg_reimbursement where reimburse_id=%d' % a2) == 'pending')
    check('审别人的不受影响', api.put('/kangaroo/reimburse/%d/approve' % sub('k1', good(itemName='别人的'))['data']['reimburseId'], {}, token=atok).get('code') == 200)
    sql("update sys_user set phonenumber='' where user_name='admin'")
    check('后台账号没写手机号：对应不上，不挡', api.put('/kangaroo/reimburse/%d/approve' % a2, {}, token=atok).get('code') == 200)
    sql("update sys_user set phonenumber='%s' where user_name='admin'" % k2_phone)
    check('已通过的也不能自己标打款（403）', api.put('/kangaroo/reimburse/%d/paid' % a2, token=atok).get('code') == 403)
    sql("update sys_user set phonenumber='%s' where user_name='admin'" % original)
    check('换回来能标打款', api.put('/kangaroo/reimburse/%d/paid' % a2, token=atok).get('code') == 200)

    # ---------------------------------------------------------------- 并发审核
    print('\n=== 并发审核 ===')
    age(u['k1']['id'])

    def fresh(item):
        x = sub('k1', good(itemName=item))
        assert x.get('code') == 200, x
        return x['data']['reimburseId']

    x = fresh('并发通过')
    res = parallel([lambda: api.put('/kangaroo/reimburse/%d/approve' % x, {}, token=atok) for _ in range(12)])
    check('12 个人同时通过：只成功一次，其余 409', sorted(y.get('code') for y in res) == [200] + [409] * 11, [y.get('code') for y in res])
    check('只发一条通知', notices(x) == '1')
    res = parallel([lambda: api.put('/kangaroo/reimburse/%d/paid' % x, token=atok) for _ in range(12)])
    check('12 个人同时标打款：只成功一次，其余 409', sorted(y.get('code') for y in res) == [200] + [409] * 11, [y.get('code') for y in res])
    check('打款通知也只发一条', notices(x) == '2')
    for n in range(3):
        x = fresh('并发通过驳回%d' % n)
        res = parallel([lambda: api.put('/kangaroo/reimburse/%d/approve' % x, {}, token=atok), lambda: api.put('/kangaroo/reimburse/%d/reject' % x, {'reason': '不行'}, token=atok),
                        lambda: api.put('/kangaroo/reimburse/%d/approve' % x, {}, token=atok), lambda: api.put('/kangaroo/reimburse/%d/reject' % x, {'reason': '不行'}, token=atok)])
        status = sql('select status from kg_reimbursement where reimburse_id=%d' % x)
        check('通过和驳回撞上（第 %d 轮）：只有一个成功，状态和它对得上' % (n + 1), sorted(y.get('code') for y in res) == [200, 409, 409, 409] and status in ('approved', 'rejected') and notices(x) == '1', [y.get('code') for y in res])
    for n in range(3):
        x = fresh('并发撤回通过%d' % n)
        res = parallel([lambda: api.put('/kangaroo/reimburse/%d/approve' % x, {}, token=atok), lambda: api.delete('/app/staff/reimbursements/%d' % x, token=u['k1']['tok'])])
        state = sql('select concat(status,del_flag) from kg_reimbursement where reimburse_id=%d' % x)
        check('撤回和通过撞上（第 %d 轮）：只有一个成功' % (n + 1), sorted(y.get('code') for y in res) in ([200, 404], [200, 409]) and state in ('approved0', 'pending2'), [y.get('code') for y in res] + [state])
    x = fresh('并发再交')
    api.put('/kangaroo/reimburse/%d/reject' % x, {'reason': '重拍'}, token=atok)
    res = parallel([lambda: api.put('/app/staff/reimbursements/%d' % x, good(itemName='并发再交改%d' % i), token=u['k1']['tok']) for i in range(6)])
    check('连点 6 下再交：只成功一次', sorted(y.get('code') for y in res) == [200] + [409] * 5 and sql('select revision from kg_reimbursement where reimburse_id=%d' % x) == '2', [y.get('code') for y in res])

    # ---------------------------------------------------------------- 门店改成非直营
    print('\n=== 门店改成非直营 ===')
    age(u['k1']['id'])
    pend = fresh('改类型前待审')
    rej = fresh('改类型前驳回')
    api.put('/kangaroo/reimburse/%d/reject' % rej, {'reason': '再看看'}, token=atok)
    sql("update kg_store set op_type='managed' where store_id=%d" % s['A'])
    acc = api.get('/app/staff/reimbursements/access?storeId=%d' % s['A'], token=u['k1']['tok'])['data']
    check('access：有老记录还能看，不能新交', acc['visible'] is True and acc['canSubmit'] is False, acc)
    check('不能新交（403）', sub('k1').get('code') == 403)
    check('驳回的不能再交（403）', api.put('/app/staff/reimbursements/%d' % rej, good(), token=u['k1']['tok']).get('code') == 403)
    d = api.get('/app/staff/reimbursements/%d' % rej, token=u['k1']['tok'])
    check('老记录能看，canEdit 收起来', d.get('code') == 200 and d['data']['canEdit'] is False, d)
    check('待审的还能撤', api.delete('/app/staff/reimbursements/%d' % pend, token=u['k1']['tok']).get('code') == 200)
    sql("update kg_store set op_type='direct' where store_id=%d" % s['A'])

    # ---------------------------------------------------------------- 列表、合计、筛选、导出
    print('\n=== 后台表格 ===')
    all_rows = admin_list('pageSize=50')
    check('列表返回 rows、total、summary（若依页面照旧读 rows、total）', all_rows.get('code') == 200 and isinstance(all_rows.get('rows'), list) and isinstance(all_rows.get('total'), int) and 'summary' in all_rows, str(all_rows)[:300])
    order = [x['status'] for x in all_rows['rows']]
    rank = {'pending': 0, 'approved': 1, 'rejected': 2, 'paid': 3}
    check('待审排前，已通过其次', order == sorted(order, key=lambda v: rank[v]) and order[0] == 'pending', order[:60])
    ids = [x['reimburseId'] for x in all_rows['rows'] if x['status'] == 'pending']
    check('同状态新的在前', ids == sorted(ids, reverse=True))
    db_total = sql("select count(*) from kg_reimbursement where del_flag='0'")
    check('total 和库里对得上（撤回的不算）', all_rows['total'] == int(db_total), (all_rows['total'], db_total))
    sm = all_rows['summary']
    expect = rows_of("select status, count(*), sum(amount) from kg_reimbursement where del_flag='0' group by status")
    check('合计：sumAmount 是全部行', sm['sumAmount'] == sql("select cast(sum(amount) as decimal(14,2)) from kg_reimbursement where del_flag='0'"), sm)
    check('合计：validAmount 去掉驳回', sm['validAmount'] == sql("select cast(sum(amount) as decimal(14,2)) from kg_reimbursement where del_flag='0' and status<>'rejected'"), sm)
    check('合计：分状态条数和金额', all(sm['byStatus'][st]['count'] == int(n) and sm['byStatus'][st]['amount'] == amt for st, n, amt in expect) and sm['count'] == int(db_total), (sm, expect))
    row = next(x for x in admin_list('appUserId=%d&pageSize=50' % u['k2']['id'])['rows'] if x['reimburseId'] == a2)
    check('后台行带审核人、打款人、提交人 ID', row['auditBy'] == 'admin' and row['paidBy'] == 'admin' and row['appUserId'] == u['k2']['id'] and row['submitterName'] == '小院二号' and row['amount'] == '20.20', row)
    q = admin_list('storeId=%d&pageSize=50' % s['E'])
    check('按门店筛', q['total'] == 2 and all(x['storeId'] == s['E'] for x in q['rows']), q['total'])
    check('按门店名筛', admin_list('storeName=' + urllib.parse.quote('报销走查E' + NONCE))['total'] == 2)
    check('门店名带 % 不会全匹配', admin_list('storeName=' + urllib.parse.quote('%'))['total'] == 0)
    q = admin_list('status=paid&pageSize=50')
    check('按状态筛，合计跟着筛', all(x['status'] == 'paid' for x in q['rows']) and q['total'] >= 2 and q['summary']['sumAmount'] == sql("select cast(sum(amount) as decimal(14,2)) from kg_reimbursement where del_flag='0' and status='paid'"), q['summary'])
    check('按提交人 ID 筛', all(x['appUserId'] == u['k2']['id'] for x in admin_list('appUserId=%d' % u['k2']['id'])['rows']))
    check('按提交人昵称筛', admin_list('submitter=' + urllib.parse.quote('小院二号'))['total'] == int(sql("select count(*) from kg_reimbursement where del_flag='0' and app_user_id=%d" % u['k2']['id'])))
    check('昵称带 _ 不当通配符', admin_list('submitter=' + urllib.parse.quote('小院二_'))['total'] == 0)
    old_item = sub('k1', good(itemName='一年前买的', purchaseDate=today(-365), amount='33.33'))['data']['reimburseId']
    q = admin_list('purchaseEnd=%s' % today(-300))
    check('按购买日期筛（到某天）', old_item in [x['reimburseId'] for x in q['rows']] and all(x['purchaseDate'] <= today(-300) for x in q['rows']), q['total'])
    q = admin_list('purchaseBegin=%s&purchaseEnd=%s&pageSize=50' % (today(-365), today(-365)))
    check('按购买日期筛（当天两头都含）', old_item in [x['reimburseId'] for x in q['rows']], q['total'])
    check('按提交日期筛', admin_list('submitBegin=%s&submitEnd=%s' % (today(), today()))['total'] > 0 and admin_list('submitEnd=%s' % today(-30))['total'] == 0)
    check('筛选写错 422（状态、日期、门店、页码）', codes(admin_list('status=xx'), admin_list('purchaseBegin=2026-02-30'), admin_list('submitEnd=abc'), admin_list('storeId=abc'), admin_list('pageNum=abc')) == [422] * 5)
    check('翻页不掉行：第一页 + 第二页', len(admin_list('pageNum=1&pageSize=10')['rows']) == 10 and admin_list('pageNum=2&pageSize=10')['pageNum'] == 2)
    st = api.raw('GET', '/kangaroo/reimburse/stores', token=atok)
    check('门店下拉：每家一行', st.get('code') == 200 and sorted(x['storeId'] for x in st['data']) == sorted(int(y) for y in rows_col("select distinct store_id from kg_reimbursement where del_flag='0'")), st)
    sql("update kg_store set store_name='改过名%s' where store_id=%d" % (NONCE, s['E']))
    check('门店改名后老行还是当时的名字（快照）', admin_list('storeId=%d' % s['E'])['rows'][0]['storeName'] == '报销走查E' + NONCE)
    sql("update kg_store set store_name='报销走查E%s' where store_id=%d" % (NONCE, s['E']))
    d = api.raw('GET', '/kangaroo/reimburse/%d' % a1, token=atok)
    check('后台详情', d.get('code') == 200 and d['data']['itemName'] == '枕头改' and d['data']['receipts'] == [IMG] and d['data']['revision'] == 2 and d['data']['paidBy'] == 'admin', d)

    status, blob, ctype = api.request('POST', '/kangaroo/reimburse/export', token=atok, data=b'', content_type='application/x-www-form-urlencoded')
    check('导出是真 xlsx', status == 200 and 'spreadsheetml' in ctype and blob[:2] == b'PK', (status, ctype, blob[:80]))
    parts = xlsx_parts(blob)
    sheet = parts.get('xl/worksheets/sheet1.xml', '')
    text = ''.join(parts.values())
    check('导出：表头、状态是中文、合计行', all(w in text for w in ('品名', '金额（元）', '已打款', '待审核', '合计（全部', '合计（不含已驳回）')), sheet[:300])
    check('导出：撤回的不在里面', '要撤回的' not in text)
    check('导出：用户填的 =1+1 不是公式', '=1+1' in text and '<f>' not in sheet, sheet[:200])
    check('导出：金额是数字格子（能求和）', re.search(r'<c r="F2"[^>]*(?:t="n")?[^>]*><v>[0-9.]+</v>', sheet) is not None, sheet[:800])
    total_v = re.findall(r'<c r="F(\d+)"[^>]*><v>([0-9.E+-]+)</v>', sheet)
    check('导出：合计金额和列表一样', abs(float(total_v[-2][1]) - float(sm_now(admin_list('')))) < 0.005 and len(total_v) == int(sql("select count(*) from kg_reimbursement where del_flag='0'")) + 2, total_v[-3:])
    status, blob2, _ = api.request('POST', '/kangaroo/reimburse/export', token=atok, data=('status=paid&storeId=%d' % s['A']).encode(), content_type='application/x-www-form-urlencoded')
    t2 = ''.join(xlsx_parts(blob2).values())
    check('导出跟着筛选走', status == 200 and '已打款' in t2 and '待审核' not in t2, t2[:200])
    r = api.request('POST', '/kangaroo/reimburse/export', token=atok, data=b'status=xx', content_type='application/x-www-form-urlencoded')
    check('导出筛选写错 422', json.loads(r[1].decode()).get('code') == 422, r[1][:200])

    # ---------------------------------------------------------------- 注销、数据导出
    print('\n=== 注销和数据导出 ===')
    g = u['gone']
    pic = img('gone')

    def gsub(item, amount):
        x = sub('gone', good(itemName=item, amount=amount, receipts=[pic], remark='备注' + item), store='K')
        assert x.get('code') == 200, x
        return x['data']['reimburseId']

    paid_id, pend_id, rej_id, wd_id = gsub('已打款的', '1.11'), gsub('待审核的', '2.22'), gsub('已驳回的', '3.33'), gsub('撤回的', '4.44')
    api.put('/kangaroo/reimburse/%d/approve' % paid_id, {}, token=atok)
    api.put('/kangaroo/reimburse/%d/reject' % rej_id, {'reason': '不报'}, token=atok)
    api.delete('/app/staff/reimbursements/%d' % wd_id, token=g['tok'])
    # 数据导出：本人的报销都在，撤回的不在
    status, blob, _ = api.request('GET', '/app/me/export.xlsx', token=g['tok'])
    parts = xlsx_parts(blob)
    check('个人数据导出有「采购报销」这一张表', status == 200 and '采购报销' in parts.get('xl/workbook.xml', ''), parts.get('xl/workbook.xml', '')[:400])
    text = ''.join(parts.values())
    check('个人数据导出：品名、金额都在，撤回的不在', all(w in text for w in ('已打款的', '待审核的', '已驳回的', '1.11', '2.22')) and '撤回的' not in text and '4.44' not in text, text[:300])

    chk = api.get('/app/me/deactivation', token=g['tok'])['data']
    check('有待审核、待打款：注销被挡，说清楚几笔', any('采购报销' in b and '2 笔' in b for b in chk['blockers']), chk['blockers'])
    check('注销页的「保留」里写了报销', any('采购报销' in x for x in chk['willKeep']), chk['willKeep'])
    api.put('/kangaroo/reimburse/%d/paid' % paid_id, token=atok)
    api.put('/kangaroo/reimburse/%d/approve' % pend_id, {}, token=atok)
    chk = api.get('/app/me/deactivation', token=g['tok'])['data']
    check('已通过没打款的还挡', any('采购报销' in b and '1 笔' in b for b in chk['blockers']), chk['blockers'])
    api.put('/kangaroo/reimburse/%d/paid' % pend_id, token=atok)
    chk = api.get('/app/me/deactivation', token=g['tok'])['data']
    check('都打完了、只剩驳回的：不再挡', not any('采购报销' in b for b in chk['blockers']), chk['blockers'])
    sql("update kg_staff_grant set status='1' where app_user_id=%d" % g['id'])
    chk = api.get('/app/me/deactivation', token=g['tok'])['data']
    check('撤了授权之后可以注销', chk['canDeactivate'] is True, chk['blockers'])
    r = api.post('/app/me/deactivate', {'confirm': '确认注销', 'reason': '测试'}, token=g['tok'])
    check('注销成功', r.get('code') == 200, r)
    rows = rows_of("select reimburse_id, item_name, amount, receipts, remark, status, submitter_name, anonymous, app_user_id from kg_reimbursement where app_user_id=%d order by reimburse_id" % g['id'])
    check('财务记录都留着：打款、驳回的都在，提交人转匿名', [(int(x[0]), x[1], x[2], x[5], x[6], x[7]) for x in rows] == [(paid_id, '已打款的', '1.11', 'paid', '已注销用户', '1'), (pend_id, '待审核的', '2.22', 'paid', '已注销用户', '1'), (rej_id, '已驳回的', '3.33', 'rejected', '已注销用户', '1')], rows)
    check('凭证、备注留着', rows and rows[0][3] == json.dumps([pic]) and rows[0][4] == '备注已打款的', rows[:1])
    check('撤回的不是财务记录，不留', sql('select count(*) from kg_reimbursement where reimburse_id=%d' % wd_id) == '0')
    check('本人的审核通知下架了', sql("select count(*) from kg_message where biz_type='reimburse' and recipient_id=%d and del_flag='0'" % g['id']) == '0')
    q = admin_list('storeId=%d' % s['K'])
    check('后台表格看得到，提交人显示已注销用户', q['total'] == 3 and all(x['submitterName'] == '已注销用户' and x['anonymous'] is True for x in q['rows']), q['rows'][:1])
    check('昵称不在任何一行里', '要注销的' not in json.dumps(q, ensure_ascii=False))
    check('注销后令牌失效', api.get('/app/staff/reimbursements?storeId=%d' % s['K'], token=g['tok']).get('code') in (401, 403))
    jar.stop()
    return s, u


def rows_of(q):
    out = sql(q)
    return [x.split('\t') for x in out.split('\n')] if out else []


def rows_col(q):
    return [x[0] for x in rows_of(q)]


def sm_now(listed):
    return listed['summary']['sumAmount']


def run_new_phase(s):
    """new 阶段：小院主理人含义不变。关了 jar 改库再起：RoleSwitchSchema 启动时读阶段"""
    sql("update kg_role_switch set phase='new' where id=1")
    jar = Jar(NEW_JAR, 'new-new')
    api = jar.api
    ping = api.get('/app/auth/ping')['data']
    check('new 阶段：新 jar 启动', ping.get('rolePhase') == 'new', ping)
    atok = api.admin()

    def login(tag, name):
        return api.login('reimburse-%s-%s' % (NONCE, tag), name)[0]

    keeper = login('new', '新阶段主理人')
    photo = api.upload(keeper)
    body = {'itemName': '新阶段买的', 'quantity': 1, 'amount': '66.60', 'purchaseDate': today(), 'receipts': [photo]}
    r = api.post('/app/staff/reimbursements?storeId=%d' % s['M'], body, token=keeper)
    check('new：直营店的小院主理人能交', r.get('code') == 200 and r['data']['status'] == 'pending', r)
    rid = r['data']['reimburseId']
    check('new：能看列表、详情', codes(api.get('/app/staff/reimbursements?storeId=%d' % s['M'], token=keeper), api.get('/app/staff/reimbursements/%d' % rid, token=keeper)) == [200, 200])
    for tag, nick, store, label in (('mgr', '义工主理人甲', 'A', '义工主理人'), ('st', '老员工甲', 'A', '老员工（已退役）'), ('lead', '城市负责人甲', 'A', '城市负责人'), ('svc', '商家服务人甲', 'A', '商家服务人'), ('ol', '线上专员甲', 'A', '线上专员')):
        tok = login(tag, nick)
        check('new：%s不能交（403）' % label, api.post('/app/staff/reimbursements?storeId=%d' % s[store], body, token=tok).get('code') == 403)
    km = login('km', '代运营店主理人')
    check('new：代运营店的小院主理人不能交', api.post('/app/staff/reimbursements?storeId=%d' % s['C'], body, token=km).get('code') == 403)
    kb = login('kb', '别家主理人')
    check('new：别家门店不能交', api.post('/app/staff/reimbursements?storeId=%d' % s['M'], body, token=kb).get('code') == 403)
    r = api.put('/kangaroo/reimburse/%d/approve' % rid, {'revision': 1}, token=atok)
    check('new：后台能通过', r.get('code') == 200, r)
    msg = [x for x in api.get('/app/message/list?mode=staff', token=keeper).get('rows') or [] if x.get('bizType') == 'reimburse' and int(x.get('bizId')) == rid]
    check('new：通知到提交人', len(msg) == 1 and msg[0]['route'] == '/pages/staff/reimburse?id=%d&storeId=%d' % (rid, s['M']), msg)
    check('new：后台能标打款', api.put('/kangaroo/reimburse/%d/paid' % rid, token=atok).get('code') == 200)
    jar.stop()
    sql("update kg_role_switch set phase='legacy' where id=1")


def main():
    clone()
    sql("update sys_config set config_value='false' where config_key='sys.account.captchaEnabled';update sys_user set password='%s' where user_name='admin';" % ADMIN_HASH)
    # 账号 ID 每次不一样：限流键按账号算，不撞上一轮
    sql('alter table kg_app_user auto_increment = %d' % random.randint(5000000, 9000000))
    for v, name in ((64, 'V64__role_switch.sql'), (65, 'V65__stay_comment.sql'), (66, 'V66__forms.sql')):
        if sql('select count(*) from kg_schema_version where version=%d' % v) == '0':
            script(MIG / name)
    check('账本有 66、没有 61', sql('select count(*) from kg_schema_version where version=66') == '1' and sql('select count(*) from kg_schema_version where version=61') == '0')
    check('kg_reimbursement 是空的', sql('select count(*) from kg_reimbursement') == '0')

    print('\n=== 老 jar：V66 空表 ===')
    old_jar_pass('空表', 'empty')

    print('\n=== 新 jar：legacy ===')
    s, _ = run_new_legacy()
    check('探针是活的：新 jar 碰过表，计数不是 0（老 jar 那两条才有意义）', io_count() > 0, io_count())

    print('\n=== 新 jar：new 阶段 ===')
    run_new_phase(s)

    print('\n=== 老 jar：有报销数据 ===')
    n = sql('select count(*) from kg_reimbursement')
    old_jar_pass('有数据', 'data')
    check('老 jar 没改报销数据', sql('select count(*) from kg_reimbursement') == n)


try:
    main()
finally:
    for j in started:
        j.stop()
    drop_db()
    print('已删除', GUARD)
print('\n总计 %d 项，通过 %d，失败 %d' % (len(results), len(results) - len(fails), len(fails)))
for f in fails:
    print('  失败：' + f)
sys.exit(1 if fails else 0)
