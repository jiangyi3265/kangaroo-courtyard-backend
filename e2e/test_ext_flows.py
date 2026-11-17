# -*- coding: utf-8 -*-
"""V7–V16 扩展模块的接口回归：资金链路、幂等、越权与本轮（2026-09-24）修过的缺陷。

此前 V7 起的扩展模块没有任何进入一键验收的自动化用例（冒烟脚本放在会话临时目录里，已经丢了）。
这里只收"能证明业务结果"的断言：金额回库对账、房量回库对账、状态机回库确认、越权要被拦且数据没变。

覆盖：
  E1 旅居下单 → 联调支付 → 退款申请 → 门店审批 的整条资金链（金额由服务端算，客户端改价无效）
  E2 同一次提交并发到达（双击 / 网络重试）只生成一张单、只占一次房（本轮修复：幂等键）
  E3 越权：别人的订单看不到、退不了；别家门店的主理人、本店普通员工都批不了退款；重复审批不会重复退款
  E4 安心商家 / 含保险 的筛选与首页、城市页口径（本轮修复：极性判反）
  E5 后台钱包流水只能追加、不能改（本轮修复：通用表单曾允许改已入账金额）
  E6 V8 员工运营菜单（投保申请 / 保险方案 / 调度日志 / 分享追踪 / 员工操作日志）在后台可达（本轮修复：V12 删掉了它们）
  E7 旅居改版 R2（2026-10-17，V51）：后台填区县 / 主理人信息 / 出行参考 → 区县接口、列表按区县精确筛、详情往返；
     员工端表单选项（设施字典有学习室 / 健身房、越权拿不到）、上线中不能改、下架后保存的单行化 / 超长拦截 / 设施代码规整

前置：后端以联调模式运行（python .dev/tools/dev_backend.py，带 kangaroo.pay.mode=mock），本轮用独立真实 API 创建房源/房型/门店，结束时按本轮 ID 清理。
测试数据：本次运行新建的账号（code 前缀 ext-<RUN>-）与独立门店、房源和订单；finally 清理。

用法：
    python e2e/test_ext_flows.py
"""
import os
import sys
import time
import urllib.parse
import uuid
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from test_kangaroo_flow import http, scalar, sql, check, section, admin_login, PASSED, FAILED  # noqa: E402

from isolated_stay_fixture import IsolatedStayFixture

FIXTURE = None
RUN = uuid.uuid4().hex[:8]
AGREEMENT = '2026-09'


def app_login(tag):
    status, body = http('POST', '/app/auth/wx-login', {'code': 'ext-%s-%s' % (RUN, tag)})
    assert body.get('code') == 200, body
    if FIXTURE is not None:
        FIXTURE.user(body['token'], body['account']['id'])
    return body['token'], int(body['account']['id'])


def grant(admin, uid, store_id, role):
    status, body = http('POST', '/kangaroo/grant', {'appUserId': uid, 'storeId': int(store_id), 'roleCode': role}, token=admin)
    assert body.get('code') == 200, body


def relogin(tag):
    """授权变更后重新登录，拿到带最新门店授权快照的令牌"""
    return app_login(tag)[0]


def day(offset):
    # 后端与库按北京时间（+08:00）算日期；本机时区不同时用 localtime 会差一天
    return time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + offset * 86400))


def occupied(room_type_id, d):
    v = scalar("select coalesce(sum(occupied), 0) from kg_stay_calendar where room_type_id=%s and stay_date='%s'" % (room_type_id, d))
    return int(v or 0)


