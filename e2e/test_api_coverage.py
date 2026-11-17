# -*- coding: utf-8 -*-
"""补测从来没有被任何自动化测试打到过的 21 个接口。

## 这些接口是怎么找出来的

把 controller 里的 59 个接口路径，与 e2e 里实际发起过的请求路径逐条比对，
剩下的就是这 21 个。它们不是"不重要"，只是一直没人测：
未读数（小程序 tabbar 红点就靠它）、员工端报名详情（完整手机号只在这里下发）、
后台的各种列表与导出，都在其中。

## 断言写法

一律断言**业务结果**，不只断言 200：
计数要和数据库对得上、导出要解开 xlsx 看枚举是不是中文、
标记已读之后要回库确认 read_flag 真的变了、越权要真的被拒。

用法：
    python e2e/test_api_coverage.py        # 需要后端已在 8080 运行
"""
import io
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import (  # noqa: E402
    sign_contract, BASE, http, sql, scalar, sql_exec, check, section, admin_login, PASSED, FAILED)

RUN = uuid.uuid4().hex[:8]


def norm(v):
    return None if v is None or v == 'NULL' else v


def post_binary(path, token):
    """RuoYi 的导出是 form 表单 POST + 二进制响应。"""
    req = urllib.request.Request(
        BASE + path, data=b'',
        headers={'Content-Type': 'application/x-www-form-urlencoded',
                 'Authorization': 'Bearer ' + token},
        method='POST')
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.read()


def xlsx_text(blob):
    try:
        zf = zipfile.ZipFile(io.BytesIO(blob))
        return b''.join(zf.read(n) for n in zf.namelist()
                        if n.endswith('.xml')).decode('utf-8', 'replace')
    except Exception:                                      # noqa: BLE001
        return ''


