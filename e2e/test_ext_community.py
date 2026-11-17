# -*- coding: utf-8 -*-
"""V10 社区与活动模块的接口回归：动态 / 评论 / 互动 / 拉黑 / 举报 / 后台审核、门店活动与报名资金链、公开行程与私信群聊。

只收"能证明业务结果"的断言：每个动作之后回库确认状态与计数，另一方（后台 / 其他用户）看得到或看不到，
越权、未登录、非法输入要被拦且数据没变。

覆盖：
  C1 规则同意（423 CONSENT → 同意 → 重试）与手机号核验（428 → 微信手机号模拟授权）
  C2 发布、审核方式（mock：本地检查通过直接发布；含联系方式转人工）、信息流可见性
  C3 点赞 / 收藏 / 评论 / 回复 / 评论点赞 / 删评论：计数与库一致
  C4 删帖：只能删自己的
  C5 拉黑：双向隐藏内容、互动与私信被拦、关注关系解除
  C6 举报：去重、后台看得到、判定成立后内容隐藏
  C7 后台审核动作（通过 / 驳回 / 隐藏 / 评论隐藏恢复）在小程序侧生效
  A1 员工创建活动（直营门店 = 小院活动，在线收费）→ 待审核 → 后台通过 → 用户可见
  A2 免费活动报名：名额、候补、重复与并发报名、取消递补、计数对账
  A3 员工按签到码签到
  A4 付费活动：待支付 → 联调支付 → 取消转退款待审批 → 门店 / 后台审批 → 支付单退款额对账；驳回；待支付取消
  A5 活动状态操作、现场通知、员工列表与数据、用户自发活动（活动管理页）
  T1 公开行程（审核）/ 同行匹配 / 个人页 / 关闭
  T2 私信：打招呼上限、非成员不可读、不能退出私信、拉黑后不能发
  T3 活动群：成员可进、非成员不可进、退出、群主不能退、群公告权限；我的行程
  T4 门店会话进消息列表（2026-10-15）：主理人 / 线上专员「联系接单人」后员工身份「私信」里有这段会话（已读、只一行）；
     接单人回复 → 同一行变未读并排到最前；员工读掉 → 清掉；接单人那边是门店名的一行

前置：后端以联调模式运行（kangaroo.pay.mode=mock、内容审核 mock），库里有演示基线的直营门店。
测试数据：本次运行新建的账号（code 前缀 comm-<RUN>-）及其动态、活动、报名、行程、会话；reset-to-demo.sql 会一并清掉。

用法：
    python e2e/test_ext_community.py
"""
import os
import random
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import http, scalar, sql, check, section, admin_login, PASSED, FAILED  # noqa: E402
from fixture_assets import require_bundled_asset

RUN = uuid.uuid4().hex[:8]
# 标题里带一个纯字母的运行标识，信息流按关键词过滤只看本次数据（避免数字拼出手机号样式触发人工审核）
TAG = 'hz' + ''.join(random.choice('abcdefghjkmnpqrstuwyz') for _ in range(6))
CITY = '大理'


# ---------------------------------------------------------------- 小工具

def app_login(tag):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'comm-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    return body['token'], int(body['account']['id'])


def grant(admin, uid, store_id, role):
    status, body = http('POST', '/kangaroo/grant', {'appUserId': uid, 'storeId': int(store_id), 'roleCode': role}, token=admin)
    assert body.get('code') == 200, body


def code(body):
    return body.get('code')


def data(body):
    return body.get('data') or {}


def refused(name, body, expect_code=None):
    """越权 / 非法请求要被拒；给了 expect_code 就精确比对"""
    if expect_code is None:
        return check(name, '非 200', code(body), ok=code(body) != 200)
    return check(name, expect_code, code(body))


def new_phone():
    return '139' + '%08d' % random.randint(0, 99999999)


def consent(tok, doc):
    status, body = http('POST', '/app/community/consent', {'doc': doc}, app_token=tok)
    assert code(body) == 200, body


def verify_phone(tok):
    phone = new_phone()
    status, body = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + phone}, app_token=tok)
    assert code(body) == 200, body
    return phone


def day(offset, hm=None):
    t = time.gmtime(time.time() + 8 * 3600 + offset * 86400)   # 后端与库按北京时间（+08:00）算日期，本机时区不同会错一天
    return time.strftime('%Y-%m-%d', t) + ((' ' + hm) if hm else '')


def n(q):
    return int(scalar(q) or 0)


def msgs(uid, title, mode=None):
    return n("select count(*) from kg_message where recipient_id=%s and title='%s'%s"
             % (uid, title, '' if mode is None else " and mode='%s'" % mode))


def feed_ids(tok=None, keyword=TAG, kind=None):
    q = '/app/community/feed?city=' + urllib_quote(CITY) + '&keyword=' + keyword
    if kind:
        q += '&kind=' + kind
    status, body = http('GET', q, app_token=tok)
    if code(body) != 200:
        # 游客被拒（401）时不中断整轮，返回 None 让断言如实失败
        print('       （信息流请求失败：%s）' % body)
        return None
    return [int(r['postId']) for r in data(body).get('rows', [])]


def has(ids, x):
    return None if ids is None else (x in ids)


def urllib_quote(s):
    import urllib.parse
    return urllib.parse.quote(s)


def post_row(pid, cols):
    r = sql("select %s from kg_post where post_id=%s" % (cols, pid))
    return tuple(r[0]) if r else None


def publish(tok, title, content='', kind='daily', **extra):
    body = {'kind': kind, 'title': title, 'content': content, 'city': CITY}
    body.update(extra)
    status, b = http('POST', '/app/community/posts', body, app_token=tok)
    return b


def act_counts(aid):
    r = sql("select signed, waitlist, capacity, status from kg_activity where activity_id=%s" % aid)[0]
    return int(r[0]), int(r[1]), int(r[2]), r[3]


def act_db_counts(aid):
    signed = n("select count(*) from kg_activity_signup where activity_id=%s and status in ('confirmed','pending_payment')" % aid)
    wait = n("select count(*) from kg_activity_signup where activity_id=%s and status='waitlist'" % aid)
    return signed, wait


def check_counters(label, aid):
    s, w, _, _ = act_counts(aid)
    check(label + '：报名数 / 候补数 与报名记录对账（signed, waitlist）', act_db_counts(aid), (s, w))


def signup_row(sid, cols='status'):
    r = sql("select %s from kg_activity_signup where signup_id=%s" % (cols, sid))
    return (r[0][0] if len(r[0]) == 1 else tuple(r[0])) if r else None


def staff_create(tok, store_id, title, capacity=2, fee='0', intro='', offset=3):
    payload = {'category': '手作课程', 'title': title, 'cover': '', 'place': '门店一楼手作区', 'capacity': capacity,
               'startTime': day(offset, '14:00'), 'endTime': day(offset, '17:00'), 'feeYuan': fee, 'intro': intro}
    status, body = http('POST', '/app/staff/activities?storeId=%s' % store_id, payload, app_token=tok)
    return body


def signup(tok, aid, channel='community'):
    status, body = http('POST', '/app/activities/%s/signup' % aid, {'channel': channel}, app_token=tok)
    return body


def pay(tok, pay_no):
    s1, b1 = http('POST', '/app/pay/%s/prepay' % pay_no, {}, app_token=tok)
    s2, b2 = http('POST', '/app/pay/%s/mock-confirm' % pay_no, {}, app_token=tok)
    return code(b1) == 200 and code(b2) == 200


def admin_list(admin, entity, **q):
    import urllib.parse
    qs = urllib.parse.urlencode(dict(q, pageSize=200))
    status, body = http('GET', '/kangaroo/ext/%s/list?%s' % (entity, qs), token=admin)
    assert code(body) == 200, body
    return body.get('rows') or []


# ---------------------------------------------------------------- T4 门店会话进消息列表

def named_login(tag, nickname):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'comm-%s-%s' % (RUN, tag), 'nickname': nickname})
    assert body.get('code') == 200, body
    return body['token'], int(body['account']['id'])


def inbox(tok, mode, category=None):
    """消息中心列表（员工 / 用户身份），返回 (行列表, 总未读数)"""
    q = '/app/message/list?mode=' + mode + ('&category=' + category if category else '')
    status, body = http('GET', q, app_token=tok)
    assert code(body) == 200, body
    return body.get('rows') or [], body.get('unread')


def conv_rows(rows, conv):
    return [r for r in rows if r.get('bizType') == 'chat_conv' and int(r.get('bizId') or 0) == conv]


def t4_store_chat_rows(admin):
    """2026-10-15 主人反馈：门店员工在线上任务交付页点「联系接单人」发了消息，员工身份「消息 › 全部 / 私信」里什么都没有。
    现在每人每个身份下每个会话固定一行（发消息的人自己也有一行已读的「我：…」），消息页的「私信」就是会话列表。
    用一家新建门店，避免演示门店的线上接待账号设置影响谁能联系。"""
    section('T4 门店会话进消息列表：联系接单人后员工「私信」有这段会话；回复后同一行变未读排最前；读掉清除；接单人那边是门店名')
    status, b = http('POST', '/kangaroo/store', {'storeName': '社区会话回归小院-%s' % RUN, 'city': CITY, 'address': '%s古城会话路%s号' % (CITY, RUN[:3]),
                                                  'intro': '门店会话回归', 'contactPhone': '18500000000'}, token=admin)
    assert code(b) == 200, b
    store = int(b.get('data') or 0)
    store_name = scalar("select store_name from kg_store where store_id=%s" % store)
    _, boss_id = named_login('t4boss', '会话主理人')
    _, ost_id = named_login('t4ost', '会话专员')
    grant(admin, boss_id, store, 'manager')
    grant(admin, ost_id, store, 'online_staff')
    boss_tok, ost_tok = named_login('t4boss', '会话主理人')[0], named_login('t4ost', '会话专员')[0]
    w1_tok, w1_id = named_login('t4w1', '接单人小周')
    w2_tok, _ = named_login('t4w2', '接单人小吴')
    w1_name = scalar("select nickname from kg_app_user where user_id=%s" % w1_id)
    ost_name = scalar("select nickname from kg_app_user where user_id=%s" % ost_id)
    task_title = TAG + '会话回归文案'
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % store,
                     {'title': task_title, 'taskType': '内容写作', 'deliverables': '800 字文案', 'criteria': '原创', 'description': '会话回归线上任务',
                      'commissionFen': 5000, 'quota': 3, 'deadline': time.strftime('%Y-%m-%d %H:%M', time.gmtime(time.time() + 8 * 3600 + 3 * 86400))},
                     app_token=boss_tok)
    jid = int(data(b).get('jobId') or 0)
    status, b = http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    check('T4 准备：新门店发布线上任务', (200, 'published'), (code(b), scalar("select status from kg_job where job_id=%s" % jid)))
    apps = {}
    for tag, tok in (('t4w1', w1_tok), ('t4w2', w2_tok)):
        status, b = http('POST', '/app/application', {'jobId': jid, 'name': '接单人', 'age': 24, 'phone': '13500135000', 'arrival': day(3),
                                                      'days': 30, 'idemKey': 'comm-%s-%s' % (RUN, tag)}, app_token=tok)
        apps[tag] = int(data(b).get('appId') or 0)
    check('T4 准备：两位接单人报名', True, all(apps.values()), ok=all(apps.values()))

    # ---- 主理人「联系接单人」并发一条：自己员工身份「私信」里马上有这段会话（已读、只一行），未读数不变
    _, boss_unread0 = inbox(boss_tok, 'staff')
    status, b = http('POST', '/app/chat/open', {'type': 'store', 'appId': apps['t4w1']}, app_token=boss_tok)
    conv = int(data(b).get('convId') or 0)
    # 线上任务的城市是「不限」，不写在后面（之前页面上和接待说明连成「不限线上任务接待」）
    check('T4 员工打开会话：顶栏是接单人昵称，下面写「线上任务 · 岗位」、不带「不限」', (200, w1_name, '线上任务 · ' + task_title),
          (code(b), data(b).get('title'), data(b).get('otherMeta')))
    # 2026-10-15 主人反馈（以为是接单人都在的群聊）：顶部写清楚是和谁的单独沟通、本店还有哪位接待同事也能看到（没设接待账号 = 本店全部在职员工）
    check('T4 员工打开会话：顶部说明写明是和这位接单人的单独沟通，点名也能看到的接待同事',
          '这是和 %s 的单独沟通，其他接单人看不到；本店接待 %s 也能看到并回复。' % (w1_name, ost_name), data(b).get('receptionNotice'))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '你好，作品收到了'}, app_token=boss_tok)
    check('T4 主理人发出第一条', 200, code(b))
    rows, boss_unread1 = inbox(boss_tok, 'staff', 'chat')
    mine = conv_rows(rows, conv)
    check('T4 主理人员工身份「私信」里有这段会话：一行、已读、标题「接单人昵称 · 任务名」、正文「我：…」',
          (1, True, w1_name + ' · ' + task_title, '我：你好，作品收到了'),
          (len(mine),) + ((mine[0].get('read'), mine[0].get('title'), mine[0].get('content')) if mine else (None, None, None)))
    check('T4 自己发的不算未读（员工身份未读数不变）', boss_unread0, boss_unread1)
    row_id = int(mine[0]['id']) if mine else 0

    # ---- 线上专员也联系同一位接单人：同一个会话；专员自己有一行已读；主理人那一行变未读、写明是哪位同事
    status, b = http('POST', '/app/chat/open', {'type': 'store', 'appId': apps['t4w1']}, app_token=ost_tok)
    check('T4 线上专员打开的是同一个会话', (200, conv), (code(b), int(data(b).get('convId') or 0)))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '我是线上专员，有问题找我'}, app_token=ost_tok)
    rows, _ = inbox(ost_tok, 'staff', 'chat')
    mine = conv_rows(rows, conv)
    check('T4 线上专员员工身份「私信」里也有这段会话（已读）', (200, 1, True, '我：我是线上专员，有问题找我'),
          (code(b), len(mine)) + ((mine[0].get('read'), mine[0].get('content')) if mine else (None, None)))
    rows, _ = inbox(boss_tok, 'staff', 'chat')
    mine = conv_rows(rows, conv)
    check('T4 同事发的：主理人那一行（还是同一行）变未读、正文写「同事昵称（接待）：」',
          (1, row_id, False, ost_name + '（接待）：我是线上专员，有问题找我'),
          (len(mine),) + ((int(mine[0]['id']), mine[0].get('read'), mine[0].get('content')) if mine else (None, None, None)))
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=boss_tok)
    rows, unread = inbox(boss_tok, 'staff', 'chat')
    check('T4 主理人进会话看过后这一行已读、未读数回到原样', (200, True, boss_unread0),
          (code(b), all(r.get('read') for r in conv_rows(rows, conv)), unread))

    # ---- 主理人再联系第二位接单人（新的一行，msg_id 比第一位那一行大），隔一秒后第一位接单人回复
    status, b = http('POST', '/app/chat/open', {'type': 'store', 'appId': apps['t4w2']}, app_token=boss_tok)
    conv2 = int(data(b).get('convId') or 0)
    http('POST', '/app/chat/%s/messages' % conv2, {'content': '第二位你好'}, app_token=boss_tok)
    check('T4 两位接单人是两个会话', True, conv2 not in (0, conv), ok=conv2 not in (0, conv))

    # ---- 接单人这边（用户身份）：一行门店名的未读
    rows, _ = inbox(w1_tok, 'user', 'chat')
    mine = conv_rows(rows, conv)
    # 接单人还没看过：主理人、专员各发了一条。正文只写最近一条，不再写「（2 条新消息）」（红点已经说明有新消息）
    check('T4 接单人用户身份「私信」：一行、标题是门店名、未读、正文是最近一条', (1, store_name, False, '我是线上专员，有问题找我'),
          (len(mine),) + ((mine[0].get('title'), mine[0].get('read'), mine[0].get('content')) if mine else (None, None, None)))
    check('T4 库里存的正文也没有「（N 条新消息）」', '我是线上专员，有问题找我',
          scalar("select content from kg_message where recipient_id=%s and mode='user' and biz_type='chat_conv' and biz_id=%s and del_flag='0' "
                 "order by msg_id desc limit 1" % (w1_id, conv)))
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=w1_tok)
    rows, _ = inbox(w1_tok, 'user', 'chat')
    mine = conv_rows(rows, conv)
    check('T4 接单人看过后：这一行已读、正文不变', (200, True, '我是线上专员，有问题找我'),
          (code(b),) + ((mine[0].get('read'), mine[0].get('content')) if mine else (None, None)))
    time.sleep(1.2)   # create_time 精确到秒：隔开一秒，排序只能靠「最近时间」而不是插入顺序
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '改好了，请再看看'}, app_token=w1_tok)
    check('T4 接单人回复', 200, code(b))
    rows, _ = inbox(w1_tok, 'user', 'chat')
    mine = conv_rows(rows, conv)
    check('T4 接单人自己那一行变成已读的「我：…」（还是一行）', (1, True, '我：改好了，请再看看'),
          (len(mine),) + ((mine[0].get('read'), mine[0].get('content')) if mine else (None, None)))

    # ---- 员工这边：同一行变未读、排在「全部」最前面（第二位接单人那一行 msg_id 更大也排在它后面）
    rows, unread = inbox(boss_tok, 'staff')
    check('T4 接单人回复后：主理人「全部」第一行就是这段会话、还是原来那一行、未读、正文是回复',
          (conv, row_id, False, '改好了，请再看看'),
          ((int(rows[0].get('bizId') or 0), int(rows[0]['id']), rows[0].get('read'), rows[0].get('content')) if rows else (None, None, None, None)))
    check('T4 第二位接单人的会话也在列表里（已读）', [True], [r.get('read') for r in conv_rows(rows, conv2)])
    status, b = http('GET', '/app/message/unread?mode=staff', app_token=boss_tok)
    check('T4 员工身份未读数 +1（按会话算）', (boss_unread0 + 1, boss_unread0 + 1), (unread, data(b).get('unread')))
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=boss_tok)
    rows, unread = inbox(boss_tok, 'staff', 'chat')
    check('T4 主理人看过后：这一行已读、未读数回到原样', (True, boss_unread0), (all(r.get('read') for r in conv_rows(rows, conv)), unread))
    check('T4 库里每人每个身份下这个会话只有一行', (1, 1, 1),
          tuple(n("select count(*) from kg_message where recipient_id=%s and mode='%s' and biz_type='chat_conv' and biz_id=%s and del_flag='0'" % (u, m, conv))
                for u, m in ((boss_id, 'staff'), (ost_id, 'staff'), (w1_id, 'user'))))
    status, b = http('GET', '/app/chat/%s' % conv, app_token=w1_tok)
    check('T4 接单人打开会话：顶栏仍是门店名、下面是任务名（不带「不限」）', (200, store_name, task_title),
          (code(b), data(b).get('title'), data(b).get('otherMeta')))
    check('T4 接单人这边的顶部说明：门店接待在这里回复、其他接单人看不到', '门店接待会在这里回复你，其他接单人看不到。',
          data(b).get('receptionNotice'))


