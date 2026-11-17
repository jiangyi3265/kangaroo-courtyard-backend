# -*- coding: utf-8 -*-
"""用户端扩展（V11 用户服务 / V14 账号 / V15 微信订阅消息）的接口回归。

只收"能证明业务结果"的断言：每个动作之后回库确认状态、另一端（后台 / 门店 / 对方用户）确实看得到，
以及负向用例（别人的数据、未登录 401、非法输入、重复提交）。

覆盖：
  U1  资料编辑（昵称 / 性别 / 生日 / 自我介绍 / 头像的校验与落库）、积分
  U2  短信验证码（联调通道）：错码、场景隔离、验证码一次性、号码已被占用、频率限制
  U3  实名核验（联调通道）：姓名 / 校验位、跨账号重复证件、只下发脱敏信息
  U4  登录设备：列表、退出指定设备（该令牌下一次请求 401）、退出其他设备、不能动别人的会话
  U5  黑名单（双向不可见、不能评论 / 关注、移出后恢复）与撤回同意
  U6  提醒偏好（暂停某类后 KgMessageServiceImpl.send 不再写站内信）与 V15 订阅消息额度（最多 5 次、发一条扣一次、撤销清零）
  U7  简历：基本信息 / 技能 / 手填经历增改删，越权，找机会
  U8  新机会订阅、关注门店、三类收藏
  U9  首页 / 城市页 / 岗位筛选 / 条件匹配 / 智能客服
  U10 客服工单 → 后台回复 → 用户看到回复与站内信；满意度
  U11 旅行体验官申请 → 后台驳回 / 通过
  U12 报名全流程：同行队伍、责任书签署、到店信息、到店路线的电话、平台经历不可改、写评价
  U13 住宿 / 二消 / 活动订单 → 订单聚合、路线电话、注销阻塞项
  U14 个人数据导出
  U15 账号注销（令牌失效、证件摘要释放）

前置：后端以联调模式运行（pay / 内容审核 / 短信 / 实名 / 订阅消息 均为 mock），每轮独立创建门店、房源、房型与增值服务并 finally 清理，显式 KG_BASE / KG_DB 必须指向隔离环境。
测试数据：本次运行新建的账号（code 前缀 usr-<RUN>-）与它们产生的数据；若库里没有「社区互动」订阅模板，
本脚本会临时建一个并在结束时删掉。

用法：
    python e2e/test_ext_user.py
"""
import base64
import io
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import http, scalar, sql, check, section, admin_login, PASSED, FAILED, BASE  # noqa: E402
from test_ext_flows import free_days  # noqa: E402

from isolated_stay_fixture import IsolatedStayFixture

FIXTURE = None
RUN = uuid.uuid4().hex[:8]
AGREEMENT = '2026-09'


# ---------------------------------------------------------------- helpers

def login(tag):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'usr-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    if FIXTURE is not None:
        FIXTURE.user(body['token'], body['account']['id'])
    return body['token'], int(body['account']['id'])


def api(method, path, tok=None, body=None, admin=None):
    status, b = http(method, path, body, token=admin, app_token=tok)
    return b


def ok(b):
    return b.get('code') == 200


def not_ok(b):
    return b.get('code') != 200


def data(b):
    return b.get('data')


def n(q):
    return int(scalar(q) or 0)


def uuid_of(tok):
    """小程序令牌是 JWT，会话键 app_login_uuid 在载荷里（设备列表的 id 就是它）"""
    seg = tok.split('.')[1]
    seg += '=' * (-len(seg) % 4)
    return json.loads(base64.urlsafe_b64decode(seg.encode()).decode())['app_login_uuid']


def rand_phone():
    for _ in range(50):
        p = '19' + ''.join(random.choice('0123456789') for _ in range(9))
        if n("select count(*) from kg_app_user where phone='%s'" % p) == 0:
            return p
    raise RuntimeError('找不到空闲手机号')


ID_W = [7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2]
ID_C = '10X98765432'


def make_id():
    body = str(random.randint(1, 6)) + ''.join(random.choice('0123456789') for _ in range(16))
    return body + ID_C[sum(int(body[i]) * ID_W[i] for i in range(17)) % 11]


def bad_checksum(idno):
    last = idno[-1]
    return idno[:-1] + ('0' if last != '0' else '1')


def day(offset):
    # 后端与库按北京时间（+08:00）算日期；本机时区不同时用 localtime 会差一天
    return time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + offset * 86400))


def get_bytes(path, tok):
    req = urllib.request.Request(BASE + path, headers={'X-App-Token': tok} if tok else {}, method='GET')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.headers.get('Content-Type', ''), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), e.read()


def dumps(o):
    return json.dumps(o, ensure_ascii=False)


def main():
    global FIXTURE
    with IsolatedStayFixture('usr-' + RUN) as fixture:
        FIXTURE = fixture
        tpl_cleanup = []
        try:
            return body(fixture.admin, tpl_cleanup, fixture)
        finally:
            for tpl_id in tpl_cleanup:
                b = api('DELETE', '/kangaroo/ext/substpl/%s' % tpl_id, admin=fixture.admin)
                assert b.get('code') == 200, ('临时模板清理失败', tpl_id, b)
            FIXTURE = None


