# -*- coding: utf-8 -*-
"""袋鼠小院 三端互通端到端测试。

覆盖：管理后台 -> 后端 -> 小程序，以及 小程序 -> 后端 -> 管理后台 两个方向，
外加权限、越权、并发、幂等、非法状态流转等负向用例。

前置条件：
  1. 后端已启动在 http://localhost:8080，且以 -Dkangaroo.wx.mock-enabled=true 运行
     （没有真实微信凭证时用它跑通链路；真实微信登录需另行验证）
  2. MySQL dkxy 库已执行 sql/kangaroo/V1 与 V2
  3. Redis 可访问（用于读取图形验证码答案，从而走真实的后台登录流程）

运行：
  python e2e/test_kangaroo_flow.py

退出码 0 表示全部通过。每条用例都打印 预期/实际。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

os.environ['no_proxy'] = ','.join(filter(None, [os.environ.get('no_proxy', os.environ.get('NO_PROXY', '')), 'localhost', '127.0.0.1', '::1']))
import uuid
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from redis_client import MiniRedis  # noqa: E402

BASE = os.environ.get('KG_BASE', 'http://localhost:8080')
MYSQL = os.environ.get('KG_MYSQL', r'D:\MySQL Server 8.0\bin\mysql.exe')
DB = os.environ.get('KG_DB', 'dkxy')
DB_USER = os.environ.get('KG_DB_USER', 'root')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from dbcred import db_password  # noqa: E402

DB_PASSWORD = db_password(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REDIS_DB = int(os.environ.get('KG_REDIS_DB', '9'))

PASSED = []
FAILED = []


# ---------------------------------------------------------------- helpers

def http(method, path, body=None, token=None, app_token=None, raw=False):
    url = BASE + path
    data = None
    headers = {'Content-Type': 'application/json;charset=UTF-8'}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode('utf-8')
    if token:
        headers['Authorization'] = 'Bearer ' + token
    if app_token:
        headers['X-App-Token'] = app_token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode('utf-8')
            return r.status, (text if raw else json.loads(text))
    except urllib.error.HTTPError as e:
        text = e.read().decode('utf-8', 'replace')
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, {'raw': text}


def sign_contract(app_id, app_token):
    """Supported signing flow: read current terms, explicitly agree, persist evidence."""
    response = http('GET', '/app/application/%s/contract' % app_id, app_token=app_token)[1]
    assert response.get('code') == 200, response
    terms = response['data']
    return http('POST', '/app/application/%s/contract/sign' % app_id,
                {'name': terms['signerDefault'], 'hash': terms['hash'], 'agree': True}, app_token=app_token)


END_MARK = '__KG_END__'


def _mysql(statement):
    """跑一条（或几条）SQL，返回结束标记之前的输出行。

    为什么要 -n 和结束标记：这台 Windows 上的 mysql.exe 在 stdout 是管道时，
    约 1/200 的调用会**正常退出（exit 0）却一个字节都不输出**（实测 800 次里 4 次，
    stderr 也是空的）。对查询来说这等于"查到 0 行"：
      · 要值的地方会在下游 int(None) 上莫名其妙地炸（后台动作用例的准备数据就这么红过）；
      · 断言"某行已不存在"的地方会**假通过**。
    -n（每条语句后刷新输出）从根上消除了它（1200 次 0 次丢失）；
    结束标记是兜底的探测器：标记没到，说明输出丢了，这次结果不可信 —— 直接报错，
    **不重试**：重试会把"输出会丢"这件事重新藏起来。
    """
    env = dict(os.environ)
    env['MYSQL_PWD'] = DB_PASSWORD
    stmt = statement.rstrip().rstrip(';') + "; select '%s'" % END_MARK
    out = subprocess.run(
        [MYSQL, '--no-defaults', '--protocol=TCP', '-h127.0.0.1', '-P3306', '-u' + DB_USER, '--default-character-set=utf8mb4', '-n', '-N', '-B', DB,
         '-e', stmt],
        capture_output=True, env=env)
    if out.returncode != 0:
        raise RuntimeError('mysql failed: ' + out.stderr.decode('utf-8', 'replace'))
    # Windows 下 mysql 客户端输出行尾带 CR，不清理会让字符串比对出现看不见的差异
    lines = [l.rstrip('\r') for l in out.stdout.decode('utf-8', 'replace').split('\n')]
    while lines and not lines[-1]:
        lines.pop()
    if not lines or lines[-1] != END_MARK:
        raise RuntimeError('mysql 输出丢失（exit 0 但没收到结束标记），这次结果不可信：'
                           + statement[:200])
    return lines[:-1]


def sql(query):
    """执行只读 SQL，返回行列表（不含表头）。"""
    return [line.split('\t') for line in _mysql(query)]


def scalar(query):
    rows = sql(query)
    return rows[0][0] if rows else None


def sql_exec(statement):
    """执行写 SQL。只用于在测试里**人为制造异常状态**，再验证修复路径真的能修回来。

    正常业务断言一律走接口，不靠直接改库；这个口子只服务于
    "先把数据搞坏、再证明运维接口能修好"这一类用例。

    结束标记在写语句之后执行：收到它就说明写语句已经跑完。
    """
    _mysql(statement)


def check(name, expected, actual, ok=None):
    """断言一条并打印证据。

    注意 ok= 的语义：给了 ok 就用它判定，expected/actual 只用于打印。
    早先这会让日志出现"[PASS] 预期: True 实际: False"这种自相矛盾的行，
    读日志的人无从判断到底按哪个口径过的。所以用了 ok= 时显式标注出来。
    """
    passed = (expected == actual) if ok is None else ok
    tag = 'PASS' if passed else 'FAIL'
    (PASSED if passed else FAILED).append(name)
    note = '' if ok is None else '（判定口径为显式 ok=%s，上面两行仅供参考）' % bool(ok)
    print('[%s] %s\n       预期: %s\n       实际: %s%s' % (tag, name, expected, actual, note))
    return passed


def section(title):
    print('\n' + '=' * 78)
    print('  ' + title)
    print('=' * 78)


# ---------------------------------------------------------------- admin login

def admin_login():
    """走真实的后台登录流程：取验证码 -> 从 Redis 读答案 -> 提交。

    不关闭验证码开关，这样测试覆盖的是生产同款登录路径。
    """
    status, body = http('GET', '/captchaImage')
    assert status == 200 and body.get('code') == 200, body
    uuid_ = body['uuid']
    captcha_enabled = body.get('captchaEnabled', True)

    code = ''
    if captcha_enabled:
        r = MiniRedis(db=REDIS_DB)
        try:
            raw = r.get('captcha_codes:' + uuid_)
        finally:
            r.close()
        assert raw is not None, 'Redis 中找不到验证码答案 captcha_codes:' + uuid_
        code = raw.decode('utf-8').strip().strip('"')

    status, body = http('POST', '/login',
                        {'username': 'admin', 'password': 'admin123',
                         'code': code, 'uuid': uuid_})
    assert body.get('code') == 200, body
    return body['token']


# ---------------------------------------------------------------- main

def _run(run_id):
    print('测试运行标识 run_id=%s（所有测试数据都带该标识，便于清理）' % run_id)

    # ============================================================ 0. 登录
    section('0. 管理后台登录（真实验证码流程）')
    admin_token = admin_login()
    check('管理员登录返回 token', True, bool(admin_token))

    status, body = http('GET', '/getInfo', token=admin_token)
    check('getInfo 返回 admin 用户', 'admin', body.get('user', {}).get('userName'))
    perms = body.get('permissions', [])
    check('admin 拥有全部权限标识', True, '*:*:*' in perms)

    status, body = http('GET', '/getRouters', token=admin_token)
    router_names = [r.get('name') for r in body.get('data', [])]
    check('后台路由包含袋鼠小院目录', True, 'Kangaroo' in router_names,
          ok=any('kangaroo' in str(r.get('path', '')).lower() for r in body.get('data', [])))

    # ============================================================ 1. 凭证隔离
    section('1. 凭证隔离：后台令牌不能访问小程序接口，反之亦然')
    status, body = http('GET', '/app/message/list', token=admin_token)
    check('后台 Authorization 令牌访问 /app/** 被拒', 401, body.get('code'))

    # ============================================================ 2. 管理后台建门店
    section('2. 管理后台 -> 后端：创建门店与岗位')
    store_name = 'E2E测试小院-' + run_id
    status, body = http('POST', '/kangaroo/store',
                        {'storeName': store_name, 'city': '大理',
                         'address': '大理古城苍山路88号', 'intro': '端到端测试门店',
                         'contactPhone': '18512345678'},
                        token=admin_token)
    check('新增门店成功', 200, body.get('code'))
    store_id = body.get('data')
    check('返回门店ID', True, store_id is not None)

    db_name = scalar("select store_name from kg_store where store_id=%s" % store_id)
    check('门店已真实落库且名称一致', store_name, db_name)

    status, body = http('POST', '/kangaroo/store',
                        {'storeName': store_name, 'city': '大理', 'address': 'x'},
                        token=admin_token)
    check('同城同名门店被拒绝', 500, body.get('code'))

    # 认证只能走专用接口
    safe_cert = scalar("select safe_cert from kg_store where store_id=%s" % store_id)
    check('新建门店默认未认证', '1', safe_cert)
    status, body = http('PUT', '/kangaroo/store', {'storeId': store_id, 'safeCert': '0'}, token=admin_token)
    safe_cert = scalar("select safe_cert from kg_store where store_id=%s" % store_id)
    check('通用编辑接口无法把门店改成已认证', '1', safe_cert)
    status, body = http('PUT', '/kangaroo/store/cert/%s?certified=true' % store_id, token=admin_token)
    safe_cert = scalar("select safe_cert from kg_store where store_id=%s" % store_id)
    check('专用认证接口可以认证', '0', safe_cert)

    # 岗位
    start_date = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400))
    status, body = http('POST', '/kangaroo/job',
                        {'storeId': store_id, 'jobType': 'volunteer',
                         'title': 'E2E咖啡馆驻店伙伴-' + run_id, 'city': '大理',
                         'address': '苍山路88号', 'salaryType': 'daily',
                         'salaryMin': 120, 'salaryUnit': '元/天',
                         'settleDesc': '月结 · 次月5日前发放',
                         'startDate': start_date, 'minDays': 14,
                         'dailyHours': '每天4小时', 'boardType': 'all',
                         'insuranceFlag': '0', 'headcount': 1,
                         'tags': '手作,咖啡,包吃包住',
                         'content': '协助日常出品与店面整理', 'requirement': '性格开朗'},
                        token=admin_token)
    check('新增岗位成功', 200, body.get('code'))
    job_id = body.get('data')

    job_status = scalar("select status from kg_job where job_id=%s" % job_id)
    check('新建岗位强制为草稿', 'draft', job_status)

    status, body = http('GET', '/app/job/list?type=volunteer')
    ids = [r['jobId'] for r in body.get('rows', [])]
    check('草稿岗位不出现在小程序列表', False, job_id in ids)

    status, body = http('GET', '/app/job/%s' % job_id)
    check('草稿岗位详情对小程序不可见', 500, body.get('code'))

    # 非法状态流转
    status, body = http('PUT', '/kangaroo/job/changeStatus', {'jobId': job_id, 'status': 'onboarded'},
                        token=admin_token)
    check('岗位改成不存在的状态被拒绝', 500, body.get('code'))

    status, body = http('PUT', '/kangaroo/job/changeStatus', {'jobId': job_id, 'status': 'published'},
                        token=admin_token)
    check('岗位发布成功', 200, body.get('code'))
    publish_time = scalar("select publish_time from kg_job where job_id=%s" % job_id)
    check('发布时间已写入', True, publish_time not in (None, 'NULL'))

    status, body = http('GET', '/app/job/list?type=volunteer')
    rows = body.get('rows', [])
    ids = [r['jobId'] for r in rows]
    check('发布后岗位出现在小程序列表', True, job_id in ids)
    row = [r for r in rows if r['jobId'] == job_id][0]
    check('列表返回服务端拼好的薪资文案', '120 元/天', row.get('salaryText'))
    check('列表返回门店名', store_name, row.get('storeName'))
    check('列表返回安心认证标记', '0', row.get('safeCert'))
    check('列表返回标签数组', ['手作', '咖啡', '包吃包住'], row.get('tags'))

    # ============================================================ 3. 小程序登录
    section('3. 小程序登录与账号隔离')
    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-a-' + run_id, 'nickname': '小院同学A'})
    check('用户A 登录成功', 200, body.get('code'))
    token_a = body.get('token')
    account_a = body.get('account')
    check('返回 account.id', True, bool(account_a.get('id')))
    check('新账号没有员工授权', [], account_a.get('staffGrants'))
    check('登录响应标注了本地联调模式', True, body.get('mockLogin') is True)
    check('不下发 openid', True, 'openid' not in account_a)
    user_a_id = int(account_a['id'])

    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-b-' + run_id, 'nickname': '店长B'})
    token_b = body.get('token')
    user_b_id = int(body['account']['id'])
    check('用户B 登录成功且与A不同账号', True, user_a_id != user_b_id)

    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-a-' + run_id, 'nickname': '小院同学A'})
    check('同一 code 再次登录复用同一账号', user_a_id, int(body['account']['id']))
    token_a = body.get('token')

    status, body = http('GET', '/kangaroo/store/list', app_token=token_a)
    check('小程序令牌访问管理后台接口被拒', 401, body.get('code'))

    # ============================================================ 4. 报名
    section('4. 小程序 -> 后端 -> 管理后台：提交报名')
    arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400 * 3))
    idem = 'e2e-' + run_id + '-1'

    status, body = http('POST', '/app/application',
                        {'jobId': job_id, 'name': '测试同学', 'age': 22, 'phone': '13800138000',
                         'skills': '会做手冲', 'arrival': arrival, 'days': 30,
                         'note': '希望长期', 'idemKey': idem},
                        app_token=token_a)
    check('提交报名成功', 200, body.get('code'))
    app_id = body.get('data', {}).get('appId')
    app_no = body.get('data', {}).get('appNo')
    check('返回报名单号', True, bool(app_no))

    row = sql("select app_user_id, job_id, store_id, status, real_name, age, phone, stay_days "
              "from kg_application where app_id=%s" % app_id)[0]
    check('报名归属用户A', str(user_a_id), row[0])
    check('报名关联岗位', str(job_id), row[1])
    check('报名快照门店', str(store_id), row[2])
    check('初始状态为已提交', 'submitted', row[3])
    check('姓名落库', '测试同学', row[4])
    check('停留天数落库', '30', row[7])

    applied = scalar("select applied_count from kg_job where job_id=%s" % job_id)
    check('岗位报名计数 +1', '1', applied)

    log_cnt = scalar("select count(*) from kg_application_log where app_id=%s" % app_id)
    check('写入一条状态流转日志', '1', log_cnt)

    msg_cnt = scalar("select count(*) from kg_message where recipient_id=%s and biz_id=%s and mode='user'"
                     % (user_a_id, app_id))
    check('给用户A 生成一条站内消息', '1', msg_cnt)

    # 幂等
    status, body = http('POST', '/app/application',
                        {'jobId': job_id, 'name': '测试同学', 'age': 22, 'phone': '13800138000',
                         'arrival': arrival, 'days': 30, 'idemKey': idem},
                        app_token=token_a)
    check('相同幂等键重复提交返回同一条报名', app_id, body.get('data', {}).get('appId'))
    cnt = scalar("select count(*) from kg_application where job_id=%s and app_user_id=%s" % (job_id, user_a_id))
    check('数据库仍只有一条报名', '1', cnt)

    # 重复报名
    status, body = http('POST', '/app/application',
                        {'jobId': job_id, 'name': '测试同学', 'age': 22, 'phone': '13800138000',
                         'arrival': arrival, 'days': 30, 'idemKey': 'e2e-' + run_id + '-2'},
                        app_token=token_a)
    check('换幂等键重复报名同一岗位被拒绝', 500, body.get('code'))
    check('拒绝原因是已报名', True, '已报名' in str(body.get('msg')))

    # 参数校验
    bad_cases = [
        ({'jobId': job_id, 'name': '', 'age': 22, 'phone': '13800138000', 'arrival': arrival, 'days': 30},
         '姓名为空'),
        ({'jobId': job_id, 'name': 'x', 'age': 999, 'phone': '13800138000', 'arrival': arrival, 'days': 30},
         '年龄越界'),
        ({'jobId': job_id, 'name': 'x', 'age': 22, 'phone': '123', 'arrival': arrival, 'days': 30},
         '手机号格式错'),
        ({'jobId': job_id, 'name': 'x', 'age': 22, 'phone': '13800138000', 'arrival': '2000-01-01', 'days': 30},
         '到岗日期早于今天'),
        ({'jobId': job_id, 'name': 'x', 'age': 22, 'phone': '13800138000', 'arrival': arrival, 'days': 3},
         '停留天数低于岗位最短要求'),
        ({'jobId': 99999999, 'name': 'x', 'age': 22, 'phone': '13800138000', 'arrival': arrival, 'days': 30},
         '岗位不存在'),
    ]
    for payload, label in bad_cases:
        payload['idemKey'] = 'e2e-%s-bad-%s' % (run_id, label)
        status, body = http('POST', '/app/application', payload, app_token=token_b)
        check('参数校验：' + label + ' 被服务端拒绝', 500, body.get('code'))

    # 越权
    status, body = http('GET', '/app/application/%s' % app_id, app_token=token_b)
    check('用户B 无法查看用户A 的报名', 403, body.get('code'))
    status, body = http('POST', '/app/application/%s/withdraw' % app_id, app_token=token_b)
    check('用户B 无法撤回用户A 的报名', 403, body.get('code'))
    status, body = http('GET', '/app/application/%s' % app_id)
    check('未登录无法查看报名', 401, body.get('code'))

    # 管理后台能看到
    status, body = http('GET', '/kangaroo/application/list?appNo=' + app_no, token=admin_token)
    check('管理后台能查到这条报名', 1, body.get('total'))
    admin_row = body['rows'][0]
    check('后台列表带岗位标题', True, run_id in str(admin_row.get('jobTitle')))
    check('后台列表带门店名', store_name, admin_row.get('storeName'))

    # ============================================================ 5. 员工授权
    section('5. 员工授权：管理后台 -> 小程序员工身份')
    status, body = http('GET', '/app/staff/applications', app_token=token_b)
    check('无授权账号访问员工接口被拒', 403, body.get('code'))

    status, body = http('POST', '/kangaroo/grant',
                        {'appUserId': user_b_id, 'storeId': store_id, 'roleCode': 'manager'},
                        token=admin_token)
    check('后台授予员工成功', 200, body.get('code'))

    status, body = http('GET', '/app/auth/account', app_token=token_b)
    grants = body.get('data', {}).get('staffGrants', [])
    check('小程序侧账号出现员工授权', 1, len(grants))
    check('授权携带门店名', store_name, grants[0].get('storeName') if grants else None)
    check('授权 roleName 为主理人', '主理人', grants[0].get('roleName') if grants else None)
    check('授权 active 为 true', True, grants[0].get('active') if grants else None)

    status, body = http('GET', '/app/staff/applications?storeId=%s' % store_id, app_token=token_b)
    check('员工能看到本门店报名', 200, body.get('code'))
    staff_rows = body.get('rows', [])
    check('员工列表包含该报名', True, any(r['appId'] == app_id for r in staff_rows))
    staff_row = [r for r in staff_rows if r['appId'] == app_id][0]
    check('员工列表中姓名已脱敏（首尾保留，中间打码）', '测**学', staff_row.get('applicantName'))
    check('员工列表中手机号已脱敏', '138****8000', staff_row.get('applicantPhone'))

    # 跨门店越权
    status, body = http('POST', '/kangaroo/store',
                        {'storeName': '别人家小院-' + run_id, 'city': '丽江', 'address': 'y'},
                        token=admin_token)
    other_store_id = body.get('data')
    status, body = http('GET', '/app/staff/applications?storeId=%s' % other_store_id, app_token=token_b)
    check('员工访问未授权门店被拒', 403, body.get('code'))

    # ============================================================ 6. 审核
    section('6. 报名审核与状态机')
    status, body = http('POST', '/app/application/%s/confirm' % app_id, {'intent': 'accept'}, app_token=token_a)
    check('未面试就确认意向被状态机拒绝', 500, body.get('code'))

    status, body = http('POST', '/app/staff/applications/%s/audit' % app_id, {'pass': False},
                        app_token=token_b)
    check('驳回不填原因被拒绝', 500, body.get('code'))

    status, body = http('POST', '/app/staff/applications/%s/audit' % app_id, {'pass': True},
                        app_token=token_b)
    check('员工审核通过', 200, body.get('code'))
    st = scalar("select status from kg_application where app_id=%s" % app_id)
    check('状态变为审核通过', 'review_passed', st)
    audit_by = scalar("select audit_by_type from kg_application where app_id=%s" % app_id)
    check('记录审核人类型为 staff', 'staff', audit_by)

    msg = sql("select title from kg_message where recipient_id=%s and biz_id=%s and mode='user' order by msg_id desc limit 1"
              % (user_a_id, app_id))
    check('用户A 收到审核通过消息', '报名审核通过', msg[0][0] if msg else None)

    status, body = http('POST', '/app/staff/applications/%s/audit' % app_id, {'pass': True},
                        app_token=token_b)
    check('重复审核被拒绝', 500, body.get('code'))

    # ============================================================ 7. 面试
    section('7. 面试时段与并发抢占')
    status, body = http('GET', '/app/application/%s/slots' % app_id, app_token=token_a)
    check('此时没有可选时段', [], body.get('data'))

    t0 = time.time() + 86400
    slot_ids = []
    for i in range(2):
        s = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(t0 + i * 3600))
        e = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(t0 + i * 3600 + 1200))
        status, body = http('POST', '/kangaroo/slot',
                            {'storeId': store_id, 'startTime': s, 'endTime': e}, token=admin_token)
        check('后台新增面试时段 %d' % (i + 1), 200, body.get('code'))
    rows = sql("select slot_id from kg_interview_slot where store_id=%s and del_flag='0' order by slot_id" % store_id)
    slot_ids = [int(r[0]) for r in rows]

    status, body = http('GET', '/app/application/%s/slots' % app_id, app_token=token_a)
    check('小程序能看到 2 个可选时段', 2, len(body.get('data', [])))

    status, body = http('POST', '/app/application/%s/book-interview' % app_id, {'slotId': slot_ids[0]},
                        app_token=token_a)
    check('预约面试成功', 200, body.get('code'))
    st = scalar("select status from kg_application where app_id=%s" % app_id)
    check('状态变为已预约面试', 'interview_booked', st)
    slot_st = scalar("select status from kg_interview_slot where slot_id=%s" % slot_ids[0])
    check('时段被标记为已占用', 'booked', slot_st)

    status, body = http('PUT', '/kangaroo/slot', {'slotId': slot_ids[0], 'storeId': store_id}, token=admin_token)
    check('已占用时段不可修改', 500, body.get('code'))
    status, body = http('DELETE', '/kangaroo/slot/%s' % slot_ids[0], token=admin_token)
    check('已占用时段不可删除', 500, body.get('code'))

    # 并发抢同一时段
    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-c-' + run_id, 'nickname': '同学C'})
    token_c = body['token']
    user_c_id = int(body['account']['id'])
    status, body = http('POST', '/app/application',
                        {'jobId': job_id, 'name': '同学丙', 'age': 24, 'phone': '13900139000',
                         'arrival': arrival, 'days': 30, 'idemKey': 'e2e-%s-c' % run_id},
                        app_token=token_c)
    app_id_c = body['data']['appId']
    http('POST', '/app/staff/applications/%s/audit' % app_id_c, {'pass': True}, app_token=token_b)

    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-d-' + run_id, 'nickname': '同学D'})
    token_d = body['token']
    status, body = http('POST', '/app/application',
                        {'jobId': job_id, 'name': '同学丁', 'age': 25, 'phone': '13700137000',
                         'arrival': arrival, 'days': 30, 'idemKey': 'e2e-%s-d' % run_id},
                        app_token=token_d)
    app_id_d = body['data']['appId']
    http('POST', '/app/staff/applications/%s/audit' % app_id_d, {'pass': True}, app_token=token_b)

    def book(args):
        aid, tok = args
        return http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slot_ids[1]}, app_token=tok)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(book, [(app_id_c, token_c), (app_id_d, token_d)]))
    oks = [r for r in results if r[1].get('code') == 200]
    check('并发抢同一面试时段只有一个成功', 1, len(oks))
    # 抢到时段的是丙还是丁取决于并发先后；后面「名额已满」那段要用真正约上面试的那位，不能写死丙
    winner = [p for p, r in zip([(app_id_c, token_c), (app_id_d, token_d)], results) if r[1].get('code') == 200]
    quota_app, quota_tok = winner[0] if winner else (app_id_c, token_c)
    booked_cnt = scalar("select count(*) from kg_interview where slot_id=%s" % slot_ids[1])
    check('该时段只产生一条面试记录', '1', booked_cnt)

    # ============================================================ 8. 双向确认
    section('8. 双向确认与录用名额')
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    status, body = http('POST', '/app/application/%s/finish-interview' % app_id,
                        {'durationSeconds': 1107, 'understandJob': '清楚', 'acceptBoard': '接受',
                         'confirmArrival': '可以', 'feedbackNote': '沟通顺畅'}, app_token=token_a)
    check('结束面试成功', 200, body.get('code'))
    st = scalar("select status from kg_application where app_id=%s" % app_id)
    check('状态变为面试已完成', 'interview_done', st)
    dur = scalar("select duration_seconds from kg_interview where app_id=%s" % app_id)
    check('面试时长落库', '1107', dur)

    status, body = http('POST', '/app/application/%s/confirm' % app_id, {'intent': 'pending'}, app_token=token_a)
    check('选择"还想考虑"不推进状态', 200, body.get('code'))
    st = scalar("select status from kg_application where app_id=%s" % app_id)
    check('状态仍为面试已完成', 'interview_done', st)

    status, body = http('POST', '/app/staff/applications/%s/confirm' % app_id, app_token=token_b)
    check('用户未确认前商家不能确认录用', 500, body.get('code'))

    status, body = http('POST', '/app/application/%s/confirm' % app_id, {'intent': 'accept'}, app_token=token_a)
    check('用户确认愿意前往', 200, body.get('code'))
    row = sql("select status, confirm_deadline, user_intent from kg_application where app_id=%s" % app_id)[0]
    check('状态变为用户已确认', 'user_confirmed', row[0])
    check('写入商家确认截止时间', True, row[1] not in (None, 'NULL'))
    check('记录用户意向', 'accept', row[2])

    status, body = http('POST', '/app/application/%s/sign' % app_id, app_token=token_a)
    check('旧签约入口不能绕过责任书证据', 409, body.get('code'))

    status, body = http('POST', '/app/staff/applications/%s/confirm' % app_id, app_token=token_b)
    check('商家确认录用成功', 200, body.get('code'))
    hired = scalar("select hired_count from kg_job where job_id=%s" % job_id)
    check('岗位已录用人数 +1', '1', hired)

    # 名额已满
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(quota_app)
    http('POST', '/app/application/%s/finish-interview' % quota_app, {}, app_token=quota_tok)
    http('POST', '/app/application/%s/confirm' % quota_app, {'intent': 'accept'}, app_token=quota_tok)
    status, body = http('POST', '/app/staff/applications/%s/confirm' % quota_app, app_token=token_b)
    check('名额已满时无法再确认录用', 500, body.get('code'))
    check('拒绝原因是名额已满', True, '名额' in str(body.get('msg')))
    hired = scalar("select hired_count from kg_job where job_id=%s" % job_id)
    check('名额未被超额扣减', '1', hired)

    status, body = sign_contract(app_id, token_a)
    check('用户签署责任书成功', 200, body.get('code'))
    status, body = http('POST', '/app/staff/applications/%s/onboard' % app_id, app_token=token_b)
    check('员工标记入职成功', 200, body.get('code'))
    st = scalar("select status from kg_application where app_id=%s" % app_id)
    check('最终状态为已入职', 'onboarded', st)

    logs = sql("select to_status from kg_application_log where app_id=%s order by log_id" % app_id)
    chain = [r[0] for r in logs]
    check('状态流转日志完整可追溯',
          ['submitted', 'review_passed', 'interview_booked', 'interview_done', 'interview_done',
           'user_confirmed', 'store_confirmed', 'contract_signed', 'onboarded'],
          chain)

    status, body = http('POST', '/app/staff/applications/%s/onboard' % app_id, app_token=token_b)
    check('终态不可再流转', 500, body.get('code'))

    # ============================================================ 9. 消息
    section('9. 消息中心')
    status, body = http('GET', '/app/message/list', app_token=token_a)
    rows = body.get('rows', [])
    check('用户A 能看到自己的消息', True, len(rows) >= 5)
    check('消息 recipientId 为字符串且等于账号ID', str(user_a_id), rows[0].get('recipientId'))
    check('未读数大于 0', True, body.get('unread') > 0)

    other = [r for r in rows if r.get('recipientId') != str(user_a_id)]
    check('消息列表不含他人消息', 0, len(other))

    msg_id = rows[0]['id']
    status, body = http('POST', '/app/message/%s/read' % msg_id, app_token=token_b)
    check('用户B 无法把用户A 的消息标记已读', 0, body.get('data', {}).get('updated'))

    status, body = http('POST', '/app/message/%s/read' % msg_id, app_token=token_a)
    check('用户A 可以标记自己的消息已读', 1, body.get('data', {}).get('updated'))
    flag = scalar("select read_flag from kg_message where msg_id=%s" % msg_id)
    check('已读标记落库', '1', flag)

    status, body = http('GET', '/app/message/list?mode=staff', app_token=token_a)
    check('无员工授权时请求 staff 视角自动降级为 user', 'user', body.get('mode'))

    status, body = http('GET', '/app/message/list?mode=staff', app_token=token_b)
    check('有授权时可看员工消息', 'staff', body.get('mode'))
    staff_msgs = body.get('rows', [])
    check('员工收到了报名待办消息', True, any('报名' in r.get('title', '') for r in staff_msgs))

    # ============================================================ 10. 收藏
    section('10. 收藏')
    status, body = http('POST', '/app/job/favorite/%s?favorite=true' % job_id, app_token=token_a)
    check('收藏成功', 200, body.get('code'))
    status, body = http('POST', '/app/job/favorite/%s?favorite=true' % job_id, app_token=token_a)
    check('重复收藏不报错', 200, body.get('code'))
    cnt = scalar("select count(*) from kg_job_favorite where app_user_id=%s and job_id=%s" % (user_a_id, job_id))
    check('收藏只有一条记录', '1', cnt)
    status, body = http('GET', '/app/job/list?type=volunteer', app_token=token_a)
    row = [r for r in body.get('rows', []) if r['jobId'] == job_id][0]
    check('列表回显收藏状态', True, row.get('favorite'))
    status, body = http('POST', '/app/job/favorite/%s?favorite=false' % job_id, app_token=token_a)
    cnt = scalar("select count(*) from kg_job_favorite where app_user_id=%s and job_id=%s" % (user_a_id, job_id))
    check('取消收藏后记录删除', '0', cnt)

    # ============================================================ 11. 撤销授权
    section('11. 撤销员工授权立即生效')
    grant_id = scalar("select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s"
                      % (user_b_id, store_id))
    status, body = http('PUT', '/kangaroo/grant/revoke/%s' % grant_id, token=admin_token)
    check('后台撤销授权成功', 200, body.get('code'))
    status, body = http('GET', '/app/staff/applications?storeId=%s' % store_id, app_token=token_b)
    check('撤销后旧令牌立即无法访问员工接口', 403, body.get('code'))
    status, body = http('GET', '/app/auth/account', app_token=token_b)
    check('账号信息中员工授权已清空', [], body.get('data', {}).get('staffGrants'))

    # ============================================================ 12. 账号停用
    section('12. 账号停用')
    status, body = http('PUT', '/kangaroo/appuser/changeStatus', {'userId': user_c_id, 'status': '1'},
                        token=admin_token)
    check('后台停用账号成功', 200, body.get('code'))
    status, body = http('GET', '/app/application/mine', app_token=token_c)
    check('停用后原令牌立即失效', 401, body.get('code'))

    # ============================================================ 13. 门店停用的连锁效果
    section('13. 门店停用后的连锁效果')
    # 重新授权用户B，用来验证"门店停用 -> 员工权限失效"
    status, body = http('POST', '/kangaroo/grant',
                        {'appUserId': user_b_id, 'storeId': store_id, 'roleCode': 'manager'},
                        token=admin_token)
    check('重新授予员工成功', 200, body.get('code'))
    status, body = http('GET', '/app/auth/account', app_token=token_b)
    check('重新授权后小程序侧恢复员工身份', 1, len(body.get('data', {}).get('staffGrants', [])))

    status, body = http('PUT', '/kangaroo/store', {'storeId': store_id, 'status': '1'}, token=admin_token)
    check('后台停用门店成功', 200, body.get('code'))

    status, body = http('GET', '/app/job/%s' % job_id)
    check('停用门店的岗位详情对小程序不可见', 500, body.get('code'))
    status, body = http('GET', '/app/job/list?type=volunteer')
    ids = [r['jobId'] for r in body.get('rows', [])]
    check('停用门店的岗位不出现在小程序列表', False, job_id in ids)

    status, body = http('GET', '/app/auth/account', app_token=token_b)
    check('停用门店后该门店授权不再下发', [], body.get('data', {}).get('staffGrants'))
    status, body = http('GET', '/app/staff/applications?storeId=%s' % store_id, app_token=token_b)
    check('停用门店后员工无法再操作该门店数据', 403, body.get('code'))

    status, body = http('PUT', '/kangaroo/store', {'storeId': store_id, 'status': '0'}, token=admin_token)
    check('恢复门店', 200, body.get('code'))
    status, body = http('GET', '/app/job/list?type=volunteer')
    ids = [r['jobId'] for r in body.get('rows', [])]
    check('恢复后岗位重新可见', True, job_id in ids)

    # ============================================================ 14. 登出与手机号绑定
    section('14. 登出与手机号绑定')
    status, body = http('POST', '/app/auth/wx-login', {'code': 'e2e-user-e-' + run_id, 'nickname': '同学E'})
    token_e = body['token']
    user_e_id = int(body['account']['id'])
    status, body = http('POST', '/app/auth/bind-phone', {'phone': '123'}, app_token=token_e)
    check('非法手机号绑定返回输入校验错误', 422, body.get('code'))
    check('非法手机号不写入账号', '1', scalar("select phone is null or phone='' from kg_app_user where user_id=%d" % user_e_id))
    # This probe is reusable without a database reset. A fixed phone belongs to
    # the previous run's account and must legitimately be rejected by uniqueness.
    probe_phone = '136' + str(int(run_id, 16) % 100000000).zfill(8)
    assert scalar("select count(*) from kg_app_user where phone='%s'" % probe_phone) == '0', 'Phone fixture already occupied'
    status, body = http('POST', '/app/auth/bind-phone', {'phone': probe_phone}, app_token=token_e)
    check('绑定手机号成功', 200, body.get('code'))
    check('账号手机号已脱敏下发', probe_phone[:3] + '****' + probe_phone[-4:], body.get('data', {}).get('phone'))
    check('标记为已绑定', True, body.get('data', {}).get('phoneBound'))
    db_phone = scalar("select phone from kg_app_user where user_id=%s" % int(body['data']['id']))
    check('手机号已落库（明文仅存服务端）', probe_phone, db_phone)

    status, body = http('POST', '/app/auth/logout', app_token=token_e)
    check('登出成功', 200, body.get('code'))
    status, body = http('GET', '/app/auth/account', app_token=token_e)
    check('登出后令牌立即失效', 401, body.get('code'))

    # ============================================================ 16. 回归：发布前排查确认的缺陷
    #
    # 下面每一条都对应一个实际发生过的缺陷。修复前这些断言会失败，
    # 修复后必须持续通过；以后同类问题应该由这里先报出来，而不是靠人工点击发现。
    section('16. 回归用例（对应已修复的缺陷）')

    def fresh_user(tag):
        """新建一个干净的小程序账号并返回 (token, userId)"""
        st, bd = http('POST', '/app/auth/wx-login',
                      {'code': 'e2e-%s-%s' % (tag, run_id), 'nickname': tag})
        assert bd.get('code') == 200, bd
        return bd['token'], int(bd['account']['id'])

    def new_job(title, headcount=1, min_days=1):
        """新建并发布一个岗位，返回 jobId"""
        st, bd = http('POST', '/kangaroo/job',
                      {'storeId': store_id, 'jobType': 'volunteer', 'title': title,
                       'city': '大理', 'address': 'x', 'salaryType': 'daily',
                       'salaryMin': 100, 'salaryUnit': '元/天',
                       'startDate': time.strftime('%Y-%m-%d'),
                       'minDays': min_days, 'headcount': headcount},
                      token=admin_token)
        assert bd.get('code') == 200, bd
        jid = bd['data']
        http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin_token)
        return jid

    # 前面的用例已经把 section 7 建的时段占满了，回归段自己造新的。
    # 每次错开 1 小时：V4 给 kg_interview_slot 加了 (门店, 开始, 结束) 唯一索引，
    # 时间重复会被正确拒绝。
    slot_seq = {'n': 0}

    def new_slot():
        slot_seq['n'] += 1
        base = time.time() + 86400 * 7 + slot_seq['n'] * 3600
        s0 = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base))
        e0 = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base + 1200))
        st, bd = http('POST', '/kangaroo/slot',
                      {'storeId': store_id, 'startTime': s0, 'endTime': e0}, token=admin_token)
        assert bd.get('code') == 200, bd
        return bd

    def walk_to_store_confirmed(jid, tok, uid, name):
        """把一条报名推进到 store_confirmed（商家已确认录用），返回 appId"""
        arv = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400))
        st, bd = http('POST', '/app/application',
                      {'jobId': jid, 'name': name, 'age': 24, 'phone': '13500135000',
                       'arrival': arv, 'days': 30, 'idemKey': 'e2e-%s-%s-%s' % (run_id, jid, uid)},
                      app_token=tok)
        assert bd.get('code') == 200, bd
        aid = bd['data']['appId']
        http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin_token)
        new_slot()
        st, bd = http('GET', '/app/application/%s/slots' % aid, app_token=tok)
        slots_avail = bd.get('data') or []
        assert slots_avail, '需要可选面试时段'
        http('POST', '/app/application/%s/book-interview' % aid,
             {'slotId': slots_avail[0]['slotId']}, app_token=tok)
        from interview_clock_fixture import advance_interview_fixture
        advance_interview_fixture(aid)
        http('POST', '/app/application/%s/finish-interview' % aid, {}, app_token=tok)
        http('POST', '/app/application/%s/confirm' % aid, {'intent': 'accept'}, app_token=tok)
        st, bd = http('PUT', '/kangaroo/application/confirm/%s' % aid, token=admin_token)
        assert bd.get('code') == 200, bd
        return aid

    # ---------- R1 确认录用后终止必须归还名额（原为 blocker：名额被永久吞掉，岗位彻底作废）----------
    r_job = new_job('回归-名额归还-' + run_id, headcount=1)
    tok_r1, uid_r1 = fresh_user('r1')
    app_r1 = walk_to_store_confirmed(r_job, tok_r1, uid_r1, '回归一')
    check('录用后名额扣减为 1', '1', scalar("select hired_count from kg_job where job_id=%s" % r_job))

    status, body = http('PUT', '/kangaroo/application/terminate',
                        {'appId': app_r1, 'reason': '回归测试：试用不合适'}, token=admin_token)
    check('终止已录用的报名成功', 200, body.get('code'))
    check('R1 终止后名额被归还', '0', scalar("select hired_count from kg_job where job_id=%s" % r_job))
    check('R1 名额与实际录用人数一致', '0',
          scalar("select count(*) from kg_application where job_id=%s and del_flag='0' "
                 "and status in ('store_confirmed','contract_signed','onboarded')" % r_job))

    tok_r1b, uid_r1b = fresh_user('r1b')
    arrival2 = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400))
    status, body = http('POST', '/app/application',
                        {'jobId': r_job, 'name': '回归一乙', 'age': 25, 'phone': '13500135001',
                         'arrival': arrival2, 'days': 30, 'idemKey': 'e2e-%s-r1b' % run_id},
                        app_token=tok_r1b)
    check('R1 名额归还后岗位可以继续报名', 200, body.get('code'))

    # ---------- R2 面试后选"不合适"必须释放面试时段（原为 high：时段被永久锁死）----------
    r_job2 = new_job('回归-时段释放-' + run_id, headcount=2)
    tok_r2, uid_r2 = fresh_user('r2')
    arv = time.strftime('%Y-%m-%d', time.localtime(time.time() + 86400))
    status, body = http('POST', '/app/application',
                        {'jobId': r_job2, 'name': '回归二', 'age': 23, 'phone': '13500135002',
                         'arrival': arv, 'days': 30, 'idemKey': 'e2e-%s-r2' % run_id},
                        app_token=tok_r2)
    app_r2 = body['data']['appId']
    http('PUT', '/kangaroo/application/audit', {'appId': app_r2, 'pass': True}, token=admin_token)
    new_slot()
    status, body = http('GET', '/app/application/%s/slots' % app_r2, app_token=tok_r2)
    assert body.get('data'), '需要可选面试时段'
    slot_r2 = body['data'][0]['slotId']
    http('POST', '/app/application/%s/book-interview' % app_r2, {'slotId': slot_r2}, app_token=tok_r2)
    check('R2 预约后时段被占用', 'booked',
          scalar("select status from kg_interview_slot where slot_id=%s" % slot_r2))
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_r2)
    http('POST', '/app/application/%s/finish-interview' % app_r2, {}, app_token=tok_r2)

    status, body = http('POST', '/app/application/%s/confirm' % app_r2, {'intent': 'reject'}, app_token=tok_r2)
    check('用户面试后选"不合适"成功', 200, body.get('code'))
    check('R2 选择不合适后报名进入终止态', 'terminated',
          scalar("select status from kg_application where app_id=%s" % app_r2))
    check('R2 时段被释放回可约池', 'open',
          scalar("select status from kg_interview_slot where slot_id=%s" % slot_r2))
    check('R2 时段占用标记被清空', 'NULL',
          str(scalar("select ifnull(booked_app_id,'NULL') from kg_interview_slot where slot_id=%s" % slot_r2)))
    # "释放时段"与"作废面试记录"是两件事。这条报名是面试完（interview_done）之后
    # 用户才选的"不合适"，那场面试真的发生过：时段必须还回可约池（上面两条），
    # 但 kg_interview 要保持 done，标成 canceled 等于把历史改错。
    # 只有还停在 booked（约了没来）的面试记录才该被作废。
    check('R2 面试记录保持 done，不被改写成 canceled', 'done',
          scalar("select status from kg_interview where app_id=%s" % app_r2))

    tok_r2b, uid_r2b = fresh_user('r2b')
    status, body = http('POST', '/app/application',
                        {'jobId': r_job2, 'name': '回归二乙', 'age': 26, 'phone': '13500135003',
                         'arrival': arv, 'days': 30, 'idemKey': 'e2e-%s-r2b' % run_id},
                        app_token=tok_r2b)
    app_r2b = body['data']['appId']
    http('PUT', '/kangaroo/application/audit', {'appId': app_r2b, 'pass': True}, token=admin_token)
    status, body = http('POST', '/app/application/%s/book-interview' % app_r2b,
                        {'slotId': slot_r2}, app_token=tok_r2b)
    check('R2 释放出来的时段别人能约到', 200, body.get('code'))

    # ---------- R3 幂等键必须绑定用户与岗位（原为 high：跨账号泄漏 appNo + 假成功）----------
    tok_r3a, uid_r3a = fresh_user('r3a')
    tok_r3b, uid_r3b = fresh_user('r3b')
    r_job3 = new_job('回归-幂等作用域-' + run_id, headcount=5)
    shared_key = 'e2e-%s-shared-idem' % run_id
    status, body = http('POST', '/app/application',
                        {'jobId': r_job3, 'name': '回归三甲', 'age': 22, 'phone': '13500135004',
                         'arrival': arv, 'days': 30, 'idemKey': shared_key}, app_token=tok_r3a)
    check('R3 用户A 提交成功', 200, body.get('code'))
    app_r3a = body['data']['appId']

    status, body = http('POST', '/app/application',
                        {'jobId': r_job3, 'name': '回归三乙', 'age': 22, 'phone': '13500135005',
                         'arrival': arv, 'days': 30, 'idemKey': shared_key}, app_token=tok_r3b)
    check('R3 用户B 复用同一幂等键被拒绝', 500, body.get('code'))
    # 这里必须看**整个响应体**，不能只看 data：
    #   1) 业务异常走的是 msg，data 通常根本不存在，只查 data 等于恒真断言；
    #   2) R3 防的是"把别人的报名单号回显给当前用户"，单号是 app_no（KA…），
    #      只比数字主键 app_id 抓不到真正的泄漏形态。
    leak_no = scalar('select app_no from kg_application where app_id=%s' % app_r3a)
    whole = json.dumps(body, ensure_ascii=False)
    leaked = [x for x in (str(app_r3a), str(leak_no)) if x and x in whole]
    check('R3 拒绝时整个响应体都不含他人的 appId 与报名单号', [], leaked)
    check('R3 用户B 名下确实没有报名', 0,
          len(http('GET', '/app/application/mine', app_token=tok_r3b)[1].get('data') or []))

    r_job3b = new_job('回归-幂等换岗位-' + run_id, headcount=5)
    status, body = http('POST', '/app/application',
                        {'jobId': r_job3b, 'name': '回归三甲', 'age': 22, 'phone': '13500135004',
                         'arrival': arv, 'days': 30, 'idemKey': shared_key}, app_token=tok_r3a)
    check('R3 同一用户换岗位复用旧幂等键被拒绝', 500, body.get('code'))

    # ---------- R4 并发唯一性（原为 high：select 判重在并发下失效）----------
    tok_r4, uid_r4 = fresh_user('r4')
    r_job4 = new_job('回归-并发报名-' + run_id, headcount=9)

    def submit_once(i):
        return http('POST', '/app/application',
                    {'jobId': r_job4, 'name': '回归四', 'age': 24, 'phone': '13500135006',
                     'arrival': arv, 'days': 30, 'idemKey': 'e2e-%s-r4-%d' % (run_id, i)},
                    app_token=tok_r4)

    with ThreadPoolExecutor(max_workers=8) as pool:
        r4_results = list(pool.map(submit_once, range(8)))
    r4_ok = [r for r in r4_results if r[1].get('code') == 200]
    check('R4 同用户同岗位 8 并发提交只有 1 个成功', 1, len(r4_ok))
    check('R4 数据库里只有 1 条活跃报名', '1',
          scalar("select count(*) from kg_application where job_id=%s and app_user_id=%s "
                 "and del_flag='0' and status not in ('review_rejected','withdrawn','terminated')"
                 % (r_job4, uid_r4)))

    def make_store(i):
        return http('POST', '/kangaroo/store',
                    {'storeName': '回归并发门店-' + run_id, 'city': '并发城', 'address': 'x'},
                    token=admin_token)

    with ThreadPoolExecutor(max_workers=6) as pool:
        r4s_results = list(pool.map(make_store, range(6)))
    r4s_ok = [r for r in r4s_results if r[1].get('code') == 200]
    check('R4 同城同名门店 6 并发只有 1 个成功', 1, len(r4s_ok))
    check('R4 数据库里只有 1 行同名门店', '1',
          scalar("select count(*) from kg_store where store_name='回归并发门店-%s' and del_flag='0'" % run_id))

    # ---------- R5 时区：Java 写入与 MySQL 写入必须落在同一个时钟（原为 high：相差 16 小时）----------
    skew = scalar("select abs(timestampdiff(minute, create_time, submit_time)) "
                  "from kg_application where app_id=%s" % app_r3a)
    check('R5 报名的业务时间与创建时间落在同一时钟（相差分钟数）', True,
          skew is not None and int(skew) <= 1,
          ok=(skew is not None and int(skew) <= 1))
    # 只看本次运行新产生的数据：历史行是在时区修复之前写入的，
    # 它们的偏移属于存量脏数据，由 cleanup + 重新灌种解决，不该让回归用例为它们失败。
    #
    # 圈定范围必须用 app_no 里的 run_id，**不能用 create_time 做时间窗**：
    # create_time 正是这条检查要审计的列。如果缺陷复发的方向是 create_time 落后，
    # 本次新写的行就进不了时间窗，count 返回 0，这条检查反而自动变绿。
    # 本次运行创建的岗位，标题里带 run_id；报名通过 job_id 挂在这些岗位上。
    # 用这条关联圈定"本次运行产生的行"，而不是用 app_no（服务端流水号，不含 run_id），
    # 也不用 create_time（那正是要审计的列）。
    scope = ("job_id in (select job_id from kg_job where title like %s)"
             % repr('%' + run_id + '%'))
    rows_this_run = int(scalar('select count(*) from kg_application where ' + scope) or 0)
    check('R5 本次运行确实产生了可供审计的报名行', True, rows_this_run > 0,
          ok=rows_this_run > 0)
    recent_skew = scalar(
        'select count(*) from kg_application where ' + scope +
        '  and abs(timestampdiff(minute, create_time, submit_time)) > 1')
    check('R5 本次运行产生的报名没有一条出现时间偏移（共 %d 行）' % rows_this_run,
          '0', str(recent_skew))

    # ---------- R6 收藏列表与列表/详情口径一致（原为 low：收藏页出现幽灵卡片）----------
    tok_r6, uid_r6 = fresh_user('r6')
    r_job6 = new_job('回归-收藏口径-' + run_id, headcount=3)
    http('POST', '/app/job/favorite/%s?favorite=true' % r_job6, app_token=tok_r6)
    status, body = http('GET', '/app/job/favorites', app_token=tok_r6)
    check('R6 收藏后能在收藏列表看到', True,
          any(r['jobId'] == r_job6 for r in (body.get('data') or [])))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': r_job6, 'status': 'paused'}, token=admin_token)
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': r_job6, 'status': 'closed'}, token=admin_token)
    status, body = http('GET', '/app/job/favorites', app_token=tok_r6)
    check('R6 岗位关闭后不再出现在收藏列表', False,
          any(r['jobId'] == r_job6 for r in (body.get('data') or [])))

    # ---------- R7 后台岗位编辑：统计字段写不进去，合法编辑也不被并发误伤 ----------
    # 这里要证明两件互相独立的事，必须分开断言：
    #   a) 真正的不变量：hired_count / applied_count 无论请求里带什么都不会被改；
    #   b) 一次完全合法的编辑不会因为"表单摊开期间有人报名"而被拒。
    #
    # 旧写法是 PUT {'jobId': x, 'hiredCount': 99} 然后断言 code==500。
    # 去掉"试图改计数就拒绝"的逻辑之后它依然返回 500 —— 但原因变成了"岗位标题不能为空"
    #（半截请求根本过不了必填校验）。断言还绿着，证明的却不再是"篡改被拒"。
    # 所以改成走完整对象、直接回数据库看那两列有没有变。
    status, body = http('GET', '/kangaroo/job/%s' % r_job2, token=admin_token)
    full = body.get('data') or {}
    full['address'] = '回归编辑-' + run_id
    status, body = http('PUT', '/kangaroo/job', full, token=admin_token)
    check('R7 整对象回填编辑岗位仍然成功', 200, body.get('code'))

    hired_before = scalar('select hired_count from kg_job where job_id=%s' % r_job2)
    applied_before = scalar('select applied_count from kg_job where job_id=%s' % r_job2)
    status, body = http('GET', '/kangaroo/job/%s' % r_job2, token=admin_token)
    tamper = body.get('data') or {}
    tamper['hiredCount'] = 99
    tamper['appliedCount'] = 99
    tamper['address'] = '回归篡改-' + run_id
    status, body = http('PUT', '/kangaroo/job', tamper, token=admin_token)
    check('R7 整对象里带着被改过的统计字段，编辑本身仍然成功', 200, body.get('code'))
    check('R7 hired_count 没有被写进库', hired_before,
          scalar('select hired_count from kg_job where job_id=%s' % r_job2))
    check('R7 applied_count 没有被写进库', applied_before,
          scalar('select applied_count from kg_job where job_id=%s' % r_job2))
    # 没有这条，上面三条也可能因为"整个 PUT 其实没生效"而全绿
    check('R7 这次编辑本身确实落库了', '回归篡改-' + run_id,
          scalar('select address from kg_job where job_id=%s' % r_job2))

    # b) 模拟"运营摊开编辑表单期间，有用户报名了这个岗位"。
    #    报名会执行 increaseAppliedCount，顺带刷新 kg_job.update_time；
    #    而 update_time 曾被当成乐观锁的版本号，于是这次完全合法的编辑会被判成冲突。
    status, body = http('GET', '/kangaroo/job/%s' % r_job2, token=admin_token)
    stale_form = body.get('data') or {}
    stale_form['address'] = '回归并发编辑-' + run_id
    # 前提自检：表单里确实带回了 updateTime，否则"版本号过期"这个场景根本不成立，
    # 下面那条断言就会在没复现出问题的情况下自动变绿。
    check('R7 前置：GET 回来的表单确实带着 updateTime', True,
          bool(stale_form.get('updateTime')), ok=bool(stale_form.get('updateTime')))

    ut_before = scalar('select update_time from kg_job where job_id=%s' % r_job2)
    # 表单里那份 updateTime 必须就是库里当前这份，否则"报名把版本推走了"这件事无从谈起
    check('R7 前置：表单里的 updateTime 与库里当前值一致', ut_before, stale_form.get('updateTime'))
    # kg_job.update_time 是 datetime，精度只到秒。不跨过秒边界的话，报名之后 update_time
    # 可能和表单里那份一模一样，版本比对"恰好相等"，这条用例就会在没有复现出误拒的情况下通过。
    time.sleep(1.1)
    tok_r7, uid_r7 = fresh_user('r7')
    status, body = http('POST', '/app/application',
                        {'jobId': r_job2, 'name': '回归七', 'age': 27, 'phone': '13500135007',
                         'arrival': arv, 'days': 30, 'idemKey': 'e2e-%s-r7' % run_id},
                        app_token=tok_r7)
    check('R7 前置：编辑期间确实有人成功报名了这个岗位', 200, body.get('code'))
    ut_after = scalar('select update_time from kg_job where job_id=%s' % r_job2)
    check('R7 前置：这次报名确实把 update_time 推进了（%s -> %s）' % (ut_before, ut_after),
          True, ut_before != ut_after, ok=(ut_before != ut_after))

    status, body = http('PUT', '/kangaroo/job', stale_form, token=admin_token)
    check('R7 编辑期间有人报名，先前取回的表单仍能保存成功', 200, body.get('code'))
    check('R7 并发场景下这次编辑也确实落库了', '回归并发编辑-' + run_id,
          scalar('select address from kg_job where job_id=%s' % r_job2))
    check('R7 并发编辑没有波及 applied_count（仍是报名后的真实值）',
          scalar("select count(*) from kg_application where job_id=%s and del_flag='0'" % r_job2),
          scalar('select applied_count from kg_job where job_id=%s' % r_job2))

    # ---------- R9 部分唯一索引不能挡住正常业务路径（防止 V4 修复引入回归）----------
    # V4 给 kg_application / kg_store 加了"部分唯一索引"（终态与软删行的生成列为 NULL）。
    # 这几条确认它挡住的只是并发重复，没有连正常的"撤回后重投""驳回后重投""删店后重建"一起挡掉。
    tok_r9, uid_r9 = fresh_user('r9')
    r_job9 = new_job('回归-唯一索引路径-' + run_id, headcount=5)

    def submit_r9(key):
        return http('POST', '/app/application',
                    {'jobId': r_job9, 'name': '回归九', 'age': 25, 'phone': '13700137009',
                     'arrival': arv, 'days': 10, 'idemKey': 'e2e-%s-r9-%s' % (run_id, key)},
                    app_token=tok_r9)

    status, body = submit_r9('a')
    check('R9 首次报名成功', 200, body.get('code'))
    app_r9 = body['data']['appId']
    http('POST', '/app/application/%s/withdraw' % app_r9, app_token=tok_r9)
    check('R9 撤回后唯一键置空（不再参与唯一性）', 'NULL',
          str(scalar("select ifnull(active_uk,'NULL') from kg_application where app_id=%s" % app_r9)))
    status, body = submit_r9('b')
    check('R9 撤回后可以重新报名同一岗位', 200, body.get('code'))
    app_r9b = body['data']['appId']

    http('PUT', '/kangaroo/application/audit',
         {'appId': app_r9b, 'pass': False, 'reason': '回归：唯一索引路径'}, token=admin_token)
    status, body = submit_r9('c')
    check('R9 被驳回后可以重新报名同一岗位', 200, body.get('code'))

    status, body = submit_r9('d')
    check('R9 仍有活跃报名时重复报名被拒绝', 500, body.get('code'))

    status, body = http('POST', '/kangaroo/store',
                        {'storeName': '回归软删门店-' + run_id, 'city': '回归城', 'address': 'x'},
                        token=admin_token)
    tmp_store = body['data']
    status, body = http('DELETE', '/kangaroo/store/%s' % tmp_store, token=admin_token)
    check('R9 无岗位无报名的门店可以删除', 200, body.get('code'))
    status, body = http('POST', '/kangaroo/store',
                        {'storeName': '回归软删门店-' + run_id, 'city': '回归城', 'address': 'x'},
                        token=admin_token)
    check('R9 门店软删后可以用同名同城重建', 200, body.get('code'))

    # ---------- R10 权限标识与菜单双向一致（漏配菜单会让非超管角色永远授不到该权限）----------
    # 代码里加了 @PreAuthorize 却忘了往 sys_menu 补按钮，是很容易反复出现的疏漏：
    # admin 有 *:*:* 所以本地怎么点都正常，等运营建了受限角色才会暴露。
    import re as _re
    perms_in_code = set()
    ctrl_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                            'ruoyi-kangaroo', 'src', 'main', 'java', 'com', 'ruoyi',
                            'kangaroo', 'controller')
    for root_dir, _dirs, files in os.walk(ctrl_dir):
        for fn in files:
            if not fn.endswith('.java'):
                continue
            with open(os.path.join(root_dir, fn), encoding='utf-8') as f:
                for m in _re.finditer(r"hasPermi\('([^']+)'\)", f.read()):
                    perms_in_code.add(m.group(1))
    # 通用增删改查页（ExtEntity）的权限是运行时拼出来的，字面量扫描看不到；按同一套登记从源码读出来
    import ext_perms as _ext
    perms_in_code |= _ext.backend_perms()

    rows = sql("select perms from sys_menu where perms like 'kangaroo:%' and perms <> ''")
    perms_in_menu = set(r[0] for r in rows)

    missing_in_menu = sorted(perms_in_code - perms_in_menu)
    check('R10 后端每个权限标识都有对应的菜单记录', [], missing_in_menu)
    # 这里不再豁免任何后缀。原先排除 ':list' 没有写明理由，
    # 等于让一整类权限标识不被检查 —— 正是"白名单掩盖问题"的写法。
    dead_perms = sorted(perms_in_menu - perms_in_code)
    check('R10 菜单里没有代码未使用的死权限', [], dead_perms)
    check('R10 权限标识数量合理', True, len(perms_in_code) >= 30,
          ok=len(perms_in_code) >= 30)

    # ---------- R8 重算名额接口可用（存量漂移的运维兜底）----------
    # 必须先人为制造漂移再重算：在一个计数本来就对的岗位上调一次，
    # 哪怕 recalcCounters 被写成 return success() 的空壳，断言也会全绿 ——
    # 而这个接口的全部价值就是修漂移，测不出"它真的算了"等于没测。
    sql_exec('update kg_job set hired_count = 99, applied_count = 99 where job_id = %s' % r_job)
    check('R8 前置：已人为把计数改错', '99',
          scalar('select hired_count from kg_job where job_id=%s' % r_job))
    status, body = http('PUT', '/kangaroo/job/recalc?jobId=%s' % r_job, token=admin_token)
    check('R8 重算名额接口可用', 200, body.get('code'))
    check('R8 重算把 hired_count 修回真实值', '0',
          scalar('select hired_count from kg_job where job_id=%s' % r_job))
    check('R8 重算把 applied_count 修回真实值',
          scalar("select count(*) from kg_application where job_id=%s and del_flag='0'" % r_job),
          scalar('select applied_count from kg_job where job_id=%s' % r_job))

    # ============================================================ 15. 删除保护
    section('15. 删除保护')
    status, body = http('DELETE', '/kangaroo/store/%s' % store_id, token=admin_token)
    check('有岗位的门店不能删除', 500, body.get('code'))
    status, body = http('DELETE', '/kangaroo/job/%s' % job_id, token=admin_token)
    check('有报名的岗位不能删除', 500, body.get('code'))

    # ============================================================ 汇总
    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 run_id=%s；本次作用域将在 finally 清理。' % run_id)
    return 1 if FAILED else 0


def main():
    # Verify the service and SQL target before any test business writes. Cleanup
    # discovers only this unpredictable run suffix, including unknown outcomes.
    from pathlib import Path
    import re
    sys.path.insert(0,str(Path(__file__).resolve().parents[2]))
    import verify
    verify.assert_isolated_backend()
    from isolated_stay_fixture import IsolatedStayFixture
    run_id = uuid.uuid4().hex[:8]
    try:
        return _run(run_id)
    finally:
        assert re.fullmatch('[0-9a-f]{8}',run_id)
        fixture=IsolatedStayFixture(run_id)
        fixture.stores=[int(r[0]) for r in sql("select store_id from kg_store where store_name like '%%-%s'" % run_id)]
        fixture.users={int(r[0]) for r in sql("select user_id from kg_app_user where openid like 'mock_e2e-%%-%s'" % run_id)}
        fixture.cleanup()


if __name__ == '__main__':
    sys.exit(main())