def app_login(tag):
    """联调模式下 code 直接当 openid，所以用不同的 code 就是不同的账号。"""
    status, body = http('POST', '/app/auth/wx-login', {'code': 'cov-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    return body['token'], body['account']['id']


def job_city_terms(name):
    """选中城市的全部写法：它自己 + 字典里只属于它的全称 / 搜索别名（含中文、两个字以上）。
    和后端 KgCityService.jobCityTerms 同一规则：区县不算，和字典名重复的写法、被两个地方共用的别名不算。"""
    rows = sql("select name, coalesce(full_name, ''), coalesce(level, ''), coalesce(search_aliases, '') from kg_city")
    names = set(r[0] for r in rows)
    owner, ambiguous = {}, set()
    for n, full, level, aliases in rows:
        if level == 'district':
            continue
        for t in [full] + (aliases or '').split(','):
            a = (t or '').strip()
            if not a or a in names:
                continue
            if owner.setdefault(a, n) != n:
                ambiguous.add(a)
    out = [name]
    for a in sorted(owner):
        if owner[a] == name and a not in ambiguous and len(a) >= 2 and any(ord(ch) > 0x7f for ch in a) and a not in out:
            out.append(a)
    return out


def job_city_sql(terms):
    """岗位城市条件（KgJobCity.sql 的 SQL 字面量版）：两个字以上「以它结尾」，一个字只认相等；% _ \\ 按字面"""
    def lit(s):
        return "'" + s.replace('\\', '\\\\').replace("'", "''") + "'"
    parts = []
    for t in terms:
        if len(t) >= 2:
            esc = t.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')
            parts.append('trim(j.city) like ' + lit('%' + esc))
        else:
            parts.append('trim(j.city) = ' + lit(t))
    return '(' + ' or '.join(parts) + ')'


def job_city_match(raw, terms):
    r = (raw or '').strip()
    return bool(r) and any(r.endswith(t) if len(t) >= 2 else r == t for t in terms)


# 2026-10-16 第二轮改版：岗位关键词按规则拆词（后端 KgJobKeyword）。一个普通词出现在标题 / 标签 / 地址 / 介绍任一处就算，
# 或者是线下岗位、岗位城市等于它或以它结尾（和城市筛选同一口径 KgJobCity；线上任务全国可接，不按城市认）。
# 这些写法后端会特殊处理（连接词、「的」、海边 / 一二线 / 小众安静 / 风景这类感觉词、开头的去 / 找 / 想），不拿来验「普通词」
KEYWORD_SPECIAL = ('的', '城市', '地方', '去处', '附近', '想去', '想要', '我想', '喜欢', '一点', '比较', '有点', '感觉', '氛围', '风格',
                   '等等', '比如', '之类', '那种', '一些', '一个', '特别', '非常', '一下', '看看', '工作', '岗位', '机会',
                   '海边', '海景', '看海', '靠海', '沙滩', '海岛', '海滨', '赶海', '一二线', '大城市', '新一线', '一线城市', '二线城市',
                   '小众', '安静', '清静', '僻静', '人少', '宁静', '慢生活', '自然风光', '风景', '山水')


# 后端只剩这些单字的词不要（KgJobKeyword.SINGLE_FILLERS）
KEYWORD_FILLERS = '美好很挺超个些点和与或等也都就去找想要我吧呢啊呀'


def plain_keyword(title):
    """标题开头 2–4 个中文 / 字母 / 数字，后端会当成一个普通词的；没有就返回 None。
    城市字典里的名字 / 全称 / 搜索别名（含区县）也不要：后端会把它归一成城市、带上别名去比岗位城市，
    keyword_sql 只按它自己比城市"""
    import re
    m = re.match(r'[\u4e00-\u9fa5A-Za-z0-9]{2,4}', title or '')
    kw = m.group(0) if m else ''
    if len(kw) < 2 or kw[0] in '去找想' or any(w in kw for w in KEYWORD_SPECIAL) or all(ch in KEYWORD_FILLERS for ch in kw):
        return None
    for name, full, aliases in sql("select name, coalesce(full_name, ''), coalesce(search_aliases, '') from kg_city"):
        if kw in [name, full] + [a.strip() for a in (aliases or '').split(',')]:
            return None
    return kw


def keyword_sql(kw):
    """一个普通词的 SQL 字面量条件（KgJobMapper.selectAppJobList 的 keywordTerms 同一口径）：
    标题 / 标签 / 地址 / 介绍任一处包含它（% _ \\ 按字面），或者是线下岗位、岗位城市等于它或以它结尾（job_city_sql）。
    之前还按 j.city like %词% 比：线上任务会按发布门店的城市被搜出来，「大连」也会带出「五大连池」"""
    esc = kw.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_').replace("'", "''")
    like = "'%" + esc + "%'"
    text = ' or '.join('j.%s like %s' % (c, like) for c in ('title', 'tags', 'address', 'content'))
    return '(' + text + " or (j.job_type <> 'online' and " + job_city_sql([kw]) + '))'


def main():
    admin = admin_login()

    # ================================================================ 准备
    section('X0 准备数据')
    store_id = scalar("select store_id from kg_store where del_flag='0' and status='0' "
                      "order by store_id limit 1")
    assert store_id, '库里没有可用门店'
    city = scalar('select city from kg_store where store_id=%s' % store_id)

    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-接口覆盖-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'daily', 'salaryMin': 100, 'salaryMax': 100,
        'headcount': 2, 'status': 'draft'}, token=admin)
    check('X0 建岗位', 200, body.get('code'))
    job_id = scalar("select job_id from kg_job where title=%s order by job_id desc limit 1"
                    % repr('回归-接口覆盖-' + RUN))
    http('PUT', '/kangaroo/job/changeStatus',
         {'jobId': int(job_id), 'status': 'published'}, token=admin)

    user_tok, user_id = app_login('u1')
    staff_tok, staff_id = app_login('s1')
    status, body = http('POST', '/kangaroo/grant',
                        {'appUserId': int(staff_id), 'storeId': int(store_id),
                         'roleCode': 'manager'}, token=admin)
    check('X0 授予员工', 200, body.get('code'))
    grant_id = scalar('select grant_id from kg_staff_grant where app_user_id=%s '
                      'and store_id=%s order by grant_id desc limit 1' % (staff_id, store_id))

    arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 7 * 86400))
    status, body = http('POST', '/app/application',
                        {'jobId': int(job_id), 'name': '覆盖测试员', 'age': 24,
                         'phone': '13700137001', 'arrival': arrival, 'days': 10,
                         'idemKey': 'cov-' + RUN}, app_token=user_tok)
    check('X0 提交报名', 200, body.get('code'))
    app_id = scalar('select app_id from kg_application where app_user_id=%s '
                    'and job_id=%s order by app_id desc limit 1' % (user_id, job_id))
    assert app_id, '报名没落库'

    # ================================================================ 小程序端
    section('X1 GET /app/auth/ping')
    status, body = http('GET', '/app/auth/ping')
    check('X1 探活不需要登录即可访问', 200, body.get('code'))
    data = body.get('data') or {}
    check('X1 返回 ok 标记', True, data.get('ok'))
    check('X1 如实上报当前是否为本地联调模式', True, 'wxMock' in data, ok='wxMock' in data)
    check('X1 上报了构建指纹', True, bool(data.get('build')), ok=bool(data.get('build')))

    section('X2 GET /app/message/unread —— tabbar 红点的唯一数据源')
    db_unread = scalar("select count(*) from kg_message where recipient_id=%s "
                       "and mode='user' and read_flag='0' and del_flag='0'" % user_id)
    status, body = http('GET', '/app/message/unread?mode=user', app_token=user_tok)
    check('X2 接口可访问', 200, body.get('code'))
    check('X2 未读数与数据库一致', str(db_unread), str((body.get('data') or {}).get('unread')))
    status, body = http('GET', '/app/message/unread?mode=user')
    check('X2 未登录访问被拒', 401, body.get('code'))

    section('X3 GET /app/message/summary')
    status, body = http('GET', '/app/message/summary?mode=user', app_token=user_tok)
    check('X3 接口可访问', 200, body.get('code'))
    cats = body.get('categories')
    check('X3 返回分类统计', True, isinstance(cats, list), ok=isinstance(cats, list))
    total_in_cats = sum(int(c.get('total') or 0) for c in (cats or []))
    db_total = scalar("select count(*) from kg_message where recipient_id=%s "
                      "and mode='user' and del_flag='0'" % user_id)
    check('X3 分类总数与数据库一致', str(db_total), str(total_in_cats))
    check('X3 汇总里的未读数与数据库一致', str(db_unread), str(body.get('unread')))

    section('X4 POST /app/message/read-all')
    status, body = http('POST', '/app/message/read-all?mode=user', app_token=user_tok)
    check('X4 接口可访问', 200, body.get('code'))
    check('X4 返回的更新条数与之前的未读数一致', str(db_unread),
          str((body.get('data') or {}).get('updated')))
    left = scalar("select count(*) from kg_message where recipient_id=%s "
                  "and mode='user' and read_flag='0' and del_flag='0'" % user_id)
    check('X4 数据库里该用户已无未读', '0', left)
    status, body = http('GET', '/app/message/unread?mode=user', app_token=user_tok)
    check('X4 未读数接口也归零', '0', str((body.get('data') or {}).get('unread')))
    # 不能把别人的消息也标掉
    other_unread = scalar("select count(*) from kg_message where recipient_id<>%s "
                          "and read_flag='0' and del_flag='0'" % user_id)
    check('X4 其他账号的未读消息没有被连带标记', True, int(other_unread) > 0,
          ok=int(other_unread) > 0)

    section('X5 GET /app/staff/stores')
    status, body = http('GET', '/app/staff/stores', app_token=staff_tok)
    check('X5 员工可访问', 200, body.get('code'))
    rows = body.get('data') or []
    check('X5 返回该员工的授权门店', True,
          any(str(g.get('storeId')) == str(store_id) for g in rows),
          ok=any(str(g.get('storeId')) == str(store_id) for g in rows))
    status, body = http('GET', '/app/staff/stores', app_token=user_tok)
    check('X5 无员工授权的账号被拒', 403, body.get('code'))

    section('X6 GET /app/staff/applications/{appId} —— 完整手机号只在这里下发')
    status, body = http('GET', '/app/staff/applications/%s' % app_id, app_token=staff_tok)
    check('X6 员工可访问本店报名详情', 200, body.get('code'))
    d = body.get('data') or {}
    check('X6 下发完整手机号（员工需要联系应聘者）', '13700137001', d.get('phone'))
    check('X6 下发真实姓名', '覆盖测试员', d.get('realName'))
    status, body = http('GET', '/app/staff/applications/%s' % app_id, app_token=user_tok)
    check('X6 非员工账号被拒', 403, body.get('code'))
    status, body = http('GET', '/app/staff/applications/%s' % app_id)
    check('X6 未登录被拒', 401, body.get('code'))

    # ================================================================ 管理后台
    section('X7 GET /kangaroo/job/list —— 后台能看到草稿，小程序看不到')
    q = urllib.parse.quote('回归-接口覆盖-' + RUN)
    status, body = http('GET', '/kangaroo/job/list?title=%s' % q, token=admin)
    check('X7 后台岗位列表可访问', 200, body.get('code'))
    check('X7 能查到刚建的岗位', 1, body.get('total'))
    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-草稿可见性-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'none', 'headcount': 1, 'status': 'draft'}, token=admin)
    check('X7 再建一个草稿岗位', 200, body.get('code'))
    q2 = urllib.parse.quote('回归-草稿可见性-' + RUN)
    status, body = http('GET', '/kangaroo/job/list?title=%s' % q2, token=admin)
    check('X7 后台能看到草稿岗位', 1, body.get('total'))
    status, body = http('GET', '/app/job/list?pageSize=100')
    titles = [r.get('title') for r in (body.get('rows') or [])]
    check('X7 小程序看不到草稿岗位', False, ('回归-草稿可见性-' + RUN) in titles)

    section('X8 GET /kangaroo/appuser/list 与 /{userId}')
    db_users = scalar('select count(*) from kg_app_user')
    status, body = http('GET', '/kangaroo/appuser/list?pageSize=5', token=admin)
    check('X8 账号列表可访问', 200, body.get('code'))
    check('X8 总数与数据库一致', str(db_users), str(body.get('total')))
    # openid 的下发口径是**有意设计**的，见 KgAppUserController 的注释：
    # 列表不下发（避免批量导出微信身份），详情下发（客服要核对身份）。
    # 我最初把断言写成"详情也不下发"，跑出来是红的 —— 错的是断言不是实现，已按真实约定改写。
    # 要看**值**而不是键名：列表 SQL 根本没查 openid/unionid，
    # 但 Jackson 会把 bean 的所有属性都序列化出来，键在、值是 null。
    # 最初按键名判断，结果把"字段名出现了"误判成"微信身份泄露了"。
    rows = body.get('rows') or []
    leaked = [r.get('userId') for r in rows if r.get('openid') or r.get('unionid')]
    check('X8 列表里没有任何一行带出 openid/unionid 的值', [], leaked)
    status, body = http('GET', '/kangaroo/appuser/%s' % user_id, token=admin)
    check('X8 账号详情可访问', 200, body.get('code'))
    check('X8 详情是这个账号', str(user_id), str((body.get('data') or {}).get('userId')))
    check('X8 详情下发 openid 供客服核对身份（设计如此）', True,
          bool((body.get('data') or {}).get('openid')),
          ok=bool((body.get('data') or {}).get('openid')))

    section('X9 GET /kangaroo/grant/list、/{grantId} 与 PUT /kangaroo/grant')
    status, body = http('GET', '/kangaroo/grant/list?storeId=%s' % store_id, token=admin)
    check('X9 授权列表可访问', 200, body.get('code'))
    check('X9 列表里有刚授予的那条', True,
          any(str(r.get('grantId')) == str(grant_id) for r in (body.get('rows') or [])),
          ok=any(str(r.get('grantId')) == str(grant_id) for r in (body.get('rows') or [])))
    status, body = http('GET', '/kangaroo/grant/%s' % grant_id, token=admin)
    check('X9 授权详情可访问', 200, body.get('code'))
    check('X9 详情里的角色是 manager', 'manager', (body.get('data') or {}).get('roleCode'))
    status, body = http('PUT', '/kangaroo/grant',
                        {'grantId': int(grant_id), 'roleCode': 'keeper'}, token=admin)
    check('X9 改角色成功', 200, body.get('code'))
    check('X9 角色变更已落库', 'keeper',
          scalar('select role_code from kg_staff_grant where grant_id=%s' % grant_id))
    status, body = http('GET', '/app/auth/account', app_token=staff_tok)
    grants = (body.get('data') or {}).get('staffGrants') or []
    check('X9 小程序侧立即看到新角色', True,
          any(g.get('roleCode') == 'keeper' for g in grants),
          ok=any(g.get('roleCode') == 'keeper' for g in grants))
    status, body = http('PUT', '/kangaroo/grant',
                        {'grantId': int(grant_id), 'roleCode': 'not_a_role'}, token=admin)
    check('X9 非法角色被拒', 500, body.get('code'))

    section('X10 GET /kangaroo/slot/list')
    db_slots = scalar("select count(*) from kg_interview_slot where store_id=%s "
                      "and del_flag='0'" % store_id)
    status, body = http('GET', '/kangaroo/slot/list?storeId=%s&pageSize=1' % store_id,
                        token=admin)
    check('X10 时段列表可访问', 200, body.get('code'))
    check('X10 总数与数据库一致', str(db_slots), str(body.get('total')))

    section('X11 GET /kangaroo/message/list 与 /{msgId}')
    db_msgs = scalar("select count(*) from kg_message where del_flag='0'")
    status, body = http('GET', '/kangaroo/message/list?pageSize=1', token=admin)
    check('X11 站内消息列表可访问', 200, body.get('code'))
    check('X11 总数与数据库一致', str(db_msgs), str(body.get('total')))
    msg_id = scalar('select msg_id from kg_message where recipient_id=%s '
                    'order by msg_id desc limit 1' % user_id)
    status, body = http('GET', '/kangaroo/message/%s' % msg_id, token=admin)
    check('X11 消息详情可访问', 200, body.get('code'))
    check('X11 详情是这条消息', str(msg_id), str((body.get('data') or {}).get('msgId')))

    section('X12 GET /kangaroo/application/{appId} —— 含流转时间线')
    status, body = http('GET', '/kangaroo/application/%s' % app_id, token=admin)
    check('X12 报名详情可访问', 200, body.get('code'))
    d = body.get('data') or {}
    # 后台详情把报名本体放在 data.application 下，另外平铺 statusLabel / logs / interview
    check('X12 详情是这条报名', str(app_id), str((d.get('application') or {}).get('appId')))
    check('X12 带上了中文状态名', True, bool(d.get('statusLabel')), ok=bool(d.get('statusLabel')))
    check('X12 带上了面试信息字段', True, 'interview' in d, ok='interview' in d)
    logs = d.get('logs') or []
    db_logs = scalar('select count(*) from kg_application_log where app_id=%s' % app_id)
    check('X12 流转日志条数与数据库一致', str(db_logs), str(len(logs)))

    section('X13 PUT /kangaroo/application/onboard/{appId} —— 后台标记入职')
    # 先把报名推到"已签约"，否则入职是非法流转
    http('PUT', '/kangaroo/application/audit',
         {'appId': int(app_id), 'pass': True}, token=admin)
    slot_start = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(time.time() + 3 * 86400))
    slot_end = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(time.time() + 3 * 86400 + 1800))
    http('POST', '/kangaroo/slot', {'storeId': int(store_id), 'startTime': slot_start,
                                    'endTime': slot_end}, token=admin)
    status, body = http('GET', '/app/application/%s/slots' % app_id, app_token=user_tok)
    slots = body.get('data') or []
    assert slots, '没有可选面试时段'
    http('POST', '/app/application/%s/book-interview' % app_id,
         {'slotId': slots[0]['slotId']}, app_token=user_tok)
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    http('POST', '/app/application/%s/finish-interview' % app_id,
         {'durationSeconds': 600}, app_token=user_tok)
    http('POST', '/app/application/%s/confirm' % app_id,
         {'intent': 'accept'}, app_token=user_tok)
    http('PUT', '/kangaroo/application/confirm/%s' % app_id, None, token=admin)
    sign_contract(app_id, user_tok)
    check('X13 前置：已到签约态', 'contract_signed',
          scalar('select status from kg_application where app_id=%s' % app_id))
    status, body = http('PUT', '/kangaroo/application/onboard/%s' % app_id, None, token=admin)
    check('X13 后台标记入职成功', 200, body.get('code'))
    check('X13 状态已变为已入职', 'onboarded',
          scalar('select status from kg_application where app_id=%s' % app_id))
    status, body = http('PUT', '/kangaroo/application/onboard/%s' % app_id, None, token=admin)
    check('X13 重复入职被拒', 500, body.get('code'))

    # 入职是终态，占着的面试时段必须放回可约池 —— 这是本轮修过的一个 bug：
    # 原先的终态列举漏了 onboarded，走完整流程入职的报名把时段永久占死，
    # 后台既改不了也删不了，同门店同区间又被唯一索引挡着重建不了。
    # 修完一直没有断言保护，这里补上。
    booked_slot = slots[0]['slotId']
    check('X13 入职后面试时段回到可约池', 'open',
          scalar('select status from kg_interview_slot where slot_id=%s' % booked_slot))
    check('X13 入职后时段的占用标记被清空', None,
          norm(scalar('select booked_app_id from kg_interview_slot where slot_id=%s'
                      % booked_slot)))
    # 但那场面试是**真发生过的**，记录不能被改写成"已取消"
    check('X13 入职后面试记录仍是 done，没有被改写成 canceled', 'done',
          scalar('select status from kg_interview where app_id=%s' % app_id))
    # 释放出来的时段，别人要能真的约到
    user3_tok, user3_id = app_login('u3')
    status, body = http('POST', '/app/application',
                        {'jobId': int(job_id), 'name': '覆盖测试员三', 'age': 26,
                         'phone': '13700137003', 'arrival': arrival, 'days': 10,
                         'idemKey': 'cov3-' + RUN}, app_token=user3_tok)
    if body.get('code') == 200:
        app3 = scalar('select app_id from kg_application where app_user_id=%s and job_id=%s '
                      'order by app_id desc limit 1' % (user3_id, job_id))
        http('PUT', '/kangaroo/application/audit',
             {'appId': int(app3), 'pass': True}, token=admin)
        status, body = http('POST', '/app/application/%s/book-interview' % app3,
                            {'slotId': int(booked_slot)}, app_token=user3_tok)
        check('X13 释放出来的时段别人能真的约到', 200, body.get('code'))
        check('X13 时段的新占用者已更新', str(app3),
              scalar('select booked_app_id from kg_interview_slot where slot_id=%s'
                     % booked_slot))
        # 收尾：把它撤回，免得占着影响后续用例
        http('POST', '/app/application/%s/withdraw' % app3, {}, app_token=user3_tok)

    section('X18 撤回一条已预约面试的报名，时段要回到可约池')
    user4_tok, user4_id = app_login('u4')
    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-撤回释放时段-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'none', 'headcount': 1, 'status': 'draft'}, token=admin)
    check('X18 前置：建岗位', 200, body.get('code'))
    job4 = scalar('select job_id from kg_job where title=%s order by job_id desc limit 1'
                  % repr('回归-撤回释放时段-' + RUN))
    http('PUT', '/kangaroo/job/changeStatus',
         {'jobId': int(job4), 'status': 'published'}, token=admin)
    status, body = http('POST', '/app/application',
                        {'jobId': int(job4), 'name': '覆盖测试员四', 'age': 27,
                         'phone': '13700137004', 'arrival': arrival, 'days': 10,
                         'idemKey': 'cov4-' + RUN}, app_token=user4_tok)
    check('X18 前置：提交报名', 200, body.get('code'))
    app4 = scalar('select app_id from kg_application where app_user_id=%s and job_id=%s '
                  'order by app_id desc limit 1' % (user4_id, job4))
    http('PUT', '/kangaroo/application/audit',
         {'appId': int(app4), 'pass': True}, token=admin)
    s4 = time.strftime('%Y-%m-%d %H:%M:00', time.localtime(time.time() + 5 * 86400))
    http('POST', '/kangaroo/slot', {'storeId': int(store_id), 'startTime': s4,
                                    'endTime': s4.replace(':00', ':30', 1)}, token=admin)
    status, body = http('GET', '/app/application/%s/slots' % app4, app_token=user4_tok)
    opts = body.get('data') or []
    if not opts:
        print('  [跳过] 没有可选时段，撤回释放无法验证')
    else:
        slot4 = opts[0]['slotId']
        status, body = http('POST', '/app/application/%s/book-interview' % app4,
                            {'slotId': slot4}, app_token=user4_tok)
        check('X18 前置：预约成功', 200, body.get('code'))
        check('X18 前置：时段已被占用', 'booked',
              scalar('select status from kg_interview_slot where slot_id=%s' % slot4))
        status, body = http('POST', '/app/application/%s/withdraw' % app4, {},
                            app_token=user4_tok)
        check('X18 撤回成功', 200, body.get('code'))
        check('X18 撤回后报名进入已撤回态', 'withdrawn',
              scalar('select status from kg_application where app_id=%s' % app4))
        check('X18 撤回后时段回到可约池', 'open',
              scalar('select status from kg_interview_slot where slot_id=%s' % slot4))
        check('X18 撤回后时段的占用标记被清空', None,
              norm(scalar('select booked_app_id from kg_interview_slot where slot_id=%s'
                          % slot4)))
        check('X18 撤回后那条 booked 的面试记录被标记为已取消', 'canceled',
              scalar('select status from kg_interview where app_id=%s' % app4))

    section('X19 报名详情接口的成功返回体（此前只验过越权被拒）')
    status, body = http('GET', '/app/application/%s' % app_id, app_token=user_tok)
    check('X19 本人可读自己的报名详情', 200, body.get('code'))
    d = body.get('data') or {}
    check('X19 返回的是这条报名', str(app_id), str(d.get('appId')))
    check('X19 带上报名单号', True, bool(d.get('appNo')), ok=bool(d.get('appNo')))
    check('X19 状态与数据库一致',
          scalar('select status from kg_application where app_id=%s' % app_id),
          d.get('status'))
    check('X19 带上中文状态名', True, bool(d.get('statusLabel')), ok=bool(d.get('statusLabel')))
    check('X19 带上岗位标题', True, bool(d.get('jobTitle')), ok=bool(d.get('jobTitle')))
    check('X19 不下发 openid', False, 'openid' in str(d).lower())

    section('X14 POST /app/staff/applications/{appId}/terminate —— 员工端终止')
    # 另建一条走到已录用的报名，验证员工端终止会归还名额
    user2_tok, user2_id = app_login('u2')
    status, body = http('POST', '/app/application',
                        {'jobId': int(job_id), 'name': '覆盖测试员二', 'age': 25,
                         'phone': '13700137002', 'arrival': arrival, 'days': 10,
                         'idemKey': 'cov2-' + RUN}, app_token=user2_tok)
    check('X14 前置：第二份报名已提交', 200, body.get('code'))
    app2 = scalar('select app_id from kg_application where app_user_id=%s and job_id=%s '
                  'order by app_id desc limit 1' % (user2_id, job_id))
    http('PUT', '/kangaroo/application/audit', {'appId': int(app2), 'pass': True}, token=admin)
    http('POST', '/kangaroo/slot', {'storeId': int(store_id),
                                    'startTime': slot_start.replace(':', ':', 1),
                                    'endTime': slot_end}, token=admin)
    status, body = http('GET', '/app/application/%s/slots' % app2, app_token=user2_tok)
    s2 = body.get('data') or []
    if s2:
        http('POST', '/app/application/%s/book-interview' % app2,
             {'slotId': s2[0]['slotId']}, app_token=user2_tok)
        from interview_clock_fixture import advance_interview_fixture
        advance_interview_fixture(app2)
        http('POST', '/app/application/%s/finish-interview' % app2,
             {'durationSeconds': 600}, app_token=user2_tok)
        http('POST', '/app/application/%s/confirm' % app2,
             {'intent': 'accept'}, app_token=user2_tok)
        http('POST', '/app/staff/applications/%s/confirm' % app2, {}, app_token=staff_tok)
    hired_before = scalar('select hired_count from kg_job where job_id=%s' % job_id)
    status, body = http('POST', '/app/staff/applications/%s/terminate' % app2,
                        {'reason': '覆盖测试终止'}, app_token=staff_tok)
    check('X14 员工端终止成功', 200, body.get('code'))
    check('X14 状态变为已终止', 'terminated',
          scalar('select status from kg_application where app_id=%s' % app2))
    hired_after = scalar('select hired_count from kg_job where job_id=%s' % job_id)
    check('X14 终止后名额被归还', str(int(hired_before) - 1), str(hired_after))
    status, body = http('POST', '/app/staff/applications/%s/terminate' % app2,
                        {'reason': 'x'}, app_token=user2_tok)
    check('X14 非员工账号不能终止', 403, body.get('code'))

    section('X16 岗位列表的城市 / 关键词 / 分页（此前只测过 type 这一条路径）')
    # 数据库口径：小程序能看到的岗位 = 已发布 + 未软删 + 门店启用且未软删
    visible = ("from kg_job j join kg_store s on s.store_id = j.store_id "
               "where j.status='published' and j.del_flag='0' "
               "  and s.status='0' and s.del_flag='0'")

    db_all = int(scalar('select count(*) ' + visible) or 0)
    status, body = http('GET', '/app/job/list?pageSize=50')
    check('X16 不带筛选时返回全部可见岗位', str(db_all), str(body.get('total')))

    # --- 城市 ---
    # 2026-10-16 审计：城市按「等于或以城市 / 别名结尾」匹配（KgJobCity）。选的城市先归一成业务城市名，
    # 再带上字典里只属于它的全称 / 搜索别名（平潭 → 平潭岛、平潭县…）；「云南大理」算大理，「五大连池」不算大连。
    # 取「有岗位的城市」下拉里的第一个城市，下拉的数量也要和点进去的列表对得上
    _, cities_body = http('GET', '/app/job/cities')
    city_rows = cities_body.get('data') or []
    city_pick = city_rows[0].get('name') if city_rows else None
    if not city_pick:
        print('  [跳过] 库里没有可见岗位，城市筛选无法验证')
    else:
        terms = job_city_terms(city_pick)
        # 用户已明确线上任务全国可接，城市条件只限制线下岗位；后台所属城市不改变。
        db_city = int(scalar("select count(*) " + visible + " and (" + job_city_sql(terms) + " or j.job_type='online')") or 0)
        q = urllib.parse.quote(city_pick)
        status, body = http('GET', '/app/job/list?city=%s&pageSize=50' % q)
        check('X16 按城市筛选的条数与数据库一致（%s，写法 %s）' % (city_pick, '/'.join(terms)), str(db_city), str(body.get('total')))
        wrong = [r.get('title') for r in (body.get('rows') or []) if r.get('jobType') != 'online' and not job_city_match(r.get('city'), terms)]
        check('X16 城市筛选不混入其他城市的线下岗位', [], wrong)
        city_total = body.get('total') or 0
        status, body = http('GET', '/app/job/list?city=%s&pageSize=50'
                            % urllib.parse.quote('不存在的城市-' + RUN))
        db_online = int(scalar("select count(*) " + visible + " and j.job_type='online'") or 0)
        check('X16 不存在的城市只返回全国线上任务', db_online, body.get('total'))
        check('X16 不存在城市的结果不含任何线下岗位', [],
              [r.get('title') for r in (body.get('rows') or []) if r.get('jobType') != 'online'])
        # 下拉的数量（只数线下岗位）= 选中它时列表的条数 - 全国线上任务
        check('X16 「有岗位的城市」的数量与点进去的列表一致（%s）' % city_pick,
              (city_total or 0) - (body.get('total') or 0), city_rows[0].get('count'))
        for job_type in ('volunteer', 'parttime', 'fulltime'):
            db_local = int(scalar("select count(*) " + visible + " and " + job_city_sql(terms) + " and j.job_type=%s" % repr(job_type)) or 0)
            _, local = http('GET', '/app/job/list?type=%s&city=%s&pageSize=50' % (job_type, q))
            check('X16 %s 分类保留严格城市筛选' % job_type, db_local, local.get('total'))
            check('X16 %s 分类每条均为所选城市及类型' % job_type, [],
                  [r.get('jobId') for r in (local.get('rows') or []) if not job_city_match(r.get('city'), terms) or r.get('jobType') != job_type])
        _, remote = http('GET', '/app/job/list?type=online&city=%s&pageSize=50' % urllib.parse.quote('不存在的城市-' + RUN))
        check('X16 独立线上分类不受城市限制', db_online, remote.get('total'))
        check('X16 线上分类不混入线下岗位', [], [r.get('jobId') for r in (remote.get('rows') or []) if r.get('jobType') != 'online'])

    # --- 关键词 ---
    # 2026-10-16 起按规则拆词：普通词出现在标题 / 标签 / 地址 / 介绍任一处，或线下岗位的城市等于它 / 以它结尾就算（KgJobKeyword），不再只看标题
    kw_title = scalar('select j.title ' + visible + ' order by j.job_id desc limit 1')
    kw = plain_keyword(kw_title)
    if not kw:
        print('  [跳过] 库里没有可见岗位（或最新标题开头没有可用的普通词），关键词筛选无法验证')
    else:
        db_kw = int(scalar("select count(*) " + visible + " and " + keyword_sql(kw)) or 0)
        status, body = http('GET', '/app/job/list?keyword=%s&pageSize=50'
                            % urllib.parse.quote(kw))
        check('X16 按关键词筛选的条数与数据库一致（%s）' % kw, str(db_kw), str(body.get('total')))
        ids = [str(r.get('jobId')) for r in (body.get('rows') or []) if r.get('jobId') is not None]
        hit = set(str(x[0]) for x in sql("select j.job_id " + visible + " and " + keyword_sql(kw)
                                          + " and j.job_id in (" + ','.join(ids) + ")")) if ids else set()
        check('X16 按关键词筛选的结果都含该关键词（标题 / 标签 / 地址 / 介绍，或线下岗位的城市）', [], [i for i in ids if i not in hit])
        status, body = http('GET', '/app/job/list?keyword=%s&pageSize=50'
                            % urllib.parse.quote('绝不可能出现的关键词-' + RUN))
        check('X16 不存在的关键词返回空集', 0, body.get('total'))
    # 线上任务全国可接：关键词不按发布门店的城市认（之前线上页签搜「平潭」会列出平潭门店发的、文字里没写平潭的线上任务）
    online_city = scalar("select trim(j.city) " + visible + " and j.job_type='online' and trim(j.city) not in ('', '不限') "
                         "and char_length(trim(j.city)) between 2 and 16 order by j.job_id desc limit 1")
    import re
    # 只拿后端会当成一个整词的城市名来验（纯中文 / 字母 / 数字、不含特殊写法、不是虚字）；线上任务只比文字，城市字典的归一不影响它
    if online_city and re.fullmatch(r'[\u4e00-\u9fa5A-Za-z0-9]+', online_city) and online_city[0] not in '去找想' \
            and not any(w in online_city for w in KEYWORD_SPECIAL) and not all(ch in KEYWORD_FILLERS for ch in online_city):
        db_on = int(scalar("select count(*) " + visible + " and j.job_type='online' and " + keyword_sql(online_city)) or 0)
        status, body = http('GET', '/app/job/list?type=online&keyword=%s&pageSize=50' % urllib.parse.quote(online_city))
        check('X16 线上页签按关键词搜城市名，只看文字、不按发布门店的城市认（%s）' % online_city, str(db_on), str(body.get('total')))

    # --- 分页 ---
    if db_all >= 2:
        status, p1 = http('GET', '/app/job/list?pageNum=1&pageSize=1')
        status, p2 = http('GET', '/app/job/list?pageNum=2&pageSize=1')
        check('X16 分页时 total 仍是全量条数', str(db_all), str(p1.get('total')))
        check('X16 第一页只返回 1 条', 1, len(p1.get('rows') or []))
        check('X16 第二页只返回 1 条', 1, len(p2.get('rows') or []))
        id1 = (p1.get('rows') or [{}])[0].get('jobId')
        id2 = (p2.get('rows') or [{}])[0].get('jobId')
        check('X16 两页返回的不是同一条岗位', True, id1 != id2, ok=id1 != id2)
        # 越界与非法值不能把接口打挂，也不能退化成全量
        status, body = http('GET', '/app/job/list?pageNum=9999&pageSize=10')
        # 先断状态码再断行数：接口 500 时 AjaxResult 里根本没有 rows，
        # `body.get('rows') or []` 得到空列表、长度 0，只数行数的话
        # 这条名为"不报错"的断言恰好在真的报错时通过。
        check('X16 页码越界仍返回 200（而不是报错）', 200, body.get('code'))
        check('X16 页码越界返回空集', 0, len(body.get('rows') or []))
        status, body = http('GET', '/app/job/list?pageNum=0&pageSize=0')
        check('X16 非法页码页长被纠正而不是报错', 200, body.get('code'))
        check('X16 非法页长被收敛到默认值（不超过 50）', True,
              len(body.get('rows') or []) <= 50, ok=len(body.get('rows') or []) <= 50)
        status, body = http('GET', '/app/job/list?pageSize=999')
        check('X16 超大页长被收敛，不会一次拖全库', True,
              len(body.get('rows') or []) <= 50, ok=len(body.get('rows') or []) <= 50)

    # --- 组合筛选 ---
    if city_pick and kw:
        db_both = int(scalar("select count(*) " + visible +
                             " and (" + job_city_sql(job_city_terms(city_pick)) + " or j.job_type='online') and " + keyword_sql(kw)) or 0)
        status, body = http('GET', '/app/job/list?city=%s&keyword=%s&pageSize=50'
                            % (urllib.parse.quote(city_pick),
                               urllib.parse.quote(kw)))
        check('X16 城市与关键词组合筛选的条数与数据库一致', str(db_both), str(body.get('total')))

    section('X20 门店员工不能处理自己提交的报名（自审自批）')
    # 这个人既是门店员工（X0 里已授予），又用同一个账号报了本店的岗位。
    # 两个条件各自都合法：他确实有这家店的授权，他也确实可以报名。
    # 但合起来就是"自己通过自己的审核、自己确认录用自己、自己给自己标记入职"。
    self_arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 9 * 86400))
    status, body = http('POST', '/app/application',
                        {'jobId': int(job_id), 'name': '自审测试员', 'age': 26,
                         'phone': '13700137020', 'arrival': self_arrival, 'days': 8,
                         'idemKey': 'selfdeal-' + RUN}, app_token=staff_tok)
    check('X20 员工本人也能正常报名（这一步本来就该允许）', 200, body.get('code'))
    self_app = scalar('select app_id from kg_application where app_user_id=%s '
                      'and job_id=%s order by app_id desc limit 1' % (staff_id, job_id))
    assert self_app, '员工自己的报名没落库'

    # 读自己的详情是正当的，不该被拦
    status, body = http('GET', '/app/staff/applications/%s' % self_app, app_token=staff_tok)
    check('X20 看自己那条报名的详情不被拦', 200, body.get('code'))

    # 四个写动作必须全部 403
    for path, payload, label in (
            ('/app/staff/applications/%s/audit' % self_app, {'pass': True}, '审核通过'),
            ('/app/staff/applications/%s/audit' % self_app, {'pass': False, 'reason': '不合适'}, '驳回'),
            ('/app/staff/applications/%s/confirm' % self_app, None, '确认录用'),
            ('/app/staff/applications/%s/onboard' % self_app, None, '标记入职'),
            ('/app/staff/applications/%s/terminate' % self_app, {'reason': '测试'}, '终止')):
        status, body = http('POST', path, payload, app_token=staff_tok)
        check('X20 员工%s自己的报名被拒（403）' % label, 403, body.get('code'))

    check('X20 被拒之后状态没被改动', 'submitted',
          scalar('select status from kg_application where app_id=%s' % self_app))

    # 2026-10-04 用户确认：普通员工只查看；其他员工也不能审核。
    # 正向用本店另一名管家完成，保留自我处理与普通角色两道权限边界。
    other_tok, other_id = app_login('s2')
    status, body = http('POST', '/kangaroo/grant',
                        {'appUserId': int(other_id), 'storeId': int(store_id),
                         'roleCode': 'staff'}, token=admin)
    check('X20 再授予一名员工', 200, body.get('code'))
    status, body = http('POST', '/app/staff/applications/%s/audit' % self_app,
                        {'pass': False, 'reason': '回归用例收尾'}, app_token=other_tok)
    check('X20 同店普通员工不能审核且状态不变', (403, 'submitted'),
          (body.get('code'), scalar('select status from kg_application where app_id=%s' % self_app)))
    keeper_tok, keeper_id = app_login('s2-keeper')
    status, body = http('POST', '/kangaroo/grant',
                       {'appUserId': int(keeper_id), 'storeId': int(store_id), 'roleCode': 'keeper'}, token=admin)
    check('X20 授予本店管家', 200, body.get('code'))
    status, body = http('POST', '/app/staff/applications/%s/audit' % self_app,
                       {'pass': False, 'reason': '回归用例收尾'}, app_token=keeper_tok)
    check('X20 本店管家可以正常处理其他人的报名', 200, body.get('code'))
    check('X20 处理后状态确实变了', 'review_rejected',
          scalar('select status from kg_application where app_id=%s' % self_app))

    # ---------- X21 角色边界：普通员工（staff）只查看，招聘写操作只给主理人、管家 ----------
    # 2026-09-24 验收发现：三个动作在后端只校验"有这家店的授权"，staff 角色直接调接口就能录用、入职、终止，
    # 详情接口也把完整手机号给了 staff。判据看"被角色拦下"这一句，而不是只看 403 ——
    # 上面 X20 的自我处理守卫同样回 403，只比状态码分不清是哪道闸拦的。
    for path, payload, label in (
            ('/app/staff/applications/%s/confirm' % self_app, None, '确认录用'),
            ('/app/staff/applications/%s/onboard' % self_app, None, '标记入职'),
            ('/app/staff/applications/%s/terminate' % self_app, {'reason': '越权测试'}, '终止')):
        status, body = http('POST', path, payload, app_token=other_tok)
        check('X21 staff 角色%s被角色权限拦下（403 + 角色提示）' % label, (403, True),
              (body.get('code'), '没有「岗位管理·编辑」权限' in str(body.get('msg'))))
    check('X21 被拦之后状态没被改动', 'review_rejected',
          scalar('select status from kg_application where app_id=%s' % self_app))
    denied = scalar("select count(*) from kg_staff_op_log where app_user_id=%s and ok='0' "
                    "and action in ('application_confirm','application_onboard','application_terminate')" % other_id)
    check('X21 三次越权尝试都记进了员工操作日志（失败）', '3', denied)
    status, body = http('GET', '/app/staff/applications/%s' % self_app, app_token=other_tok)
    check('X21 staff 角色看详情只拿到脱敏手机号', (200, False, True),
          (body.get('code'), (body.get('data') or {}).get('phone') == '13700137020',
           (body.get('data') or {}).get('contactMasked')))
    status, body = http('GET', '/app/staff/applications/%s' % self_app, app_token=staff_tok)
    check('X21 主理人看详情拿到完整手机号', (200, '13700137020'),
          (body.get('code'), (body.get('data') or {}).get('phone')))

    other_grant =scalar('select grant_id from kg_staff_grant where app_user_id=%s '
                         'and store_id=%s order by grant_id desc limit 1' % (other_id, store_id))
    if other_grant:
        http('PUT', '/kangaroo/grant/revoke/%s' % other_grant, None, token=admin)
    keeper_grant = scalar('select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s order by grant_id desc limit 1' % (keeper_id, store_id))
    if keeper_grant:
        http('PUT', '/kangaroo/grant/revoke/%s' % keeper_grant, None, token=admin)

    section('X21 限流与手机号归属')
    # 限流键带窗口期，主体必须每次运行都不同，否则连跑两次第二次一上来就是被限的。
    # 账号由 app_login 按 RUN 生成；手机号也从 RUN 派生出一个稳定但唯一的号。
    phone_seed = str(int(RUN[:6], 16) % 100000000).rjust(8, '0')
    owned_phone = '139' + phone_seed

    owner_tok, owner_id = app_login('p1')
    other_tok, other_uid = app_login('p2')

    status, body = http('POST', '/app/auth/bind-phone', {'phone': owned_phone},
                        app_token=owner_tok)
    check('X21 本人绑定手机号成功', 200, body.get('code'))
    check('X21 手机号确实落到这个账号上', owned_phone,
          scalar('select phone from kg_app_user where user_id=%s' % owner_id))

    # 别人不能绑同一个号 —— 修之前这里是能成功的，
    # 而这个号会作为联系方式下发给门店员工，等于给陌生人招来一堆招聘电话
    status, body = http('POST', '/app/auth/bind-phone', {'phone': owned_phone},
                        app_token=other_tok)
    check('X21 别的账号绑同一个手机号返回归属冲突', 409, body.get('code'))
    check('X21 被拒时对方账号的手机号没有被写进去', None,
          norm(scalar('select phone from kg_app_user where user_id=%s' % other_uid)))
    check('X21 原主人的绑定也没有被动过', owned_phone,
          scalar('select phone from kg_app_user where user_id=%s' % owner_id))

    # 本人重复绑同一个号应当仍然允许（这不是"占用"，是幂等的再确认）
    status, body = http('POST', '/app/auth/bind-phone', {'phone': owned_phone},
                        app_token=owner_tok)
    check('X21 本人重复绑同一个号仍然允许', 200, body.get('code'))

    # 限流：同一账号 5 次 / 10 分钟。上面已经用掉 2 次，再打到超限。
    limited_at = 0
    for i in range(3, 12):
        status, body = http('POST', '/app/auth/bind-phone',
                            {'phone': '139' + str((int(phone_seed) + i) % 100000000).rjust(8, '0')},
                            app_token=owner_tok)
        if body.get('code') != 200 and '频繁' in str(body.get('msg') or ''):
            limited_at = i
            break
    check('X21 反复绑定手机号会被限流（而不是无限次改绑）', True, limited_at > 0,
          ok=limited_at > 0)
    check('X21 限流在合理次数内触发（<= 10 次）', True, 0 < limited_at <= 10,
          ok=0 < limited_at <= 10)

    # 报名 → 撤回 循环的上限：同一账号同一岗位 5 次 / 天
    loop_tok, loop_uid = app_login('loop')
    loop_arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 11 * 86400))
    loop_limited_at = 0
    for i in range(1, 18):   # 上限 12 次/天，要循环到能撞上它
        status, body = http('POST', '/app/application',
                            {'jobId': int(job_id), 'name': '刷单测试员', 'age': 23,
                             'phone': '13700137021', 'arrival': loop_arrival, 'days': 5,
                             'idemKey': 'loop-%s-%d' % (RUN, i)}, app_token=loop_tok)
        if body.get('code') != 200:
            if '频繁' in str(body.get('msg') or '') or '次数过多' in str(body.get('msg') or ''):
                loop_limited_at = i
                break
            check('X21 报名循环第 %d 次意外失败：%s' % (i, body.get('msg')), 200, body.get('code'))
            break
        loop_app = scalar('select app_id from kg_application where app_user_id=%s '
                          'and job_id=%s and del_flag=\'0\' order by app_id desc limit 1'
                          % (loop_uid, job_id))
        http('POST', '/app/application/%s/withdraw' % loop_app, None, app_token=loop_tok)
    check('X21 「报名→撤回」对同一岗位的循环有上限', True, loop_limited_at > 0,
          ok=loop_limited_at > 0)
    check('X21 上限在合理次数内（<= 15 次）', True, 0 < loop_limited_at <= 15,
          ok=0 < loop_limited_at <= 15)

    section('X22 后端层补测：详情正向字段 / 删除 / 非法流转 / 伪造身份 / 菜单字典 / 分类字段 / 启用')

    # ---------------------------------------------------------------- B02
    status, body = http('GET', '/app/job/%s' % job_id)
    check('X22 岗位详情可匿名访问', 200, body.get('code'))
    jd = body.get('data') or {}
    st = jd.get('store') or {}
    check('X22 详情带门店块（门店名）', True, bool(st.get('storeName')), ok=bool(st.get('storeName')))
    check('X22 门店名与库一致',
          scalar('select store_name from kg_store where store_id=%s' % store_id),
          st.get('storeName'))
    check('X22 详情带薪资下限（与库一致）',
          str(scalar('select salary_min from kg_job where job_id=%s' % job_id)).rstrip('0').rstrip('.'),
          str(jd.get('salaryMin')).rstrip('0').rstrip('.'))
    check('X22 详情带招聘名额（与库一致）',
          str(scalar('select headcount from kg_job where job_id=%s' % job_id)),
          str(jd.get('headcount')))
    check('X22 详情带食宿方式', True, 'boardType' in jd, ok='boardType' in jd)
    check('X22 详情**不**下发门店联系电话', False,
          'contactPhone' in str(body) or 'contact_phone' in str(body))

    # ---------------------------------------------------------------- B06 删除草稿
    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-删草稿-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'none', 'headcount': 1}, token=admin)
    check('X22 建一个草稿岗位', 200, body.get('code'))
    draft_id = scalar("select job_id from kg_job where title=%s" % repr('回归-删草稿-' + RUN))
    status, body = http('DELETE', '/kangaroo/job/%s' % draft_id, token=admin)
    check('X22 删除草稿岗位成功', 200, body.get('code'))
    check('X22 删除后 del_flag 真的置位（软删）', True,
          scalar('select del_flag from kg_job where job_id=%s' % draft_id) != '0',
          ok=scalar('select del_flag from kg_job where job_id=%s' % draft_id) != '0')

    # ---------------------------------------------------------------- B07 非法流转
    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-非法流转-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'none', 'headcount': 1}, token=admin)
    flow_id = scalar("select job_id from kg_job where title=%s" % repr('回归-非法流转-' + RUN))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': int(flow_id), 'status': 'published'}, token=admin)
    for frm, to in (('published', 'draft'), ('published', 'published')):
        status, body = http('PUT', '/kangaroo/job/changeStatus',
                            {'jobId': int(flow_id), 'status': to}, token=admin)
        check('X22 %s -> %s 这条合法状态之间的流转被拒' % (frm, to), 500, body.get('code'))
        check('X22 被拒之后状态仍是 %s' % frm, frm,
              scalar('select status from kg_job where job_id=%s' % flow_id))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': int(flow_id), 'status': 'closed'}, token=admin)
    for to in ('published', 'paused', 'draft'):
        status, body = http('PUT', '/kangaroo/job/changeStatus',
                            {'jobId': int(flow_id), 'status': to}, token=admin)
        check('X22 closed -> %s 被拒（关闭是终态）' % to, 500, body.get('code'))
    check('X22 关闭后状态始终停在 closed', 'closed',
          scalar('select status from kg_job where job_id=%s' % flow_id))

    # ---------------------------------------------------------------- B08 草稿但已有报名
    status, body = http('POST', '/kangaroo/job', {
        'storeId': int(store_id), 'title': '回归-草稿有报名-' + RUN, 'jobType': 'parttime',
        'city': city, 'salaryType': 'none', 'headcount': 1}, token=admin)
    dr2 = scalar("select job_id from kg_job where title=%s" % repr('回归-草稿有报名-' + RUN))
    # 正常流程造不出"草稿但已有报名"：报名只能投已发布岗位，而已发布回不到草稿。
    # 这条保护是纵深防御，只能用 SQL 把残局直接造出来。
    sql_exec("insert into kg_application (app_no, job_id, store_id, app_user_id, real_name, age, phone, "
             "arrival_date, stay_days, status, del_flag, create_time, submit_time) values "
             "('B08-%s', %s, %s, %s, 'B08残局', 25, '13700000008', curdate(), 5, 'withdrawn', '0', now(), now())"
             % (RUN, dr2, store_id, user_id))
    status, body = http('DELETE', '/kangaroo/job/%s' % dr2, token=admin)
    check('X22 草稿但已有报名的岗位删除被拒', 500, body.get('code'))
    check('X22 拒绝理由说的是"已有报名"', True, '报名' in str(body.get('msg')),
          ok='报名' in str(body.get('msg')))
    check('X22 被拒之后 del_flag 没被改动', '0',
          scalar('select del_flag from kg_job where job_id=%s' % dr2))
    sql_exec("delete from kg_application where app_no='B08-%s'" % RUN)

    # ---------------------------------------------------------------- C04 伪造身份
    forge_tok, forge_uid = app_login('forge')
    forge_arrival = time.strftime('%Y-%m-%d', time.localtime(time.time() + 12 * 86400))
    status, body = http('POST', '/app/application',
                        {'jobId': int(job_id), 'name': '伪造测试', 'age': 30,
                         'phone': '13700137022', 'arrival': forge_arrival, 'days': 6,
                         'appUserId': int(user_id), 'userId': int(user_id),
                         'idemKey': 'forge-' + RUN}, app_token=forge_tok)
    check('X22 请求体里夹带别人的 appUserId 也能提交（字段被忽略）', 200, body.get('code'))
    forge_owner = scalar("select app_user_id from kg_application where job_id=%s "
                         "and real_name='伪造测试' order by app_id desc limit 1" % job_id)
    check('X22 报名归属的是登录态账号，不是请求体里伪造的那个',
          str(forge_uid), str(forge_owner))

    # ---------------------------------------------------------------- F01 系统菜单
    sys_menus = int(scalar('select count(*) from sys_menu where menu_id between 1 and 1100') or 0)
    check('X22 若依系统菜单已补齐（sys_menu 系统段非空）', True, sys_menus > 30,
          ok=sys_menus > 30)
    status, body = http('GET', '/getRouters', token=admin)
    check('X22 /getRouters 可访问', 200, body.get('code'))
    names = ' '.join(str(r.get('meta', {}).get('title', '')) for r in (body.get('data') or []))
    check('X22 下发的路由里有「系统管理」目录', True, '系统管理' in names, ok='系统管理' in names)
    check('X22 下发的路由里有「袋鼠小院」目录', True, '袋鼠' in names, ok='袋鼠' in names)

    # ---------------------------------------------------------------- F02 8 类字典
    # kg_msg_category 6 → 7：WP01 按计划把 group 群聊直接写进 V2（不再由后续迁移往 V2 的类型里追加）
    # 7 → 8：V17（2026-09-30 审计修复）补了 staff（员工待办）：员工工作台的消息以 category='staff' 写入，字典里原先没有这个值，
    #        后台消息页无法按它筛选、列表直接显示英文原值
    # 8 → 10：V43（2026-10-15 消息页改版）补了 comment（收到的评论）、like（收到的赞）
    # kg_salary_type 4 → 6：V23（2026-10-02 岗位薪资周期）补了 hourly 时薪、weekly 周薪
    expect_dicts = {'kg_job_type': 4, 'kg_job_status': 4, 'kg_salary_type': 6,
                    'kg_board_type': 5, 'kg_app_status': 11, 'kg_staff_role': 4,  # V2 的 3 个 + 后来加的线上任务专员 online_staff
                   
                    'kg_msg_category': 10, 'kg_slot_status': 3}
    for dt, n in expect_dicts.items():
        status, body = http('GET', '/system/dict/data/type/%s' % dt, token=admin)
        got = len(body.get('data') or [])
        check('X22 字典 %s 取值数与 V2 脚本一致' % dt, n, got)

    # ---------------------------------------------------------------- F04 分类专属字段
    special = {'shiftDesc': '早班 7-15 / 晚班 15-23', 'shiftTime': '每周 5 天',
               'trainDesc': '入职带教 3 天', 'probation': '试用 7 天',
               'benefits': '包住 · 夜宵', 'deliverDesc': '每周交付 3 篇', 'estHours': '每篇 2 小时'}
    payload = {'storeId': int(store_id), 'title': '回归-分类字段-' + RUN, 'jobType': 'parttime',
               'city': city, 'salaryType': 'none', 'headcount': 1}
    payload.update(special)
    status, body = http('POST', '/kangaroo/job', payload, token=admin)
    check('X22 带分类专属字段新建岗位', 200, body.get('code'))
    sp_id = scalar("select job_id from kg_job where title=%s" % repr('回归-分类字段-' + RUN))
    status, body = http('GET', '/kangaroo/job/%s' % sp_id, token=admin)
    back = body.get('data') or {}
    for k, v in special.items():
        check('X22 分类字段 %s 落库并原样读回' % k, v, back.get(k))

    # ---------------------------------------------------------------- F07 启用
    en_tok, en_uid = app_login('enable')
    status, body = http('PUT', '/kangaroo/appuser/changeStatus',
                        {'userId': int(en_uid), 'status': '1'}, token=admin)
    check('X22 停用账号', 200, body.get('code'))
    status, body = http('GET', '/app/application/mine', app_token=en_tok)
    check('X22 停用后原令牌立即失效', 401, body.get('code'))
    status, body = http('PUT', '/kangaroo/appuser/changeStatus',
                        {'userId': int(en_uid), 'status': '0'}, token=admin)
    check('X22 启用账号（此前全项目从没调过 status=0）', 200, body.get('code'))
    check('X22 启用后库里状态回到 0', '0',
          scalar('select status from kg_app_user where user_id=%s' % en_uid))
    re_tok, re_uid = app_login('enable')
    check('X22 启用后同一个人能重新登录，且是同一个账号', str(en_uid), str(re_uid))

    section('X15 三个导出接口：枚举必须是中文')
    for path, label, cn_words in (
            ('/kangaroo/store/export', '门店导出', ('已认证', '未认证')),
            ('/kangaroo/appuser/export', '账号导出', ('正常', '停用')),
            ('/kangaroo/application/export', '报名导出', ('已提交', '已入职', '已终止'))):
        status, blob = post_binary(path, admin)
        check('X15 %s 返回 200' % label, 200, status)
        check('X15 %s 是 xlsx' % label, b'PK', blob[:2])
        text = xlsx_text(blob)
        raw_codes = ('onsite', 'parttime', 'published', 'submitted', 'onboarded',
                     'terminated', 'manager')
        found_raw = [c for c in raw_codes if '>%s<' % c in text]
        check('X15 %s 里没有英文枚举原始码' % label, [], found_raw)
        found_cn = [w for w in cn_words if w in text]
        check('X15 %s 里出现了中文枚举' % label, True, found_cn, ok=bool(found_cn))

    # ================================================================ 清理
    section('清理本用例造的数据')
    http('PUT', '/kangaroo/grant/revoke/%s' % grant_id, None, token=admin)
    # X22 新建的两个草稿（分类字段、草稿有报名）也要收掉；草稿可以直接关闭
    for t in ('回归-接口覆盖-' + RUN, '回归-草稿可见性-' + RUN, '回归-撤回释放时段-' + RUN,
              '回归-分类字段-' + RUN, '回归-草稿有报名-' + RUN):
        jid = scalar('select job_id from kg_job where title=%s' % repr(t))
        if jid:
            http('PUT', '/kangaroo/job/changeStatus',
                 {'jobId': int(jid), 'status': 'closed'}, token=admin)
    left = scalar("select count(*) from kg_job where title like %s and del_flag='0' "
                  "and status <> 'closed'" % repr('回归-%' + RUN))
    check('清理后无未关闭的测试岗位', '0', left)

    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