def body(admin, tpl_cleanup, fixture):
    # ================================================================ U0 准备
    section('U0 准备：联调通道、门店 / 房源 / 服务基线')
    store_id, room_type_id, stay_id, service_id = fixture.store_id, fixture.room_type_id, fixture.stay_id, fixture.service_id
    store_phone = scalar("select contact_phone from kg_store where store_id=%s" % store_id)
    other_store = fixture.other_store_id
    check('U0 基线门店 %s（房型 %s / 服务 %s / 另一家 %s）' % (store_id, room_type_id, service_id, other_store), True, True)

    p_tok, p_id = login('profile')
    prof = data(api('GET', '/app/me/profile', p_tok)) or {}
    check('U0 短信 / 实名为联调通道（smsEnabled / realNameEnabled）', (True, True), (prof.get('smsEnabled'), prof.get('realNameEnabled')))
    sub = data(api('GET', '/app/subscribe/status', p_tok)) or {}
    check('U0 订阅消息为 mock 通道', 'mock', sub.get('mode'))

    # ================================================================ U1 资料
    section('U1 资料编辑：校验与落库')
    b = api('GET', '/app/me/profile')
    check('U1 未登录读资料 401', 401, b.get('code'))
    check('U1 新账号资料：会员号 = 账号ID，积分 0、等级幼崽鼠', (p_id, 0, '幼崽鼠'),
          (prof.get('memberNo'), prof.get('points'), prof.get('levelName')))
    before_nick = scalar("select nickname from kg_app_user where user_id=%s" % p_id)
    for bad, why in [({'nickname': ''}, '空昵称'), ({'nickname': '一' * 17}, '17 字昵称'), ({'gender': '3'}, '性别 3'),
                     ({'birthday': '2000-08-29'}, '生日格式'), ({'birthday': '02-30'}, '不存在的日期'), ({'intro': '字' * 61}, '61 字介绍'),
                     ({'avatar': 'https://evil.example.com/a.png'}, '外站头像'), ({}, '空提交')]:
        b = api('POST', '/app/me/profile', p_tok, bad)
        check('U1 %s 被拒（422）' % why, 422, b.get('code'))
    b = api('POST', '/app/me/profile', p_tok, {'nickname': '加我微信 kg13800138000 电话13800138000'})
    check('U1 昵称含联系方式被拒', True, not_ok(b), ok=not_ok(b))
    check('U1 被拒的修改没有落库', before_nick, scalar("select nickname from kg_app_user where user_id=%s" % p_id))
    nick = '回归' + RUN[:6]
    b = api('POST', '/app/me/profile', p_tok, {'nickname': nick, 'gender': '2', 'birthday': '08-29', 'city': '大理',
                                               'intro': '喜欢咖啡和山', 'avatar': '/profile/upload/2026/09/26/a.png'})
    d = data(b) or {}
    check('U1 合法修改成功并返回新资料', (200, nick, '女', '8月29日'), (b.get('code'), d.get('nickname'), d.get('genderLabel'), d.get('birthdayLabel')))
    check('U1 资料落库', [nick, '2', '08-29', '大理', '喜欢咖啡和山', '/profile/upload/2026/09/26/a.png'],
          sql("select nickname, gender, birthday, city, intro, avatar from kg_app_user where user_id=%s" % p_id)[0])
    acc = data(api('GET', '/app/auth/account', p_tok)) or {}
    check('U1 账号接口（顶部问候读它）拿到新昵称', nick, acc.get('nickname'))
    b = api('POST', '/app/me/profile', p_tok, {'birthday': '', 'intro': ''})
    check('U1 清空生日与介绍', ['NULL', 'NULL'], sql("select ifnull(birthday,'NULL'), ifnull(intro,'NULL') from kg_app_user where user_id=%s" % p_id)[0])
    b = api('GET', '/app/me/points?page=1', p_tok)
    d = data(b) or {}
    # 2026-10-17：资料六项填齐一次性得 20 积分（完善个人资料），之后清空生日 / 介绍不扣回；只发一次
    check('U1 积分明细与库一致（资料填齐得一次 20 分，只此一条）',
          (20, [('完善个人资料', 20)], 20),
          (d.get('points'), [(r.get('title'), r.get('delta')) for r in d.get('records') or []],
           n("select coalesce(sum(delta),0) from kg_point_entry where app_user_id=%s" % p_id)))

    # ================================================================ U2 短信
    section('U2 短信验证码（联调通道）绑定手机号')
    o_tok, o_id = login('organizer')
    x_tok, x_id = login('phonethief')
    phone1 = rand_phone()
    b = api('POST', '/app/me/sms/send', o_tok, {'phone': '12345', 'scene': 'bind'})
    check('U2 非法手机号发码被拒（422）', 422, b.get('code'))
    b = api('POST', '/app/me/sms/send', o_tok, {'scene': 'bind'})
    check('U2 没绑手机时不传号码被拒（422，不会发到空号）', 422, b.get('code'))
    b = api('POST', '/app/me/sms/send', None, {'phone': phone1, 'scene': 'bind'})
    check('U2 未登录发码 401', 401, b.get('code'))
    b = api('POST', '/app/me/sms/send', o_tok, {'phone': phone1, 'scene': 'bind'})
    d = data(b) or {}
    code1 = str(d.get('code') or '')
    check('U2 发码成功，联调通道回传 6 位验证码', (200, True, 6), (b.get('code'), d.get('simulated'), len(code1)))
    wrong = '000000' if code1 != '000000' else '111111'
    b = api('POST', '/app/me/phone/bind', o_tok, {'phone': phone1, 'code': wrong, 'scene': 'bind'})
    check('U2 错误验证码被拒（422）', 422, b.get('code'))
    b = api('POST', '/app/me/phone/bind', o_tok, {'phone': phone1, 'code': code1, 'scene': 'rebind'})
    check('U2 绑定场景的验证码不能用于换绑场景', 422, b.get('code'))
    check('U2 失败尝试后手机号仍未绑定', 'NULL', scalar("select ifnull(phone,'NULL') from kg_app_user where user_id=%s" % o_id))
    b = api('POST', '/app/me/phone/bind', o_tok, {'phone': phone1, 'code': code1, 'scene': 'bind'})
    check('U2 正确验证码绑定成功', 200, b.get('code'))
    r = sql("select phone, phone_verified_time is not null from kg_app_user where user_id=%s" % o_id)[0]
    check('U2 手机号落库并标记已核验', [phone1, '1'], r)
    b = api('POST', '/app/me/phone/bind', o_tok, {'phone': phone1, 'code': code1, 'scene': 'bind'})
    check('U2 验证码一次性：再用一次被拒', 422, b.get('code'))
    prof = data(api('GET', '/app/me/profile', o_tok)) or {}
    check('U2 资料里只下发脱敏号码', (phone1[:3] + '****' + phone1[-4:], True, True, False),
          (prof.get('phone'), prof.get('phoneBound'), prof.get('phoneVerified'), phone1 in dumps(prof)))
    b = api('POST', '/app/me/sms/send', x_tok, {'phone': phone1, 'scene': 'bind'})
    code_x = str((data(b) or {}).get('code') or '')
    b = api('POST', '/app/me/phone/bind', x_tok, {'phone': phone1, 'code': code_x, 'scene': 'bind'})
    check('U2 别人已绑定的号码，验证码对了也绑不上', True, not_ok(b), ok=not_ok(b))
    check('U2 抢号失败后对方账号手机号仍为空、原主人不变', ('NULL', phone1),
          (scalar("select ifnull(phone,'NULL') from kg_app_user where user_id=%s" % x_id), scalar("select phone from kg_app_user where user_id=%s" % o_id)))
    b = api('POST', '/app/me/sms/send', o_tok, {'scene': 'settle'})
    check('U2 不传号码时发到已绑定手机（结算验证码路径）', 200, b.get('code'))
    rl_tok, _ = login('ratelimit')
    phone_rl = rand_phone()
    codes = [api('POST', '/app/me/sms/send', rl_tok, {'phone': phone_rl, 'scene': 'bind'}).get('code') for _ in range(6)]
    check('U2 同一号码 10 分钟内第 6 次发码被限流（前 5 次成功）', ([200] * 5, True), (codes[:5], codes[5] != 200))

    # ================================================================ U3 实名
    section('U3 实名核验（联调通道：只校验格式与校验位）')
    r1_tok, r1_id = login('realname1')
    r2_tok, r2_id = login('realname2')
    id1 = make_id()
    b = api('POST', '/app/me/real-name', r1_tok, {'name': 'Tom', 'idNo': id1})
    check('U3 非中文姓名被拒（422）', 422, b.get('code'))
    b = api('POST', '/app/me/real-name', r1_tok, {'name': '测试甲', 'idNo': bad_checksum(id1)})
    check('U3 校验位错误被拒（422）', 422, b.get('code'))
    b = api('POST', '/app/me/real-name', r1_tok, {'name': '测试甲', 'idNo': '1234'})
    check('U3 位数不对被拒（422）', 422, b.get('code'))
    check('U3 被拒后账号未实名', '0', scalar("select real_name_verified_time is not null from kg_app_user where user_id=%s" % r1_id))
    b = api('POST', '/app/me/real-name', r1_tok, {'name': '测试甲', 'idNo': id1.lower()})
    d = data(b) or {}
    check('U3 合法证件通过，返回脱敏姓名与后四位、标明模拟', (200, '测**', id1[-4:].upper(), True),
          (b.get('code'), d.get('name'), d.get('idLast4'), d.get('simulated')))
    check('U3 返回体不含完整证件号与完整姓名', (False, False), (id1.upper() in dumps(b), '测试甲' in dumps(d)))
    r = sql("select real_name_verified_time is not null, id_last4, length(id_hash), id_hash <> '%s' from kg_app_user where user_id=%s" % (id1, r1_id))[0]
    check('U3 库里只存后四位与 64 位摘要（不存完整证件号）', ['1', id1[-4:].upper(), '64', '1'], r)
    prof = data(api('GET', '/app/me/profile', r1_tok)) or {}
    check('U3 资料页：已实名、脱敏展示、无完整证件号', (True, '测**（已实名）', False, False),
          (prof.get('realNameVerified'), prof.get('realNameLabel'), id1.upper() in dumps(prof), '测试甲' in dumps(prof)))
    b = api('POST', '/app/me/real-name', r2_tok, {'name': '测试乙', 'idNo': id1})
    check('U3 同一证件在另一个账号实名被拒（409）', 409, b.get('code'))
    check('U3 被拒的账号没有被标记实名', '0', scalar("select real_name_verified_time is not null from kg_app_user where user_id=%s" % r2_id))

    # ================================================================ U4 设备
    section('U4 登录设备：退出指定设备 / 其他设备')
    t1, e_id = login('sessions')
    t2, _ = login('sessions')
    t3, _ = login('sessions')
    f_tok, f_id = login('sessions-other')
    b = api('GET', '/app/me/sessions')
    check('U4 未登录看设备 401', 401, b.get('code'))
    rows = (data(api('GET', '/app/me/sessions', t3)) or {}).get('rows') or []
    ids = set(r.get('id') for r in rows)
    check('U4 列表里有这三次登录、当前设备排第一且只有一个', (True, True, 1),
          ({uuid_of(t1), uuid_of(t2), uuid_of(t3)} <= ids, bool(rows) and rows[0].get('id') == uuid_of(t3) and rows[0].get('current') is True,
           sum(1 for r in rows if r.get('current'))))
    b = api('POST', '/app/me/sessions/revoke', t3, {'id': uuid_of(t3)})
    check('U4 不能用"退出指定设备"退出当前设备（422）', 422, b.get('code'))
    b = api('POST', '/app/me/sessions/revoke', f_tok, {'id': uuid_of(t2)})
    check('U4 别人拿到我的会话ID也退不掉', True, api('GET', '/app/me/profile', t2).get('code') == 200,
          ok=api('GET', '/app/me/profile', t2).get('code') == 200)
    b = api('POST', '/app/me/sessions/revoke', t3, {'id': uuid_of(t1)})
    check('U4 退出指定设备成功', 200, b.get('code'))
    check('U4 被退出的令牌下一次请求 401', 401, api('GET', '/app/me/profile', t1).get('code'))
    check('U4 其余设备不受影响', (200, 200), (api('GET', '/app/me/profile', t2).get('code'), api('GET', '/app/me/profile', t3).get('code')))
    b = api('POST', '/app/me/sessions/revoke-others', t3)
    check('U4 退出其他设备成功', 200, b.get('code'))
    check('U4 其他设备令牌 401、当前设备仍可用', (401, 200), (api('GET', '/app/me/profile', t2).get('code'), api('GET', '/app/me/profile', t3).get('code')))
    rows = (data(api('GET', '/app/me/sessions', t3)) or {}).get('rows') or []
    check('U4 设备列表只剩当前这台', [uuid_of(t3)], [r.get('id') for r in rows])

    # ================================================================ U5 黑名单 / 同意
    section('U5 黑名单：双向不可见、不能互动；撤回同意')
    a_tok, a_id = login('author')
    c_tok, c_id = login('commenter')
    d_tok, d_id = login('blocker')
    for t in (a_tok, c_tok, d_tok):
        api('POST', '/app/community/consent', t, {'doc': 'community'})
    kw = 'u' + RUN
    b = api('POST', '/app/community/posts', a_tok, {'kind': 'daily', 'title': '回归动态A ' + kw, 'content': '苍山脚下的一天', 'city': '大理'})
    post_a = (data(b) or {}).get('postId')
    check('U5 作者发布动态（联调审核直接上线）', (200, 'online'), (b.get('code'), (data(b) or {}).get('status')))
    b = api('POST', '/app/community/posts', d_tok, {'kind': 'daily', 'title': '回归动态D ' + kw, 'content': '洱海边骑车', 'city': '大理'})
    post_d = (data(b) or {}).get('postId')

    def feed_ids(tok):
        d = data(api('GET', '/app/community/feed?keyword=' + urllib.parse.quote(kw), tok)) or {}
        return set(r.get('postId') for r in d.get('rows') or [])
    check('U5 拉黑前双方互相看得到', (True, True), (post_a in feed_ids(d_tok), post_d in feed_ids(a_tok)))
    b = api('POST', '/app/community/users/%s/block' % d_id, d_tok)
    check('U5 不能拉黑自己', True, not_ok(b), ok=not_ok(b))
    b = api('POST', '/app/community/users/%s/block' % a_id, d_tok)
    check('U5 拉黑成功并落库', (200, True, 1), (b.get('code'), (data(b) or {}).get('blocked'),
                                          n("select count(*) from kg_user_block where user_id=%s and blocked_id=%s" % (d_id, a_id))))
    check('U5 拉黑后：我看不到对方、对方也看不到我', (False, False), (post_a in feed_ids(d_tok), post_d in feed_ids(a_tok)))
    check('U5 拉黑后直接打开对方动态 404', 404, api('GET', '/app/community/posts/%s' % post_a, d_tok).get('code'))
    b = api('POST', '/app/community/posts/%s/comments' % post_a, d_tok, {'content': '拉黑了还能评论吗'})
    check('U5 拉黑后不能评论对方动态、库里没有这条评论', (True, 0),
          (not_ok(b), n("select count(*) from kg_post_comment where post_id=%s and app_user_id=%s" % (post_a, d_id))))
    check('U5 拉黑后双方都不能关注对方（403）', (403, 403),
          (api('POST', '/app/community/users/%s/follow' % a_id, d_tok).get('code'), api('POST', '/app/community/users/%s/follow' % d_id, a_tok).get('code')))
    rows = (data(api('GET', '/app/me/blocks', d_tok)) or {}).get('rows') or []
    check('U5 黑名单列表里有对方', [a_id], [r.get('userId') for r in rows])
    check('U5 未登录移出黑名单 401', 401, api('POST', '/app/me/blocks/%s/remove' % a_id).get('code'))
    b = api('POST', '/app/me/blocks/%s/remove' % a_id, d_tok)
    check('U5 移出黑名单成功并删库', ('已移出黑名单', 0),
          ((data(b) or {}).get('message'), n("select count(*) from kg_user_block where user_id=%s and blocked_id=%s" % (d_id, a_id))))
    check('U5 移出后又互相看得到', (True, True), (post_a in feed_ids(d_tok), post_d in feed_ids(a_tok)))
    b = api('POST', '/app/me/blocks/%s/remove' % a_id, d_tok)
    check('U5 重复移出如实提示', '对方不在黑名单里', (data(b) or {}).get('message'))

    auth = data(api('GET', '/app/me/authorizations', d_tok)) or {}
    check('U5 授权管理里有社区公约的同意记录', True, 'community' in [x.get('doc') for x in auth.get('consents') or []],
          ok='community' in [x.get('doc') for x in auth.get('consents') or []])
    b = api('POST', '/app/me/consent/withdraw', d_tok, {'doc': 'community'})
    check('U5 撤回同意：删库', (200, 0), (b.get('code'), n("select count(*) from kg_consent where app_user_id=%s and doc='community'" % d_id)))
    b = api('POST', '/app/community/posts', d_tok, {'kind': 'daily', 'title': '撤回后再发', 'content': 'x', 'city': '大理'})
    check('U5 撤回后再发动态会重新征求同意（423）', 423, b.get('code'))
    b = api('POST', '/app/me/consent/withdraw', d_tok, {'doc': 'community'})
    check('U5 重复撤回如实提示', '没有找到这条同意记录', (data(b) or {}).get('message'))
    mine = data(api('GET', '/app/community/posts/mine', a_tok)) or []
    check('U5 「我的动态」列出本人发布的动态', True, post_a in [x.get('postId') for x in mine], ok=post_a in [x.get('postId') for x in mine])

    # ================================================================ U6 提醒偏好 + 订阅消息
    section('U6 提醒偏好与 V15 订阅消息额度')
    tpl = sql("select tpl_id, template_id, status from kg_subscribe_template where biz_key='community'")
    if not tpl:
        tid = 'e2e-tpl-' + RUN
        b = api('POST', '/kangaroo/ext/substpl', admin=admin, body={'bizKey': 'community', 'title': '社区互动（回归临时）', 'templateId': tid,
                                                                  'fields': 'thing1=title,thing2=content,time3=now', 'status': '0', 'remark': 'test_ext_user 临时建'})
        check('U6 后台新建「社区互动」订阅模板', 200, b.get('code'))
        tpl_cleanup.append(scalar("select tpl_id from kg_subscribe_template where template_id='%s'" % tid))
    else:
        tid = tpl[0][1]
        check('U6 已有启用的「社区互动」订阅模板', '0', tpl[0][2])

    def comment():
        b = api('POST', '/app/community/posts/%s/comments' % post_a, c_tok, {'content': '好美 ' + uuid.uuid4().hex[:6]})
        assert b.get('code') == 200, b
        return b

    # 10-15 消息页改版起评论通知一条评论一行：biz_type='comment'、biz_id=评论ID（之前是 biz_type='post'、biz_id=动态ID）
    MSG_ON_POST = ("from kg_message where recipient_id=%s and biz_type='comment' "
                   "and biz_id in (select comment_id from kg_post_comment where post_id=%s)" % (a_id, post_a))

    def msgs():
        return n("select count(*) " + MSG_ON_POST)

    def logs(st=None):
        return n("select count(*) from kg_subscribe_log where app_user_id=%s%s" % (a_id, '' if st is None else " and status='%s'" % st))

    def quota():
        return n("select quota from kg_subscribe_grant where app_user_id=%s and template_id='%s'" % (a_id, tid))

    m0, l0 = msgs(), logs()
    comment()
    check('U6 有人评论：作者收到站内信', m0 + 1, msgs())
    check('U6 没有授权额度：只记"未发送"留痕、不发', (logs('sent'), l0 + 1), (0, logs()))
    check('U6 未登录记录授权 401', 401, api('POST', '/app/subscribe/accept', None, {tid: 'accept'}).get('code'))
    b = api('POST', '/app/subscribe/accept', a_tok, {tid: 'reject', 'errMsg': 'requestSubscribeMessage:ok'})
    check('U6 用户拒绝授权不加额度', (0, 0), ((data(b) or {}).get('accepted'), quota()))
    b = api('POST', '/app/subscribe/accept', a_tok, {'not-a-template-' + RUN: 'accept'})
    check('U6 不存在的模板不加额度', (0, 0), ((data(b) or {}).get('accepted'), quota()))
    got = []
    for _ in range(6):
        b = api('POST', '/app/subscribe/accept', a_tok, {tid: 'accept', 'errMsg': 'requestSubscribeMessage:ok'})
        got.append(quota())
    check('U6 每授权一次 +1，最多攒 5 次', [1, 2, 3, 4, 5, 5], got)
    st = data(api('GET', '/app/subscribe/status', a_tok)) or {}
    row = [r for r in st.get('rows') or [] if r.get('bizKey') == 'community']
    check('U6 订阅状态：社区互动已开启、剩 5 次', [(True, 5)], [(r.get('on'), r.get('quota')) for r in row])
    comment()
    last_msg = scalar("select max(msg_id) " + MSG_ON_POST)
    check('U6 业务消息触发一次订阅推送：扣 1 次额度', 4, quota())
    check('U6 mock 通道写"已发送"留痕并关联这条站内信', [[last_msg, tid]],
          sql("select msg_id, template_id from kg_subscribe_log where app_user_id=%s and status='sent'" % a_id))
    b = api('POST', '/app/subscribe/revoke', a_tok, {'bizKey': 'community'})
    check('U6 关闭这类微信提醒：额度清零', (200, 0), (b.get('code'), quota()))
    m1, s1 = msgs(), logs('sent')
    comment()
    check('U6 额度为 0：站内信照写、不再发送', (m1 + 1, s1, 0), (msgs(), logs('sent'), quota()))

    # 提醒偏好（「我的提醒」列表页）
    for bad, why in [({'key': 'bogus', 'enabled': False}, '未知类型'), ({'key': 'order', 'freq': 'daily'}, '非新机会类设频率'),
                     ({'key': 'job_new', 'freq': 'daily'}, '没有订阅就设频率')]:
        check('U6 提醒偏好：%s 被拒（422）' % why, 422, api('PUT', '/app/notify-prefs', a_tok, bad).get('code'))
    b = api('PUT', '/app/notify-prefs', a_tok, {'key': 'community', 'enabled': False})
    d = data(b) or {}
    check('U6 暂停「社区互动」：落库且出现在已暂停列表', ('0', True),
          (scalar("select enabled from kg_notify_pref where app_user_id=%s and pref_key='community'" % a_id),
           'community' in [x.get('key') for x in d.get('paused') or []]))
    m2, l2 = msgs(), logs()
    comment()
    check('U6 暂停后有人评论：不写站内信、也不走订阅推送', (m2, l2), (msgs(), logs()))

    # 提醒设置屏（消息 · 提醒页）
    ns = data(api('GET', '/app/notify-settings', a_tok)) or {}
    check('U6 设置屏里社区互动的小程序开关为关', [False], [r.get('app') for r in ns.get('rows') or [] if r.get('key') == 'community'])
    for bad, why in [({'quiet': {'on': True, 'start': '22:00', 'end': '22:00'}}, '免打扰起止相同'),
                     ({'quiet': {'on': True, 'start': '25:00', 'end': '08:00'}}, '免打扰时间非法'), ({'freq': 'daily'}, '没有订阅设频率')]:
        check('U6 设置屏：%s 被拒（422）' % why, 422, api('POST', '/app/notify-settings', a_tok, bad).get('code'))
    b = api('POST', '/app/notify-settings', a_tok, {'rows': [{'key': 'community', 'app': True, 'staff': True}, {'key': 'bogus', 'app': False}],
                                                     'quiet': {'on': True, 'start': '23:30', 'end': '07:00'}})
    check('U6 设置屏保存：社区互动重新开启、免打扰落库、未知类型被忽略、无员工身份不写 staff 行',
          (200, '1', ['1', '23:30-07:00'], 0, 0),
          (b.get('code'), scalar("select enabled from kg_notify_pref where app_user_id=%s and pref_key='community'" % a_id),
           sql("select enabled, freq from kg_notify_pref where app_user_id=%s and pref_key='quiet'" % a_id)[0],
           n("select count(*) from kg_notify_pref where app_user_id=%s and pref_key='bogus'" % a_id),
           n("select count(*) from kg_notify_pref where app_user_id=%s and pref_key like 'staff:%%'" % a_id)))
    m3 = msgs()
    comment()
    check('U6 重新开启后评论提醒恢复写入', m3 + 1, msgs())
    b = api('POST', '/app/notify-settings', a_tok, {'rows': [{'key': 'community', 'app': False}]})
    m4 = msgs()
    comment()
    check('U6 在设置屏关掉小程序通知同样生效', m4, msgs())
    api('POST', '/app/notify-settings', a_tok, {'rows': [{'key': 'community', 'app': True}], 'quiet': {'on': False, 'start': '22:00', 'end': '08:00'}})

    # ================================================================ U7 简历
    section('U7 简历：基本信息 / 技能 / 经历')
    rs_tok, rs_id = login('resume')
    rs2_tok, _ = login('resume-other')
    d = data(api('GET', '/app/resume', rs_tok)) or {}
    check('U7 新账号简历：还没有落库、经历为空', (True, []), ((d.get('resume') or {}).get('isNew'), d.get('exps')))
    b = api('POST', '/app/resume/reuse', rs_tok)
    check('U7 没有报名记录时复用被拒（404）', 404, b.get('code'))
    for bad, why in [({'name': ''}, '空称呼'), ({'name': '小王', 'age': 10}, '年龄 10'), ({'name': '小王', 'phone': '123'}, '手机号'),
                     ({'name': '小王', 'gender': '未知'}, '性别'), ({'name': '小王', 'intro': '字' * 201}, '201 字介绍')]:
        check('U7 基本信息：%s 被拒（422）' % why, 422, api('PUT', '/app/resume/basic', rs_tok, bad).get('code'))
    b = api('PUT', '/app/resume/basic', rs_tok, {'name': '回归简历' + RUN[:4], 'gender': '女', 'age': 26, 'phone': '13900139000', 'city': '大理', 'intro': '做过两年咖啡师'})
    check('U7 基本信息落库', ['回归简历' + RUN[:4], '女', '26', '13900139000', '大理'],
          sql("select name, gender, age, phone, city from kg_resume where app_user_id=%s" % rs_id)[0] if b.get('code') == 200 else b)
    check('U7 技能：非 http 链接被拒（422）', 422, api('PUT', '/app/resume/skills', rs_tok, {'skill': '咖啡', 'links': ['ftp://x.y/z']}).get('code'))
    b = api('PUT', '/app/resume/skills', rs_tok, {'skill': '手冲咖啡、拉花、民宿前台接待、摄影修图，会做社群运营', 'extra': '驾照', 'links': ['https://example.com/works']})
    check('U7 技能落库', ('https://example.com/works', True), (scalar("select links from kg_resume where app_user_id=%s" % rs_id),
                                                            '咖啡' in (scalar("select skill_text from kg_resume where app_user_id=%s" % rs_id) or '')))
    check('U7 经历：非法类型被拒（422）', 422, api('POST', '/app/resume/exps', rs_tok, {'kind': 'hack', 'title': 'x'}).get('code'))
    check('U7 经历：日期格式非法被拒（422）', 422, api('POST', '/app/resume/exps', rs_tok, {'kind': 'volunteer', 'title': 'x', 'startDate': '2025/01/01'}).get('code'))
    b = api('POST', '/app/resume/exps', rs_tok, {'kind': 'parttime', 'title': '回归兼职' + RUN[:4], 'place': '大理古城', 'startDate': '2025-03-01',
                                                 'endDate': '2025-04-30', 'content': '做咖啡'})
    exp_id = n("select max(exp_id) from kg_resume_exp where app_user_id=%s and title='回归兼职%s'" % (rs_id, RUN[:4]))
    check('U7 手填经历落库（来源 manual）', ['manual', 'parttime', '2025-03-01'],
          sql("select source, kind, start_date from kg_resume_exp where exp_id=%s" % exp_id)[0] if exp_id else b)
    b = api('POST', '/app/resume/exps', rs_tok, {'kind': 'volunteer', 'title': '倒着的经历', 'startDate': '2025-05-01', 'endDate': '2025-01-01'})
    bad_exp = n("select count(*) from kg_resume_exp where app_user_id=%s and title='倒着的经历' and del_flag='0'" % rs_id)
    check('U7 结束日期早于开始日期的经历被拒', (True, 0), (not_ok(b), bad_exp))
    b = api('PUT', '/app/resume/exps/%s' % exp_id, rs2_tok, {'kind': 'parttime', 'title': '被别人改了'})
    check('U7 别人改不了我的经历（403）且内容未变', (403, '回归兼职' + RUN[:4]), (b.get('code'), scalar("select title from kg_resume_exp where exp_id=%s" % exp_id)))
    b = api('PUT', '/app/resume/exps/%s' % exp_id, rs_tok, {'kind': 'parttime', 'title': '回归兼职改', 'place': '喜洲', 'startDate': '2025-03-01', 'endDate': '2025-05-31'})
    check('U7 本人修改经历落库', ['回归兼职改', '喜洲', '2025-05-31'], sql("select title, place, end_date from kg_resume_exp where exp_id=%s" % exp_id)[0])
    b = api('DELETE', '/app/resume/exps/%s' % exp_id, rs2_tok)
    check('U7 别人删不了我的经历（403）', (403, '0'), (b.get('code'), scalar("select del_flag from kg_resume_exp where exp_id=%s" % exp_id)))
    d = data(api('GET', '/app/resume', rs_tok)) or {}
    check('U7 简历完整度随填写增加、统计里有 1 段兼职', (True, 1),
          ((d.get('completeness') or 0) >= 80, [s.get('num') for s in d.get('stats') or [] if s.get('title') == '兼职经历'][0] if d.get('stats') else None))
    b = api('DELETE', '/app/resume/exps/%s' % exp_id, rs_tok)
    d = data(b) or {}
    check('U7 本人删除：软删落库、简历里不再出现', ('2', False), (scalar("select del_flag from kg_resume_exp where exp_id=%s" % exp_id),
                                                   exp_id in [e.get('expId') for e in d.get('exps') or []]))
    b = api('GET', '/app/resume/opportunities?city=' + urllib.parse.quote('大理'), rs_tok)
    jobs = (data(b) or {}).get('jobs') or []
    bad_jobs = [j.get('jobId') for j in jobs if scalar("select concat(status, '|', del_flag) from kg_job where job_id=%s" % j.get('jobId')) != 'published|0']
    check('U7 找机会：按简历技能（咖啡制作）匹配、只返回在招岗位', (200, True, []), (b.get('code'), '咖啡制作' in ((data(b) or {}).get('skills') or []), bad_jobs))

    # ================================================================ U8 订阅 / 关注 / 收藏
    section('U8 新机会订阅、关注门店、收藏')
    sb_tok, sb_id = login('subscriber')
    sb2_tok, _ = login('subscriber-other')
    check('U8 空订阅被拒（422）', 422, api('POST', '/app/job-subscriptions', sb_tok, {'freq': 'daily'}).get('code'))
    b = api('POST', '/app/job-subscriptions', sb_tok, {'cities': ['大理', ' '], 'jobTypes': ['咖啡/茶饮'], 'fromDate': day(3), 'minMatch': 70, 'freq': 'daily'})
    sub_id = (data(b) or {}).get('subId')
    check('U8 新建订阅落库（空白城市被剔除、每日汇总）', ['大理', '咖啡/茶饮', '70', 'daily', '0'],
          sql("select cities, job_types, min_match, freq, status from kg_job_subscribe where sub_id=%s and app_user_id=%s" % (sub_id, sb_id))[0] if sub_id else b)
    lst = data(api('GET', '/app/job-subscriptions', sb_tok)) or []
    check('U8 订阅列表里有它；别人的列表里没有', (True, False),
          (sub_id in [x.get('subId') for x in lst], sub_id in [x.get('subId') for x in data(api('GET', '/app/job-subscriptions', sb2_tok)) or []]))
    b = api('PUT', '/app/job-subscriptions/%s' % sub_id, sb2_tok, {'cities': ['丽江']})
    check('U8 别人改不了我的订阅（404）且内容未变', (404, '大理'), (b.get('code'), scalar("select cities from kg_job_subscribe where sub_id=%s" % sub_id)))
    b = api('PUT', '/app/job-subscriptions/%s' % sub_id, sb_tok, {'cities': ['丽江', '大理'], 'jobTypes': [], 'freq': 'instant', 'minMatch': 10})
    check('U8 本人修改订阅落库（匹配度越界回落 60）', ['丽江,大理', 'instant', '60'], sql("select cities, freq, min_match from kg_job_subscribe where sub_id=%s" % sub_id)[0])
    b = api('PUT', '/app/notify-prefs', sb_tok, {'key': 'job_new', 'freq': 'daily'})
    check('U8 在「我的提醒」把新机会改成每日汇总：所有订阅跟着改', (200, 'daily', '每日汇总'),
          (b.get('code'), scalar("select freq from kg_job_subscribe where sub_id=%s" % sub_id),
           [x.get('freq') for x in (data(b) or {}).get('active', []) if x.get('key') == 'job_new'][0] if ok(b) else None))
    b = api('POST', '/app/notify-settings', sb_tok, {'freq': 'instant'})
    check('U8 在设置屏改回即时：订阅频率同步', (200, 'instant', 'instant'),
          (b.get('code'), scalar("select freq from kg_job_subscribe where sub_id=%s" % sub_id), (data(b) or {}).get('freq')))
    # 岗位详情 / 同行队伍页的「有空位提醒我」、城市页的「订阅这个城市」都直接调新建订阅；重复点不应攒出重复订阅
    api('POST', '/app/job-subscriptions', sb_tok, {'storeId': store_id, 'freq': 'instant'})
    api('POST', '/app/job-subscriptions', sb_tok, {'storeId': store_id, 'freq': 'instant'})
    dup = n("select count(*) from kg_job_subscribe where app_user_id=%s and store_id=%s" % (sb_id, store_id))
    check('U8 同一门店「有空位提醒我」点两次不产生重复订阅', 1, dup)
    api('POST', '/app/stores/%s/follow?follow=false' % store_id, sb_tok)
    api('POST', '/app/stores/%s/follow?follow=false' % store_id, sb_tok)
    check('U8 （清理）取消关注后该门店订阅清空', 0, n("select count(*) from kg_job_subscribe where app_user_id=%s and store_id=%s" % (sb_id, store_id)))
    b = api('POST', '/app/stores/%s/follow?follow=true' % store_id, sb_tok)
    api('POST', '/app/stores/%s/follow?follow=true' % store_id, sb_tok)
    check('U8 关注门店：生成一条只带门店的订阅，重复关注不重复', (200, True, 1),
          (b.get('code'), (data(b) or {}).get('following'), n("select count(*) from kg_job_subscribe where app_user_id=%s and store_id=%s" % (sb_id, store_id))))
    check('U8 关注不存在的门店被拒', True, not_ok(api('POST', '/app/stores/99999999/follow?follow=true', sb_tok)),
          ok=not_ok(api('POST', '/app/stores/99999999/follow?follow=true', sb_tok)))
    b = api('POST', '/app/stores/%s/follow?follow=false' % store_id, sb_tok)
    check('U8 取消关注删库', (False, 0), ((data(b) or {}).get('following'), n("select count(*) from kg_job_subscribe where app_user_id=%s and store_id=%s" % (sb_id, store_id))))
    b = api('DELETE', '/app/job-subscriptions/%s' % sub_id, sb2_tok)
    check('U8 别人删不了我的订阅（404）', (404, 1), (b.get('code'), n("select count(*) from kg_job_subscribe where sub_id=%s" % sub_id)))
    b = api('DELETE', '/app/job-subscriptions/%s' % sub_id, sb_tok)
    check('U8 本人删除订阅', (200, 0), (b.get('code'), n("select count(*) from kg_job_subscribe where sub_id=%s" % sub_id)))
    check('U8 删除不存在的订阅（404）', 404, api('DELETE', '/app/job-subscriptions/%s' % sub_id, sb_tok).get('code'))
    made = [api('POST', '/app/job-subscriptions', sb_tok, {'keyword': 'k%d' % i}).get('code') for i in range(11)]
    check('U8 订阅最多 10 条：第 11 条被拒（409）', ([200] * 10, 409), (made[:10], made[10]))
    for sid in [r[0] for r in sql("select sub_id from kg_job_subscribe where app_user_id=%s" % sb_id)]:
        api('DELETE', '/app/job-subscriptions/%s' % sid, sb_tok)

    fav_job = int(scalar("select job_id from kg_job where status='published' and del_flag='0' and job_type<>'online' order by job_id desc limit 1"))
    api('POST', '/app/job/favorite/%s?favorite=true' % fav_job, sb_tok)
    api('POST', '/app/stay/%s/favorite?favorite=true' % stay_id, sb_tok)
    api('POST', '/app/shop/services/%s/favorite?favorite=true' % service_id, sb_tok)
    fj = [x.get('jobId') for x in data(api('GET', '/app/job/favorites', sb_tok)) or []]
    fs = [x.get('stayId') for x in (data(api('GET', '/app/stay/favorites?pageNum=1&pageSize=50', sb_tok)) or {}).get('rows') or []]
    fv = [x.get('serviceId') for x in data(api('GET', '/app/shop/favorites', sb_tok)) or []]
    check('U8 三类收藏都能在「我的收藏」里读回', (True, True, True), (fav_job in fj, stay_id in fs, service_id in fv))
    check('U8 别人的收藏列表里没有', (False, False, False),
          (fav_job in [x.get('jobId') for x in data(api('GET', '/app/job/favorites', sb2_tok)) or []],
           stay_id in [x.get('stayId') for x in (data(api('GET', '/app/stay/favorites?pageNum=1&pageSize=50', sb2_tok)) or {}).get('rows') or []],
           service_id in [x.get('serviceId') for x in data(api('GET', '/app/shop/favorites', sb2_tok)) or []]))
    api('POST', '/app/job/favorite/%s?favorite=false' % fav_job, sb_tok)
    api('POST', '/app/stay/%s/favorite?favorite=false' % stay_id, sb_tok)
    api('POST', '/app/shop/services/%s/favorite?favorite=false' % service_id, sb_tok)
    check('U8 取消收藏后三类都清空（库）', (0, 0), (n("select count(*) from kg_job_favorite where app_user_id=%s" % sb_id),
                                              n("select count(*) from kg_favorite where app_user_id=%s" % sb_id)))

    # ================================================================ U9 首页 / 城市 / 筛选 / 客服问答
    section('U9 首页、城市页、岗位筛选、条件匹配、智能客服')
    cities = data(api('GET', '/app/cities')) or {}
    hot_db = set(r[0] for r in sql("select name from kg_city where status='0' and hot='1'"))
    check('U9 城市列表的热门城市与库一致', hot_db, set(x.get('name') if isinstance(x, dict) else x for x in cities.get('hot') or []))
    dl = sql("select lat, lng from kg_city where name='大理' and status='0'")
    loc = data(api('GET', '/app/cities/locate?lat=%s&lng=%s' % (dl[0][0], dl[0][1]))) or {}
    far = data(api('GET', '/app/cities/locate?lat=0&lng=0')) or {}
    check('U9 定位：大理坐标定位到大理；海上定位不到', (True, '大理', False), (loc.get('found'), loc.get('name'), far.get('found')))
    ov = data(api('GET', '/app/cities/overview?city=' + urllib.parse.quote('大理'))) or {}
    check('U9 城市页数字与库一致（在招岗位 / 房源 / 门店）',
          (n("select count(*) from kg_job where del_flag='0' and status='published' and job_type<>'online' and city like '%大理%'"),
           n("select count(*) from kg_stay s where s.del_flag='0' and s.status='online' and s.city='大理' "
             "and exists(select 1 from kg_store st where st.store_id=s.store_id and st.status='0' and st.del_flag='0') "
             "and exists(select 1 from kg_stay_room_type rt where rt.stay_id=s.stay_id and rt.status='0' and rt.del_flag='0')"),
           n("select count(*) from kg_store where del_flag='0' and status='0' and city like '%大理%'")),
          (ov.get('jobs'), ov.get('stays'), ov.get('stores')))
    feed = data(api('GET', '/app/home/feed?city=' + urllib.parse.quote('大理'), rs_tok)) or {}
    fj = feed.get('jobs') or []
    bad = [j.get('jobId') for j in fj if scalar("select concat(status,'|',job_type<>'online','|',city like '%%大理%%') from kg_job where job_id=%s" % j.get('jobId')) != 'published|1|1']
    check('U9 首页推荐：最多 3 个、都是大理在招的线下岗位、有简历技能即个性化', (True, [], True), (len(fj) <= 3, bad, feed.get('personalized')))
    res = data(api('POST', '/app/jobs/search', rs_tok, {'cities': ['大理'], 'board': 'all', 'types': [], 'hours': 0})) or {}
    js = res.get('jobs') or []
    bad = [j.get('jobId') for j in js if scalar("select concat(city like '%%大理%%','|',board_type) from kg_job where job_id=%s" % j.get('jobId')) != '1|all']
    check('U9 筛选「大理 + 包食宿」：结果都满足条件、total 与条数一致', ([], len(js)), (bad, res.get('total')))
    res = data(api('POST', '/app/jobs/search', rs_tok, {'cities': ['大理'], 'salaryMin': 99999, 'minStay': 7})) or {}
    check('U9 筛选无结果时给出冲突条件与放宽建议', (0, True, True),
          (res.get('total'), '期望薪资区间' in (res.get('conflicts') or []), 'salary' in [x.get('key') for x in res.get('relax') or []]))
    m = data(api('POST', '/app/home/match', rs_tok, {'text': '想在大理待两周，会做咖啡', 'city': '大理'})) or {}
    bad = [j.get('jobId') for j in m.get('jobs') or [] if (scalar("select city like '%%大理%%' from kg_job where job_id=%s" % j.get('jobId')) != '1')]
    check('U9 条件匹配：城市大理、停留 14 天、岗位都在大理', ('大理', 14, []), (m.get('city'), m.get('stayDays'), bad))
    q = data(api('GET', '/app/support/quick?context=order')) or []
    check('U9 客服快捷问题（订单场景 3 条 / 我的场景 5 条）', (3, 5), (len(q), len(data(api('GET', '/app/support/quick?context=profile')) or [])))
    # 这几个接口标注了 @AppAuth(required = false)，服务层也写了 me == null 的游客分支（按 IP 限流、游客不个性化）
    anon = [(p, api(m, p, None, bd).get('code')) for m, p, bd in [
        ('GET', '/app/home/feed?city=' + urllib.parse.quote('大理'), None), ('POST', '/app/home/match', {'city': '大理'}),
        ('POST', '/app/jobs/search', {'cities': ['大理']}), ('POST', '/app/support/ask', {'question': '附近有什么兼职？'}),
        ('GET', '/app/jobs/%s/team' % fav_job, None)]]
    check('U9 游客可访问首页推荐 / 条件匹配 / 岗位筛选 / 智能客服 / 同行队伍（标注为免登录）', [], [x for x in anon if x[1] != 200])
    check('U9 空问题被拒（422）', 422, api('POST', '/app/support/ask', rs_tok, {'question': '  '}).get('code'))
    b = api('POST', '/app/support/ask', rs_tok, {'question': '量子力学是什么 ' + RUN, 'context': 'home'})
    q_log = (data(b) or {}).get('logId')
    check('U9 答不上来如实转人工并留痕（记录提问人、意图 unknown）', ('unknown', True, [str(rs_id), 'unknown']),
          ((data(b) or {}).get('intent'), (data(b) or {}).get('canHuman'),
           sql("select app_user_id, intent from kg_support_log where log_id=%s" % q_log)[0] if q_log else None))
    b = api('POST', '/app/support/ask', rs_tok, {'question': '我的报名进度怎么样', 'context': 'profile'})
    check('U9 没有报名的用户问进度：如实说没有', ('progress', True), ((data(b) or {}).get('intent'), '还没有报名' in str((data(b) or {}).get('text'))))
    log_id = (data(b) or {}).get('logId')
    api('POST', '/app/support/feedback', rs_tok, {'logId': log_id, 'helpful': True})
    api('POST', '/app/support/feedback', rs2_tok, {'logId': log_id, 'helpful': False})
    check('U9 回答有用反馈落库；别人改不了我的反馈', '1', scalar("select helpful from kg_support_log where log_id=%s" % log_id))
    ct = data(api('GET', '/app/support/contact')) or {}
    cfg = scalar("select config_value from sys_config where config_key='kangaroo.support.phone'") or ''
    check('U9 客服热线与参数设置一致（没配就如实说没开通）', (cfg.strip(), bool(cfg.strip())), (ct.get('phone'), ct.get('available')))

    # ================================================================ U10 工单
    section('U10 客服工单 → 后台回复 → 用户看到')
    tk_tok, tk_id = login('ticket')
    tk2_tok, _ = login('ticket-other')
    check('U10 未登录提交工单 401', 401, api('POST', '/app/support/tickets', None, {'question': 'x'}).get('code'))
    check('U10 空问题被拒（422）', 422, api('POST', '/app/support/tickets', tk_tok, {'question': ''}).get('code'))
    b = api('POST', '/app/support/tickets', tk_tok, {'question': '退款多久到账 ' + RUN, 'context': 'order', 'summary': '问退款', 'related': 'S123'})
    ticket = (data(b) or {}).get('ticketId')
    check('U10 工单落库为待处理', ['open', tk_id and str(tk_id)], sql("select status, app_user_id from kg_support_ticket where ticket_id=%s" % ticket)[0] if ticket else b)
    lst = data(api('GET', '/app/support/tickets', tk_tok)) or []
    check('U10 我的工单里能看到（处理中）；别人看不到', ([('处理中', None)], False),
          ([(x.get('statusLabel'), x.get('reply')) for x in lst if x.get('ticketId') == ticket],
           ticket in [x.get('ticketId') for x in data(api('GET', '/app/support/tickets', tk2_tok)) or []]))
    check('U10 小程序令牌调后台回复接口 401', 401, api('PUT', '/kangaroo/usersvc/ticket/%s/reply' % ticket, tk_tok, {'reason': 'x'}).get('code'))
    check('U10 后台空回复被拒', True, not_ok(api('PUT', '/kangaroo/usersvc/ticket/%s/reply' % ticket, admin=admin, body={'reason': ' '})),
          ok=scalar("select status from kg_support_ticket where ticket_id=%s" % ticket) == 'open')
    reply = '1–5 个工作日原路退回 ' + RUN
    b = api('PUT', '/kangaroo/usersvc/ticket/%s/reply' % ticket, admin=admin, body={'reason': reply})
    check('U10 后台回复：工单已回复并记录回复人', (200, ['replied', reply, 'admin:admin']),
          (b.get('code'), sql("select status, reply, replied_by from kg_support_ticket where ticket_id=%s" % ticket)[0]))
    lst = data(api('GET', '/app/support/tickets', tk_tok)) or []
    check('U10 用户侧工单显示已回复与回复内容', [('已回复', reply)], [(x.get('statusLabel'), x.get('reply')) for x in lst if x.get('ticketId') == ticket])
    check('U10 用户消息中心收到客服回复', 1, n("select count(*) from kg_message where recipient_id=%s and biz_type='support_ticket' and biz_id=%s" % (tk_id, ticket)))
    check('U10 满意度越界被拒（422）', 422, api('POST', '/app/support/tickets/%s/rate' % ticket, tk_tok, {'score': 6}).get('code'))
    check('U10 别人给我的工单打分（404）', 404, api('POST', '/app/support/tickets/%s/rate' % ticket, tk2_tok, {'score': 1}).get('code'))
    b = api('POST', '/app/support/tickets/%s/rate' % ticket, tk_tok, {'score': 5})
    check('U10 本人打分落库', (200, '5'), (b.get('code'), scalar("select satisfaction from kg_support_ticket where ticket_id=%s" % ticket)))
    made = [api('POST', '/app/support/tickets', tk_tok, {'question': '第%d个问题' % i}).get('code') for i in range(6)]
    check('U10 待处理工单最多 5 个：第 6 个被拒（409）', ([200] * 5, 409), (made[:5], made[5]))

    # ================================================================ U11 体验官
    section('U11 旅行体验官申请 → 后台审核')
    for bad, why in [({'cities': [], 'plan': '写攻略写攻略写攻略写攻略'}, '没选城市'), ({'cities': ['大理'], 'plan': '太短'}, '计划太短'),
                     ({'cities': ['a', 'b', 'c', 'd', 'e', 'f'], 'plan': '写攻略写攻略写攻略写攻略'}, '超过 5 个城市')]:
        check('U11 %s 被拒（422）' % why, 422, api('POST', '/app/guide-apply', tk_tok, bad).get('code'))
    b = api('POST', '/app/guide-apply', tk_tok, {'cities': ['大理', '丽江'], 'plan': '每月写两篇大理小众路线攻略', 'works': 'https://example.com/a'})
    apply_id = n("select max(apply_id) from kg_guide_apply where app_user_id=%s" % tk_id)
    check('U11 申请落库为待审核', (200, ['pending', '大理,丽江']), (b.get('code'), sql("select status, cities from kg_guide_apply where apply_id=%s" % apply_id)[0]))
    check('U11 审核中重复申请被拒（409）', 409, api('POST', '/app/guide-apply', tk_tok, {'cities': ['大理'], 'plan': '每月写两篇大理小众路线攻略'}).get('code'))
    st = data(api('GET', '/app/guide-apply', tk_tok)) or {}
    check('U11 申请状态页显示最新一份为待审核', 'pending', (st.get('latest') or {}).get('status'))
    check('U11 驳回不写原因被拒', 'pending', scalar("select status from kg_guide_apply where apply_id=%s" % apply_id) if not_ok(
        api('PUT', '/kangaroo/usersvc/guide/%s/reject' % apply_id, admin=admin, body={'reason': ''})) else 'rejected-without-reason')
    b = api('PUT', '/kangaroo/usersvc/guide/%s/reject' % apply_id, admin=admin, body={'reason': '作品不足'})
    check('U11 后台驳回：状态、原因落库，用户收到通知', (200, ['rejected', '作品不足'], 1),
          (b.get('code'), sql("select status, audit_note from kg_guide_apply where apply_id=%s" % apply_id)[0],
           n("select count(*) from kg_message where recipient_id=%s and biz_type='guide_apply' and biz_id=%s" % (tk_id, apply_id))))
    check('U11 已驳回的不能再审', True, not_ok(api('PUT', '/kangaroo/usersvc/guide/%s/approve' % apply_id, admin=admin)),
          ok=scalar("select status from kg_guide_apply where apply_id=%s" % apply_id) == 'rejected')
    api('POST', '/app/guide-apply', tk_tok, {'cities': ['大理'], 'plan': '补充了作品，再申请一次试试看'})
    apply2 = n("select max(apply_id) from kg_guide_apply where app_user_id=%s" % tk_id)
    b = api('PUT', '/kangaroo/usersvc/guide/%s/approve' % apply2, admin=admin)
    check('U11 驳回后可再次申请，后台通过', (True, 'approved'), (apply2 != apply_id, scalar("select status from kg_guide_apply where apply_id=%s" % apply2)))

    # ================================================================ U12 报名全流程
    section('U12 报名全流程：同行队伍、责任书、到店信息、路线电话、平台经历、评价')
    j_tok, j_id = login('applicant')
    k_tok, k_id = login('teammate-viewer')
    mgr_tok_tmp, mgr_id = login('manager')
    b = api('POST', '/kangaroo/grant', admin=admin, body={'appUserId': mgr_id, 'storeId': store_id, 'roleCode': 'manager'})
    assert b.get('code') == 200, b
    mgr_tok, _ = login('manager')
    b = api('POST', '/kangaroo/job', admin=admin, body={
        'storeId': store_id, 'jobType': 'volunteer', 'title': '回归义工-' + RUN, 'city': '大理', 'address': '古城人民路 1 号',
        'salaryType': 'daily', 'salaryMin': 100, 'salaryUnit': '元/天', 'startDate': day(0), 'minDays': 1, 'dailyHours': '每天4小时',
        'boardType': 'all', 'headcount': 2, 'tags': '咖啡,前台', 'content': '协助前台接待', 'requirement': '开朗'})
    assert b.get('code') == 200, b
    job_id = int(b['data'])
    api('PUT', '/kangaroo/job/changeStatus', admin=admin, body={'jobId': job_id, 'status': 'published'})
    check('U12 准备：新岗位已发布', 'published', scalar("select status from kg_job where job_id=%s" % job_id))

    b = api('POST', '/app/jobs/%s/team' % job_id, j_tok, {'visible': True})
    check('U12 没报名就加入同行队伍被拒（403）', (403, 0), (b.get('code'), n("select count(*) from kg_team_member where job_id=%s and app_user_id=%s" % (job_id, j_id))))
    b = api('POST', '/app/application', j_tok, {'jobId': job_id, 'name': '回归同学', 'age': 24, 'phone': '13500135000', 'skills': '咖啡',
                                                 'arrival': day(2), 'days': 20, 'idemKey': 'usr-%s-app' % RUN})
    assert b.get('code') == 200, b
    app_id = int(b['data']['appId'])
    rt = data(api('GET', '/app/route?storeId=%s' % store_id, j_tok)) or {}
    check('U12 刚报名（未录用）看路线：有地址、不给门店电话', (store_id, None, True), (rt.get('storeId'), rt.get('phone'), bool(rt.get('phoneHint'))))
    ct = data(api('GET', '/app/application/%s/contract' % app_id, j_tok)) or {}
    check('U12 未录用：责任书不可签、不给门店电话与地址', (False, None, None), (ct.get('canSign'), ct.get('storePhone'), ct.get('address')))
    check('U12 别人查不到我的责任书（404）', 404, api('GET', '/app/application/%s/contract' % app_id, k_tok).get('code'))
    b = api('POST', '/app/application/%s/contract/sign' % app_id, j_tok, {'name': '回归同学', 'agree': True, 'hash': ct.get('hash')})
    check('U12 未录用签署被拒（409）且没有签署记录', (409, 0), (b.get('code'), n("select count(*) from kg_contract_sign where app_id=%s" % app_id)))
    check('U12 未录用填写到店信息被拒（409）', 409, api('PUT', '/app/application/%s/arrival' % app_id, j_tok, {'arriveDate': day(2)}).get('code'))

    b = api('POST', '/app/jobs/%s/team' % job_id, j_tok, {'visible': True})
    tm = data(b) or {}
    check('U12 报名后加入同行队伍：自己在队伍里', (200, True, True), (b.get('code'), tm.get('joined'), j_id in [x.get('userId') for x in tm.get('members') or []]))
    tv = data(api('GET', '/app/jobs/%s/team' % job_id, k_tok)) or {}
    check('U12 其他用户看得到这位同行，且不下发手机号', (True, False), (j_id in [x.get('userId') for x in tv.get('members') or []], '13500135000' in dumps(tv)))
    api('POST', '/app/community/users/%s/block' % j_id, k_tok)
    tv = data(api('GET', '/app/jobs/%s/team' % job_id, k_tok)) or {}
    check('U12 拉黑后同行队伍里看不到对方', False, j_id in [x.get('userId') for x in tv.get('members') or []])
    api('POST', '/app/me/blocks/%s/remove' % j_id, k_tok)
    api('POST', '/app/jobs/%s/team' % job_id, j_tok, {'visible': False})
    tv = data(api('GET', '/app/jobs/%s/team' % job_id, k_tok)) or {}
    check('U12 本人设为不公开后，别人看不到', ('0', False), (scalar("select visible from kg_team_member where job_id=%s and app_user_id=%s" % (job_id, j_id)),
                                               j_id in [x.get('userId') for x in tv.get('members') or []]))

    # 推进到 门店确认录用
    b = api('PUT', '/kangaroo/application/audit', admin=admin, body={'appId': app_id, 'pass': True})
    assert b.get('code') == 200, b
    base = time.time() + 86400 * 9 + random.randint(0, 20000) * 60
    b = api('POST', '/kangaroo/slot', admin=admin, body={'storeId': store_id, 'startTime': time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base)),
                                                      'endTime': time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base + 1200))})
    assert b.get('code') == 200, b
    slots = data(api('GET', '/app/application/%s/slots' % app_id, j_tok)) or []
    assert slots, '需要可选面试时段'
    booked = api('POST', '/app/application/%s/book-interview' % app_id, j_tok, {'slotId': slots[0]['slotId']})
    assert booked.get('code') == 200, booked
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(app_id)
    for step, bb in [('预约面试', booked),
                     ('结束面试', api('POST', '/app/application/%s/finish-interview' % app_id, j_tok, {})),
                     ('确认意向', api('POST', '/app/application/%s/confirm' % app_id, j_tok, {'intent': 'accept'})),
                     ('门店确认录用', api('PUT', '/kangaroo/application/confirm/%s' % app_id, admin=admin))]:
        assert bb.get('code') == 200, (step, bb)
    check('U12 准备：报名已到「门店确认录用」', 'store_confirmed', scalar("select status from kg_application where app_id=%s" % app_id))

    rt = data(api('GET', '/app/route?storeId=%s' % store_id, j_tok)) or {}
    check('U12 录用后看路线：给门店电话', store_phone, rt.get('phone'))
    rt0 = data(api('GET', '/app/route', j_tok)) or {}
    check('U12 不传参数：自动取最近要去的录用门店', ('store', store_id, True), (rt0.get('kind'), rt0.get('storeId'), '回归义工' in str(rt0.get('visit'))))
    rt2 = data(api('GET', '/app/route?storeId=%s' % other_store, j_tok)) or {}
    check('U12 与我无关的门店：不给电话', None, rt2.get('phone'))
    ct = data(api('GET', '/app/application/%s/contract' % app_id, j_tok)) or {}
    check('U12 录用后：可签署、给门店电话与地址', (True, store_phone, True), (ct.get('canSign'), ct.get('storePhone'), bool(ct.get('address'))))
    # 责任书「点此打开地图」之前坐标一律取门店的。岗位地址（古城人民路 1 号）不是门店地址、岗位也没在地图上选点时，
    # 门店登记了坐标也不给，页面只显示地址文字，免得把人导航到门店
    b = api('PUT', '/kangaroo/store', admin=admin, body={'storeId': store_id, 'latitude': 25.6065, 'longitude': 100.2679})
    assert b.get('code') == 200, b
    ct = data(api('GET', '/app/application/%s/contract' % app_id, j_tok)) or {}
    check('U12 岗位地址不是门店地址、岗位没选点：只给地址、不给门店坐标', ('古城人民路 1 号', None, None),
          (ct.get('address'), ct.get('latitude'), ct.get('longitude')))
    for bad, code, why in [({'name': '回归同学', 'agree': True, 'hash': 'deadbeef'}, 409, '条款摘要不符'),
                           ({'name': '回归同学', 'agree': False, 'hash': ct.get('hash')}, 422, '没勾选同意'),
                           ({'name': '回', 'agree': True, 'hash': ct.get('hash')}, 422, '姓名太短'),
                           ({'name': '回归同学', 'idLast4': '12a4', 'agree': True, 'hash': ct.get('hash')}, 422, '后四位非法')]:
        check('U12 签署：%s 被拒（%s）' % (why, code), code, api('POST', '/app/application/%s/contract/sign' % app_id, j_tok, bad).get('code'))
    check('U12 被拒的签署都没有落库', ('store_confirmed', 0),
          (scalar("select status from kg_application where app_id=%s" % app_id), n("select count(*) from kg_contract_sign where app_id=%s" % app_id)))
    b = api('POST', '/app/application/%s/contract/sign' % app_id, k_tok, {'name': '冒名顶替', 'agree': True, 'hash': ct.get('hash')})
    check('U12 别人替我签署被拒（404）', (404, 0), (b.get('code'), n("select count(*) from kg_contract_sign where app_id=%s" % app_id)))
    b = api('POST', '/app/application/%s/contract/sign' % app_id, j_tok, {'name': '回归同学', 'idLast4': '123x', 'agree': True, 'hash': ct.get('hash')})
    check('U12 本人签署：状态变已签、存证落库（摘要 / 后四位 / 版本）', (200, 'contract_signed', [ct.get('hash'), '123X', '2026-10']),
          (b.get('code'), scalar("select status from kg_application where app_id=%s" % app_id),
           sql("select content_hash, id_last4, version from kg_contract_sign where app_id=%s" % app_id)[0] if n("select count(*) from kg_contract_sign where app_id=%s" % app_id) else None))
    check('U12 重复签署被拒（409）', 409, api('POST', '/app/application/%s/contract/sign' % app_id, j_tok, {'name': '回归同学', 'agree': True, 'hash': ct.get('hash')}).get('code'))
    for bad, why in [({'transport': '火箭'}, '交通方式'), ({'arriveTime': '25:00'}, '到达时间'), ({'arriveDate': '明天'}, '到达日期'),
                     ({'tickets': ['https://evil.example.com/t.png']}, '外站票据')]:
        check('U12 到店信息：%s 非法被拒（422）' % why, 422, api('PUT', '/app/application/%s/arrival' % app_id, j_tok, bad).get('code'))
    mgr_msgs = n("select count(*) from kg_message where recipient_id=%s and biz_type='application' and biz_id=%s and title='报名人更新了到店信息'" % (mgr_id, app_id))
    # 票据图片必须是本人上传过的（arrivalTickets.validateForSave）；随手写一个别人的上传地址要被拒，不带票据照常保存
    check('U12 到店信息：非本人上传的票据被拒（422）', 422, api('PUT', '/app/application/%s/arrival' % app_id, j_tok,
          {'arriveDate': day(2), 'tickets': ['/profile/upload/2026/09/26/t.png']}).get('code'))
    b = api('PUT', '/app/application/%s/arrival' % app_id, j_tok, {'arriveDate': day(2), 'arriveTime': '15:30', 'transport': '火车', 'transportNo': 'D8888',
                                                                   'needPickup': True, 'tickets': [], 'note': '行李多'})
    # 「接站需求」已下线：仍按旧版小程序的样子带 needPickup:True，服务端不读也不写，新行 need_pickup 是建表默认值 '0'
    check('U12 到店信息落库（旧版带的接站开关被忽略）', (200, [day(2), '15:30', '火车', 'D8888', '0', '']),
          (b.get('code'), sql("select arrive_date, arrive_time, transport, transport_no, need_pickup, tickets from kg_app_arrival where app_id=%s" % app_id)[0]
           if n("select count(*) from kg_app_arrival where app_id=%s" % app_id) else None))
    check('U12 门店主理人收到到店信息通知', mgr_msgs + 1,
          n("select count(*) from kg_message where recipient_id=%s and biz_type='application' and biz_id=%s and title='报名人更新了到店信息'" % (mgr_id, app_id)))
    notice = scalar("select content from kg_message where recipient_id=%s and biz_type='application' and biz_id=%s and title='报名人更新了到店信息' "
                    "order by msg_id desc limit 1" % (mgr_id, app_id)) or ''
    check('U12 到店信息通知里不再有「接站」', (True, False), ('火车' in notice, '接站' in notice))
    b = api('POST', '/app/staff/applications/%s/onboard' % app_id, mgr_tok)
    check('U12 门店标记入职', (200, 'onboarded'), (b.get('code'), scalar("select status from kg_application where app_id=%s" % app_id)))

    rs = data(api('GET', '/app/resume', j_tok)) or {}
    plat = [e for e in rs.get('exps') or [] if e.get('source') == 'platform']
    check('U12 入职后简历自动出现一段平台经历', (1, '回归义工-' + RUN), (len(plat), plat[0].get('title') if plat else None))
    pexp = plat[0].get('expId') if plat else 0
    b = api('PUT', '/app/resume/exps/%s' % pexp, j_tok, {'kind': 'volunteer', 'title': '改成别的'})
    check('U12 平台经历不能改（403）', (403, '回归义工-' + RUN), (b.get('code'), scalar("select title from kg_resume_exp where exp_id=%s" % pexp)))
    b = api('DELETE', '/app/resume/exps/%s' % pexp, j_tok)
    check('U12 平台经历不能删（403）', (403, '0'), (b.get('code'), scalar("select del_flag from kg_resume_exp where exp_id=%s" % pexp)))
    b = api('POST', '/app/resume/reuse', j_tok)
    check('U12 从最近一次报名复用：称呼 / 电话 / 年龄进简历', ['回归同学', '13500135000', '24'],
          sql("select name, phone, age from kg_resume where app_user_id=%s" % j_id)[0] if ok(b) else b)
    b = api('POST', '/app/support/ask', j_tok, {'question': '我的报名进度', 'context': 'profile'})
    check('U12 客服查进度：卡片里有这条报名', True, ('/pages/jobs/progress?appId=%s' % app_id) in [c.get('url') for c in (data(b) or {}).get('cards') or []],
          ok=('/pages/jobs/progress?appId=%s' % app_id) in [c.get('url') for c in (data(b) or {}).get('cards') or []])

    todo = data(api('GET', '/app/reviews/todo', j_tok)) or []
    check('U12 待评价里出现这条报名', True, ('application', app_id) in [(x.get('bizType'), x.get('bizId')) for x in todo],
          ok=('application', app_id) in [(x.get('bizType'), x.get('bizId')) for x in todo])
    od = [o for o in data(api('GET', '/app/orders', j_tok)) or [] if o.get('title') == '回归义工-' + RUN]
    check('U12 订单聚合里这条报名带「去评价」按钮', True, bool(od) and '去评价' in [x.get('label') for x in od[0].get('btns') or []],
          ok=bool(od) and '去评价' in [x.get('label') for x in od[0].get('btns') or []])
    for bad, code, why in [({'bizType': 'application', 'bizId': app_id, 'dims': [5, 6]}, 422, '分项 6 星'),
                           ({'bizType': 'application', 'bizId': app_id, 'dims': []}, 422, '没打分'),
                           ({'bizType': 'hack', 'bizId': app_id, 'dims': [5]}, 422, '未知类型'),
                           ({'bizType': 'application', 'bizId': app_id, 'dims': [5], 'content': '字' * 1001}, 422, '超 1000 字')]:
        check('U12 写评价：%s 被拒（%s）' % (why, code), code, api('POST', '/app/reviews', j_tok, bad).get('code'))
    b = api('POST', '/app/reviews', k_tok, {'bizType': 'application', 'bizId': app_id, 'dims': [1, 1, 1, 1, 1], 'content': '替别人差评'})
    check('U12 别人不能评价我的报名（403）', (403, 0), (b.get('code'), n("select count(*) from kg_review where biz_type='application' and biz_id=%s" % app_id)))
    content = '【住宿】干净【氛围】伙伴很好 ' + RUN
    mgr_rv = n("select count(*) from kg_message where recipient_id=%s and biz_type='review' and biz_id=%s" % (mgr_id, app_id))
    b = api('POST', '/app/reviews', j_tok, {'bizType': 'application', 'bizId': app_id, 'dims': [5, 4, 5, 4, 5], 'content': content, 'anonymous': True})
    check('U12 评价落库（评分取分项平均、匿名、展示）', (200, ['store', str(store_id), '5', '1', '0']),
          (b.get('code'), sql("select target_type, target_id, rating, anonymous, status from kg_review where biz_type='application' and biz_id=%s" % app_id)[0]
           if n("select count(*) from kg_review where biz_type='application' and biz_id=%s" % app_id) else None))
    check('U12 重复评价被拒（409）', 409, api('POST', '/app/reviews', j_tok, {'bizType': 'application', 'bizId': app_id, 'dims': [3]}).get('code'))
    check('U12 门店员工收到新评价通知', mgr_rv + 1, n("select count(*) from kg_message where recipient_id=%s and biz_type='review' and biz_id=%s" % (mgr_id, app_id)))
    check('U12 待评价里不再有它、「已发布」里有它',
          (False, True), (('application', app_id) in [(x.get('bizType'), x.get('bizId')) for x in data(api('GET', '/app/reviews/todo', j_tok)) or []],
                          RUN in dumps(data(api('GET', '/app/reviews/mine?featured=false', j_tok)))))
    sr = data(api('GET', '/app/stores/%s/reviews' % store_id)) or {}
    mine_rv = [x for x in sr.get('list') or [] if RUN in str(x.get('content'))]
    check('U12 门店评价页（同行队伍页入口）能看到、匿名展示', [('匿名用户', '')], [(x.get('name'), x.get('avatar')) for x in mine_rv])
    sr2 = data(api('GET', '/app/stores/%s/reviews?filter=%s' % (store_id, urllib.parse.quote('兼职')))) or {}
    check('U12 门店评价按「兼职」筛选不应混进义工岗位的评价', False, RUN in dumps(sr2.get('list')))

    # ================================================================ U13 住宿 / 二消 / 活动 → 订单聚合
    section('U13 住宿 / 二消 / 活动订单：订单聚合、路线电话、注销阻塞')
    g_tok, g_id = login('guest')
    h_tok, h_id = login('guest2')
    check_in, check_out = free_days(room_type_id, 2, 90 + random.randint(0, 80))
    b = api('POST', '/app/stay/orders', g_tok, {'roomTypeId': room_type_id, 'checkIn': check_in, 'checkOut': check_out, 'guests': 1, 'guestName': '回归住客',
                                                'guestPhone': '13800138000', 'agreementVersion': AGREEMENT, 'idemKey': 'usr-' + RUN})
    assert b.get('code') == 200, b
    order_no, pay_no = b['data']['orderNo'], b['data']['payNo']
    api('POST', '/app/pay/%s/prepay' % pay_no, g_tok, {})
    b = api('POST', '/app/pay/%s/mock-confirm' % pay_no, g_tok, {})
    check('U13 准备：住宿订单已支付确认', 'confirmed', scalar("select status from kg_stay_order where order_no='%s'" % order_no))
    b = api('POST', '/app/support/ask', h_tok, {'question': '取消要扣多少？', 'context': 'order', 'orderNo': order_no})
    check('U13 客服退款试算：别人的订单号查不到金额', True, not_ok(b) or not any(c.get('type') == 'refund' for c in (data(b) or {}).get('cards') or []),
          ok=not_ok(b) or not any(c.get('type') == 'refund' for c in (data(b) or {}).get('cards') or []))
    b = api('POST', '/app/support/ask', g_tok, {'question': '取消要扣多少？', 'context': 'order', 'orderNo': order_no})
    rc = [c for c in (data(b) or {}).get('cards') or [] if c.get('type') == 'refund']
    pv = data(api('GET', '/app/stay/orders/%s/refund-preview' % order_no, g_tok)) or {}
    check('U13 客服退款试算与订单页试算一致', [pv.get('refundFen')], [c.get('refundFen') for c in rc])
    rt = data(api('GET', '/app/route', g_tok)) or {}
    check('U13 不传参数：路线取进行中的住宿订单、给门店电话', ('stay', stay_id, store_phone), (rt.get('kind'), rt.get('stayId'), rt.get('phone')))
    rt_h = data(api('GET', '/app/route?stayId=%s' % stay_id, h_tok)) or {}
    check('U13 没有订单的人看同一房源：有地址、没电话', (True, None), (bool(rt_h.get('address')), rt_h.get('phone')))
    check('U13 什么都没有时路线 404', 404, api('GET', '/app/route', h_tok).get('code'))

    sl = data(api('GET', '/app/shop/services/%s/slots?stayOrderNo=%s' % (service_id, order_no), g_tok)) or {}
    pick = None
    for dd in sl.get('days') or []:
        for s in dd.get('slots') or []:
            if s.get('left', 0) > 0 and pick is None:
                pick = (dd.get('date'), s.get('slot'))
    assert pick, ('没有可预约的服务时段', sl)
    b = api('POST', '/app/shop/orders', g_tok, {'serviceId': service_id, 'stayOrderNo': order_no, 'useDate': pick[0], 'timeSlot': pick[1], 'qty': 1,
                                                'idemKey': 'usr-shop-' + RUN})
    check('U13 准备：二消订单已下单', 200, b.get('code'))
    shop_no = scalar("select order_no from kg_shop_order where app_user_id=%s order by order_id desc limit 1" % g_id)

    api('POST', '/app/community/consent', o_tok, {'doc': 'activity'})
    st = time.strftime('%Y-%m-%d %H:%M', time.localtime(time.time() + 86400 * 5))
    b = api('POST', '/app/activities', o_tok, {'title': '回归活动' + RUN, 'startTime': st, 'hours': 2, 'place': '古城南门', 'capacity': 5,
                                               'city': '大理', 'category': '其他', 'intro': '一起散步'})
    act_id = n("select max(activity_id) from kg_activity where app_user_id=%s and title='回归活动%s'" % (o_id, RUN))
    check('U13 准备：已验证手机号的用户发起免费活动（联调审核直接开放）', (200, 'open'), (b.get('code'), scalar("select status from kg_activity where activity_id=%s" % act_id)))
    b = api('POST', '/app/activities/%s/signup' % act_id, g_tok, {'channel': 'home'})
    check('U13 未同意活动须知不能报名（423）', 423, b.get('code'))
    api('POST', '/app/community/consent', g_tok, {'doc': 'activity'})
    org_msgs = lambda: n("select count(*) from kg_message where recipient_id=%s and biz_type='activity' and biz_id=%s" % (o_id, act_id))
    om0 = org_msgs()
    b = api('POST', '/app/activities/%s/signup' % act_id, g_tok, {'channel': 'home'})
    check('U13 报名免费活动直接确认', (200, 'confirmed'), (b.get('code'), (data(b) or {}).get('status')))
    check('U13 发起人收到新报名通知', om0 + 1, org_msgs())
    b = api('PUT', '/app/notify-prefs', o_tok, {'key': 'activity', 'enabled': False})
    api('POST', '/app/community/consent', h_tok, {'doc': 'activity'})
    hm0 = n("select count(*) from kg_message where recipient_id=%s and biz_type='activity' and biz_id=%s" % (h_id, act_id))
    b = api('POST', '/app/activities/%s/signup' % act_id, h_tok, {'channel': 'home'})
    check('U13 发起人暂停「活动提醒」后，再有人报名不再写给他；报名者本人照常收到', (200, om0 + 1, hm0 + 1),
          (b.get('code'), org_msgs(), n("select count(*) from kg_message where recipient_id=%s and biz_type='activity' and biz_id=%s" % (h_id, act_id))))
    api('PUT', '/app/notify-prefs', o_tok, {'key': 'activity', 'enabled': True})

    allo = data(api('GET', '/app/orders', g_tok)) or []
    types = {}
    for o in allo:
        types.setdefault(o.get('type'), []).append(o)
    stay_card = [o for o in types.get('住宿', []) if any(order_no in str(x.get('url')) for x in o.get('btns') or [])]
    shop_card = [o for o in types.get('二消', []) if any(str(shop_no) in str(x.get('url')) for x in o.get('btns') or [])]
    act_card = [o for o in types.get('活动', []) if o.get('title') == '回归活动' + RUN]
    check('U13 「我的订单」聚合了住宿（进行中）、二消（待付款）、活动（进行中）', (['进行中'], ['待付款'], ['进行中']),
          ([o.get('status') for o in stay_card], [o.get('status') for o in shop_card], [o.get('status') for o in act_card]))
    check('U13 住宿金额与订单一致', [str(int(scalar("select pay_fen from kg_stay_order where order_no='%s'" % order_no)) // 100)],
          [o.get('price') for o in stay_card])
    ing = data(api('GET', '/app/orders?tab=' + urllib.parse.quote('进行中'), g_tok)) or []
    check('U13 按「进行中」筛选只剩进行中的', (True, True), (all(o.get('status') == '进行中' for o in ing), len(ing) >= 2))
    other = dumps(data(api('GET', '/app/orders', h_tok)) or [])
    check('U13 别人的订单聚合里没有我的住宿 / 二消单', (False, False), (order_no in other, str(shop_no) in other))
    check('U13 未登录看订单 401', 401, api('GET', '/app/orders').get('code'))

    dc = data(api('GET', '/app/me/deactivation', g_tok)) or {}
    bl = ' '.join(dc.get('blockers') or [])
    check('U13 有进行中的订单 / 活动时不能注销，并说明原因', (False, True, True, True), (dc.get('canDeactivate'), '住宿' in bl, '二消' in bl, '活动' in bl))
    b = api('POST', '/app/me/deactivate', g_tok, {'confirm': '确认注销'})
    check('U13 强行注销被拒（409）且账号仍在', (409, '0'), (b.get('code'), scalar("select del_flag from kg_app_user where user_id=%s" % g_id)))

    # ================================================================ U14 导出
    section('U14 个人数据导出')
    st_, ct_, raw = get_bytes('/app/me/export.xlsx', None)
    check('U14 未登录导出被拒', True, b'401' in raw or st_ == 401, ok=b'401' in raw or st_ == 401)
    before = n("select count(*) from kg_pii_access where viewer_id=%s and biz_type='self_export'" % j_id)
    st_, ct_, raw = get_bytes('/app/me/export.xlsx', j_tok)
    names, strings = [], ''
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
        names = z.read('xl/workbook.xml').decode('utf-8')
        strings = z.read('xl/sharedStrings.xml').decode('utf-8') if 'xl/sharedStrings.xml' in z.namelist() else ''
        for nm in z.namelist():
            if nm.startswith('xl/worksheets/'):
                strings += z.read(nm).decode('utf-8')
    except Exception as e:  # noqa: BLE001
        names = 'NOT-XLSX: %s' % e
    check('U14 导出是 xlsx，含账号 / 简历 / 报名 / 评价等工作表', (200, True, True, True, True),
          (st_, 'spreadsheetml' in ct_, '账号信息' in names, '报名' in names, '我的评价' in names))
    check('U14 导出内容有本人数据（岗位名、评价），且留痕', (True, True, before + 1),
          ('回归义工-' + RUN in strings, RUN in strings, n("select count(*) from kg_pii_access where viewer_id=%s and biz_type='self_export'" % j_id)))
    st_, ct_, raw1 = get_bytes('/app/me/export.xlsx', r1_tok)
    s1 = ''
    try:
        z = zipfile.ZipFile(io.BytesIO(raw1))
        s1 = ''.join(z.read(nm).decode('utf-8') for nm in z.namelist() if nm.startswith('xl/'))
    except Exception:  # noqa: BLE001
        pass
    check('U14 实名用户的导出件只有脱敏姓名与后四位，不含完整证件号与全名', (True, False, False),
          ('测**' in s1, id1.upper() in s1, '测试甲' in s1))
    st_, ct_, raw_o = get_bytes('/app/me/export.xlsx', o_tok)
    so = ''
    try:
        z = zipfile.ZipFile(io.BytesIO(raw_o))
        so = ''.join(z.read(nm).decode('utf-8') for nm in z.namelist() if nm.startswith('xl/'))
    except Exception:  # noqa: BLE001
        pass
    check('U14 导出件里手机号脱敏', (True, False), (phone1[:3] + '****' + phone1[-4:] in so, phone1 in so))

    # ================================================================ U15 注销
    section('U15 账号注销：令牌失效、资料清除、证件摘要释放')
    r1_2, _ = login('realname1')
    dc = data(api('GET', '/app/me/deactivation', r1_tok)) or {}
    check('U15 干净账号可以注销', (True, []), (dc.get('canDeactivate'), dc.get('blockers')))
    b = api('POST', '/app/me/deactivate', r1_tok, {'confirm': '确认', 'reason': '回归'})
    check('U15 确认词不对被拒（422）且账号仍在', (422, '0'), (b.get('code'), scalar("select del_flag from kg_app_user where user_id=%s" % r1_id)))
    b = api('POST', '/app/me/deactivate', r1_tok, {'confirm': '确认注销', 'reason': '回归测试'})
    check('U15 注销成功', 200, b.get('code'))
    r = sql("select del_flag, status, ifnull(real_name,'NULL'), ifnull(id_hash,'NULL'), nickname, openid like 'deleted:%%' from kg_app_user where user_id=%s" % r1_id)[0]
    check('U15 账号标记删除、实名信息与证件摘要清空、openid 作废', ['2', '1', 'NULL', 'NULL', '已注销用户', '1'], r)
    check('U15 注销记录落库', 1, n("select count(*) from kg_account_deletion where app_user_id=%s and reason='回归测试'" % r1_id))
    check('U15 该账号所有设备的令牌立即 401', (401, 401), (api('GET', '/app/me/profile', r1_tok).get('code'), api('GET', '/app/me/profile', r1_2).get('code')))
    nt, nid = login('realname1')
    check('U15 同一微信再登录是一个全新账号', True, nid != r1_id, ok=nid != r1_id)
    b = api('POST', '/app/me/real-name', r2_tok, {'name': '测试乙', 'idNo': id1})
    check('U15 注销后证件释放：另一个账号可以用它实名', (200, '1'),
          (b.get('code'), scalar("select real_name_verified_time is not null from kg_app_user where user_id=%s" % r2_id)))

    # ---------------------------------------------------------------- 汇总
    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 RUN=%s（账号 code 前缀 usr-%s-）' % (RUN, RUN))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