def free_days(room_type_id, nights, start):
    """从 start 天后起找连续 nights 晚都还有空房、没关房的入住日（前几轮留下的待支付单会占房，不能写死日期）"""
    stock = int(scalar("select stock from kg_stay_room_type where room_type_id=%s" % room_type_id))
    for off in range(start, start + 200):
        ok = True
        for k in range(nights):
            d = day(off + k)
            r = sql("select occupied, coalesce(stock, %s), closed from kg_stay_calendar where room_type_id=%s and stay_date='%s'"
                    % (stock, room_type_id, d))
            if r and (int(r[0][0]) >= int(r[0][1]) or r[0][2] != '0'):
                ok = False
                break
        if ok:
            return day(off), day(off + nights)
    raise RuntimeError('200 天内找不到空房日期')


def main():
    global FIXTURE
    with IsolatedStayFixture('ext-' + RUN) as fixture:
        FIXTURE = fixture
        try:
            return run_fixture(fixture.admin, fixture)
        finally:
            FIXTURE = None


def run_fixture(admin, fixture):

    # ---------------------------------------------------------------- 准备
    section('E0 准备：一家在线房源、住客两名、三个门店角色')
    room_type_id, stay_id, store_id = fixture.room_type_id, fixture.stay_id, fixture.store_id
    other_store = fixture.other_store_id

    guest_tok, guest_id = app_login('guest')
    other_tok, other_id = app_login('other')
    _, mgr_id = app_login('mgr')
    _, far_id = app_login('far')
    _, staff_id = app_login('staff')
    grant(admin, mgr_id, store_id, 'manager')
    grant(admin, far_id, other_store, 'manager')
    grant(admin, staff_id, store_id, 'staff')
    mgr_tok, far_tok, staff_tok = relogin('mgr'), relogin('far'), relogin('staff')
    check('E0 准备完成（房型 %s / 门店 %s / 另一家门店 %s）' % (room_type_id, store_id, other_store), True, True)

    # 入住日期放在 25 天以后：离入住足够远，取消政策一般是全额可退
    check_in, check_out = free_days(room_type_id, 2, 25)

    # ================================================================ E1 下单与支付
    section('E1 旅居下单 → 联调支付：金额由服务端算')
    status, body = http('POST', '/app/stay/orders/quote',
                        {'roomTypeId': room_type_id, 'checkIn': check_in, 'checkOut': check_out, 'guests': 1}, app_token=guest_tok)
    q = body.get('data') or {}
    check('E1 报价可订', (200, True), (body.get('code'), q.get('bookable')))
    pay_fen = int(q.get('payFen') or 0)
    check('E1 报价金额大于 0', True, pay_fen > 0, ok=pay_fen > 0)

    before = occupied(room_type_id, check_in)
    status, body = http('POST', '/app/stay/orders', {
        'roomTypeId': room_type_id, 'checkIn': check_in, 'checkOut': check_out, 'guests': 1,
        'guestName': '回归住客', 'guestPhone': '13800138000', 'agreementVersion': AGREEMENT,
        'idemKey': 'e1-' + RUN,
        # 客户端伪造的金额字段一律不该被采信
        'payFen': 1, 'roomFen': 1, 'amount': 1}, app_token=guest_tok)
    o = body.get('data') or {}
    order_no, pay_no = o.get('orderNo'), o.get('payNo')
    check('E1 下单成功并生成支付单', (200, True, True), (body.get('code'), bool(order_no), bool(pay_no)))
    check('E1 订单应付 = 服务端报价（客户端传的 payFen=1 被忽略）', str(pay_fen),
          scalar("select pay_fen from kg_stay_order where order_no='%s'" % order_no))
    check('E1 支付单金额 = 订单应付', str(pay_fen), scalar("select amount from kg_pay_order where pay_no='%s'" % pay_no))
    check('E1 下单即占房（入住首晚占用 +1）', before + 1, occupied(room_type_id, check_in))

    status, body = http('POST', '/app/pay/%s/mock-confirm' % pay_no, {}, app_token=other_tok)
    check('E1 别人不能替我确认支付', True, body.get('code') != 200, ok=body.get('code') != 200)
    check('E1 别人确认之后支付单仍待支付', 'pending', scalar("select status from kg_pay_order where pay_no='%s'" % pay_no))

    status, body = http('POST', '/app/pay/%s/prepay' % pay_no, {}, app_token=guest_tok)
    check('E1 拉起支付（联调通道）', (200, 'mock'), (body.get('code'), (body.get('data') or {}).get('channel')))
    status, body = http('POST', '/app/pay/%s/mock-confirm' % pay_no, {}, app_token=guest_tok)
    check('E1 确认到账', 200, body.get('code'))
    check('E1 订单已确认、支付单已支付', ('confirmed', 'paid'),
          (scalar("select status from kg_stay_order where order_no='%s'" % order_no),
           scalar("select status from kg_pay_order where pay_no='%s'" % pay_no)))
    status, body = http('POST', '/app/pay/%s/mock-confirm' % pay_no, {}, app_token=guest_tok)
    check('E1 重复确认被拒（不会二次触发入账回调）', True, body.get('code') != 200, ok=body.get('code') != 200)
    check('E1 同一订单只有一张已支付的支付单', '1',
          scalar("select count(*) from kg_pay_order where biz_type='stay_order' and biz_id=(select order_id from kg_stay_order where order_no='%s') "
                 "and status='paid'" % order_no))

    # ================================================================ E2 并发重复提交
    section('E2 同一次提交并发到达：只生成一张单、只占一次房')
    d_in, d_out = free_days(room_type_id, 1, 60)
    base = occupied(room_type_id, d_in)
    payload = {'roomTypeId': room_type_id, 'checkIn': d_in, 'checkOut': d_out, 'guests': 1,
               'guestName': '回归住客', 'guestPhone': '13800138000', 'agreementVersion': AGREEMENT, 'idemKey': 'e2-' + RUN}
    # 第一次提交因价格校验失败（409）时，幂等键必须随事务一起回滚，不能留下挡住重试的孤儿键
    status, body = http('POST', '/app/stay/orders', dict(payload, expectedPayFen=1), app_token=guest_tok)
    check('E2 带错的期望金额先提交一次：被 409 拒', 409, body.get('code'))
    check('E2 被拒那次没有留下幂等键', '0',
          scalar("select count(*) from kg_idempotency where idem_key='stay:%s:e2-%s'" % (guest_id, RUN)))
    status, body = http('POST', '/app/stay/orders', dict(payload, idemKey='x' * 49), app_token=guest_tok)
    check('E2 超长幂等键被拒（不会截断后和别的请求撞键）', True, body.get('code') != 200, ok=body.get('code') != 200)
    with ThreadPoolExecutor(max_workers=4) as ex:
        results = list(ex.map(lambda _: http('POST', '/app/stay/orders', payload, app_token=guest_tok), range(4)))
    nos = set((b.get('data') or {}).get('orderNo') for _, b in results if b.get('code') == 200)
    check('E2 四个并发请求都拿到结果（成功或"正在提交"）', True,
          all(b.get('code') in (200, 409) for _, b in results), ok=all(b.get('code') in (200, 409) for _, b in results))
    check('E2 返回的订单号只有一个', 1, len(nos - {None}))
    check('E2 库里只有一张这次提交的订单', '1',
          scalar("select count(*) from kg_stay_order where app_user_id=%s and create_by='idem:e2-%s'" % (guest_id, RUN)))
    check('E2 房量只占了一次', base + 1, occupied(room_type_id, d_in))
    status, body = http('POST', '/app/stay/orders', payload, app_token=guest_tok)
    check('E2 事后重放同一个幂等键返回同一张单', list(nos - {None}), [(body.get('data') or {}).get('orderNo')])
    e2_no = (body.get('data') or {}).get('orderNo')
    status, body = http('POST', '/app/stay/orders/%s/cancel' % e2_no, {}, app_token=other_tok)
    check('E2 别人不能取消我的待支付单', True, body.get('code') != 200, ok=body.get('code') != 200)
    status, body = http('POST', '/app/stay/orders/%s/cancel' % e2_no, {}, app_token=guest_tok)
    check('E2 本人取消待支付单（接口可用；小程序侧暂无入口，见报告）', 200, body.get('code'))
    check('E2 取消后释放房量、支付单关闭', (base, 'closed'),
          (occupied(room_type_id, d_in), scalar("select p.status from kg_pay_order p join kg_stay_order o on o.pay_no=p.pay_no where o.order_no='%s'" % e2_no)))

    # ================================================================ E3 越权与退款审批
    section('E3 越权：别人的订单；退款审批的门店与角色边界；重复审批')
    status, body = http('GET', '/app/stay/orders/%s' % order_no, app_token=other_tok)
    check('E3 别人查不到我的订单', True, body.get('code') != 200, ok=body.get('code') != 200)
    status, body = http('POST', '/app/stay/orders/%s/refund' % order_no, {'reason': '越权'}, app_token=other_tok)
    check('E3 别人不能替我申请退款', True, body.get('code') != 200, ok=body.get('code') != 200)
    check('E3 越权之后订单状态没变', 'confirmed', scalar("select status from kg_stay_order where order_no='%s'" % order_no))

    status, body = http('GET', '/app/stay/orders/%s/refund-preview' % order_no, app_token=guest_tok)
    pv = body.get('data') or {}
    check('E3 退款预览可申请', (200, True), (body.get('code'), pv.get('allowed')))
    refund_fen = int(pv.get('refundFen') or 0)
    check('E3 预计退款不超过实付', True, 0 < refund_fen <= pay_fen, ok=0 < refund_fen <= pay_fen)
    status, body = http('POST', '/app/stay/orders/%s/refund' % order_no,
                        {'reason': '行程变动', 'expectedRefundFen': refund_fen}, app_token=guest_tok)
    check('E3 本人申请退款', (200, 'refund_pending'), (body.get('code'), scalar("select status from kg_stay_order where order_no='%s'" % order_no)))

    approve = {'bizType': 'stay', 'orderNo': order_no}
    status, body = http('POST', '/app/staff/stay/refunds/approve', approve, app_token=far_tok)
    check('E3 别家门店的主理人批不了（403）', 403, body.get('code'))
    status, body = http('POST', '/app/staff/stay/refunds/approve', approve, app_token=staff_tok)
    check('E3 本店普通员工批不了（没有房源审核权限，403）', 403, body.get('code'))
    status, body = http('POST', '/app/staff/stay/refunds/approve', approve, app_token=guest_tok)
    check('E3 住客自己不能批（不是员工，403）', 403, body.get('code'))
    check('E3 越权审批之后仍在退款审核中、支付单未退', ('refund_pending', '0'),
          (scalar("select status from kg_stay_order where order_no='%s'" % order_no),
           scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay_no)))

    occ_before_refund = occupied(room_type_id, check_in)
    status, body = http('POST', '/app/staff/stay/refunds/approve', approve, app_token=mgr_tok)
    check('E3 本店主理人同意退款', 200, body.get('code'))
    check('E3 支付单退款金额 = 申请时算好的退款额', str(refund_fen),
          scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay_no))
    check('E3 订单进入已取消（联调通道即时到账）', 'cancelled', scalar("select status from kg_stay_order where order_no='%s'" % order_no))
    check('E3 退款后释放房量', occ_before_refund - 1, occupied(room_type_id, check_in))
    status, body = http('POST', '/app/staff/stay/refunds/approve', approve, app_token=mgr_tok)
    check('E3 重复审批被拒', True, body.get('code') != 200, ok=body.get('code') != 200)
    check('E3 重复审批没有重复退款', str(refund_fen), scalar("select refund_amount from kg_pay_order where pay_no='%s'" % pay_no))
    check('E3 用户收到退款通知', True,
          int(scalar("select count(*) from kg_message where recipient_id=%s and title='退款已原路退回'" % guest_id) or 0) >= 1,
          ok=int(scalar("select count(*) from kg_message where recipient_id=%s and title='退款已原路退回'" % guest_id) or 0) >= 1)

    # ================================================================ E4 安心商家 / 含保险 口径
    section('E4 安心商家 / 含保险：字典口径是 0 是 / 1 否')
    insured_ids = set(r[0] for r in sql("select job_id from kg_job where status='published' and del_flag='0' and insurance_flag='0' and job_type<>'online'"))
    uninsured_ids = set(r[0] for r in sql("select job_id from kg_job where status='published' and del_flag='0' and insurance_flag='1' and job_type<>'online'"))
    status, body = http('POST', '/app/jobs/search', {'insured': True}, app_token=guest_tok)
    got = set(str(j.get('jobId')) for j in (body.get('data') or {}).get('jobs', []))
    check('E4 筛「含保险」只返回 insurance_flag=0 的岗位', (True, True),
          (got <= insured_ids, not (got & uninsured_ids)))
    check('E4 筛「含保险」确实有结果（演示基线有含保险岗位）', True, bool(got) == bool(insured_ids), ok=bool(got) == bool(insured_ids))
    cert_jobs = set(r[0] for r in sql("select j.job_id from kg_job j join kg_store s on s.store_id=j.store_id where j.status='published' "
                                      "and j.del_flag='0' and s.safe_cert='0' and j.job_type<>'online'"))
    status, body = http('POST', '/app/jobs/search', {'safe': True}, app_token=guest_tok)
    got = set(str(j.get('jobId')) for j in (body.get('data') or {}).get('jobs', []))
    check('E4 筛「安心商家」只返回已认证门店（safe_cert=0）的岗位，且不为空', (True, True),
          (got <= cert_jobs, bool(got) == bool(cert_jobs)))
    city = scalar("select city from kg_store where store_id=%s" % store_id)
    status, body = http('GET', '/app/home/feed?city=' + urllib.parse.quote(city), app_token=guest_tok)
    cards = (body.get('data') or {}).get('jobs') or []
    check('E4 首页推荐有岗位卡片（否则下一条对比会空过）', (200, True), (body.get('code'), len(cards) > 0))
    wrong =[c.get('jobId') for c in cards
             if c.get('safe') != (scalar("select s.safe_cert from kg_job j join kg_store s on s.store_id=j.store_id where j.job_id=%s" % c.get('jobId')) == '0')
             or c.get('insured') != (scalar("select insurance_flag from kg_job where job_id=%s" % c.get('jobId')) == '0')]
    check('E4 首页推荐卡片的「安心商家 / 含保险」与库里一致', [], wrong)

    # ================================================================ E5 钱包流水只能追加
    section('E5 后台钱包流水只能追加，不能改已入账金额')
    status, body = http('GET', '/kangaroo/ext/walletentry/meta', token=admin)
    meta = body.get('data') or {}
    check('E5 通用表单不开放修改 / 删除，保留新增（人工调整）', (False, False, True),
          (meta.get('allowEdit'), meta.get('allowDelete'), meta.get('allowAdd')))
    status, body = http('POST', '/kangaroo/ext/walletentry', {
        'appUserId': mgr_id, 'storeId': store_id, 'entryType': 'adjust', 'sourceType': 'adjust',
        'bizNo': 'ADJ-' + RUN, 'amountFen': 100, 'availableDate': day(0), 'remark': '回归：人工调整'}, token=admin)
    check('E5 人工调整走新增', 200, body.get('code'))
    entry_id = scalar("select entry_id from kg_wallet_entry where biz_no='ADJ-%s'" % RUN)
    status, body = http('PUT', '/kangaroo/ext/walletentry', {'entryId': int(entry_id or 0), 'amountFen': 999999}, token=admin)
    check('E5 改已入账金额被拒', True, body.get('code') != 200, ok=body.get('code') != 200)
    check('E5 金额没被改动', '100', scalar("select amount_fen from kg_wallet_entry where biz_no='ADJ-%s'" % RUN))

    # ================================================================ E6 员工运营菜单
    section('E6 V8 员工运营菜单在后台可达（V12 曾把它们删掉）')
    status, body = http('GET', '/getRouters', token=admin)

    def paths(nodes, prefix=''):
        out = []
        for n in nodes or []:
            p = prefix + '/' + str(n.get('path', '')).strip('/')
            out.append(p)
            out += paths(n.get('children'), p)
        return out
    all_paths = paths(body.get('data'))
    for key in ('insapply', 'insplan', 'dispatch', 'jobshare', 'staffop'):
        check('E6 后台路由里有 /kgstaff/%s' % key, True, ('/kgstaff/' + key) in all_paths, ok=('/kgstaff/' + key) in all_paths)
    check('E6 经营管理目录仍在（没有被反向覆盖）', True, any(p.startswith('/kgmanage/') for p in all_paths),
          ok=any(p.startswith('/kgmanage/') for p in all_paths))
    check('E6 「投保处理」按钮权限在菜单里（受限角色才授得到）', '1',
          scalar("select count(*) from sys_menu where perms='kangaroo:insapply:handle'"))

    # ================================================================ E7 旅居改版 R2（V51）
    section('E7 旅居改版 R2：所在区县筛选、出行参考 / 主理人信息往返、员工端保存校验、设施字典（V51）')
    district = ('e2e区' + RUN)[:32]
    status, body = http('PUT', '/kangaroo/ext/stay', {
        'stayId': stay_id, 'district': district, 'hostAge': ' 90后 ', 'hostHometown': '云南大理', 'hostSkills': '做饭、带徒步',
        'travelPros': '近洱海\n步行 5 分钟', 'travelCons': '', 'travelAirport': '机场打车约 40 分钟', 'travelRail': '大理站打车 20 分钟'}, token=admin)
    check('E7 后台房源管理能填区县、主理人信息、出行参考', 200, body.get('code'))
    check('E7 后台填的换行并成空格、首尾空白去掉', ('近洱海 步行 5 分钟', '90后', district),
          tuple((scalar("select concat_ws('|', travel_pros, host_age, district) from kg_stay where stay_id=%s" % stay_id) or '').split('|')))
    city = urllib.parse.quote('大理')
    status, body = http('GET', '/app/stay/districts?city=%s' % city)
    data = body.get('data') or {}
    mine = [d for d in data.get('districts') or [] if d.get('name') == district]
    check('E7 区县接口带出这个区县和家数 1', [{'name': district, 'count': 1}], mine)
    check('E7 区县接口带全城家数', True, int(data.get('all') or 0) >= 1, ok=int(data.get('all') or 0) >= 1)
    status, body = http('GET', '/app/stay/list?city=%s&district=%s&pageNum=1&pageSize=10' % (city, urllib.parse.quote(district)))
    data = body.get('data') or {}
    rows = data.get('rows') or []
    check('E7 列表按区县精确筛：只有这一家，卡片带区县', (1, [stay_id], [district]),
          (int(data.get('total') or 0), [r.get('stayId') for r in rows], [r.get('district') for r in rows]))
    status, body = http('GET', '/app/stay/list?city=%s&district=%s&pageNum=1&pageSize=10' % (city, urllib.parse.quote(district + 'x')))
    check('E7 区县对不上（不做模糊匹配）就没有', 0, int((body.get('data') or {}).get('total') or 0))
    status, body = http('GET', '/app/stay/%s' % stay_id)
    data = body.get('data') or {}
    host = data.get('host') or {}
    check('E7 详情带出行参考四段（没填的是空串）', {'pros': '近洱海 步行 5 分钟', 'cons': '', 'airport': '机场打车约 40 分钟', 'rail': '大理站打车 20 分钟'},
          data.get('travel'))
    check('E7 详情主理人带年龄 / 籍贯 / 擅长；没有带分项的评价时主理人评分为空', ('90后', '云南大理', '做饭、带徒步', None, 0),
          (host.get('age'), host.get('hometown'), host.get('skills'), host.get('score'), host.get('scoreCount')))
    check('E7 详情顶层不重复带 travel* / host* 原始列', [], [k for k in data if k.startswith('travel') and k != 'travel' or k in ('hostAge', 'hostHometown', 'hostSkills')])
    status, body = http('GET', '/app/staff/stay/form-options?stayId=%s' % stay_id, app_token=mgr_tok)
    data = body.get('data') or {}
    codes = [f.get('value') for f in data.get('facilities') or []]
    check('E7 员工端表单选项：设施字典有学习室 / 健身房', (200, True, True), (body.get('code'), 'study' in codes, 'gym' in codes))
    check('E7 员工端表单选项：这座城市房源上手填的区县也在里面', True, district in (data.get('districts') or []), ok=district in (data.get('districts') or []))
    status, body = http('GET', '/app/staff/stay/form-options?stayId=%s' % stay_id, app_token=far_tok)
    check('E7 别家门店的主理人拿不到这间房源的表单选项', True, body.get('code') != 200, ok=body.get('code') != 200)
    # 2026-10-17 主人：区县 / 主理人 / 出行参考 / 设施这些补充信息，上线中的房源员工端可以直接改，不用下架重审
    status, body = http('PUT', '/app/staff/stay/stays/%s' % stay_id, {'travelCons': '巷子窄'}, app_token=mgr_tok)
    check('E7 上线中的房源员工端直接改出行参考，房源仍在线', (200, '巷子窄', 'online'),
          (body.get('code'), scalar('select travel_cons from kg_stay where stay_id=%s' % stay_id), scalar('select status from kg_stay where stay_id=%s' % stay_id)))
    status, body = http('POST', '/app/staff/stay/stays/%s/offline' % stay_id, {'reason': 'E7 回归：改出行参考'}, app_token=mgr_tok)
    check('E7 员工下架房源', 200, body.get('code'))
    status, body = http('PUT', '/app/staff/stay/stays/%s' % stay_id, {'district': '区' * 33}, app_token=mgr_tok)
    check('E7 员工端区县超长被拒，说清是哪一项', (True, '所在区县最多 32 个字'), (body.get('code') != 200, body.get('msg')), ok=body.get('code') != 200 and body.get('msg') == '所在区县最多 32 个字')
    status, body = http('PUT', '/app/staff/stay/stays/%s' % stay_id, {'travelCons': ' 巷子窄，\n车开不到门口 ', 'hostSkills': '手冲咖啡',
                                                                    'facilities': 'parking,study, gym,STUDY,bad-code'}, app_token=mgr_tok)
    check('E7 员工端保存出行参考、主理人擅长、设施', 200, body.get('code'))
    check('E7 员工端保存后入库：单行化、设施代码去重只收小写', ('巷子窄， 车开不到门口', '手冲咖啡', 'parking,study,gym', district),
          tuple((scalar("select concat_ws('|', travel_cons, host_skills, facilities, district) from kg_stay where stay_id=%s" % stay_id) or '').split('|')))
    check('E7 下架后只改补充信息：状态不变（不再被打回草稿）', 'offline', scalar('select status from kg_stay where stay_id=%s' % stay_id))
    status, body = http('GET', '/app/stay/districts?city=%s' % city)
    check('E7 下架后区县接口不再算这一家', [], [d for d in (body.get('data') or {}).get('districts') or [] if d.get('name') == district])

    # ---------------------------------------------------------------- 汇总
    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 RUN=%s（账号 code 前缀 ext-%s-，finally 按本轮 ID 清理）' % (RUN, RUN))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