# ---------------------------------------------------------------- main

def main():
    require_bundled_asset('/static/media/activity-cover.jpg')
    admin = admin_login()
    print('测试数据标识 RUN=%s TAG=%s' % (RUN, TAG))

    # ================================================================ C0 准备
    section('C0 准备：作者 A、读者 B、第三人 C、游客')
    a_tok, a_id = app_login('a')
    b_tok, b_id = app_login('b')
    c_tok, c_id = app_login('c')
    check('C0 三个新账号登录成功', True, len({a_id, b_id, c_id}) == 3, ok=len({a_id, b_id, c_id}) == 3)

    # ================================================================ C1 规则同意与手机号核验
    section('C1 规则同意（423 CONSENT → 同意 → 重试）与手机号核验（428）')
    b = publish(None, TAG + ' 游客发帖')
    check('C1 未登录发帖 → 401', 401, code(b))
    b = publish(a_tok, TAG + ' 未同意公约')
    check('C1 未同意《社区公约》发帖 → 423，msg 以 CONSENT:community: 开头', (423, True),
          (code(b), str(b.get('msg', '')).startswith('CONSENT:community:')))
    check('C1 被拦后库里没有这条动态', 0, n("select count(*) from kg_post where app_user_id=%s" % a_id))
    status, b = http('GET', '/app/community/rules/community')
    check('C1 游客可读《社区公约》全文（版本 2026-09）', (200, '《社区公约》', '2026-09'),
          (code(b), data(b).get('title'), data(b).get('version')))
    status, b = http('GET', '/app/community/rules/nonexistent')
    refused('C1 不存在的规则文档被拒', b)
    status, b = http('POST', '/app/community/consent', {'doc': 'nonexistent'}, app_token=a_tok)
    refused('C1 同意一个不存在的文档被拒', b)
    check('C1 被拒的同意没有落库', 0, n("select count(*) from kg_consent where app_user_id=%s" % a_id))
    status, b = http('POST', '/app/community/consent', {'doc': 'community'})
    check('C1 未登录同意规则 → 401', 401, code(b))
    consent(a_tok, 'community')
    consent(a_tok, 'community')
    check('C1 同意两次只记一条（版本 2026-09）', '1',
          scalar("select count(*) from kg_consent where app_user_id=%s and doc='community' and version='2026-09'" % a_id))
    status, b = http('GET', '/app/community/status', app_token=a_tok)
    check('C1 准入状态：已同意公约、未同意活动须知、手机号未核验', (True, False, False, 'mock'),
          (data(b).get('communityConsented'), data(b).get('activityConsented'), data(b).get('phoneVerified'), data(b).get('reviewMode')))
    # 发找搭子还要同意《找搭子安全须知》（2026-10-10 起，未同意时 423 CONSENT:buddy 见 C2b）
    consent(a_tok, 'buddy')
    b = publish(a_tok, TAG + ' 找搭子未验证', kind='buddy')
    check('C1 同意后发「找搭子」但手机号未核验 → 428', 428, code(b))
    phone_plain = new_phone()
    status, b = http('POST', '/app/auth/bind-phone', {'phone': phone_plain}, app_token=a_tok)
    check('C1 普通绑定手机号成功（非微信核验）', 200, code(b))
    b = publish(a_tok, TAG + ' 找搭子仅绑定', kind='buddy')
    check('C1 只绑定、未经微信核验仍 → 428', 428, code(b))
    status, b = http('POST', '/app/auth/phone-verify', {'code': 'not-a-code'}, app_token=a_tok)
    refused('C1 非法的手机号授权 code 被拒', b)
    check('C1 被拒后仍未核验', '1', scalar("select phone_verified_time is null from kg_app_user where user_id=%s" % a_id))
    a_phone = new_phone()
    status, b = http('POST', '/app/auth/phone-verify', {'code': 'mock-phone-' + a_phone}, app_token=a_tok)
    check('C1 微信手机号模拟授权成功', 200, code(b))
    r = sql("select phone, phone_verified_time is not null from kg_app_user where user_id=%s" % a_id)[0]
    check('C1 库里手机号 = 授权号码、核验时间已记录', (a_phone, '1'), (r[0], r[1]))
    status, b = http('GET', '/app/community/status', app_token=a_tok)
    check('C1 准入状态变为已核验', True, data(b).get('phoneVerified'))
    check('C1 前面被拦的几次都没有落库', 0, n("select count(*) from kg_post where app_user_id=%s" % a_id))

    # ================================================================ C2 发布与信息流
    section('C2 发布、审核方式与信息流可见性')
    b = publish(a_tok, TAG + ' 洱海边的清晨', '今天在洱海边骑行，风很舒服。', tags='骑行,洱海')
    p1 = int(data(b).get('postId') or 0)
    check('C2 普通动态发布：mock 审核方式下直接发布', (200, 'online'), (code(b), data(b).get('status')))
    check('C2 库里状态 online、审核方式 mock、作者正确', ('online', 'mock', str(a_id)), post_row(p1, 'status, review_mode, app_user_id'))
    check('C2 B 的信息流里看得到', True, has(feed_ids(b_tok), p1))
    check('C2 游客的信息流里看得到', True, has(feed_ids(None), p1))
    # 控制器约定：信息流、找搭子、帖子、评论、准入状态、活动列表 / 推荐 / 详情 游客可看（@AppAuth(required = false)）
    status, b = http('GET', '/app/community/posts/%s' % p1)
    check('C2 游客可看详情（作者 = A）', (200, a_id), (code(b), int((data(b).get('author') or {}).get('userId') or 0)))
    for path in ('/app/community/buddies', '/app/community/posts/%s/comments' % p1, '/app/community/status',
                 '/app/activities', '/app/activities/recommend'):
        status, b = http('GET', path)
        check('C2 游客访问 %s → 200' % path, 200, code(b))
    status, b = http('GET', '/app/community/posts/%s' % p1, app_token=a_tok)
    check('C2 登录用户看详情可用', 200, code(b))

    # 非法输入用另一个账号 V 发：发布接口按账号限流 10 次 / 10 分钟（被拒的也计数），全压在 A 身上会误触限流
    v_tok, v_id = app_login('v')
    consent(v_tok, 'community')
    consent(v_tok, 'buddy')
    verify_phone(v_tok)
    b = publish(v_tok, TAG + 'x' * 60)
    check('C2 标题超过 60 字 → 422', 422, code(b))
    b = publish(v_tok, TAG + ' 周末一起', '有没有人一起刷单赚钱')
    check('C2 命中拦截词 → 422（不回显命中的词）', (422, False), (code(b), '刷单' in str(b.get('msg'))))
    b = publish(v_tok, TAG + ' 类型不对', kind='nonsense')
    check('C2 非法类型 → 422', 422, code(b))
    b = publish(v_tok, TAG + ' 经验分享', kind='diary')
    check('C2 经验分享不关联已完成经历 → 422，提示里是新名字「经验分享」', (422, True, False),
          (code(b), '经验分享' in str(b.get('msg')), '义工日记' in str(b.get('msg'))))
    b = publish(v_tok, TAG + ' 冒充关联', refType='stay_order', refId=1)
    check('C2 关联不属于自己的订单 → 403', 403, code(b))
    b = publish(v_tok, TAG + ' 过去的日子', kind='buddy', eventDate=day(-2))
    check('C2 找搭子日期早于今天 → 422', 422, code(b))
    check('C2 以上被拒的都没有落库', 0, n("select count(*) from kg_post where app_user_id=%s" % v_id))

    b = publish(a_tok, TAG + ' 求拼车', '联系我 13912345678 一起走')
    p_rev = int(data(b).get('postId') or 0)
    check('C2 正文带手机号 → 转人工（pending）', (200, 'pending'), (code(b), data(b).get('status')))
    check('C2 待审核动态：B 的信息流看不到', False, has(feed_ids(b_tok), p_rev))
    check('C2 待审核动态：作者自己看得到', True, has(feed_ids(a_tok), p_rev))
    status, b = http('GET', '/app/community/posts/%s' % p_rev, app_token=b_tok)
    check('C2 待审核动态：B 打开详情 → 404', 404, code(b))
    rows = admin_list(admin, 'post', status='pending', title=TAG)
    check('C2 后台「社区动态」待审核列表里有这条', True, any(int(r.get('postId') or 0) == p_rev for r in rows),
          ok=any(int(r.get('postId') or 0) == p_rev for r in rows))
    status, b = http('POST', '/app/community/posts/%s/comments' % p_rev, {'content': '先评论'}, app_token=a_tok)
    check('C2 待审核动态不能评论 → 409', 409, code(b))
    status, b = http('PUT', '/kangaroo/community/post/%s/approve' % p_rev, token=admin)
    check('C2 后台通过', 200, code(b))
    check('C2 通过后库里 online、B 的信息流看得到', ('online', True), (post_row(p_rev, 'status')[0], has(feed_ids(b_tok), p_rev)))
    check('C2 作者收到「你的动态已通过审核」', 1, msgs(a_id, '你的动态已通过审核'))

    ev = day(5)
    b = publish(a_tok, TAG + ' 周末苍山徒步', '找两位一起走', kind='buddy', category='hike', eventDate=ev, eventDays='1天')
    p_buddy = int(data(b).get('postId') or 0)
    check('C2 手机号核验后发「找搭子」成功', (200, 'online'), (code(b), data(b).get('status')))
    status, b = http('GET', '/app/community/buddies?city=' + urllib_quote(CITY) + '&category=hike&date=' + ev, app_token=b_tok)
    got = [int(r['postId']) for r in data(b).get('rows', [])]
    check('C2 找搭子列表（按类目 + 日期筛）里有这条', True, p_buddy in got, ok=p_buddy in got)
    status, b = http('GET', '/app/community/posts/mine', app_token=a_tok)
    mine_ids = [int(r['postId']) for r in (b.get('data') or [])]
    check('C2 我的动态：列出 A 的 3 条', True, {p1, p_rev, p_buddy} <= set(mine_ids), ok={p1, p_rev, p_buddy} <= set(mine_ids))

    # ---------------- C2b 找搭子安全须知与同行性别（2026-10-10）：G 发「仅限女生」，B 男生点不开，H 女生能聊
    g_tok, g_id = app_login('g')
    consent(g_tok, 'community')
    verify_phone(g_tok)
    b = publish(g_tok, TAG + ' 女生徒步搭子', '周末一起走苍山', kind='buddy', category='hike', eventDate=ev, genderLimit='female')
    check('C2b 未同意《找搭子安全须知》发找搭子 → 423，msg 以 CONSENT:buddy: 开头', (423, True),
          (code(b), str(b.get('msg', '')).startswith('CONSENT:buddy:')))
    status, b = http('GET', '/app/community/rules/buddy')
    check('C2b 游客可读《找搭子安全须知》全文', (200, '《找搭子安全须知》', True),
          (code(b), data(b).get('title'), len(str(data(b).get('content') or '')) > 50))
    consent(g_tok, 'buddy')
    b = publish(g_tok, TAG + ' 性别乱填', kind='buddy', eventDate=ev, genderLimit='other')
    check('C2b 同行性别填了非法值 → 422', 422, code(b))
    b = publish(g_tok, TAG + ' 女生徒步搭子', '周末一起走苍山', kind='buddy', category='hike', eventDate=ev, genderLimit='female')
    p_girl = int(data(b).get('postId') or 0)
    check('C2b 发「仅限女生」的找搭子成功、库里 gender_limit=female', (200, 'online', ('female',)),
          (code(b), data(b).get('status'), post_row(p_girl, 'gender_limit')))
    base_q = '/app/community/buddies?city=' + urllib_quote(CITY) + '&date=' + ev
    status, b = http('GET', base_q + '&gender=female', app_token=b_tok)
    rows_f = {int(r['postId']): r for r in data(b).get('rows', [])}
    check('C2b 筛「女生可参加」：有仅限女生的、也有不限的', (True, True), (p_girl in rows_f, p_buddy in rows_f))
    check('C2b 列表带同行性别字段', ('female', '仅限女生', '', '不限'),
          (rows_f.get(p_girl, {}).get('genderLimit'), rows_f.get(p_girl, {}).get('genderLimitLabel'),
           rows_f.get(p_buddy, {}).get('genderLimit'), rows_f.get(p_buddy, {}).get('genderLimitLabel')))
    status, b = http('GET', base_q + '&gender=male', app_token=b_tok)
    rows_m = [int(r['postId']) for r in data(b).get('rows', [])]
    check('C2b 筛「男生可参加」：看不到仅限女生的、不限的还在', (False, True), (p_girl in rows_m, p_buddy in rows_m))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'postId': p_girl}, app_token=b_tok)
    check('C2b 没填性别点「认识一下」→ 422 提示先填性别', (422, True), (code(b), '填写性别' in str(b.get('msg'))))
    status, b = http('POST', '/app/me/profile', {'gender': '1'}, app_token=b_tok)
    check('C2b B 在个人资料填性别：男', 200, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'postId': p_girl}, app_token=b_tok)
    check('C2b 男生点「仅限女生」的认识一下 → 403', (403, '这条找搭子仅限女生参加'), (code(b), b.get('msg')))
    check('C2b 被拒后没有建会话', 0, n("select count(*) from kg_chat_conv where pair_key='%s'" % ('%d-%d' % (min(b_id, g_id), max(b_id, g_id)))))
    h_tok, h_id = app_login('h')
    consent(h_tok, 'community')
    status, b = http('POST', '/app/me/profile', {'gender': '2'}, app_token=h_tok)
    check('C2b H 在个人资料填性别：女', 200, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'postId': p_girl}, app_token=h_tok)
    check('C2b 女生点「仅限女生」的认识一下 → 能聊', 200, code(b))
    status, b = http('GET', '/app/community/refs', app_token=a_tok)
    check('C2 可关联对象接口可用（新账号为空）', (200, []), (code(b), b.get('data')))
    status, b = http('GET', '/app/community/experiences', app_token=a_tok)
    check('C2 已完成经历接口（换宿 / 兼职 / 全职）可用（新账号为空）', (200, []), (code(b), b.get('data')))

    # ================================================================ C3 点赞 / 收藏 / 评论
    section('C3 点赞 / 收藏 / 评论：计数与库一致')
    status, b = http('POST', '/app/community/posts/%s/like' % p1)
    check('C3 未登录点赞 → 401', 401, code(b))
    status, b = http('POST', '/app/community/posts/%s/like' % p1, app_token=b_tok)
    check('C3 B 点赞：liked=True、likes=1', (200, True, 1), (code(b), data(b).get('liked'), data(b).get('likes')))
    check('C3 库里 1 条点赞、计数 1', ('1', '1'),
          (scalar("select count(*) from kg_post_like where post_id=%s" % p1), post_row(p1, 'likes')[0]))
    status, b = http('POST', '/app/community/posts/%s/like' % p1, app_token=b_tok)
    check('C3 再点一次 = 取消：liked=False、likes=0', (False, 0), (data(b).get('liked'), data(b).get('likes')))
    check('C3 库里点赞行删掉、计数回 0', ('0', '0'),
          (scalar("select count(*) from kg_post_like where post_id=%s" % p1), post_row(p1, 'likes')[0]))
    http('POST', '/app/community/posts/%s/like' % p1, app_token=b_tok)
    with ThreadPoolExecutor(max_workers=4) as ex:
        list(ex.map(lambda _: http('POST', '/app/community/posts/%s/like' % p1, app_token=c_tok), range(4)))
    rows_cnt = n("select count(*) from kg_post_like where post_id=%s" % p1)
    check('C3 C 并发连点 4 次后：计数 = 点赞行数（不漂移）', rows_cnt, int(post_row(p1, 'likes')[0]))
    status, b = http('GET', '/app/community/posts/%s' % p1, app_token=b_tok)
    check('C3 B 看详情：liked=True', True, data(b).get('liked'))

    status, b = http('POST', '/app/community/posts/%s/favorite' % p1, app_token=b_tok)
    check('C3 B 收藏：favorited=True', (200, True), (code(b), data(b).get('favorited')))
    check('C3 库里收藏 1 条、计数 1', ('1', '1'),
          (scalar("select count(*) from kg_favorite where target_type='post' and target_id=%s" % p1), post_row(p1, 'favorites')[0]))
    status, b = http('GET', '/app/community/posts/%s' % p1, app_token=b_tok)
    check('C3 B 看详情：favorited=True', True, data(b).get('favorited'))
    status, b = http('POST', '/app/community/posts/%s/favorite' % p1, app_token=b_tok)
    check('C3 再点 = 取消收藏：库里 0 条、计数 0', (False, '0', '0'),
          (data(b).get('favorited'), scalar("select count(*) from kg_favorite where target_type='post' and target_id=%s" % p1),
           post_row(p1, 'favorites')[0]))
    status, b = http('POST', '/app/community/posts/99999999/favorite', app_token=b_tok)
    check('C3 收藏不存在的动态 → 404', 404, code(b))

    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '真好'}, app_token=b_tok)
    check('C3 B 未同意公约评论 → 423 CONSENT:community', (423, True), (code(b), str(b.get('msg')).startswith('CONSENT:community')))
    consent(b_tok, 'community')
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '   '}, app_token=b_tok)
    check('C3 空评论 → 422', 422, code(b))
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '风景真好'}, app_token=b_tok)
    cm1 = int(data(b).get('commentId') or 0)
    check('C3 同意后评论成功，直接可见', (200, 'visible'), (code(b), data(b).get('status')))
    check('C3 动态评论数 +1', '1', post_row(p1, 'comments')[0])
    # 2026-10-15 消息页改版：评论通知进「评论」分类，标题带评论人昵称，biz = 这条评论、记下评论人
    check('C3 作者收到「… 评论了你的动态」（评论分类、带评论人）', 1,
          n("select count(*) from kg_message where recipient_id=%s and category='comment' and biz_type='comment' and biz_id=%s "
            "and actor_id=%s and title like '%% 评论了你的动态'" % (a_id, cm1, b_id)))
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '加我 vx abcdefg 细聊'}, app_token=b_tok)
    cm_pend = int(data(b).get('commentId') or 0)
    check('C3 评论带联系方式 → 待审核，计数不变', ('pending', '1'), (data(b).get('status'), post_row(p1, 'comments')[0]))
    status, b = http('GET', '/app/community/posts/%s/comments' % p1, app_token=a_tok)
    ids_a = [int(c['commentId']) for c in data(b).get('rows', [])]
    status, b = http('GET', '/app/community/posts/%s/comments' % p1, app_token=b_tok)
    ids_b = [int(c['commentId']) for c in data(b).get('rows', [])]
    check('C3 待审核评论：作者看不到、评论人自己看得到', (True, False, True), (cm1 in ids_a, cm_pend in ids_a, cm_pend in ids_b))
    consent(a_tok, 'community')
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '谢谢喜欢', 'parentId': cm1}, app_token=a_tok)
    cm_reply = int(data(b).get('commentId') or 0)
    check('C3 作者回复 B 的评论', 200, code(b))
    check('C3 回复挂在 B 的评论下、回复对象 = B', (str(cm1), str(b_id)),
          tuple(sql("select parent_id, reply_user_id from kg_post_comment where comment_id=%s" % cm_reply)[0]))
    check('C3 B 收到「… 回复了你的评论」（评论分类、带回复人）', 1,
          n("select count(*) from kg_message where recipient_id=%s and category='comment' and biz_type='comment' and biz_id=%s "
            "and actor_id=%s and title like '%% 回复了你的评论'" % (b_id, cm_reply, a_id)))
    check('C3 作者回复别人在自己动态下的评论：作者自己不收通知', 0,
          n("select count(*) from kg_message where recipient_id=%s and biz_type='comment' and biz_id=%s" % (a_id, cm_reply)))
    status, b = http('GET', '/app/community/posts/%s/comments' % p1, app_token=b_tok)
    top = [c for c in data(b).get('rows', []) if int(c['commentId']) == cm1]
    check('C3 评论列表里回复嵌在楼中', [cm_reply], [int(r['commentId']) for r in (top[0]['replies'] if top else [])])
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '回复不存在', 'parentId': 99999999}, app_token=b_tok)
    check('C3 回复不存在的评论 → 404', 404, code(b))
    status, b = http('POST', '/app/community/comments/%s/like' % cm1, app_token=a_tok)
    check('C3 作者给 B 的评论点赞：库里计数 1', (200, True, '1'),
          (code(b), data(b).get('liked'), scalar("select likes from kg_post_comment where comment_id=%s" % cm1)))
    check('C3 B 收到一条「… 赞了你的评论」（点赞分类、不发微信订阅消息）', 1,
          n("select count(*) from kg_message where recipient_id=%s and category='like' and biz_type='comment_like' and biz_id=%s "
            "and actor_id=%s and read_flag='0' and del_flag='0' and title like '%% 赞了你的评论'" % (b_id, cm1, a_id)))
    status, b = http('POST', '/app/community/comments/%s/like' % cm_pend, app_token=a_tok)
    check('C3 待审核评论不能点赞 → 404', 404, code(b))
    status, b = http('DELETE', '/app/community/comments/%s' % cm1, app_token=a_tok)
    check('C3 作者删 B 的评论 → 403', 403, code(b))
    check('C3 越权删除后评论仍在', '0', scalar("select del_flag from kg_post_comment where comment_id=%s" % cm1))
    cnt_before = int(post_row(p1, 'comments')[0])
    status, b = http('DELETE', '/app/community/comments/%s' % cm_reply, app_token=a_tok)
    check('C3 作者删自己的回复：已删除、评论数 -1', (200, '2', cnt_before - 1),
          (code(b), scalar("select del_flag from kg_post_comment where comment_id=%s" % cm_reply), int(post_row(p1, 'comments')[0])))
    status, b = http('DELETE', '/app/community/comments/%s' % cm_reply, app_token=a_tok)
    check('C3 重复删除被拒、计数不再变', (True, cnt_before - 1), (code(b) != 200, int(post_row(p1, 'comments')[0])))

    # ================================================================ C4 删帖
    section('C4 删帖：只能删自己的')
    b = publish(a_tok, TAG + ' 临时动态', '一会儿就删')
    p_tmp = int(data(b).get('postId') or 0)
    status, b = http('DELETE', '/app/community/posts/%s' % p_tmp, app_token=b_tok)
    check('C4 B 删 A 的动态 → 403', 403, code(b))
    check('C4 越权删除后仍在信息流', ('0', True), (post_row(p_tmp, 'del_flag')[0], has(feed_ids(b_tok), p_tmp)))
    status, b = http('DELETE', '/app/community/posts/%s' % p_tmp)
    check('C4 未登录删除 → 401', 401, code(b))
    status, b = http('DELETE', '/app/community/posts/%s' % p_tmp, app_token=a_tok)
    check('C4 A 删自己的动态：库里 del_flag=2', (200, '2'), (code(b), post_row(p_tmp, 'del_flag')[0]))
    check('C4 删除后 A、B 的信息流都看不到', (False, False), (has(feed_ids(a_tok), p_tmp), has(feed_ids(b_tok), p_tmp)))
    status, b = http('GET', '/app/community/posts/%s' % p_tmp, app_token=a_tok)
    check('C4 删除后详情 → 404', 404, code(b))
    status, b = http('DELETE', '/app/community/posts/%s' % p_tmp, app_token=a_tok)
    check('C4 重复删除 → 403', 403, code(b))
    status, b = http('PUT', '/kangaroo/community/post/%s/approve' % p_tmp, token=admin)
    refused('C4 后台不能把用户已删除的动态「通过」回来', b)

    # ================================================================ C5 拉黑
    section('C5 拉黑：双向隐藏、互动与私信被拦、关注解除')
    consent(c_tok, 'community')
    b = publish(c_tok, TAG + ' C 的日常', '在喜洲吃破酥粑粑')
    p_c = int(data(b).get('postId') or 0)
    b = publish(b_tok, TAG + ' B 的日常', '双廊看日落')
    p_b = int(data(b).get('postId') or 0)
    check('C5 B、C 各发一条', (True, True), (p_c > 0, p_b > 0))
    status, b = http('POST', '/app/community/users/%s/follow' % b_id, app_token=c_tok)
    check('C5 C 关注 B', (200, True), (code(b), data(b).get('followed')))
    status, b = http('POST', '/app/community/users/%s/follow' % c_id, app_token=c_tok)
    check('C5 不能关注自己', True, code(b) != 200, ok=code(b) != 200)
    status, b = http('POST', '/app/community/users/%s/block' % c_id, app_token=b_tok)
    check('C5 B 拉黑 C', (200, True), (code(b), data(b).get('blocked')))
    check('C5 库里有拉黑记录，C→B 的关注被解除', ('1', '0'),
          (scalar("select count(*) from kg_user_block where user_id=%s and blocked_id=%s" % (b_id, c_id)),
           scalar("select count(*) from kg_user_follow where follower_id=%s and followee_id=%s" % (c_id, b_id))))
    check('C5 B 的信息流看不到 C 的动态', False, has(feed_ids(b_tok), p_c))
    check('C5 C 的信息流也看不到 B 的动态（双向）', False, has(feed_ids(c_tok), p_b))
    check('C5 A 仍然两条都看得到', (True, True), (has(feed_ids(a_tok), p_c), has(feed_ids(a_tok), p_b)))
    status, b = http('GET', '/app/community/posts/%s' % p_b, app_token=c_tok)
    check('C5 C 打开 B 的动态 → 404', 404, code(b))
    status, b = http('POST', '/app/community/posts/%s/comments' % p_b, {'content': '你好'}, app_token=c_tok)
    refused('C5 C 评论 B 的动态被拒', b)
    status, b = http('POST', '/app/community/posts/%s/like' % p_b, app_token=c_tok)
    refused('C5 C 点赞 B 的动态被拒', b)
    check('C5 被拒后 B 的动态没有 C 的评论 / 点赞', ('0', '0'),
          (scalar("select count(*) from kg_post_comment where post_id=%s and app_user_id=%s" % (p_b, c_id)),
           scalar("select count(*) from kg_post_like where post_id=%s and app_user_id=%s" % (p_b, c_id))))
    status, b = http('POST', '/app/community/users/%s/follow' % b_id, app_token=c_tok)
    check('C5 C 再关注 B → 403', 403, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': c_id}, app_token=b_tok)
    check('C5 B 给 C 发起私信 → 403', 403, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'postId': p_b}, app_token=c_tok)
    check('C5 C 从 B 的动态发起私信 → 403', 403, code(b))
    status, b = http('GET', '/app/community/posts/%s/comments' % p1, app_token=c_tok)
    names = [int(c['userId']) for c in data(b).get('rows', [])]
    check('C5 C 在第三方动态下看不到 B 的评论', False, b_id in names)
    status, b = http('POST', '/app/community/users/%s/block' % c_id, app_token=b_tok)
    check('C5 再点 = 解除拉黑，C 的动态回到 B 的信息流', (False, True), (data(b).get('blocked'), has(feed_ids(b_tok), p_c)))
    status, b = http('POST', '/app/community/users/%s/block' % b_id, app_token=b_tok)
    check('C5 不能拉黑自己', True, code(b) != 200, ok=code(b) != 200)

    # ================================================================ C6 举报
    section('C6 举报：去重、后台可见、判定成立后隐藏')
    b = publish(a_tok, TAG + ' 将被举报', '一条普通的内容')
    p_rep = int(data(b).get('postId') or 0)
    status, b = http('POST', '/app/community/reports', {'targetType': 'post', 'targetId': p_rep, 'reason': '垃圾广告 / 引流'})
    check('C6 未登录举报 → 401', 401, code(b))
    status, b = http('POST', '/app/community/reports', {'targetType': 'post', 'targetId': p_rep, 'reason': '垃圾广告 / 引流'}, app_token=b_tok)
    check('C6 B 举报 A 的动态', (200, '举报已提交，平台会在 24 小时内处理'), (code(b), b.get('msg')))
    rid = scalar("select report_id from kg_report where target_type='post' and target_id=%s and reporter_id=%s" % (p_rep, b_id))
    check('C6 库里一条待处理举报、动态被举报数 1', ('pending', '1'),
          (scalar("select status from kg_report where report_id=%s" % rid), post_row(p_rep, 'reported')[0]))
    status, b = http('POST', '/app/community/reports', {'targetType': 'post', 'targetId': p_rep, 'reason': '其他'}, app_token=b_tok)
    check('C6 重复举报：提示已举报、不重复记', ('你已经举报过了，平台正在处理', '1', '1'),
          (b.get('msg'), scalar("select count(*) from kg_report where target_type='post' and target_id=%s and reporter_id=%s" % (p_rep, b_id)),
           post_row(p_rep, 'reported')[0]))
    status, b = http('POST', '/app/community/reports', {'targetType': 'hack', 'targetId': p_rep}, app_token=b_tok)
    refused('C6 非法举报对象类型被拒', b)
    status, b = http('POST', '/app/community/reports', {'targetType': 'post', 'targetId': 987654321, 'reason': '其他'}, app_token=b_tok)
    check('C6 举报不存在的动态应被拒（不应产生孤儿举报）', True, code(b) != 200, ok=code(b) != 200)
    rows = admin_list(admin, 'report', targetType='post', status='pending')
    check('C6 后台「举报」待处理列表里有这条', True, any(str(r.get('reportId')) == str(rid) for r in rows),
          ok=any(str(r.get('reportId')) == str(rid) for r in rows))
    status, b = http('PUT', '/kangaroo/core/report/%s/uphold' % rid, {'reason': '回归：确认违规'}, token=admin)
    check('C6 后台判定举报成立', (200, '动态已隐藏'), (code(b), b.get('msg')))
    check('C6 举报已处理、动态已隐藏', ('upheld', 'hidden'),
          (scalar("select status from kg_report where report_id=%s" % rid), post_row(p_rep, 'status')[0]))
    check('C6 隐藏后 B、C 的信息流都看不到', (False, False), (has(feed_ids(b_tok), p_rep), has(feed_ids(c_tok), p_rep)))
    status, b = http('GET', '/app/community/posts/mine', app_token=a_tok)
    st = [r.get('status') for r in (b.get('data') or []) if int(r['postId']) == p_rep]
    check('C6 作者在「我的动态」里看到已隐藏', ['hidden'], st)
    status, b = http('PUT', '/kangaroo/core/report/%s/uphold' % rid, {}, token=admin)
    refused('C6 同一条举报不能重复处理', b)
    # 评论举报：成立 = 隐藏评论，动态评论数随之减少
    status, b = http('POST', '/app/community/posts/%s/comments' % p1, {'content': '一条会被举报的评论'}, app_token=c_tok)
    cm_c = int(data(b).get('commentId') or 0)
    cnt = int(post_row(p1, 'comments')[0])
    http('POST', '/app/community/reports', {'targetType': 'comment', 'targetId': cm_c, 'reason': '辱骂攻击'}, app_token=b_tok)
    rid2 = scalar("select report_id from kg_report where target_type='comment' and target_id=%s and reporter_id=%s" % (cm_c, b_id))
    status, b = http('PUT', '/kangaroo/core/report/%s/uphold' % rid2, {}, token=admin)
    check('C6 评论举报成立：评论隐藏、动态评论数 -1', (200, 'hidden', cnt - 1),
          (code(b), scalar("select status from kg_post_comment where comment_id=%s" % cm_c), int(post_row(p1, 'comments')[0])))
    # 举报不成立：内容不动
    http('POST', '/app/community/reports', {'targetType': 'post', 'targetId': p1, 'reason': '其他'}, app_token=c_tok)
    rid3 = scalar("select report_id from kg_report where target_type='post' and target_id=%s and reporter_id=%s" % (p1, c_id))
    status, b = http('PUT', '/kangaroo/core/report/%s/reject' % rid3, {'reason': '回归：不成立'}, token=admin)
    check('C6 举报不成立：举报关闭、动态仍在线', (200, 'rejected', 'online'),
          (code(b), scalar("select status from kg_report where report_id=%s" % rid3), post_row(p1, 'status')[0]))

    # ================================================================ C7 后台审核动作
    section('C7 后台审核动作在小程序侧生效')
    b = publish(a_tok, TAG + ' 待驳回', '电话 13987654321')
    p_rej = int(data(b).get('postId') or 0)
    status, b = http('PUT', '/kangaroo/community/post/%s/reject' % p_rej, {}, token=admin)
    refused('C7 驳回不填原因被拒', b)
    check('C7 未填原因时状态不变', 'pending', post_row(p_rej, 'status')[0])
    status, b = http('PUT', '/kangaroo/community/post/%s/reject' % p_rej, {'reason': '含联系方式'}, token=admin)
    check('C7 驳回待审核动态 → rejected + 原因', (200, 'rejected', '含联系方式'), (code(b),) + post_row(p_rej, 'status, audit_reason'))
    status, b = http('GET', '/app/community/posts/mine', app_token=a_tok)
    rr = [(r.get('status'), r.get('auditReason')) for r in (b.get('data') or []) if int(r['postId']) == p_rej]
    check('C7 作者在「我的动态」看到驳回原因', [('rejected', '含联系方式')], rr)
    check('C7 作者收到「你的动态未通过审核」', 1, msgs(a_id, '你的动态未通过审核'))
    status, b = http('PUT', '/kangaroo/community/post/%s/reject' % p1, {'reason': '回归：隐藏'}, token=admin)
    check('C7 隐藏已发布动态 → hidden，信息流消失', (200, 'hidden', False), (code(b), post_row(p1, 'status')[0], has(feed_ids(b_tok), p1)))
    status, b = http('PUT', '/kangaroo/community/post/%s/approve' % p1, token=admin)
    check('C7 恢复（通过）→ online，信息流回来', (200, 'online', True), (code(b), post_row(p1, 'status')[0], has(feed_ids(b_tok), p1)))
    before_msgs = msgs(a_id, '你的动态已通过审核')
    status, b = http('PUT', '/kangaroo/community/post/%s/approve' % p1, token=admin)
    check('C7 对已发布的动态重复「通过」应被拒（不重复通知作者）', (True, before_msgs), (code(b) != 200, msgs(a_id, '你的动态已通过审核')))
    status, b = http('PUT', '/kangaroo/community/comment/%s/hide' % cm1, {}, token=admin)
    refused('C7 隐藏评论不填原因被拒', b)
    cnt = int(post_row(p1, 'comments')[0])
    status, b = http('PUT', '/kangaroo/community/comment/%s/hide' % cm1, {'reason': '回归'}, token=admin)
    status2, b2 = http('GET', '/app/community/posts/%s/comments' % p1, app_token=a_tok)
    check('C7 隐藏评论：库里 hidden、计数 -1、作者看不到', (200, 'hidden', cnt - 1, False),
          (code(b), scalar("select status from kg_post_comment where comment_id=%s" % cm1), int(post_row(p1, 'comments')[0]),
           cm1 in [int(c['commentId']) for c in data(b2).get('rows', [])]))
    status, b = http('PUT', '/kangaroo/community/comment/%s/show' % cm1, token=admin)
    check('C7 恢复评论：visible、计数 +1', (200, 'visible', cnt), (code(b), scalar("select status from kg_post_comment where comment_id=%s" % cm1),
                                                              int(post_row(p1, 'comments')[0])))
    status, b = http('PUT', '/kangaroo/community/comment/%s/show' % cm_pend, token=admin)
    check('C7 通过待审核评论：visible、计数 +1', ('visible', cnt + 1),
          (scalar("select status from kg_post_comment where comment_id=%s" % cm_pend), int(post_row(p1, 'comments')[0])))
    status, b = http('PUT', '/kangaroo/community/post/%s/approve' % p1, app_token=a_tok)
    check('C7 小程序令牌调后台审核 → 401', 401, code(b))

    # ================================================================ A0 活动准备
    section('A0 活动准备：主理人 / 别家主理人 / 普通员工 / 参与者')
    row = sql("select store_id, city from kg_store where del_flag='0' and status='0' and op_type='direct' order by store_id limit 2")
    assert len(row) >= 2, '需要两家直营门店'
    store_id, store_city = int(row[0][0]), row[0][1]
    other_store = int(row[1][0])
    _, mgr_id = app_login('mgr')
    _, far_id = app_login('far')
    _, stf_id = app_login('stf')
    grant(admin, mgr_id, store_id, 'manager')
    grant(admin, far_id, other_store, 'manager')
    grant(admin, stf_id, store_id, 'staff')
    mgr_tok, far_tok, stf_tok = app_login('mgr')[0], app_login('far')[0], app_login('stf')[0]
    ptok, pid = {}, {}
    for k in range(1, 10):
        ptok[k], pid[k] = app_login('p%d' % k)
    check('A0 准备完成（门店 %s / 别家门店 %s）' % (store_id, other_store), True, True)

    # ================================================================ A1 员工创建活动 → 审核 → 可见
    section('A1 员工创建活动 → 待审核 → 后台通过 → 用户可见')
    b = staff_create(ptok[1], store_id, TAG + '越权创建')
    check('A1 非员工调员工端创建 → 403', 403, code(b))
    b = staff_create(far_tok, store_id, TAG + '别店创建')
    check('A1 别家门店主理人按本店 storeId 创建 → 403', 403, code(b))
    b = staff_create(mgr_tok, store_id, TAG + '标题' + 'x' * 30)
    check('A1 标题超过 30 字 → 422', 422, code(b))
    b = staff_create(mgr_tok, store_id, TAG + '零人', capacity=0)
    check('A1 名额 0 → 422', 422, code(b))
    b = staff_create(mgr_tok, store_id, TAG + '过去', offset=-1)
    check('A1 开始时间已过 → 422', 422, code(b))
    before = n("select count(*) from kg_activity where title like '%s%%'" % TAG)
    check('A1 以上被拒的都没有落库', 0, before)

    b = staff_create(mgr_tok, store_id, TAG + '扎染体验', capacity=2, intro='周末一起做扎染，有问题加微信问我')
    act_free = int(data(b).get('activityId') or 0)
    check('A1 介绍里带「加微信」→ 待平台审核', (200, 'pending'), (code(b), data(b).get('status')))
    r = sql("select source, store_id, fee_mode, fee_fen, status from kg_activity where activity_id=%s" % act_free)[0]
    check('A1 直营门店 = 小院活动、免费', ('yard', str(store_id), 'free', '0', 'pending'), tuple(r))
    status, b = http('GET', '/app/activities?city=' + urllib_quote(store_city), app_token=ptok[1])
    ids = [int(i['activityId']) for g in data(b).get('groups', []) for i in g.get('items', [])]
    check('A1 待审核活动不在用户列表里', False, act_free in ids)
    status, b = http('GET', '/app/activities/%s' % act_free, app_token=ptok[1])
    check('A1 用户打开待审核活动 → 404', 404, code(b))
    b = signup(ptok[1], act_free)
    consent(ptok[1], 'activity')
    b = signup(ptok[1], act_free)
    check('A1 待审核活动不能报名 → 409', 409, code(b))
    status, b = http('GET', '/app/staff/activities?storeId=%s&tab=all' % store_id, app_token=mgr_tok)
    mine_rows = [x for x in data(b).get('rows', []) if int(x['activityId']) == act_free]
    check('A1 员工端列表里有、审核标记为「待审核」', ['待审核'], [x.get('audit') for x in mine_rows])
    rows = admin_list(admin, 'activity', status='pending', title=TAG)
    check('A1 后台「社区活动」待审核列表里有', True, any(int(x['activityId']) == act_free for x in rows),
          ok=any(int(x['activityId']) == act_free for x in rows))
    status, b = http('PUT', '/kangaroo/community/activity/%s/approve' % act_free, token=admin)
    check('A1 后台通过 → open，活动群已建', (200, 'open', True),
          (code(b), act_counts(act_free)[3], scalar("select conv_id from kg_activity where activity_id=%s" % act_free) not in (None, 'NULL')))
    check('A1 门店员工收到「活动已通过审核，开始报名」', 1, msgs(mgr_id, '活动已通过审核，开始报名', 'staff'))
    status, b = http('PUT', '/kangaroo/community/activity/%s/approve' % act_free, token=admin)
    refused('A1 重复审核被拒', b)
    status, b = http('GET', '/app/activities?city=' + urllib_quote(store_city), app_token=ptok[1])
    ids = [int(i['activityId']) for g in data(b).get('groups', []) for i in g.get('items', [])]
    check('A1 通过后用户列表里看得到', True, act_free in ids)
    status, b = http('GET', '/app/activities/%s?channel=community' % act_free)
    check('A1 游客可看详情（报名中、免费）', (200, 'open', '免费'), (code(b), data(b).get('phase'), data(b).get('priceText')))

    b = staff_create(mgr_tok, store_id, TAG + '驳回用', intro='先付定金再说')
    act_rej = int(data(b).get('activityId') or 0)
    status, b = http('PUT', '/kangaroo/community/activity/%s/reject' % act_rej, {}, token=admin)
    refused('A1 驳回不填原因被拒', b)
    status, b = http('PUT', '/kangaroo/community/activity/%s/reject' % act_rej, {'reason': '回归：信息不全'}, token=admin)
    check('A1 驳回 → rejected + 原因', (200, 'rejected', '回归：信息不全'),
          (code(b), act_counts(act_rej)[3], scalar("select audit_reason from kg_activity where activity_id=%s" % act_rej)))

    b = staff_create(mgr_tok, store_id, TAG + '付费咖啡课', capacity=1, fee='0.5')
    act_paid = int(data(b).get('activityId') or 0)
    check('A1 付费小院活动直接发布（内容通过）', (200, 'open'), (code(b), data(b).get('status')))
    check('A1 费用 0.5 元 = 50 分、在线支付', ('50', 'online'),
          tuple(sql("select fee_fen, fee_mode from kg_activity where activity_id=%s" % act_paid)[0]))
    status, b = http('PUT', '/app/staff/activities/%s' % act_paid, {'title': 'x'}, app_token=far_tok)
    check('A1 别家门店主理人改本店活动 → 403', 403, code(b))
    status, b = http('GET', '/app/staff/activities/%s' % act_paid, app_token=far_tok)
    check('A1 别家门店主理人看本店活动详情 → 403', 403, code(b))
    status, b = http('GET', '/app/staff/activities?storeId=%s' % store_id, app_token=far_tok)
    check('A1 别家门店主理人按本店 storeId 查列表 → 403', 403, code(b))

    # ================================================================ A2 免费活动报名
    section('A2 免费活动：名额、候补、重复 / 并发报名、取消递补')
    b = signup(None, act_free)
    check('A2 未登录报名 → 401', 401, code(b))
    b = signup(ptok[2], act_free)
    check('A2 未同意《活动参与须知》→ 423 CONSENT:activity', (423, True), (code(b), str(b.get('msg')).startswith('CONSENT:activity')))
    check('A2 被拦时没有报名记录', 0, n("select count(*) from kg_activity_signup where activity_id=%s and app_user_id=%s" % (act_free, pid[2])))
    for k in range(2, 10):
        consent(ptok[k], 'activity')
    b = signup(ptok[1], act_free)
    s1 = int(data(b).get('signupId') or 0)
    check('A2 P1 报名免费活动 → confirmed', (200, 'confirmed'), (code(b), data(b).get('status')))
    check('A2 P1 进了活动群、收到「活动报名成功」、门店收到新报名提醒', (True, 1, True),
          (n("select count(*) from kg_chat_member m join kg_activity a on a.conv_id=m.conv_id where a.activity_id=%s and m.app_user_id=%s and m.left_time is null"
             % (act_free, pid[1])) == 1, msgs(pid[1], '活动报名成功'), msgs(mgr_id, '活动有新报名', 'staff') >= 1))
    b = signup(ptok[1], act_free)
    check('A2 P1 重复报名 → 409，只有一条记录、计数不变', (409, 1, 1),
          (code(b), n("select count(*) from kg_activity_signup where activity_id=%s and app_user_id=%s" % (act_free, pid[1])), act_counts(act_free)[0]))
    with ThreadPoolExecutor(max_workers=3) as ex:
        res = list(ex.map(lambda _: signup(ptok[2], act_free), range(3)))
    check('A2 P2 并发 3 次报名：只成功 1 次', 1, sum(1 for x in res if code(x) == 200))
    check('A2 P2 只有一条报名记录、已报名数 = 2（满）', (1, 2),
          (n("select count(*) from kg_activity_signup where activity_id=%s and app_user_id=%s" % (act_free, pid[2])), act_counts(act_free)[0]))
    b = signup(ptok[3], act_free)
    s3 = int(data(b).get('signupId') or 0)
    check('A2 满员后 P3 → 候补第 1 位', (200, 'waitlist', '1'), (code(b), data(b).get('status'), signup_row(s3, 'waitlist_no')))
    b = signup(ptok[4], act_free)
    s4 = int(data(b).get('signupId') or 0)
    check('A2 P4 → 候补第 2 位', ('waitlist', '2'), (data(b).get('status'), signup_row(s4, 'waitlist_no')))
    check_counters('A2 满员 + 两人候补', act_free)
    status, b = http('GET', '/app/activities/%s' % act_free, app_token=ptok[3])
    check('A2 详情：已满、我的状态 = 候补中', (True, 'waitlist'), (data(b).get('full'), data(b).get('myStatus')))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s1, {'reason': '越权'}, app_token=ptok[2])
    check('A2 别人取消 P1 的报名 → 404', 404, code(b))
    status, b = http('GET', '/app/activities/signups/%s' % s1, app_token=ptok[2])
    check('A2 别人看 P1 的报名凭证 → 404', 404, code(b))
    check('A2 越权后 P1 仍已确认', 'confirmed', signup_row(s1))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s1, {'reason': '临时有事'}, app_token=ptok[1])
    check('A2 P1 取消 → cancelled', (200, 'cancelled'), (code(b), signup_row(s1)))
    check('A2 候补第 1 位 P3 自动转正', 'confirmed', signup_row(s3))
    check('A2 递补后：已报名仍 2、候补剩 1', (2, 1), act_counts(act_free)[:2])
    check_counters('A2 取消递补后', act_free)
    check('A2 P1 离开活动群、P3 进群', (1, 1),
          (n("select count(*) from kg_chat_member m join kg_activity a on a.conv_id=m.conv_id where a.activity_id=%s and m.app_user_id=%s and m.left_time is not null" % (act_free, pid[1])),
           n("select count(*) from kg_chat_member m join kg_activity a on a.conv_id=m.conv_id where a.activity_id=%s and m.app_user_id=%s and m.left_time is null" % (act_free, pid[3]))))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s1, {}, app_token=ptok[1])
    check('A2 重复取消 → 409', 409, code(b))
    b = signup(ptok[1], act_free)
    check('A2 P1 取消后重新报名 → 满员排候补（复用原记录）', ('waitlist', 1),
          (data(b).get('status'), n("select count(*) from kg_activity_signup where activity_id=%s and app_user_id=%s" % (act_free, pid[1]))))
    wl = [r[0] for r in sql("select waitlist_no from kg_activity_signup where activity_id=%s and status='waitlist'" % act_free)]
    check('A2 当前候补的排位号不重复（%s）' % wl, len(wl), len(set(wl)))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s4, {}, app_token=ptok[4])
    check('A2 P4 取消候补 → cancelled，候补数 -1', (200, 'cancelled'), (code(b), signup_row(s4)))
    check_counters('A2 取消候补后', act_free)
    status, b = http('GET', '/app/activities/signups/%s' % s3, app_token=ptok[3])
    t3 = data(b)
    check('A2 P3 的凭证：已确认、6 位签到码、带活动群', (200, 'confirmed', True, True),
          (code(b), t3.get('status'), bool(str(t3.get('checkinCode') or '').isdigit() and len(str(t3.get('checkinCode'))) == 6),
           bool(t3.get('convId'))))
    status, b = http('PUT', '/app/activities/signups/%s/remind' % s3, {'remind': '2h'}, app_token=ptok[3])
    check('A2 设置集合提醒 2h 落库', (200, '2h'), (code(b), signup_row(s3, 'remind')))
    status, b = http('PUT', '/app/activities/signups/%s/remind' % s3, {'remind': 'off'}, app_token=ptok[1])
    check('A2 别人改我的提醒 → 404，值不变', (404, '2h'), (code(b), signup_row(s3, 'remind')))
    status, b = http('GET', '/app/activities/mine?tab=joined', app_token=ptok[3])
    got = [(int(x['activityId']), x.get('signupStatus')) for x in (b.get('data') or [])]
    check('A2 P3「已报名」里有这场（confirmed）', True, (act_free, 'confirmed') in got, ok=(act_free, 'confirmed') in got)
    status, b = http('GET', '/app/activities/mine?tab=done', app_token=ptok[4])
    got = [int(x['activityId']) for x in (b.get('data') or [])]
    check('A2 P4「已完成」里有已取消的这场', True, act_free in got, ok=act_free in got)
    status, b = http('POST', '/app/activities/%s/favorite' % act_free, app_token=ptok[4])
    check('A2 收藏活动落库', (200, True, 1), (code(b), data(b).get('favorited'),
                                        n("select count(*) from kg_favorite where target_type='activity' and target_id=%s and app_user_id=%s" % (act_free, pid[4]))))
    status, b = http('POST', '/app/activities/subscribe', {'city': store_city, 'on': True}, app_token=ptok[4])
    check('A2 订阅本城新活动落库', (200, 1), (code(b), n("select count(*) from kg_activity_subscribe where app_user_id=%s" % pid[4])))
    status, b = http('GET', '/app/activities/recommend?city=' + urllib_quote(store_city), app_token=ptok[4])
    check('A2 为你推荐接口可用', 200, code(b))
    status, b = http('GET', '/app/activities/hots?city=' + urllib_quote(store_city))
    check('A2 热门活动接口可用', 200, code(b))

    # ================================================================ A3 签到
    section('A3 员工按签到码签到')
    code3 = str(t3.get('checkinCode'))
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': code3}, app_token=ptok[3])
    check('A3 普通用户调员工签到 → 403', 403, code(b))
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': code3}, app_token=far_tok)
    check('A3 别家门店主理人签到 → 403', 403, code(b))
    check('A3 越权后未签到', True, signup_row(s3, 'checkin_time') in (None, 'NULL'), ok=signup_row(s3, 'checkin_time') in (None, 'NULL'))
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': '12ab'}, app_token=stf_tok)
    check('A3 签到码格式不对 → 422', 422, code(b))
    wl_code = scalar("select checkin_code from kg_activity_signup where activity_id=%s and app_user_id=%s" % (act_free, pid[1]))
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': wl_code}, app_token=stf_tok)
    check('A3 候补中的签到码 → 404（未确认不能签）', 404, code(b))
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': code3}, app_token=stf_tok)
    check('A3 本店员工按 P3 的签到码签到成功', (200, True), (code(b), signup_row(s3, 'checkin_time') not in (None, 'NULL')))
    t_first = signup_row(s3, 'checkin_time')
    status, b = http('POST', '/app/staff/activities/%s/checkin' % act_free, {'code': code3}, app_token=mgr_tok)
    check('A3 重复签到：提示已签到过、签到时间不变', (200, True, t_first), (code(b), '已签到过' in str(b.get('msg')), signup_row(s3, 'checkin_time')))
    status, b = http('GET', '/app/staff/activities/%s' % act_free, app_token=mgr_tok)
    check('A3 员工端详情：已签到 1、候补 1、名单与库一致', (1, 1, 2),
          (data(b).get('checkedIn'), data(b).get('waitlist'), len(data(b).get('confirmed') or [])))
    status, b = http('GET', '/app/staff/activities/%s/members/%s/phone' % (act_free, s3), app_token=mgr_tok)
    check('A3 活动前 24 小时窗口外查看电话 → 403', 403, code(b))
    status, b = http('GET', '/app/staff/activities/%s/data' % act_free, app_token=mgr_tok)
    check('A3 报名数据：已确认 / 候补 与库一致', act_db_counts(act_free), (data(b).get('confirmed'), data(b).get('waitlist')))

    # ================================================================ A4 付费活动
    section('A4 付费活动：待支付 → 支付 → 取消转退款待审批 → 审批 → 支付单对账')
    b = signup(ptok[5], act_paid)
    s5, pay5 = int(data(b).get('signupId') or 0), data(b).get('payNo')
    check('A4 P5 报名付费活动 → 待支付并生成支付单', (200, 'pending_payment', True), (code(b), data(b).get('status'), bool(pay5)))
    check('A4 支付单金额 50、业务类型 activity_signup、待支付', ('50', 'activity_signup', 'pending', str(s5)),
          tuple(sql("select amount, biz_type, status, biz_id from kg_pay_order where pay_no='%s'" % pay5)[0]))
    check('A4 待支付占名额（1/1）', 1, act_counts(act_paid)[0])
    b = signup(ptok[6], act_paid)
    s6 = int(data(b).get('signupId') or 0)
    check('A4 P6 → 候补，不生成支付单', ('waitlist', None), (data(b).get('status'), data(b).get('payNo')))
    status, b = http('POST', '/app/pay/%s/mock-confirm' % pay5, {}, app_token=ptok[6])
    refused('A4 别人不能替 P5 确认支付', b)
    check('A4 越权后仍待支付', ('pending_payment', 'pending'), (signup_row(s5), scalar("select status from kg_pay_order where pay_no='%s'" % pay5)))
    check('A4 P5 拉起支付并确认到账', True, pay(ptok[5], pay5))
    check('A4 报名已确认、支付单已支付、有支付时间', ('confirmed', 'paid', True),
          (signup_row(s5), scalar("select status from kg_pay_order where pay_no='%s'" % pay5), signup_row(s5, 'paid_time') not in (None, 'NULL')))
    status, b = http('POST', '/app/pay/%s/mock-confirm' % pay5, {}, app_token=ptok[5])
    refused('A4 重复确认到账被拒', b)
    status, b = http('GET', '/app/activities/signups/%s' % s5, app_token=ptok[5])
    check('A4 凭证：可退款（开始前 24 小时以上）', (True, True), (data(b).get('refundable'), data(b).get('canCancel')))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s5, {'reason': '行程变动'}, app_token=ptok[5])
    check('A4 P5 取消 → 退款待审批、退款额 = 实付 50', (200, 'refund_pending', '50'), (code(b),) + signup_row(s5, 'status, refund_fen'))
    check('A4 门店员工收到「活动退款待审批」', True, msgs(mgr_id, '活动退款待审批', 'staff') >= 1, ok=msgs(mgr_id, '活动退款待审批', 'staff') >= 1)
    r6 = sql("select status, pay_no, fee_fen from kg_activity_signup where signup_id=%s" % s6)[0]
    check('A4 候补 P6 递补为待支付并生成 50 分的新支付单', ('pending_payment', True, '50'),
          (r6[0], r6[1] not in (None, 'NULL'), scalar("select amount from kg_pay_order where pay_no='%s'" % r6[1]) if r6[1] not in (None, 'NULL') else None))
    check('A4 P6 收到「候补转正，请 30 分钟内支付」', 1, msgs(pid[6], '候补转正，请 30 分钟内支付'))
    check_counters('A4 取消转退款 + 递补后', act_paid)
    status, b = http('GET', '/app/staff/activities/%s' % act_paid, app_token=mgr_tok)
    check('A4 员工端详情的退款待审批里有 P5', [s5], [int(x['signupId']) for x in (data(b).get('refunds') or [])])

    approve5 = '/app/staff/activities/%s/refunds/%s/approve' % (act_paid, s5)
    status, b = http('POST', approve5, {}, app_token=stf_tok)
    check('A4 普通员工批退款 → 403（无审核权限）', 403, code(b))
    status, b = http('POST', approve5, {}, app_token=far_tok)
    check('A4 别家门店主理人批退款 → 403', 403, code(b))
    status, b = http('POST', approve5, {}, app_token=ptok[5])
    check('A4 用户自己批退款 → 403', 403, code(b))
    status, b = http('POST', '/app/staff/activities/%s/refunds/%s/approve' % (act_free, s5), {}, app_token=mgr_tok)
    check('A4 报名不属于该活动 → 404', 404, code(b))
    check('A4 越权后仍待审批、支付单未退', ('refund_pending', '0'),
          (signup_row(s5), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay5)))
    status, b = http('POST', approve5, {}, app_token=mgr_tok)
    check('A4 本店主理人同意退款', 200, code(b))
    check('A4 报名已退款、支付单退款 50 且状态 refunded', ('refunded', '50', 'refunded'),
          (signup_row(s5), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay5),
           scalar("select status from kg_pay_order where pay_no='%s'" % pay5)))
    check('A4 用户收到「活动报名费已退款」', 1, msgs(pid[5], '活动报名费已退款'))
    status, b = http('POST', approve5, {}, app_token=mgr_tok)
    check('A4 重复审批被拒、不重复退款', (409, '50'), (code(b), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay5)))

    pay6 = scalar("select pay_no from kg_activity_signup where signup_id=%s" % s6)
    check('A4 P6 支付递补名额', True, pay(ptok[6], pay6))
    check('A4 P6 已确认', 'confirmed', signup_row(s6))
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s6, {}, app_token=ptok[6])
    check('A4 P6 取消 → 退款待审批', 'refund_pending', signup_row(s6))
    status, b = http('PUT', '/kangaroo/community/signup/%s/refund-approve' % s6, token=admin)
    check('A4 后台同意退款：报名已退款、支付单退 50', (200, 'refunded', '50'),
          (code(b), signup_row(s6), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay6)))
    status, b = http('PUT', '/kangaroo/community/signup/%s/refund-approve' % s6, token=admin)
    check('A4 后台重复同意被拒、不重复退款', (True, '50'), (code(b) != 200, scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay6)))

    b = signup(ptok[7], act_paid)
    s7, pay7 = int(data(b).get('signupId') or 0), data(b).get('payNo')
    pay(ptok[7], pay7)
    http('POST', '/app/activities/signups/%s/cancel' % s7, {}, app_token=ptok[7])
    check('A4 P7 付款后取消 → 退款待审批', 'refund_pending', signup_row(s7))
    status, b = http('POST', '/app/staff/activities/%s/refunds/%s/reject' % (act_paid, s7), {}, app_token=mgr_tok)
    check('A4 驳回退款不填原因 → 422', 422, code(b))
    status, b = http('POST', '/app/staff/activities/%s/refunds/%s/reject' % (act_paid, s7), {'reason': '回归：临近开始'}, app_token=mgr_tok)
    check('A4 主理人驳回：报名已取消、退款额归 0、支付单未退', (200, 'cancelled', '0', '0'),
          (code(b),) + signup_row(s7, 'status, refund_fen') + (scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay7),))
    check('A4 用户收到「活动退款未通过」', 1, msgs(pid[7], '活动退款未通过'))

    b = signup(ptok[8], act_paid)
    s8, pay8 = int(data(b).get('signupId') or 0), data(b).get('payNo')
    status, b = http('POST', '/app/activities/signups/%s/cancel' % s8, {}, app_token=ptok[8])
    check('A4 待支付时取消：报名取消、支付单关闭、名额释放', (200, 'cancelled', 'closed', 0),
          (code(b), signup_row(s8), scalar("select status from kg_pay_order where pay_no='%s'" % pay8), act_counts(act_paid)[0]))
    check('A4 关闭后的支付单不能再付', False, pay(ptok[8], pay8))
    check_counters('A4 付费活动收尾', act_paid)

    # 后台取消一场有已付款 / 待支付报名的活动：已付款的转退款待审批（退款额 = 实付），待支付的关单
    b = staff_create(mgr_tok, store_id, TAG + '后台取消付费', capacity=5, fee='1')
    act_p2 = int(data(b).get('activityId') or 0)
    b = signup(ptok[7], act_p2)
    s7b, pay7b = int(data(b).get('signupId') or 0), data(b).get('payNo')
    pay(ptok[7], pay7b)
    b = signup(ptok[8], act_p2)
    s8b, pay8b = int(data(b).get('signupId') or 0), data(b).get('payNo')
    status, b = http('PUT', '/kangaroo/community/activity/%s/cancel' % act_p2, {}, token=admin)
    refused('A4 后台取消活动不填原因被拒', b)
    status, b = http('PUT', '/kangaroo/community/activity/%s/cancel' % act_p2, {'reason': '回归：场地临时不可用'}, token=admin)
    check('A4 后台取消付费活动：活动 cancelled、计数归零', (200, 'cancelled', 0, 0), (code(b),) + (act_counts(act_p2)[3], act_counts(act_p2)[0], act_counts(act_p2)[1]))
    check('A4 已付款的转退款待审批（100 分）、待支付的取消且支付单关闭', ('refund_pending', '100', 'cancelled', 'closed'),
          signup_row(s7b, 'status, refund_fen') + (signup_row(s8b), scalar("select status from kg_pay_order where pay_no='%s'" % pay8b)))
    status, b = http('PUT', '/kangaroo/community/signup/%s/refund-approve' % s7b, token=admin)
    check('A4 后台同意退款：支付单退 100、报名已退款', (200, '100', 'refunded'),
          (code(b), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay7b), signup_row(s7b)))
    status, b = http('PUT', '/kangaroo/community/activity/%s/cancel' % act_p2, {'reason': '再取消一次'}, token=admin)
    refused('A4 已取消的活动不能再取消', b)

    # 后台「支付单退款」把一张已确认报名的钱全退了：报名不应仍是已确认、名额不应仍被占
    b = signup(ptok[9], act_paid)
    s9, pay9 = int(data(b).get('signupId') or 0), data(b).get('payNo')
    pay(ptok[9], pay9)
    status, b = http('PUT', '/kangaroo/core/payorder/%s/refund' % pay9, {'reason': '回归：后台直接退款'}, token=admin)
    check('A4 后台支付单全额退款成功', (200, '50'), (code(b), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay9)))
    check('A4 钱已全退后，报名不应仍为已确认（应转已退款并释放名额）', ('refunded', 0),
          (signup_row(s9), act_counts(act_paid)[0]))

    # ================================================================ A5 活动状态、通知、用户自发
    section('A5 活动状态操作、现场通知、员工列表、用户自发活动')
    status, b = http('POST', '/app/staff/activities/%s/broadcast' % act_free, {'text': '明天下午两点门口集合'}, app_token=mgr_tok)
    confirmed_n = n("select count(*) from kg_activity_signup where activity_id=%s and status='confirmed'" % act_free)
    check('A5 现场通知：通知人数 = 已确认人数、活动群有系统消息', (200, '已通知 %d 位成员' % confirmed_n, True),
          (code(b), b.get('msg'), n("select count(*) from kg_chat_msg m join kg_activity a on a.conv_id=m.conv_id where a.activity_id=%s and m.sender_role='system' and m.content like '%%明天下午两点门口集合%%'" % act_free) == 1))
    # 员工端详情页「换封面」：页面用详情回填整份表单再 PUT（pages/staff/activities.vue changeCover / editPayload）
    status, b = http('GET', '/app/staff/activities/%s' % act_free, app_token=mgr_tok)
    dd = data(b)
    # Persist an existing bundled image: invented upload paths poison later
    # public-homepage/browser checks with failed image requests.
    page_payload = {'category': dd.get('category'), 'title': dd.get('title'), 'cover': '/static/media/activity-cover.jpg',
                    'place': dd.get('place'), 'capacity': dd.get('capacity'),
                    'startTime': str(dd.get('startTime') or '').replace('T', ' ')[:16], 'endTime': str(dd.get('endTime') or '').replace('T', ' ')[:16],
                    'feeYuan': str((dd.get('feeFen') or 0) / 100), 'intro': '换了封面，其他不变', 'safetyNote': dd.get('safetyNote')}
    status, b = http('PUT', '/app/staff/activities/%s' % act_free, page_payload, app_token=mgr_tok)
    check('A5 已有人报名的活动：只换封面 / 改介绍（时间地点不变）应可保存（报错文案也说「只能修改介绍与安全说明」）',
          (200, '/static/media/activity-cover.jpg'), (code(b), scalar("select cover from kg_activity where activity_id=%s" % act_free)))
    status, b = http('PUT', '/app/staff/activities/%s' % act_rej, {'category': '其他', 'title': TAG + '驳回后重提', 'place': '门店二楼', 'capacity': 5,
                                                                  'startTime': day(6, '10:00'), 'endTime': day(6, '12:00'), 'feeYuan': '0'},
                     app_token=mgr_tok)
    check('A5 驳回后修改重提：标题更新、驳回原因清空、重新进入报名（mock 审核）', (200, TAG + '驳回后重提', 'NULL', 'open'),
          (code(b),) + tuple(sql("select title, coalesce(audit_reason,'NULL'), status from kg_activity where activity_id=%s" % act_rej)[0]))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': pid[3], 'source': 'activity'}, app_token=mgr_tok)
    check('A5 员工私信报名人：未同意《社区公约》→ 423（员工端页面未用 gated() 包裹，见报告）', 423, code(b))
    consent(mgr_tok, 'community')
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': pid[3], 'source': 'activity'}, app_token=mgr_tok)
    check('A5 同意后员工可私信报名人（来源 = 活动）', (200, 'activity'),
          (code(b), scalar("select source from kg_chat_conv where conv_id=%s" % (data(b).get('convId') or 0))))
    status, b = http('POST', '/app/staff/activities/%s/action/pause' % act_free, {}, app_token=mgr_tok)
    check('A5 暂停报名 → paused', (200, 'paused'), (code(b), act_counts(act_free)[3]))
    b = signup(ptok[4], act_free)
    check('A5 暂停时报名 → 409', 409, code(b))
    status, b = http('POST', '/app/staff/activities/%s/action/pause' % act_free, {}, app_token=mgr_tok)
    check('A5 重复暂停 → 409', 409, code(b))
    status, b = http('POST', '/app/staff/activities/%s/action/resume' % act_free, {}, app_token=mgr_tok)
    check('A5 恢复报名 → open', (200, 'open'), (code(b), act_counts(act_free)[3]))
    status, b = http('POST', '/app/staff/activities/%s/action/fly' % act_free, {}, app_token=mgr_tok)
    refused('A5 未知操作被拒', b)

    b = staff_create(mgr_tok, store_id, TAG + '普通员工能否取消', capacity=3)
    act_c = int(data(b).get('activityId') or 0)
    signup(ptok[4], act_c)
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % act_c, {'reason': '回归：普通员工取消'}, app_token=stf_tok)
    check('A5 普通员工（编辑权限仅「部分」）不能取消整场活动', (403, 'open'), (code(b), act_counts(act_c)[3]))
    b = staff_create(mgr_tok, store_id, TAG + '主理人取消', capacity=3)
    act_x = int(data(b).get('activityId') or 0)
    b = signup(ptok[5], act_x)
    sx = int(data(b).get('signupId') or 0)
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % act_x, {}, app_token=mgr_tok)
    check('A5 取消活动不填原因 → 422', 422, code(b))
    status, b = http('POST', '/app/staff/activities/%s/action/cancel' % act_x, {'reason': '天气原因'}, app_token=mgr_tok)
    check('A5 主理人取消活动：活动 cancelled、报名 cancelled、计数归零', (200, 'cancelled', 'cancelled', 0, 0),
          (code(b), act_counts(act_x)[3], signup_row(sx), act_counts(act_x)[0], act_counts(act_x)[1]))
    check('A5 报名人收到「活动已取消」', True, msgs(pid[5], '活动已取消') >= 1, ok=msgs(pid[5], '活动已取消') >= 1)
    status, b = http('GET', '/app/activities/%s' % act_x, app_token=ptok[5])
    check('A5 已取消活动详情仍可看（阶段 = cancelled）', (200, 'cancelled'), (code(b), data(b).get('phase')))

    status, b = http('GET', '/app/staff/activities?storeId=%s&tab=open&keyword=%s' % (store_id, TAG), app_token=mgr_tok)
    ids = [int(x['activityId']) for x in data(b).get('rows', [])]
    check('A5 员工端「报名中」列表：有报名中的（含驳回后重提的），没有已取消的', (True, True, True, False),
          (act_free in ids, act_paid in ids, act_rej in ids, act_x in ids))

    # 用户自发活动（活动管理页）
    u_tok, u_id = app_login('host')
    status, b = http('POST', '/app/activities', {'title': TAG + '一起晨跑', 'startTime': day(4, '07:00'), 'hours': 2,
                                                  'place': '古城南门', 'capacity': 5, 'city': CITY}, app_token=u_tok)
    check('A5 用户发起活动未同意须知 → 423', 423, code(b))
    consent(u_tok, 'activity')
    status, b = http('POST', '/app/activities', {'title': TAG + '一起晨跑', 'startTime': day(4, '07:00'), 'hours': 2,
                                                  'place': '古城南门', 'capacity': 5, 'city': CITY}, app_token=u_tok)
    check('A5 用户发起活动未核验手机号 → 428', 428, code(b))
    verify_phone(u_tok)
    status, b = http('POST', '/app/activities', {'title': TAG + '收费晨跑', 'startTime': day(4, '07:00'), 'hours': 2,
                                                  'place': '古城南门', 'capacity': 5, 'city': CITY, 'fee': 10}, app_token=u_tok)
    check('A5 用户自发活动收费 → 422', 422, code(b))
    status, b = http('POST', '/app/activities', {'title': TAG + '一起晨跑', 'startTime': day(4, '07:00'), 'hours': 2,
                                                  'place': '古城南门', 'capacity': 5, 'city': CITY}, app_token=u_tok)
    act_u = int(data(b).get('activityId') or 0)
    check('A5 用户发起活动：用户自发、免费、报名中', (200, 'user', 'free', 'open'),
          (code(b),) + tuple(sql("select source, fee_mode, status from kg_activity where activity_id=%s" % act_u)[0]))
    b = signup(u_tok, act_u)
    check('A5 发起人不能报名自己的活动 → 409', 409, code(b))
    b = signup(ptok[2], act_u)
    su2 = int(data(b).get('signupId') or 0)
    check('A5 P2 报名用户活动 → confirmed', 'confirmed', data(b).get('status'))
    check('A5 发起人收到「你发起的活动有新报名」', 1, msgs(u_id, '你发起的活动有新报名'))
    status, b = http('GET', '/app/activities/%s/manage' % act_u, app_token=ptok[2])
    check('A5 非发起人看管理页 → 403', 403, code(b))
    status, b = http('GET', '/app/activities/%s/manage' % act_u, app_token=u_tok)
    check('A5 发起人管理页：已确认名单 = [P2]', (200, [su2]), (code(b), [int(x['signupId']) for x in (data(b).get('confirmed') or [])]))
    code_u2 = str(signup_row(su2, 'checkin_code'))
    status, b = http('POST', '/app/activities/%s/manage/checkin' % act_u, {'code': code_u2}, app_token=ptok[3])
    check('A5 非发起人签到 → 403', 403, code(b))
    status, b = http('POST', '/app/activities/%s/manage/checkin' % act_u, {'code': code_u2}, app_token=u_tok)
    check('A5 发起人按签到码签到', (200, True), (code(b), signup_row(su2, 'checkin_time') not in (None, 'NULL')))
    status, b = http('POST', '/app/activities/%s/manage/broadcast' % act_u, {'text': '记得带水'}, app_token=u_tok)
    check('A5 发起人通知', (200, '已通知 1 位成员'), (code(b), b.get('msg')))
    status, b = http('GET', '/app/activities/%s/manage/members/%s/phone' % (act_u, su2), app_token=u_tok)
    check('A5 活动窗口外发起人查看电话 → 403', 403, code(b))
    status, b = http('PUT', '/app/activities/%s' % act_u, {'title': TAG + '改时间', 'startTime': day(5, '07:00'), 'hours': 2,
                                                          'place': '古城北门', 'capacity': 5}, app_token=u_tok)
    check('A5 已有人报名后发起人不能改时间地点 → 409', 409, code(b))
    status, b = http('PUT', '/app/activities/%s' % act_u, {'title': 'x', 'startTime': day(5, '07:00'), 'place': 'x', 'capacity': 5}, app_token=ptok[2])
    check('A5 非发起人修改 → 403', 403, code(b))
    status, b = http('GET', '/app/activities/mine?tab=mine', app_token=u_tok)
    check('A5「我发起的」里有这场', True, act_u in [int(x['activityId']) for x in (b.get('data') or [])],
          ok=act_u in [int(x['activityId']) for x in (b.get('data') or [])])
    status, b = http('POST', '/app/activities/%s/manage/cancel' % act_u, {'reason': '下雨'}, app_token=u_tok)
    check('A5 发起人取消：报名随之取消', (200, 'cancelled', 'cancelled'), (code(b), act_counts(act_u)[3], signup_row(su2)))

    # ================================================================ T1 公开行程
    section('T1 公开行程：审核、同行匹配、个人页、关闭')
    for tok in (a_tok, b_tok):
        consent(tok, 'community')
    verify_phone(b_tok)
    plan = {'city': CITY, 'startDate': day(10), 'endDate': day(15), 'activities': ['城市漫步', '咖啡探店'], 'intro': '有事打 13900001111',
            'hideStay': True, 'hideTransport': True}
    status, b = http('POST', '/app/trip-plans', plan, app_token=c_tok)
    check('T1 未核验手机号发布公开行程 → 428', 428, code(b))
    status, b = http('POST', '/app/trip-plans', dict(plan, startDate=day(-1)), app_token=a_tok)
    check('T1 开始日期早于今天 → 422', 422, code(b))
    status, b = http('POST', '/app/trip-plans', dict(plan, activities=['不在列表里']), app_token=a_tok)
    check('T1 没有合法的主要活动 → 422', 422, code(b))
    status, b = http('POST', '/app/trip-plans', dict(plan, endDate=day(120)), app_token=a_tok)
    check('T1 超过 90 天 → 422', 422, code(b))
    check('T1 被拒的都没有落库', 0, n("select count(*) from kg_trip_plan where app_user_id in (%s,%s)" % (a_id, c_id)))
    status, b = http('POST', '/app/trip-plans', plan, app_token=a_tok)
    plan_a = int(data(b).get('planId') or 0)
    check('T1 A 发布公开行程（简介带电话）→ 待审核', (200, 'pending'), (code(b), data(b).get('status')))
    check('T1 隐藏住宿 / 交通按页面传值落库', ('1', '1'), tuple(sql("select hide_stay, hide_transport from kg_trip_plan where plan_id=%s" % plan_a)[0]))
    status, b = http('POST', '/app/trip-plans', {'city': CITY, 'startDate': day(12), 'endDate': day(20), 'activities': ['逛市集'],
                                                  'intro': '慢节奏'}, app_token=b_tok)
    plan_b = int(data(b).get('planId') or 0)
    check('T1 B 发布公开行程 → 直接公开', (200, 'online'), (code(b), data(b).get('status')))
    status, b = http('GET', '/app/trip-plans/matches', app_token=b_tok)
    got = [int(x['planId']) for x in data(b).get('people', [])]
    check('T1 A 的行程待审核时不出现在 B 的同行候选里', False, plan_a in got)
    status, b = http('GET', '/app/trip-plans/%s/person' % plan_a, app_token=b_tok)
    check('T1 待审核行程的个人页 → 404', 404, code(b))
    rows = admin_list(admin, 'tripplan', status='pending', appUserId=a_id)
    check('T1 后台「公开行程」待审核列表里有', True, any(int(x['planId']) == plan_a for x in rows),
          ok=any(int(x['planId']) == plan_a for x in rows))
    status, b = http('PUT', '/kangaroo/community/plan/%s/approve' % plan_a, token=admin)
    check('T1 后台通过 → online', (200, 'online'), (code(b), scalar("select status from kg_trip_plan where plan_id=%s" % plan_a)))
    status, b = http('PUT', '/kangaroo/community/plan/%s/approve' % plan_a, token=admin)
    refused('T1 重复审核被拒', b)
    status, b = http('GET', '/app/trip-plans/matches', app_token=b_tok)
    ppl = {int(x['planId']): x for x in data(b).get('people', [])}
    check('T1 通过后 B 的同行候选里有 A，重叠 4 天', (True, 4), (plan_a in ppl, (ppl.get(plan_a) or {}).get('days')))
    status, b = http('GET', '/app/trip-plans/matches?planId=%s' % plan_b, app_token=c_tok)
    check('T1 用别人的 planId 查匹配 → 当作没有行程', True, data(b).get('needPlan'))
    status, b = http('GET', '/app/trip-plans/%s/person' % plan_a, app_token=b_tok)
    check('T1 B 看 A 的同行个人页：重叠 4 天、只给公开信息（无手机号字段）', (200, 4, False),
          (code(b), data(b).get('overlapDays'), 'phone' in data(b)))
    status, b = http('GET', '/app/trip-plans/mine', app_token=a_tok)
    check('T1 我的公开行程', [('online', plan_a)], [(x.get('status'), int(x['planId'])) for x in (b.get('data') or [])])
    b_plan2 = {'city': CITY, 'startDate': day(13), 'endDate': day(18), 'activities': ['其他'], 'intro': ''}
    status, b = http('POST', '/app/trip-plans', b_plan2, app_token=b_tok)
    plan_b2 = int(data(b).get('planId') or 0)
    check('T1 同城再发一条：旧的自动关闭，只保留一条公开中', ('closed', 'online'),
          (scalar("select status from kg_trip_plan where plan_id=%s" % plan_b), scalar("select status from kg_trip_plan where plan_id=%s" % plan_b2)))

    # ================================================================ T2 私信
    section('T2 私信：打招呼上限（1 条）、非成员不可读、不能退出私信、拉黑后不能发')
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'planId': plan_a}, app_token=b_tok)
    conv = int(data(b).get('convId') or 0)
    check('T2 B 从同行个人页私信 A', (200, 'private', a_id), (code(b), data(b).get('type'), int((data(b).get('other') or {}).get('userId') or 0)))
    check('T2 会话来源 = 同行匹配、成员 = A 与 B', ('match', {str(a_id), str(b_id)}),
          (scalar("select source from kg_chat_conv where conv_id=%s" % conv),
           set(r[0] for r in sql("select app_user_id from kg_chat_member where conv_id=%s" % conv))))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': a_id}, app_token=b_tok)
    check('T2 再次打开同一对用户 → 同一个会话', conv, int(data(b).get('convId') or 0))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': b_id}, app_token=b_tok)
    check('T2 不能给自己发私信 → 422', 422, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': 99999999}, app_token=b_tok)
    check('T2 对方账号不存在 → 404', 404, code(b))
    # 2026-10-11 起：没有互相关注时，对方回复前只能先发 1 条（之前是 3 条）
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '你好，第 1 条'}, app_token=b_tok)
    check('T2 对方回复前 B 先发 1 条成功', 200, code(b))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '第二条'}, app_token=b_tok)
    check('T2 第 2 条被拦（打招呼上限 1）→ 409', 409, code(b))
    check('T2 库里 B 只有 1 条消息', 1, n("select count(*) from kg_chat_msg where conv_id=%s and sender_id=%s" % (conv, b_id)))
    check('T2 A 收到一条私信提醒', 1, n("select count(*) from kg_message where recipient_id=%s and biz_type='chat_conv' and biz_id=%s" % (a_id, conv)))
    status, b = http('GET', '/app/chat/%s/messages' % conv, app_token=a_tok)
    check('T2 A 拉到 B 的 1 条消息、提醒已读', (1, '1'),
          (len(data(b).get('rows') or []), scalar("select read_flag from kg_message where recipient_id=%s and biz_type='chat_conv' and biz_id=%s" % (a_id, conv))))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '你好呀'}, app_token=a_tok)
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '对方回复后可以继续聊'}, app_token=b_tok)
    check('T2 A 回复后 B 可以继续发', 200, code(b))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': ''}, app_token=b_tok)
    check('T2 空消息 → 422', 422, code(b))

    # ---------------- T2b 私信交换联系方式（2026-10-11）：双方都同意前，对方发来的手机号 / 微信号显示成「已隐藏」
    status, b = http('GET', '/app/chat/%s' % conv, app_token=a_tok)
    check('T2b 会话带交换状态：都还没同意', {'mine': False, 'theirs': False, 'open': False}, data(b).get('contact'))
    raw = '我电话 13912345678，微信 abc_2026x'
    shown = '我电话 [手机号已隐藏]，[微信号已隐藏]'
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': raw}, app_token=b_tok)
    check('T2b B 发联系方式：能发，回执提示对方暂时看不到', (200, True), (code(b), data(b).get('hiddenForOther')))
    contact_msg = int(data(b).get('msgId') or 0)

    def contact_view(tok):
        st, bb = http('GET', '/app/chat/%s/messages' % conv, app_token=tok)
        rows = [r for r in (data(bb).get('rows') or []) if int(r['msgId']) == contact_msg]
        return (rows[0] if rows else {}), data(bb).get('contact')

    m, st = contact_view(a_tok)
    check('T2b A 看到的是遮挡后的', shown, m.get('content'))
    m, st = contact_view(b_tok)
    check('T2b B 自己看到原文 + 「对方暂时看不到」提示', (raw, True), (m.get('content'), m.get('hiddenForOther')))
    check('T2b 库里存的是原文', raw, scalar("select content from kg_chat_msg where msg_id=%s" % contact_msg))
    check('T2b A 的站内信提醒里也没有号码', 0, n("select count(*) from kg_message where recipient_id=%s and biz_type='chat_conv' and biz_id=%s and content like '%%13912345678%%'" % (a_id, conv)))
    status, b = http('POST', '/app/chat/%s/contact' % conv, {'agree': True}, app_token=b_tok)
    check('T2b B 点「交换联系方式」', (200, {'mine': True, 'theirs': False, 'open': False}), (code(b), data(b)))
    m, st = contact_view(a_tok)
    check('T2b 只有 B 同意时 A 仍看不到号码，A 这边显示对方想交换', (shown, {'mine': False, 'theirs': True, 'open': False}), (m.get('content'), st))
    status, b = http('POST', '/app/chat/%s/contact' % conv, {'agree': False}, app_token=b_tok)
    check('T2b B 撤回请求', (200, False), (code(b), data(b).get('mine')))
    http('POST', '/app/chat/%s/contact' % conv, {'agree': True}, app_token=b_tok)
    status, b = http('POST', '/app/chat/%s/contact' % conv, {'agree': True}, app_token=a_tok)
    check('T2b A 也同意 → 已交换', (200, True), (code(b), data(b).get('open')))
    m, st = contact_view(a_tok)
    check('T2b 交换后 A 看到原文', (raw, True), (m.get('content'), (st or {}).get('open')))
    status, b = http('POST', '/app/chat/%s/contact' % conv, {'agree': False}, app_token=b_tok)
    check('T2b 交换后不能撤回 → 409', 409, code(b))
    status, b = http('POST', '/app/chat/%s/contact' % conv, {'agree': True}, app_token=c_tok)
    check('T2b 非成员不能操作 → 403', 403, code(b))

    # ---------------- T2c 谁可以私信我 + 互相关注不限条数 + 个人主页（2026-10-11 内测建议）
    def ensure_follow(tok, uid, want):
        st, bb = http('GET', '/app/community/users/%s' % uid, app_token=tok)
        if bool(data(bb).get('followed')) != want:
            http('POST', '/app/community/users/%s/follow' % uid, app_token=tok)

    status, b = http('PUT', '/app/community/settings/dm', {'value': 'mutual'}, app_token=c_tok)
    check('T2c C 设置只接收互相关注的人的私信', (200, 'mutual'), (code(b), scalar("select dm_privacy from kg_app_user where user_id=%s" % c_id)))
    status, b = http('PUT', '/app/community/settings/dm', {'value': 'nobody'}, app_token=c_tok)
    check('T2c 不认识的设置 → 422', 422, code(b))
    ensure_follow(a_tok, c_id, True)
    ensure_follow(c_tok, a_id, False)
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': c_id}, app_token=a_tok)
    check('T2c 只有 A 关注 C（没互相关注）时 A 私信 C → 403', 403, code(b))
    status, b = http('GET', '/app/community/users/%s' % c_id, app_token=a_tok)
    check('T2c C 的主页：A 已关注、不能私信', (200, True, False), (code(b), data(b).get('followed'), data(b).get('canMessage')))
    ensure_follow(c_tok, a_id, True)
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'userId': c_id}, app_token=a_tok)
    conv_ac = int(data(b).get('convId') or 0)
    check('T2c 互相关注后可以私信，且不限条数', (200, -1), (code(b), data(b).get('greetLeft')))
    for k in range(2):
        status, b = http('POST', '/app/chat/%s/messages' % conv_ac, {'content': '互关第 %d 条' % (k + 1)}, app_token=a_tok)
    check('T2c 互相关注时对方回复前也能连发', 200, code(b))
    status, b = http('GET', '/app/community/following', app_token=a_tok)
    check('T2c A 的「我关注的人」里有 C，且是互相关注', (200, True),
          (code(b), any(int(x['userId']) == c_id and x.get('mutual') for x in (b.get('data') or []))))
    http('PUT', '/app/community/settings/dm', {'value': 'all'}, app_token=c_tok)
    for path in ('/app/chat/%s' % conv, '/app/chat/%s/messages' % conv):
        status, b = http('GET', path, app_token=c_tok)
        check('T2 非成员 C 读 %s → 403' % path.replace(str(conv), '{convId}'), 403, code(b))
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '插一句'}, app_token=c_tok)
    check('T2 非成员 C 发消息 → 403，库里没有', (403, 0), (code(b), n("select count(*) from kg_chat_msg where conv_id=%s and sender_id=%s" % (conv, c_id))))
    status, b = http('GET', '/app/chat/%s' % conv)
    check('T2 未登录读会话 → 401', 401, code(b))
    status, b = http('PUT', '/app/chat/%s/flags' % conv, {'flag': 'muted', 'on': True}, app_token=a_tok)
    check('T2 A 开启免打扰落库', (200, '1'), (code(b), scalar("select muted from kg_chat_member where conv_id=%s and app_user_id=%s" % (conv, a_id))))
    status, b = http('POST', '/app/chat/%s/leave' % conv, app_token=a_tok)
    check('T2 私信不能退出 → 409', 409, code(b))
    status, b = http('GET', '/app/chat/%s/detail' % conv, app_token=a_tok)
    check('T2 私信没有群详情 → 409', 409, code(b))
    status, b = http('POST', '/app/community/reports', {'targetType': 'chat', 'targetId': conv, 'reason': '骚扰 / 辱骂'}, app_token=a_tok)
    check('T2 举报会话：会话被举报数 1', (200, '1'), (code(b), scalar("select reported from kg_chat_conv where conv_id=%s" % conv)))
    http('POST', '/app/community/users/%s/block' % b_id, app_token=a_tok)
    status, b = http('POST', '/app/chat/%s/messages' % conv, {'content': '被拉黑后'}, app_token=b_tok)
    check('T2 A 拉黑 B 后 B 发消息 → 403', 403, code(b))
    status, b = http('GET', '/app/chat/%s' % conv, app_token=b_tok)
    check('T2 会话里显示已拉黑', True, data(b).get('blocked'))
    status, b = http('GET', '/app/trip-plans/matches', app_token=b_tok)
    check('T2 拉黑后同行候选里不再有 A', False, plan_a in [int(x['planId']) for x in data(b).get('people', [])])
    http('POST', '/app/community/users/%s/block' % b_id, app_token=a_tok)
    status, b = http('POST', '/app/trip-plans/%s/close' % plan_a, app_token=c_tok)
    check('T2 别人关闭 A 的公开行程 → 404，行程仍公开', (404, 'online'), (code(b), scalar("select status from kg_trip_plan where plan_id=%s" % plan_a)))
    status, b = http('POST', '/app/trip-plans/%s/close' % plan_a, app_token=a_tok)
    check('T2 A 关闭公开行程', (200, 'closed'), (code(b), scalar("select status from kg_trip_plan where plan_id=%s" % plan_a)))
    status, b = http('GET', '/app/trip-plans/matches', app_token=b_tok)
    check('T2 关闭后 B 的候选里没有 A', False, plan_a in [int(x['planId']) for x in data(b).get('people', [])])
    status, b = http('POST', '/app/chat/open', {'type': 'private', 'planId': plan_a}, app_token=c_tok)
    check('T2 已关闭的行程不能再发起私信 → 404', 404, code(b))
    status, b = http('POST', '/app/trip-plans/%s/close' % plan_a, app_token=a_tok)
    check('T2 重复关闭 → 404', 404, code(b))

    # ================================================================ T3 活动群与我的行程
    section('T3 活动群：成员可进、非成员不可进、退出与重进、群主与公告；我的行程')
    status, b = http('POST', '/app/chat/open', {'type': 'activity', 'activityId': act_free}, app_token=ptok[3])
    gconv = int(data(b).get('convId') or 0)
    check('T3 已确认成员 P3 进活动群', (200, 'group'), (code(b), data(b).get('type')))
    status, b = http('POST', '/app/chat/open', {'type': 'activity', 'activityId': act_free}, app_token=ptok[4])
    check('T3 已取消候补的 P4 进不了活动群 → 403', 403, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'activity', 'activityId': act_free}, app_token=far_tok)
    check('T3 别家门店主理人进不了 → 403', 403, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'activity', 'activityId': act_free}, app_token=stf_tok)
    check('T3 本店员工可进活动群（管理员身份）', (200, 'admin'),
          (code(b), scalar("select role from kg_chat_member where conv_id=%s and app_user_id=%s" % (gconv, stf_id))))
    status, b = http('POST', '/app/chat/%s/messages' % gconv, {'content': '大家好'}, app_token=ptok[3])
    check('T3 成员在群里发言', 200, code(b))
    status, b = http('GET', '/app/chat/%s/messages' % gconv, app_token=ptok[4])
    check('T3 非成员读群消息 → 403', 403, code(b))
    status, b = http('GET', '/app/chat/%s/detail' % gconv, app_token=ptok[3])
    mem = [int(x['userId']) for x in data(b).get('members', [])]
    check('T3 群详情成员包含 P3、不含已取消的 P4', (200, True, False), (code(b), pid[3] in mem, pid[4] in mem))
    status, b = http('PUT', '/app/chat/%s/announcement' % gconv, {'text': '改公告'}, app_token=ptok[3])
    check('T3 普通成员改群公告 → 403', 403, code(b))
    status, b = http('PUT', '/app/chat/%s/announcement' % gconv, {'text': '集合改到门店二楼'}, app_token=mgr_tok)
    check('T3 群主（活动创建人）改群公告', (200, '集合改到门店二楼'), (code(b), scalar("select announcement from kg_chat_conv where conv_id=%s" % gconv)))
    status, b = http('POST', '/app/chat/%s/leave' % gconv, app_token=mgr_tok)
    check('T3 群主不能退出 → 409', 409, code(b))
    status, b = http('POST', '/app/chat/%s/leave' % gconv, app_token=ptok[3])
    check('T3 P3 退出群聊', (200, True), (code(b), scalar("select left_time from kg_chat_member where conv_id=%s and app_user_id=%s" % (gconv, pid[3])) not in (None, 'NULL')))
    check('T3 退群不影响报名', 'confirmed', signup_row(s3))
    status, b = http('GET', '/app/chat/%s/messages' % gconv, app_token=ptok[3])
    check('T3 退群后读群消息 → 403', 403, code(b))
    status, b = http('POST', '/app/chat/open', {'type': 'activity', 'activityId': act_free}, app_token=ptok[3])
    check('T3 退群后「可在活动页重新进群」：已确认成员重新打开活动群应成功', 200, code(b))

    status, b = http('GET', '/app/trips', app_token=ptok[3])
    items = [(x.get('refType'), int(x.get('refId'))) for g in data(b).get('groups', []) for x in g.get('items', [])]
    check('T3 P3 的「我的行程」里有这场活动', True, ('activity_signup', s3) in items, ok=('activity_signup', s3) in items)
    status, b = http('GET', '/app/trips?tab=' + urllib_quote('活动'), app_token=ptok[3])
    check('T3 按「活动」筛选仍有', True, any(x.get('kind') == 'activity' for g in data(b).get('groups', []) for x in g.get('items', [])))
    status, b = http('GET', '/app/trips/detail?refType=activity_signup&refId=%s' % s3, app_token=ptok[3])
    check('T3 行程详情：关联活动 = 这场', (200, act_free), (code(b), int(data(b).get('activityId') or 0)))
    status, b = http('GET', '/app/trips/detail?refType=activity_signup&refId=%s' % s3, app_token=ptok[4])
    check('T3 别人查我的行程详情 → 404', 404, code(b))
    status, b = http('GET', '/app/trips')
    check('T3 未登录查行程 → 401', 401, code(b))

    t4_store_chat_rows(admin)

    # ================================================================ 汇总
    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 RUN=%s TAG=%s（账号 code 前缀 comm-%s-，reset-to-demo.sql 会清掉）' % (RUN, TAG, RUN))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
