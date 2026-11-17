# -*- coding: utf-8 -*-
"""V12 经营模块 + V8/V13 员工扩展模块的接口回归（资金、状态机、越权、幂等、并发）。

只收"能证明业务结果"的断言：每个动作之后回库对账（钱包流水 / 余额 / 结算单 / 名额 / 状态），
另一端能看到（消息、列表），负向用例（别家门店、角色不够、未登录、非法输入、重复 / 幂等、资金并发）。

覆盖：
  M0 准备：新建两家门店（本店 / 别家）、各角色账号、本店房源（员工端建房源 → 上传资质 → 提交 → 后台审核上线）
  M1 分佣：后台分佣规则 → 已完成住宿单 + 已核销服务单 → 后台「重新计算」入账（金额 = 基数 × 费率）→ 幂等 → 完成后退款冲回
  M2 结算：收款账户登记 → 余额不足 / 非法金额 → 并发结算不超额 → 幂等键 → 后台 通过 / 驳回 / 登记打款 与重复操作
  M3 周报：打开 / 草稿（版本号）/ 提交冻结 / 后台退回 / 再提交；越权
  M4 日报：录入明细（幂等、并发）/ 作废 / 合计回库对账 / 导出 Excel / 发送
  M5 看板：营业额 / 二消 / 入住率 / 订单数 / 排名 与库里聚合一致；改进计划；越权
  M6 任务：发布 / 接受 / 认领（名额并发）/ 取消认领 / 验收积分 / 取消任务；角色边界
  M7 荣誉与关怀：生日（格式校验）/ 领取礼物（条件、并发、库存）/ 后台发货 / 申报处理
  S1 员工扩展准备：本店岗位、报名推进到「已确认录用」「已入职」
  S2 投保：主理人申请 → 后台登记保单号 → 员工看到生效；普通员工不能申请；重复申请；跨店
  S3 调度：名额调整（CAS 并发）/ 撤销 / 到期自动恢复 / 未来生效 / 紧急标记 / 导出（含公式注入）；角色边界
  S4 员工发岗：草稿 → 后台审核上架；分享码与访问 / 报名归因；批量暂停恢复
  S5 评价运营：回复 / 精选（不发积分）/ 内部备注 / 提请平台；角色与跨店
  S6 人才库：脱敏、kg_pii_access 留痕、收藏、邀请（去重）；角色与跨店
  S7 线上任务：发布 → 审核上架 → 报名 → 录用 → 提交成果 → 退回 → 再提交 → 验收生成结算单 → 线下付款登记 → 确认 / 申诉

前置：后端以联调模式运行（kangaroo.pay.mode=mock、微信 mock 登录）。
测试数据：本次运行新建的账号（code 前缀 mgt-<RUN>-）、门店（名称含 RUN）及其下的全部数据；
为制造"已完成 / 完成后退款 / 担任主理人满 90 天 / 到期"这类时间状态，只改本次新建的行。

用法：
    python e2e/test_ext_manage.py
"""
import io
import json
import os
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import test_kangaroo_flow as _kf  # noqa: E402
from test_kangaroo_flow import http, check, section, admin_login, PASSED, FAILED, BASE  # noqa: E402

# 后端连库用 time_zone='+08:00'（见 application-druid.yml 的 sessionVariables），本机 mysql 客户端默认是 SYSTEM 时区；
# 两边的 now() / curdate() 可能差出几个小时甚至一天。这里的 SQL 一律先切到后端同一时区，口径才一致。
TZ = "set time_zone='+08:00'; "


def sql(q):
    return _kf.sql(TZ + q)


def scalar(q):
    rows = sql(q)
    return rows[0][0] if rows else None


def sql_exec(q):
    _kf.sql_exec(TZ + q)


RUN = uuid.uuid4().hex[:8]
AGREEMENT = '2026-09'
CTX = {}


# ---------------------------------------------------------------- helpers

def app_login(tag, nickname=None):
    body = {'code': 'mgt-%s-%s' % (RUN, tag)}
    if nickname:
        body['nickname'] = nickname
    status, b = http('POST', '/app/auth/wx-login', body)
    assert b.get('code') == 200, b
    return b['token'], int(b['account']['id'])


def grant(admin, uid, store_id, role):
    status, b = http('POST', '/kangaroo/grant', {'appUserId': uid, 'storeId': int(store_id), 'roleCode': role}, token=admin)
    assert b.get('code') == 200, b


def day(offset):
    # 后端与库按北京时间（+08:00）算日期；本机时区不同时用 localtime 会差一天
    return time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + offset * 86400))


def ok(b):
    return isinstance(b, dict) and b.get('code') == 200


def denied(b):
    return isinstance(b, dict) and b.get('code') != 200


def code_of(b):
    return b.get('code') if isinstance(b, dict) else None


def data(b):
    return (b or {}).get('data') if isinstance(b, dict) else None


def n(q):
    v = scalar(q)
    return int(v) if v not in (None, 'NULL', '') else 0


def yuan_text(fen):
    """与 KgStats.yuanText 同口径：整元、千分位"""
    return ('-¥' if fen < 0 else '¥') + '{:,}'.format(abs(fen) // 100)


def pct(bp):
    if bp is None:
        return '—'
    return ('%d%%' % (bp // 100)) if bp % 100 == 0 else ('%.1f%%' % (bp / 100.0))


def java_round(x):
    import math
    return int(math.floor(x + 0.5))


def upload(path, app_token, filename, content, ctype='application/pdf'):
    boundary = '----kg' + uuid.uuid4().hex
    parts = [('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n'
              % (boundary, filename, ctype)).encode('utf-8'), content, ('\r\n--%s--\r\n' % boundary).encode('utf-8')]
    req = urllib.request.Request(BASE + path, data=b''.join(parts), method='POST',
                                 headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-App-Token': app_token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'raw': e.read().decode('utf-8', 'replace')}


def get_binary(path, app_token=None):
    headers = {}
    if app_token:
        headers['X-App-Token'] = app_token
    req = urllib.request.Request(BASE + path, headers=headers, method='GET')
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.headers.get('Content-Type', ''), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get('Content-Type', ''), e.read()


def parallel(fn, items, workers=6):
    with ThreadPoolExecutor(max_workers=workers) as ex:
        return list(ex.map(fn, items))


def msgs(uid, title_like, mode=None):
    # mode 只在需要区分身份时传：钱包类结果现在两种身份各发一条（主理人默认在员工身份，也要看得到），按身份数才是 1 条
    return n("select count(*) from kg_message where recipient_id=%s and title like '%s'%s" % (uid, title_like, " and mode='%s'" % mode if mode else ''))


def guard(name, fn):
    """一段用例抛了意外异常时记为失败并继续后面的段落，避免一处炸掉整份报告"""
    try:
        fn()
    except Exception as e:  # noqa: BLE001
        traceback.print_exc()
        check('%s 段落执行异常：%s' % (name, str(e)[:300]), '无异常', '异常', ok=False)


def revenue_db(store_id, f, t):
    """库里按 KgStats 的口径独立算一次营收：[f, t) 内支付成功计入、退款扣减"""
    q = ("select coalesce(sum(v),0), coalesce(sum(c),0) from ("
         "select pay_fen v, 1 c from kg_stay_order where store_id=%(s)s and del_flag='0' and paid_time>='%(f)s' and paid_time<'%(t)s' "
         "union all select -refund_fen, 0 from kg_stay_order where store_id=%(s)s and del_flag='0' and refund_fen>0 and refund_time>='%(f)s' and refund_time<'%(t)s' "
         "union all select pay_fen, 1 from kg_shop_order where store_id=%(s)s and del_flag='0' and paid_time>='%(f)s' and paid_time<'%(t)s' "
         "union all select -refund_fen, 0 from kg_shop_order where store_id=%(s)s and del_flag='0' and refund_fen>0 and refund_time>='%(f)s' and refund_time<'%(t)s' "
         "union all select g.fee_fen, 1 from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where a.store_id=%(s)s and g.fee_fen>0 "
         "and g.paid_time>='%(f)s' and g.paid_time<'%(t)s' "
         "union all select -g.refund_fen, 0 from kg_activity_signup g join kg_activity a on a.activity_id=g.activity_id where a.store_id=%(s)s and g.refund_fen>0 "
         "and g.refund_time>='%(f)s' and g.refund_time<'%(t)s') x") % {'s': store_id, 'f': f, 't': t}
    r = sql(q)[0]
    return int(r[0]), int(r[1])


# ================================================================ M0 准备

def m0_setup(admin):
    from fixture_assets import require_bundled_asset
    require_bundled_asset('/static/media/rooms-cover-1.jpg')
    section('M0 准备：两家新门店、各角色账号、本店上线一处房源')
    ids = {}
    for key, city in (('A', '大理'), ('B', '丽江')):
        status, b = http('POST', '/kangaroo/store', {'storeName': '经营回归小院%s-%s' % (key, RUN), 'city': city,
                                                      'address': '%s古城回归路%s号' % (city, RUN[:3]), 'intro': '经营模块回归门店',
                                                      'contactPhone': '18500000000'}, token=admin)
        assert ok(b), b
        ids[key] = int(data(b))
    CTX['A'], CTX['B'] = ids['A'], ids['B']
    users = {}
    for tag, nick in (('mgr', '=回归主理人'), ('keeper', '回归管家'), ('staff', '回归员工'), ('far', '别家主理人'),
                      ('guest', '回归住客'), ('payee2', '结算校验'), ('outsider', '路人甲'), ('worker', '接单人甲'),
                      ('worker2', '接单人乙'), ('vol', '回归义工'), ('talent', '候选人丙')):
        users[tag] = app_login(tag, nick)
    grant(admin, users['mgr'][1], CTX['A'], 'manager')
    grant(admin, users['keeper'][1], CTX['A'], 'keeper')
    grant(admin, users['staff'][1], CTX['A'], 'staff')
    grant(admin, users['far'][1], CTX['B'], 'manager')
    for tag in ('mgr', 'keeper', 'staff', 'far'):
        users[tag] = app_login(tag)
    CTX['u'] = users
    CTX['admin'] = admin
    check('M0 两家门店与 11 个账号就绪（本店 %s / 别家 %s）' % (CTX['A'], CTX['B']), True, True)

    # 本店房源：员工端新建草稿 → 房型 → 三份必需资质 → 提交 → 后台审核上线
    mgr = users['mgr'][0]
    status, b = http('POST', '/app/staff/stay/stays?storeId=%s' % CTX['A'], {
        'stayName': '回归小院-%s' % RUN, 'address': '大理古城回归路 1 号', 'areaDesc': '古城内', 'cover': '/static/media/rooms-cover-1.jpg'}, app_token=mgr)
    check('M0 主理人新建房源草稿', 200, code_of(b))
    stay_id = n("select stay_id from kg_stay where store_id=%s and stay_name='回归小院-%s'" % (CTX['A'], RUN))
    status, b = http('POST', '/app/staff/stay/stays/%s/room-types' % stay_id, {
        'typeName': '回归大床房', 'roomKind': 'private', 'capacity': 2, 'stock': 3, 'basePriceFen': 20000}, app_token=mgr)
    check('M0 新增房型', 200, code_of(b))
    rt_id = n("select room_type_id from kg_stay_room_type where stay_id=%s and del_flag='0' order by room_type_id limit 1" % stay_id)
    for qt in ('license', 'fire', 'idcard'):
        b = upload('/app/staff/stay/stays/%s/qualifications?type=%s&expire=2030-12-31' % (stay_id, qt), mgr, qt + '.pdf', b'%PDF-1.4 regression')
        check('M0 上传资质 %s' % qt, 200, code_of(b))
    status, b = http('POST', '/app/staff/stay/stays/%s/submit' % stay_id, {}, app_token=mgr)
    check('M0 提交审核', 200, code_of(b))
    status, b = http('PUT', '/kangaroo/stayops/stay/%s/approve' % stay_id, token=admin)
    check('M0 后台审核上线后房源为 online', (200, 'online'), (code_of(b), scalar("select status from kg_stay where stay_id=%s" % stay_id)))
    CTX['stay'], CTX['rt'] = stay_id, rt_id


# ================================================================ M1 分佣入账与冲回

def m1_commission():
    section('M1 分佣：规则 → 已完成订单 → 重新计算入账 → 幂等 → 退款冲回')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    guest_tok, guest_id = u['guest']
    staff_tok = u['staff'][0]

    # 规则：住宿 5%，二消 10%
    for src, bp in (('stay', 500), ('shop', 1000)):
        status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': src, 'rateBp': bp,
                                                              'effectiveFrom': day(0), 'status': '0', 'remark': 'REG-%s' % RUN}, token=admin)
        check('M1 后台新增分佣规则 %s %d 基点' % (src, bp), 200, code_of(b))
    status, b = http('POST', '/kangaroo/ext/commission', {'payeeUserId': mgr_id, 'storeId': A, 'sourceType': 'stay', 'rateBp': 5000,
                                                          'effectiveFrom': day(0), 'status': '1'}, token=admin)
    check('M1 费率超过 3000 基点（30%）被拒', True, denied(b), ok=denied(b))
    status, b = http('GET', '/app/wallet/rules', app_token=mgr_tok)
    rules = (data(b) or {}).get('rules') or []
    check('M1 主理人在「收益规则」看到两条生效规则（5% / 10%）', ['5%', '10%'], sorted([r.get('rate') for r in rules], key=len))

    # 住宿单：今天入住、明天离店，支付后核销入住
    status, b = http('POST', '/app/stay/orders', {'roomTypeId': CTX['rt'], 'checkIn': day(0), 'checkOut': day(1), 'guests': 1,
                                                  'guestName': '回归住客', 'guestPhone': '13800138000', 'agreementVersion': AGREEMENT,
                                                  'idemKey': 'm1-' + RUN}, app_token=guest_tok)
    o = data(b) or {}
    check('M1 住客下单（今天入住）', (200, True), (code_of(b), bool(o.get('orderNo'))))
    stay_no, stay_pay = o.get('orderNo'), o.get('payNo')
    http('POST', '/app/pay/%s/prepay' % stay_pay, {}, app_token=guest_tok)
    status, b = http('POST', '/app/pay/%s/mock-confirm' % stay_pay, {}, app_token=guest_tok)
    check('M1 联调支付到账，订单已确认', (200, 'confirmed'), (code_of(b), scalar("select status from kg_stay_order where order_no='%s'" % stay_no)))
    stay_fen = n("select pay_fen from kg_stay_order where order_no='%s'" % stay_no)
    vcode = scalar("select verify_code from kg_stay_order where order_no='%s'" % stay_no)
    status, b = http('POST', '/app/staff/stay/verify/confirm?storeId=%s' % A, {'code': vcode}, app_token=u['far'][0])
    check('M1 别家门店不能核销本店订单', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/stay/verify/confirm?storeId=%s' % A, {'code': vcode}, app_token=mgr_tok)
    check('M1 本店核销入住', (200, 'checked_in'), (code_of(b), scalar("select status from kg_stay_order where order_no='%s'" % stay_no)))

    # 服务单：本店增值服务，入住期间预订，支付后核销
    cat = scalar("select dict_value from sys_dict_data where dict_type='kg_shop_category' and status='0' order by dict_sort limit 1")
    status, b = http('POST', '/kangaroo/ext/shopservice', {'storeId': A, 'category': cat, 'serviceName': '回归茶歇-%s' % RUN, 'priceFen': 8800,
                                                           'unitLabel': '份', 'timeSlots': '10:00-11:00', 'slotCapacity': 5, 'advanceHours': 0,
                                                           'rescheduleLimit': 1, 'refundable': '1', 'status': '0'}, token=admin)
    check('M1 后台新增本店增值服务', 200, code_of(b))
    svc = n("select service_id from kg_shop_service where store_id=%s and service_name='回归茶歇-%s'" % (A, RUN))
    status, b = http('POST', '/app/shop/orders', {'serviceId': svc, 'stayOrderNo': stay_no, 'qty': 1, 'useDate': day(1), 'timeSlot': '10:00-11:00',
                                                  'idemKey': 'm1s-' + RUN}, app_token=guest_tok)
    so = data(b) or {}
    check('M1 入住期间预订增值服务', (200, True), (code_of(b), bool(so.get('orderNo'))))
    shop_no, shop_pay = so.get('orderNo'), so.get('payNo')
    http('POST', '/app/pay/%s/prepay' % shop_pay, {}, app_token=guest_tok)
    http('POST', '/app/pay/%s/mock-confirm' % shop_pay, {}, app_token=guest_tok)
    shop_fen = n("select pay_fen from kg_shop_order where order_no='%s'" % shop_no)
    scode = scalar("select verify_code from kg_shop_order where order_no='%s'" % shop_no)
    status, b = http('POST', '/app/staff/stay/verify/confirm?storeId=%s' % A, {'code': scode}, app_token=mgr_tok)
    check('M1 本店核销服务单（used）', (200, 'used'), (code_of(b), scalar("select status from kg_shop_order where order_no='%s'" % shop_no)))
    CTX.update(stay_no=stay_no, shop_no=shop_no, stay_fen=stay_fen, shop_fen=shop_fen)

    # 住宿单离店完成（时间推移：离店后由定时任务改为 completed，这里直接把本次的单改成已完成）
    sql_exec("update kg_stay_order set status='completed', update_time=now() where order_no='%s' and status='checked_in'" % stay_no)

    res = parallel(lambda _: http('POST', '/kangaroo/manage/commission/accrue', {}, token=admin)[1], range(3), 3)
    check('M1 后台「重新计算」并发点 3 次都成功（与定时任务并发也安全）', [200, 200, 200], [code_of(b) for b in res])
    exp_stay = stay_fen * 500 // 10000
    exp_shop = shop_fen * 1000 // 10000
    got = sql("select source_type, base_fen, rate_bp, amount_fen, available_date > curdate() from kg_wallet_entry where app_user_id=%s "
              "and entry_type='credit' order by source_type" % mgr_id)
    check('M1 入账两条：服务单 基数×10%、住宿单 基数×5%（都未到结算日）',
          [['shop', str(shop_fen), '1000', str(exp_shop), '1'], ['stay', str(stay_fen), '500', str(exp_stay), '1']], got)
    status, b = http('POST', '/kangaroo/manage/commission/accrue', {}, token=admin)
    check('M1 重复计算不重复入账（每单仍只有一条入账）', '2', scalar("select count(*) from kg_wallet_entry where app_user_id=%s and entry_type='credit'" % mgr_id))
    status, b = http('GET', '/app/wallet', app_token=mgr_tok)
    w = data(b) or {}
    check('M1 钱包：可结算 0、待结算 = 两笔入账之和', (0, exp_stay + exp_shop), (w.get('availableFen'), w.get('pendingFen')))
    status, b = http('GET', '/app/wallet/entries?keyword=' + urllib.parse.quote(stay_no), app_token=mgr_tok)
    rows = (data(b) or {}).get('rows') or []
    check('M1 流水明细按单号能查到住宿入账（状态待结算）', [('待结算', '5%')], [(r.get('status'), r.get('rate')) for r in rows])
    status, b = http('GET', '/app/wallet', app_token=guest_tok)
    check('M1 别人的钱包看不到这些收益（住客余额 0）', (0, 0), ((data(b) or {}).get('availableFen'), (data(b) or {}).get('pendingFen')))
    status, b = http('GET', '/app/wallet')
    check('M1 未登录不能看钱包', 401, code_of(b))
    status, b = http('POST', '/kangaroo/manage/commission/accrue', {}, app_token=mgr_tok)
    check('M1 小程序令牌不能调后台入账接口', True, denied(b), ok=denied(b))

    # 完成后发生退款（线下 / 客诉退一半房费）：计佣基数变小 → 冲回
    refund = stay_fen // 2
    sql_exec("update kg_stay_order set refund_fen=%d, refund_time=now() where order_no='%s'" % (refund, stay_no))
    http('POST', '/kangaroo/manage/commission/accrue', {}, token=admin)
    keep = (stay_fen - refund) * 500 // 10000
    rev = sql("select amount_fen, base_fen from kg_wallet_entry where app_user_id=%s and biz_no='%s' and entry_type='reverse'" % (mgr_id, stay_no))
    check('M1 退款后冲回一条：金额 = -(原收益 - 剩余基数×5%)，基数 = 退款额', [[str(-(exp_stay - keep)), str(refund)]], rev)
    http('POST', '/kangaroo/manage/commission/accrue', {}, token=admin)
    check('M1 再算一次不重复冲回、只通知一次', (1, 1),
          (n("select count(*) from kg_wallet_entry where app_user_id=%s and biz_no='%s' and entry_type='reverse'" % (mgr_id, stay_no)),
           msgs(mgr_id, '收益冲回', 'user')))
    status, b = http('GET', '/app/wallet', app_token=mgr_tok)
    check('M1 冲回后待结算 = 剩余住宿收益 + 服务收益', keep + exp_shop, (data(b) or {}).get('pendingFen'))
    # 第二次退款（再退一部分）：收益应再按剩余基数冲回
    refund2 = stay_fen * 3 // 4
    sql_exec("update kg_stay_order set refund_fen=%d, refund_time=now() where order_no='%s'" % (refund2, stay_no))
    http('POST', '/kangaroo/manage/commission/accrue', {}, token=admin)
    # 第二条及以后的冲回记在「订单号#R2、#R3…」上（唯一键一单一类型一条），净收益要把它们一起算
    net = n("select coalesce(sum(amount_fen),0) from kg_wallet_entry where app_user_id=%s and (biz_no='%s' or biz_no like '%s#R%%')" % (mgr_id, stay_no, stay_no))
    check('M1 同一单第二次退款后，该单净收益 = 剩余基数 × 5%', (stay_fen - refund2) * 500 // 10000, net)
    CTX['refund_total'] = refund2


# ================================================================ M2 结算申请

def m2_withdraw():
    section('M2 结算：收款账户 → 金额校验 → 并发不超额 → 幂等 → 后台审核 / 打款')
    admin, u = CTX['admin'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    p2_tok, p2_id = u['payee2']

    # 校验类负向用例放在另一个账号上（结算接口每人每天限 10 次）
    status, b = http('POST', '/app/wallet/withdraw', {'amountFen': 100, 'idemKey': 'p2-a-' + RUN}, app_token=p2_tok)
    check('M2 没登记收款账户不能结算（422）', 422, code_of(b))
    status, b = http('POST', '/kangaroo/ext/payee', {'appUserId': p2_id, 'payeeName': '结算校验', 'bankName': '回归银行',
                                                     'accountMasked': '尾号 0002'}, token=admin)
    check('M2 后台登记收款账户', 200, code_of(b))
    status, b = http('POST', '/kangaroo/ext/payee', {'appUserId': p2_id, 'payeeName': '重复', 'bankName': '回归银行',
                                                     'accountMasked': '尾号 0003'}, token=admin)
    check('M2 同一用户不能登记第二个收款账户', (True, 1), (denied(b), n("select count(*) from kg_wallet_payee where app_user_id=%s" % p2_id)))
    http('POST', '/kangaroo/ext/walletentry', {'appUserId': p2_id, 'entryType': 'adjust', 'sourceType': 'adjust', 'bizNo': 'ADJ-P2-' + RUN,
                                               'amountFen': 500, 'availableDate': day(0), 'remark': '回归：人工调整'}, token=admin)
    for label, body in (('金额低于 10 元（单笔 ¥10–¥50,000）', {'amountFen': 999, 'idemKey': 'p2-b-' + RUN}),
                        ('金额高于 50000 元', {'amountFen': 5000001, 'idemKey': 'p2-e-' + RUN}),
                        ('负数金额', {'amountFen': -300, 'idemKey': 'p2-c-' + RUN}),
                        ('缺少幂等键', {'amountFen': 200}),
                        ('超过可结算余额', {'amountFen': 501, 'idemKey': 'p2-d-' + RUN})):
        status, b = http('POST', '/app/wallet/withdraw', body, app_token=p2_tok)
        check('M2 %s被拒（422）' % label, 422, code_of(b))
    check('M2 上面的非法申请都没落库', 0, n("select count(*) from kg_wallet_withdraw where app_user_id=%s" % p2_id))

    # 主理人：登记收款账户 + 一笔今天可结算的人工调整 300 元
    http('POST', '/kangaroo/ext/payee', {'appUserId': mgr_id, 'payeeName': '回归主理人', 'bankName': '回归银行', 'accountMasked': '尾号 0001'}, token=admin)
    status, b = http('POST', '/kangaroo/ext/walletentry', {'appUserId': mgr_id, 'storeId': CTX['A'], 'entryType': 'adjust', 'sourceType': 'adjust',
                                                           'bizNo': 'ADJ-M-' + RUN, 'amountFen': 30000, 'availableDate': day(0), 'remark': '回归：可结算'}, token=admin)
    status, b = http('GET', '/app/wallet', app_token=mgr_tok)
    w = data(b) or {}
    check('M2 钱包：可结算 = 300 元、收款账户已显示', (30000, True), (w.get('availableFen'), bool(w.get('payee'))))

    # 并发：5 个不同幂等键各申请 100 元 → 同一账号同时只能有 1 笔在途，恰好 1 笔成功（设计 m6 3.7 / W09）
    res = parallel(lambda i: http('POST', '/app/wallet/withdraw', {'amountFen': 10000, 'idemKey': 'c%d-%s' % (i, RUN)}, app_token=mgr_tok)[1], range(5), 5)
    succ = [data(b).get('withdrawNo') for b in res if ok(b)]
    total = n("select coalesce(sum(amount_fen),0) from kg_wallet_withdraw where app_user_id=%s and status in ('pending','approved','paid')" % mgr_id)
    check('M2 并发 5 笔 × 100 元：只成功 1 笔（同一账号同时只能有 1 笔在途）、在途合计 100 元', (1, 10000), (len(succ), total))
    status, b = http('GET', '/app/wallet', app_token=mgr_tok)
    check('M2 并发后可结算余额 = 300 - 100 = 200 元', 20000, (data(b) or {}).get('availableFen'))
    w1 = int(scalar("select withdraw_id from kg_wallet_withdraw where withdraw_no='%s'" % succ[0]))
    status, b = http('POST', '/app/wallet/withdraw', {'amountFen': 10000, 'idemKey': 'second-' + RUN}, app_token=mgr_tok)
    check('M2 有在途申请时再申请被拒（409）', (409, 1), (code_of(b), n("select count(*) from kg_wallet_withdraw where app_user_id=%s" % mgr_id)))

    admin_w = lambda wid, act, reason=None, tok=None: http('PUT', '/kangaroo/manage/withdraw/%s/%s' % (wid, act), {'reason': reason} if reason is not None else {}, token=tok or admin)[1]
    # 驳回：需要原因；驳回后额度退回
    b = admin_w(w1, 'reject')
    check('M2 驳回不填原因被拒、状态不变', (True, 'pending'), (denied(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w1)))
    b = admin_w(w1, 'reject', '回归：账户信息待核实')
    check('M2 驳回后状态 rejected、额度退回可结算', ('rejected', 30000),
          (scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w1), (data(http('GET', '/app/wallet', app_token=mgr_tok)[1]) or {}).get('availableFen')))
    b = admin_w(w1, 'approve')
    check('M2 已驳回的申请不能再通过', (True, 'rejected'), (denied(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w1)))

    # 幂等：同一幂等键先后两次 + 并发两次，只落一张单
    body = {'amountFen': 10000, 'idemKey': 'idem-' + RUN}
    b1 = http('POST', '/app/wallet/withdraw', body, app_token=mgr_tok)[1]
    b2 = http('POST', '/app/wallet/withdraw', body, app_token=mgr_tok)[1]
    rr = parallel(lambda _: http('POST', '/app/wallet/withdraw', body, app_token=mgr_tok)[1], range(2), 2)
    nos = set([(data(x) or {}).get('withdrawNo') for x in [b1, b2] + rr])
    check('M2 同一幂等键四次提交都返回同一单号', 1, len(nos - {None}))
    check('M2 库里这个幂等键只有一张单、可结算 = 200 元', (1, 20000),
          (n("select count(*) from kg_wallet_withdraw where app_user_id=%s and idem_key='idem-%s'" % (mgr_id, RUN)),
           (data(http('GET', '/app/wallet', app_token=mgr_tok)[1]) or {}).get('availableFen')))
    w2 = int(scalar("select withdraw_id from kg_wallet_withdraw where app_user_id=%s and idem_key='idem-%s'" % (mgr_id, RUN)))

    # 审核通过 → 登记打款（四眼原则：登记打款的人不能是审批的人）
    status, b = http('PUT', '/kangaroo/ext/withdraw', {'withdrawId': w2, 'status': 'paid', 'amountFen': 1}, token=admin)
    check('M2 通用表单不能绕过审核直接改结算单状态 / 金额', (True, 'pending', '10000'),
          (denied(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w2), scalar("select amount_fen from kg_wallet_withdraw where withdraw_id=%s" % w2)))
    b = admin_w(w2, 'approve')
    check('M2 后台通过 → approved', 'approved', scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w2))
    b = admin_w(w2, 'approve')
    check('M2 重复通过被拒', True, denied(b), ok=denied(b))
    b = admin_w(w2, 'reject', '通过后想驳回')
    check('M2 已通过的申请不能再驳回（财务可能已线下付款，驳回会让金额回到余额再提一次）', (True, 'approved', 20000),
          (denied(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w2), (data(http('GET', '/app/wallet', app_token=mgr_tok)[1]) or {}).get('availableFen')))
    b = admin_w(w2, 'paid')
    check('M2 登记打款必须填凭证', (True, 'approved'), (denied(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w2)))
    b = admin_w(w2, 'paid', 'BANK-SAME-%s' % RUN)
    # 用 409 而不是 403：后台会把 403 统一显示成「当前操作没有权限」，管理员看不到真正的原因
    check('M2 四眼原则：审批人自己不能登记打款（409），状态不变', (409, 'approved'), (code_of(b), scalar("select status from kg_wallet_withdraw where withdraw_id=%s" % w2)))
    admin2 = CTX.get('admin2')
    check('M2 准备第二个后台账号（另一位财务）', True, bool(admin2), ok=bool(admin2))
    if admin2:
        b = admin_w(w2, 'paid', 'BANK-%s' % RUN, tok=admin2)
        check('M2 另一位财务登记打款 → paid，凭证落库', ('paid', 'BANK-%s' % RUN),
              tuple(sql("select status, paid_voucher from kg_wallet_withdraw where withdraw_id=%s" % w2)[0]))
        b = admin_w(w2, 'paid', 'BANK-AGAIN', tok=admin2)
        b2 = admin_w(w2, 'reject', '打款后驳回', tok=admin2)
        check('M2 已打款的不能再登记、也不能再驳回', (True, True, 'BANK-%s' % RUN), (denied(b), denied(b2), scalar("select paid_voucher from kg_wallet_withdraw where withdraw_id=%s" % w2)))
    check('M2 打款后可结算仍为 200 元（已打款计入已用额度）', 20000, (data(http('GET', '/app/wallet', app_token=mgr_tok)[1]) or {}).get('availableFen'))
    status, b = http('GET', '/app/wallet/withdraws', app_token=mgr_tok)
    st = dict(((x.get('withdrawNo'), x.get('statusText')) for x in (data(b) or [])))
    check('M2 小程序结算记录：已驳回 / 已打款 与库一致', ('已驳回', '已打款'), (st.get(succ[0]), st.get(scalar("select withdraw_no from kg_wallet_withdraw where withdraw_id=%s" % w2))))
    check('M2 主理人收到「结算已打款」「结算申请未通过」消息', (1, 1), (msgs(mgr_id, '结算已打款', 'user'), msgs(mgr_id, '结算申请未通过', 'user')))
    status, b = http('GET', '/app/wallet/withdraws', app_token=CTX['u']['guest'][0])
    check('M2 别人的结算记录列表为空', [], data(b))


# ================================================================ M3 周报

def m3_weekly():
    section('M3 周报：打开 / 草稿版本号 / 提交冻结 / 后台退回 / 再提交；越权')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    keeper_tok, keeper_id = u['keeper']

    res = parallel(lambda _: http('POST', '/app/manage/weekly/open', {'storeId': A}, app_token=mgr_tok)[1], range(3), 3)
    rids = set((data(b) or {}).get('reportId') for b in res if ok(b))
    check('M3 并发打开本周周报：都成功且同一张', (True, 1), (all(ok(b) for b in res), len(rids)))
    if not all(ok(b) for b in res):
        print('       并发打开失败的返回：%s' % [b for b in res if not ok(b)])
    _cn = time.gmtime(time.time() + 8 * 3600)
    mon = time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 - _cn.tm_wday * 86400))
    check('M3 库里本店本周只有一张周报', 1, n("select count(*) from kg_weekly_report where store_id=%s and week_start='%s'" % (A, mon)))
    rid = rids.pop()
    CTX['report'] = rid
    status, b = http('POST', '/app/manage/weekly/open', {'storeId': A, 'weekStart': day(8)}, app_token=mgr_tok)
    check('M3 下周的周报不能提前写', True, denied(b), ok=denied(b))
    for tag in ('far', 'staff', 'guest'):
        status, b = http('GET', '/app/manage/weekly/%s' % rid, app_token=u[tag][0])
        check('M3 %s 看不到本店周报（403）' % tag, 403, code_of(b))
    status, b = http('POST', '/app/manage/weekly/open', {'storeId': A}, app_token=u['far'][0])
    check('M3 别家主理人不能打开本店周报（403）', 403, code_of(b))

    d = data(http('GET', '/app/manage/weekly/%s' % rid, app_token=keeper_tok)[1]) or {}
    ver = d.get('version')
    check('M3 本店管家能看到同一张周报（草稿）', ('draft', rid), (d.get('status'), d.get('reportId')))
    payload = {'version': ver, 'note': '本周回归补充说明', 'risk': '无', 'proofs': [],
               'fees': [{'name': '采购', 'amountFen': 12345, 'remark': '回归'}], 'coops': [{'text': '需要平台协助推广', 'dept': '运营', 'done': False}]}
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, payload, app_token=mgr_tok)
    v2 = (data(b) or {}).get('version')
    check('M3 保存草稿：版本号 +1、费用与协作项落库', (200, int(ver) + 1, 2),
          (code_of(b), int(v2 or 0), n("select count(*) from kg_weekly_item where report_id=%s" % rid)))
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, dict(payload, note='旧版本覆盖'), app_token=keeper_tok)
    check('M3 用旧版本号保存被拒（409），内容没被覆盖', (409, '本周回归补充说明'), (code_of(b), scalar("select note from kg_weekly_report where report_id=%s" % rid)))
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, dict(payload, version=v2, fees=[{'name': 'x', 'amountFen': 0}]), app_token=mgr_tok)
    check('M3 费用金额为 0 被拒（422）且原费用项没被删', (422, 1),
          (code_of(b), n("select count(*) from kg_weekly_item where report_id=%s and item_type='fee'" % rid)))
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, dict(payload, version=v2, note='长' * 301), app_token=mgr_tok)
    check('M3 补充说明超过 300 字被拒（422）', 422, code_of(b))

    status, b = http('POST', '/app/manage/weekly/%s/submit' % rid, {'version': v2}, app_token=mgr_tok)
    check('M3 未勾选"内容真实"不能提交（422）', 422, code_of(b))
    status, b = http('POST', '/app/manage/weekly/%s/submit' % rid, {'version': v2, 'confirmed': True}, app_token=mgr_tok)
    r = sql("select status, snapshot is not null, submitted_by from kg_weekly_report where report_id=%s" % rid)[0]
    check('M3 提交 → submitted、系统汇总冻结、提交人落库', ['submitted', '1', str(mgr_id)], r)
    check('M3 本店管家收到「周报已提交」', 1, msgs(keeper_id, '周报已提交'))
    v3 = (data(b) or {}).get('version')
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, dict(payload, version=v3), app_token=mgr_tok)
    check('M3 提交后不能再改（409）', 409, code_of(b))
    status, b = http('POST', '/app/manage/weekly/%s/submit' % rid, {'version': v3, 'confirmed': True}, app_token=mgr_tok)
    check('M3 重复提交被拒（409）', 409, code_of(b))

    status, b = http('PUT', '/kangaroo/manage/weekly/%s/return' % rid, {}, token=admin)
    check('M3 后台退回必须填原因', (True, 'submitted'), (denied(b), scalar("select status from kg_weekly_report where report_id=%s" % rid)))
    status, b = http('PUT', '/kangaroo/manage/weekly/%s/return' % rid, {'reason': '回归：费用缺凭证'}, token=admin)
    check('M3 后台退回 → returned、原因落库、门店员工收到通知', ('returned', '回归：费用缺凭证', True),
          (scalar("select status from kg_weekly_report where report_id=%s" % rid), scalar("select return_reason from kg_weekly_report where report_id=%s" % rid),
           msgs(mgr_id, '周报被退回') >= 1))
    status, b = http('PUT', '/kangaroo/manage/weekly/%s/return' % rid, {'reason': '再退'}, token=admin)
    check('M3 已退回的不能再退回', True, denied(b), ok=denied(b))
    d = data(http('GET', '/app/manage/weekly/%s' % rid, app_token=mgr_tok)[1]) or {}
    status, b = http('PUT', '/app/manage/weekly/%s/draft' % rid, dict(payload, version=d.get('version'), note='补了凭证'), app_token=mgr_tok)
    check('M3 退回后可以继续改，状态保持 returned', (200, 'returned'), (code_of(b), scalar("select status from kg_weekly_report where report_id=%s" % rid)))
    status, b = http('POST', '/app/manage/weekly/%s/submit' % rid, {'version': (data(b) or {}).get('version'), 'confirmed': True}, app_token=mgr_tok)
    check('M3 再次提交 → submitted', 'submitted', scalar("select status from kg_weekly_report where report_id=%s" % rid))

    status, b = http('GET', '/app/manage/weekly/summary?storeId=%s' % A, app_token=mgr_tok)
    cols = dict((c.get('label'), c.get('value')) for c in ((data(b) or {}).get('orderCols') or []))
    next_mon = time.strftime('%Y-%m-%d', time.gmtime(time.time() + 8 * 3600 + (7 - time.gmtime(time.time() + 8 * 3600).tm_wday) * 86400))
    tot, cnt = revenue_db(A, mon, next_mon)
    check('M3 周报汇总「订单数 / 支付金额」与库里本周聚合一致', (str(cnt), yuan_text(tot)), (cols.get('订单数'), cols.get('支付金额(元)')))
    status, b = http('GET', '/app/manage/weekly/summary?storeId=%s' % A, app_token=u['far'][0])
    check('M3 别家主理人查本店周报汇总（403）', 403, code_of(b))


# ================================================================ M4 日报

def m4_daily():
    section('M4 日报：录入（幂等 / 并发）/ 作废 / 合计回库对账 / 导出 / 发送')
    A, u = CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    keeper_tok = u['keeper'][0]
    base = {'storeId': A, 'date': day(0), 'direction': 'income', 'category': '其他收入', 'amountFen': 12345, 'payMethod': '现金', 'remark': '回归'}

    b1 = http('POST', '/app/manage/daily/entries', dict(base, idemKey='d1-' + RUN), app_token=mgr_tok)[1]
    b2 = http('POST', '/app/manage/daily/entries', dict(base, idemKey='d1-' + RUN), app_token=mgr_tok)[1]
    check('M4 同一幂等键重放返回同一条明细', (200, True), (code_of(b1), (data(b1) or {}).get('entryId') == (data(b2) or {}).get('entryId')))
    res = parallel(lambda _: http('POST', '/app/manage/daily/entries', dict(base, amountFen=5000, direction='expense', category='采购支出',
                                                                            idemKey='d2-' + RUN), app_token=keeper_tok)[1], range(3), 3)
    check('M4 并发三次同一幂等键：库里只多一条支出', 1, n("select count(*) from kg_daily_entry where store_id=%s and amount_fen=5000 and status='0'" % A))
    check('M4 并发请求都得到成功或"已保存过"', True, all(code_of(b) in (200, 409) for b in res), ok=all(code_of(b) in (200, 409) for b in res))
    for label, patch in (('分类与方向不匹配', {'direction': 'expense', 'category': '其他收入'}), ('金额为 0', {'amountFen': 0}),
                         ('收付方式非法', {'payMethod': '比特币'}), ('90 天前', {'date': day(-91)}), ('未来日期', {'date': day(1)}),
                         ('超过 100 万元', {'amountFen': 100000001})):
        status, b = http('POST', '/app/manage/daily/entries', dict(base, idemKey='bad-%s-%s' % (label, RUN), **patch), app_token=mgr_tok)
        check('M4 %s被拒（422）' % label, 422, code_of(b))
    status, b = http('POST', '/app/manage/daily/entries', dict(base, idemKey='staff-' + RUN), app_token=u['staff'][0])
    check('M4 普通员工不能录入经营明细（403）', 403, code_of(b))
    status, b = http('POST', '/app/manage/daily/entries', dict(base, idemKey='far-' + RUN), app_token=u['far'][0])
    check('M4 别家主理人不能往本店录入（403）', 403, code_of(b))
    check('M4 本店有效手工明细只有 2 条（越权 / 非法都没落库）', 2, n("select count(*) from kg_daily_entry where store_id=%s and status='0'" % A))

    eid = (data(b1) or {}).get('entryId')
    status, b = http('POST', '/app/manage/daily/entries/%s/void' % eid, {}, app_token=u['far'][0])
    check('M4 别家主理人不能作废本店明细（403）', (403, '0'), (code_of(b), scalar("select status from kg_daily_entry where entry_id=%s" % eid)))

    def totals():
        d = data(http('GET', '/app/manage/daily?storeId=%s&date=%s' % (A, day(0)), app_token=mgr_tok)[1]) or {}
        return d.get('totals')
    rev, _ = revenue_db(A, day(0), day(1))
    man_in = n("select coalesce(sum(amount_fen),0) from kg_daily_entry where store_id=%s and biz_date=curdate() and status='0' and direction='income'" % A)
    man_out = n("select coalesce(sum(amount_fen),0) from kg_daily_entry where store_id=%s and biz_date=curdate() and status='0' and direction='expense'" % A)
    check('M4 日报合计 = 系统营收 + 手工收入 / 手工支出（回库对账）', [rev + man_in, man_out, rev + man_in - man_out], totals())
    status, b = http('POST', '/app/manage/daily/entries/%s/void' % eid, {}, app_token=mgr_tok)
    check('M4 作废 → status=1', '1', scalar("select status from kg_daily_entry where entry_id=%s" % eid))
    status, b = http('POST', '/app/manage/daily/entries/%s/void' % eid, {}, app_token=mgr_tok)
    check('M4 重复作废被拒', True, denied(b), ok=denied(b))
    check('M4 作废后合计不再含这笔收入', [rev, man_out, rev - man_out], totals())
    status, b = http('GET', '/app/manage/daily?storeId=%s&date=%s' % (A, day(1)), app_token=mgr_tok)
    check('M4 不能看未来日期的日报', True, denied(b), ok=denied(b))

    st, ctype, raw = get_binary('/app/manage/daily/report.xlsx?storeId=%s&date=%s' % (A, day(0)), mgr_tok)
    xml = ''
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
        xml = z.read('xl/sharedStrings.xml').decode('utf-8')
    except Exception:  # noqa: BLE001
        pass
    check('M4 导出 Excel：xlsx 格式、含本店名称与「收支结余」', (200, True, True, True),
          (st, 'spreadsheetml' in ctype, ('经营回归小院A-' + RUN) in xml, '收支结余' in xml))
    st, ctype, raw = get_binary('/app/manage/daily/report.xlsx?storeId=%s&date=%s' % (A, day(0)), u['far'][0])
    check('M4 别家主理人导不出本店报表', True, b'PK' != raw[:2], ok=b'PK' != raw[:2])
    st, ctype, raw = get_binary('/app/manage/daily/report.xlsx?storeId=%s&date=%s' % (A, day(0)))
    check('M4 未登录导不出报表', True, b'PK' != raw[:2], ok=b'PK' != raw[:2])

    before = msgs(mgr_id, '每日经营报表%')
    status, b = http('POST', '/app/manage/daily/report/send', {'storeId': A, 'date': day(0)}, app_token=keeper_tok)
    check('M4 管家发送日报：发给 1 位主理人，主理人消息 +1', (200, 1, before + 1), (code_of(b), (data(b) or {}).get('sent'), msgs(mgr_id, '每日经营报表%')))


# ================================================================ M5 看板

def m5_dashboard():
    section('M5 看板：营业额 / 二消 / 入住率 / 订单数 / 排名 与库里聚合一致')
    A, u = CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    f, t = day(-6), day(1)
    total, cnt = revenue_db(A, f, t)
    shop = n("select coalesce(sum(pay_fen),0) from kg_shop_order where store_id=%s and paid_time>='%s' and paid_time<'%s'" % (A, f, t)) - \
        n("select coalesce(sum(refund_fen),0) from kg_shop_order where store_id=%s and refund_fen>0 and refund_time>='%s' and refund_time<'%s'" % (A, f, t))
    check('M5 本店近 7 天营收（库）= 住宿 + 服务 - 退款（本次造的单）', CTX['stay_fen'] + CTX['shop_fen'] - CTX['refund_total'], total)
    status, b = http('GET', '/app/manage/dashboard/overview?period=7', app_token=mgr_tok)
    ms = (data(b) or {}).get('metrics') or []
    got = [m.get('value') for m in ms]
    cap = n("select coalesce(sum(r.stock),0) from kg_stay_room_type r join kg_stay s on s.stay_id=r.stay_id where s.store_id=%s and s.status='online' "
            "and s.del_flag='0' and r.status='0' and r.del_flag='0'" % A)
    sold = n("select coalesce(sum(o.units*greatest(0, datediff(least(o.check_out,'%s'), greatest(o.check_in,'%s')))),0) from kg_stay_order o where o.store_id=%s "
             "and o.del_flag='0' and o.status in ('confirmed','checked_in','completed') and o.check_in<'%s' and o.check_out>'%s'" % (t, f, A, t, f))
    occ = java_round(sold * 10000.0 / (cap * 7)) if cap else None
    check('M5 看板四项：营业额 / 入住率 / 转化率(无报名为—或按库) / 二消 与库一致',
          [yuan_text(total).replace('¥', ''), pct(occ), got[2] if len(got) > 2 else None, yuan_text(shop).replace('¥', '')], got)
    status, b = http('GET', '/app/manage/dashboard/trend?storeId=%s&period=7' % A, app_token=mgr_tok)
    orders = sum((data(b) or {}).get('orders') or [])
    check('M5 趋势：订单数合计 = 库里近 7 天支付成功的单数', cnt, orders)
    status, b = http('GET', '/app/manage/dashboard/rank?metric=revenue&period=7', app_token=mgr_tok)
    rows = (data(b) or {}).get('rows') or []
    check('M5 排名：只含本人有权限的门店，营收 = 库', [(A, total)], [(r.get('storeId'), r.get('value')) for r in rows])
    status, b = http('GET', '/app/manage/dashboard/trend?storeId=%s&period=7' % A, app_token=u['far'][0])
    check('M5 别家主理人按本店 storeId 查趋势：拿不到本店数据', 0, sum((data(b) or {}).get('orders') or [0]))
    for tag in ('staff', 'guest'):
        status, b = http('GET', '/app/manage/dashboard/overview?period=7', app_token=u[tag][0])
        check('M5 %s 不能看数据看板（403）' % tag, 403, code_of(b))
    status, b = http('GET', '/app/manage/dashboard/reviews?storeId=%s&period=7' % CTX['B'], app_token=mgr_tok)
    check('M5 评价统计：传别家门店 storeId 被拒（403）', 403, code_of(b))

    before = n("select count(*) from kg_task where store_id=%s and status='draft'" % A)
    status, b = http('POST', '/app/manage/dashboard/improvement-plan', {'storeId': A, 'advice': ['加强客房清洁', '', '优化入住接待']}, app_token=mgr_tok)
    check('M5 生成改进计划：2 条建议 → 2 条任务草稿', (200, 2, before + 2),
          (code_of(b), (data(b) or {}).get('created'), n("select count(*) from kg_task where store_id=%s and status='draft'" % A)))
    status, b = http('POST', '/app/manage/dashboard/improvement-plan', {'storeId': A, 'advice': ['x']}, app_token=u['staff'][0])
    check('M5 普通员工不能生成改进计划（403）', 403, code_of(b))


# ================================================================ S1 员工扩展准备（报名推进）

slot_seq = {'n': 0}


def new_slot(store_id):
    slot_seq['n'] += 1
    base = time.time() + 86400 * 9 + (int(RUN[:4], 16) % 600) * 60 + slot_seq['n'] * 3600
    s0 = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base))
    e0 = time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(base + 1200))
    status, b = http('POST', '/kangaroo/slot', {'storeId': store_id, 'startTime': s0, 'endTime': e0}, token=CTX['admin'])
    assert ok(b), b


def new_job(store_id, title, job_type='volunteer', headcount=3):
    status, b = http('POST', '/kangaroo/job', {'storeId': store_id, 'jobType': job_type, 'title': title, 'city': '大理', 'address': '回归路',
                                               'salaryType': 'daily', 'salaryMin': 100, 'salaryUnit': '元/天', 'startDate': day(1),
                                               'minDays': 1, 'headcount': headcount}, token=CTX['admin'])
    assert ok(b), b
    jid = int(data(b))
    status, b = http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=CTX['admin'])
    assert ok(b), b
    return jid


def apply(job_id, tok, name, tag, arrival=3):
    status, b = http('POST', '/app/application', {'jobId': job_id, 'name': name, 'age': 24, 'phone': '13500135000', 'arrival': day(arrival),
                                                  'days': 30, 'idemKey': 'mgt-%s-%s' % (RUN, tag)}, app_token=tok)
    assert ok(b), b
    return int(data(b)['appId'])


def to_store_confirmed(job_id, tok, name, tag):
    admin = CTX['admin']
    aid = apply(job_id, tok, name, tag)
    http('PUT', '/kangaroo/application/audit', {'appId': aid, 'pass': True}, token=admin)
    new_slot(CTX['A'])
    slots = data(http('GET', '/app/application/%s/slots' % aid, app_token=tok)[1]) or []
    assert slots, '需要可选面试时段'
    http('POST', '/app/application/%s/book-interview' % aid, {'slotId': slots[0]['slotId']}, app_token=tok)
    from interview_clock_fixture import advance_interview_fixture
    advance_interview_fixture(aid)
    http('POST', '/app/application/%s/finish-interview' % aid, {}, app_token=tok)
    http('POST', '/app/application/%s/confirm' % aid, {'intent': 'accept'}, app_token=tok)
    status, b = http('PUT', '/kangaroo/application/confirm/%s' % aid, token=admin)
    assert ok(b), b
    return aid


def s1_setup():
    section('S1 员工扩展准备：本店岗位，报名推进到「已确认录用」「已入职」')
    A, u = CTX['A'], CTX['u']
    jid = new_job(A, '回归咖啡伙伴-' + RUN, 'volunteer', 3)
    CTX['job'] = jid
    a1 = to_store_confirmed(jid, u['talent'][0], '候选人丙丙', 'a1')
    a2 = to_store_confirmed(jid, u['vol'][0], '回归义工', 'a2')
    b = _kf.sign_contract(a2, u['vol'][0])[1]
    b = http('POST', '/app/staff/applications/%s/onboard' % a2, {}, app_token=u['mgr'][0])[1]
    check('S1 报名一：已确认录用；报名二：已入职',
          ('store_confirmed', 'onboarded'), (scalar("select status from kg_application where app_id=%s" % a1), scalar("select status from kg_application where app_id=%s" % a2)))
    CTX['a1'], CTX['a2'] = a1, a2
    # 别家门店也建一个岗位（跨店用例）
    CTX['jobB'] = new_job(CTX['B'], '别家岗位-' + RUN, 'volunteer', 2)


# ================================================================ M6 任务

def m6_tasks():
    section('M6 门店任务：发布 / 接受 / 认领名额并发 / 取消认领 / 验收积分 / 取消；角色边界')
    A, u = CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    staff_tok, staff_id = u['staff']
    keeper_tok, keeper_id = u['keeper']
    vol_tok, vol_id = u['vol']

    def save(tok, **kw):
        body = {'storeId': A, 'title': '回归任务-' + RUN, 'taskDate': day(1), 'startTime': '10:00', 'endTime': '12:00', 'estHours': 2,
                'assignMode': 'staff', 'assignees': [staff_id], 'quota': 1, 'publish': True}
        body.update(kw)
        return http('POST', '/app/manage/tasks', body, app_token=tok)[1]

    b = save(staff_tok)
    check('M6 普通员工不能发布任务（403）', 403, code_of(b))
    b = save(u['far'][0])
    check('M6 别家主理人不能给本店发任务（403）', 403, code_of(b))
    b = save(mgr_tok, assignees=[u['outsider'][1]])
    check('M6 不能指定非本店员工（422）', 422, code_of(b))
    b = save(mgr_tok, taskDate=day(-1))
    check('M6 任务日期早于今天被拒（422）', 422, code_of(b))
    b = save(mgr_tok, startTime='12:00', endTime='10:00')
    check('M6 开始晚于结束被拒（422）', 422, code_of(b))
    b = save(mgr_tok, estHours='abc')
    check('M6 工时不是数字：给出校验错误（422）而不是 500', 422, code_of(b))

    b = save(mgr_tok)
    t1 = (data(b) or {}).get('taskId')
    check('M6 主理人发布指定员工任务：published、执行人待接受、员工收到通知', ('published', 'pending', 1),
          (scalar("select status from kg_task where task_id=%s" % t1), scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (t1, staff_id)),
           n("select count(*) from kg_message where recipient_id=%s and biz_type='task' and biz_id=%s" % (staff_id, t1))))
    status, b = http('GET', '/app/manage/tasks/eligible-staff?storeId=%s' % A, app_token=mgr_tok)
    ids = set(int(x.get('userId')) for x in (data(b) or []))
    check('M6 可指定员工 = 本店三位员工', {u['mgr'][1], keeper_id, staff_id}, ids)
    status, b = http('GET', '/app/manage/tasks/eligible-staff?storeId=%s' % A, app_token=staff_tok)
    check('M6 普通员工不能查可指定员工（403）', 403, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/claim' % t1, {}, app_token=keeper_tok)
    check('M6 没被指定的管家不能认领指定员工任务（403）', 403, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/claim' % t1, {}, app_token=staff_tok)
    check('M6 被指定员工接受 → accepted', 'accepted', scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (t1, staff_id)))
    status, b = http('POST', '/app/manage/tasks/%s/unclaim' % t1, {}, app_token=staff_tok)
    check('M6 被指定员工取消接受 → 回到待接受', 'pending', scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (t1, staff_id)))
    status, b = http('GET', '/app/manage/tasks/%s' % t1, app_token=u['outsider'][0])
    check('M6 路人看不到本店任务（403）', 403, code_of(b))

    # 义工任务：名额 1，已入职义工与管家同时认领 → 只成功 1 个
    b = save(mgr_tok, assignMode='volunteer', assignees=[], quota=1, title='回归义工任务-' + RUN)
    t2 = (data(b) or {}).get('taskId')
    status, b = http('POST', '/app/manage/tasks/%s/claim' % t2, {}, app_token=u['outsider'][0])
    check('M6 没在本店入职过的路人不能认领义工任务（403）', 403, code_of(b))
    res = parallel(lambda tok: http('POST', '/app/manage/tasks/%s/claim' % t2, {}, app_token=tok)[1], [vol_tok, keeper_tok, vol_tok], 3)
    taken = n("select count(*) from kg_task_assignee where task_id=%s and status in ('accepted','done')" % t2)
    check('M6 名额 1 的义工任务并发认领：最终只占 1 个名额', 1, taken)
    check('M6 并发认领里恰好 1 个成功', 1, sum(1 for b in res if ok(b)))
    who = scalar("select app_user_id from kg_task_assignee where task_id=%s and status='accepted'" % t2)
    who_tok = vol_tok if str(who) == str(vol_id) else keeper_tok
    status, b = http('POST', '/app/manage/tasks/%s/unclaim' % t2, {}, app_token=who_tok)
    check('M6 认领人取消认领 → cancelled，名额空出', (200, 0), (code_of(b), n("select count(*) from kg_task_assignee where task_id=%s and status='accepted'" % t2)))
    status, b = http('POST', '/app/manage/tasks/%s/claim' % t2, {}, app_token=vol_tok)
    check('M6 义工重新认领成功', 'accepted', scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (t2, vol_id)))

    # 验收：今天的任务（已开始），员工接受后主理人验收
    b = save(mgr_tok, taskDate=day(0), startTime='00:00', endTime='23:59', title='回归当日任务-' + RUN)
    t3 = (data(b) or {}).get('taskId')
    http('POST', '/app/manage/tasks/%s/claim' % t3, {}, app_token=staff_tok)
    status, b = http('POST', '/app/manage/tasks/%s/unclaim' % t3, {}, app_token=staff_tok)
    check('M6 开始前 2 小时内不能取消接受（409）', 409, code_of(b))
    pts_before = n("select coalesce(sum(delta),0) from kg_point_entry where app_user_id=%s" % staff_id)
    status, b = http('POST', '/app/manage/tasks/%s/check' % t3, {'userId': staff_id, 'hours': 2, 'rating': 5, 'comment': '好'}, app_token=staff_tok)
    check('M6 普通员工不能验收（403）', 403, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/check' % t3, {'userId': staff_id, 'hours': 30, 'rating': 5}, app_token=mgr_tok)
    check('M6 实际工时 30 小时被拒（422）', 422, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/check' % t3, {'userId': staff_id, 'hours': 2.5, 'rating': 5, 'comment': '完成得好'}, app_token=mgr_tok)
    check('M6 验收 → done、积分 25（2.5 小时 × 10）', ('done', '25'),
          tuple(sql("select status, points from kg_task_assignee where task_id=%s and app_user_id=%s" % (t3, staff_id))[0]))
    status, b = http('POST', '/app/manage/tasks/%s/check' % t3, {'userId': staff_id, 'hours': 2.5, 'rating': 5}, app_token=mgr_tok)
    check('M6 重复验收被拒且积分只加一次', (409, pts_before + 25),
          (code_of(b), n("select coalesce(sum(delta),0) from kg_point_entry where app_user_id=%s" % staff_id)))
    status, b = http('GET', '/app/points', app_token=staff_tok)
    check('M6 员工「我的积分」= 库里积分合计', pts_before + 25, (data(b) or {}).get('points'))

    status, b = http('POST', '/app/manage/tasks/%s/cancel' % t3, {'reason': 'x'}, app_token=mgr_tok)
    check('M6 已有验收记录的任务不能取消（409）', 409, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/cancel' % t2, {'reason': '天气原因'}, app_token=staff_tok)
    check('M6 普通员工不能取消任务（403）', 403, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/cancel' % t2, {'reason': '天气原因'}, app_token=mgr_tok)
    check('M6 主理人取消 → 任务 cancelled、认领人 cancelled、义工收到通知', ('cancelled', 'cancelled', True),
          (scalar("select status from kg_task where task_id=%s" % t2), scalar("select status from kg_task_assignee where task_id=%s and app_user_id=%s" % (t2, vol_id)),
           n("select count(*) from kg_message where recipient_id=%s and title='任务已取消' and biz_id=%s" % (vol_id, t2)) >= 1))
    status, b = http('POST', '/app/manage/tasks/%s/claim' % t2, {}, app_token=vol_tok)
    check('M6 已取消的任务不能再认领（409）', 409, code_of(b))
    status, b = http('POST', '/app/manage/tasks/%s/cancel' % t2, {}, app_token=mgr_tok)
    check('M6 重复取消被拒', True, denied(b), ok=denied(b))


# ================================================================ M7 荣誉与关怀

def m7_honor():
    section('M7 荣誉与关怀：生日 / 礼物领取（条件、并发、库存）/ 发货 / 申报')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    for tag in ('guest',):
        status, b = http('GET', '/app/honor/birthday', app_token=u[tag][0])
        check('M7 非员工看不到生日关怀（403）', 403, code_of(b))
    for bad in ('13-01', '2-3', '02-30', '0101'):
        status, b = http('PUT', '/app/honor/birthday', {'birthday': bad}, app_token=mgr_tok)
        check('M7 生日 %s 被拒（422）' % bad, 422, code_of(b))
    mm = time.strftime('%m', time.gmtime(time.time() + 8 * 3600))   # 后端按北京时间算月份
    status, b = http('PUT', '/app/honor/birthday', {'birthday': mm + '-01'}, app_token=mgr_tok)
    check('M7 设置生日为本月 1 日', (200, mm + '-01'), (code_of(b), scalar("select birthday from kg_app_user where user_id=%s" % mgr_id)))
    d = data(http('GET', '/app/honor/birthday', app_token=mgr_tok)[1]) or {}
    check('M7 刚授权的主理人不满足"满 90 天"，不能领取', False, d.get('canClaim'))
    status, b = http('POST', '/kangaroo/ext/giftoption', {'name': '回归礼物-' + RUN, 'subtitle': '回归', 'stock': 1, 'status': '0', 'sort': 9999}, token=admin)
    gid = n("select gift_id from kg_gift_option where name='回归礼物-%s'" % RUN)
    addr = {'giftId': gid, 'receiver': '回归主理人', 'phone': '13800138000', 'address': '大理古城回归路 1 号'}
    status, b = http('POST', '/app/honor/birthday/claim', addr, app_token=mgr_tok)
    check('M7 不满足条件领取被拒（409），库存不变', (409, '1'), (code_of(b), scalar("select stock from kg_gift_option where gift_id=%s" % gid)))
    # 时间推移：把本次的主理人授权时间前移 100 天（本月已在 M3 提交过周报）
    sql_exec("update kg_staff_grant set granted_time=date_sub(now(), interval 100 day) where app_user_id=%s and store_id=%s" % (mgr_id, A))
    d = data(http('GET', '/app/honor/birthday', app_token=mgr_tok)[1]) or {}
    check('M7 四项条件都满足后可以领取', (True, [True, True, True, True]), (d.get('canClaim'), [c.get('ok') for c in d.get('conditions') or []]))
    status, b = http('POST', '/app/honor/birthday/claim', dict(addr, phone='123'), app_token=mgr_tok)
    check('M7 手机号不合法被拒（422），库存不变', (422, '1'), (code_of(b), scalar("select stock from kg_gift_option where gift_id=%s" % gid)))
    res = parallel(lambda _: http('POST', '/app/honor/birthday/claim', addr, app_token=mgr_tok)[1], range(3), 3)
    check('M7 并发领取 3 次：只成功 1 次、领取记录 1 条、库存 1 → 0', (1, 1, '0'),
          (sum(1 for b in res if ok(b)), n("select count(*) from kg_gift_claim where app_user_id=%s" % mgr_id), scalar("select stock from kg_gift_option where gift_id=%s" % gid)))
    status, b = http('POST', '/app/honor/birthday/claim', addr, app_token=mgr_tok)
    check('M7 今年再领被拒（409）', 409, code_of(b))
    cid = n("select claim_id from kg_gift_claim where app_user_id=%s" % mgr_id)
    status, b = http('PUT', '/kangaroo/manage/giftclaim/%s/ship' % cid, {}, token=admin)
    check('M7 发货不填单号被拒', (True, 'pending'), (denied(b), scalar("select status from kg_gift_claim where claim_id=%s" % cid)))
    status, b = http('PUT', '/kangaroo/manage/giftclaim/%s/ship' % cid, {'reason': 'SF-%s' % RUN}, token=admin)
    check('M7 后台登记发货 → shipped、主理人收到通知', ('shipped', 'SF-%s' % RUN, 1),
          (scalar("select status from kg_gift_claim where claim_id=%s" % cid), scalar("select express_no from kg_gift_claim where claim_id=%s" % cid), msgs(mgr_id, '生日礼物已发货')))
    status, b = http('PUT', '/kangaroo/manage/giftclaim/%s/ship' % cid, {'reason': 'SF-2'}, token=admin)
    check('M7 重复发货被拒、单号不变', (True, 'SF-%s' % RUN), (denied(b), scalar("select express_no from kg_gift_claim where claim_id=%s" % cid)))
    d = data(http('GET', '/app/honor/birthday', app_token=mgr_tok)[1]) or {}
    ph = str((d.get('claim') or {}).get('phone'))
    check('M7 小程序显示"已发货 · 单号"、手机号脱敏（不含完整号码）', (True, True, True),
          ('SF-%s' % RUN in str((d.get('claim') or {}).get('statusText')), '13800138000' not in ph.replace(' ', ''), ph.startswith('138') and ph.endswith('8000')))
    http('PUT', '/kangaroo/ext/giftoption', {'giftId': gid, 'status': '1'}, token=admin)

    status, b = http('GET', '/app/honor', app_token=mgr_tok)
    check('M7 荣誉墙可打开', 200, code_of(b))
    status, b = http('GET', '/app/honor/growth', app_token=mgr_tok)
    check('M7 成长记录可打开', 200, code_of(b))

    opts = data(http('GET', '/app/appeal/options', app_token=mgr_tok)[1]) or {}
    types = opts.get('types') or []
    atype = (types[0].get('value') if types and isinstance(types[0], dict) else (types[0] if types else '其他'))
    status, b = http('POST', '/app/appeal', {'type': atype, 'content': '太短', 'help': '协助'}, app_token=mgr_tok)
    check('M7 申报经过少于 20 字被拒（422）', 422, code_of(b))
    status, b = http('POST', '/app/appeal', {'type': atype, 'content': '回归测试：结算打款金额与登记金额不一致，请平台协助核对流水。', 'help': '协助核对'}, app_token=mgr_tok)
    aid = (data(b) or {}).get('appealId')
    check('M7 提交申报 → pending', (200, 'pending'), (code_of(b), scalar("select status from kg_appeal where appeal_id=%s" % (aid or 0))))
    status, b = http('PUT', '/kangaroo/manage/appeal/%s/resolve' % aid, {'reason': '已核对，差额已补'}, token=admin)
    check('M7 后台处理 → resolved、申报人收到通知', ('resolved', True),
          (scalar("select status from kg_appeal where appeal_id=%s" % aid), msgs(mgr_id, '申报已处理') >= 1))
    status, b = http('PUT', '/kangaroo/manage/appeal/%s/reject' % aid, {'reason': 'x'}, token=admin)
    check('M7 已处理的申报不能再改', (True, 'resolved'), (denied(b), scalar("select status from kg_appeal where appeal_id=%s" % aid)))


# ================================================================ S2 投保

def s2_insurance():
    section('S2 投保：主理人申请 → 后台登记保单号 → 员工看到生效；普通员工不能申请；重复；跨店')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    a1, a2 = CTX['a1'], CTX['a2']
    plan = n("select plan_id from kg_insurance_plan where status='0' and del_flag='0' order by plan_id limit 1")
    check('M0 有上架的保险方案', True, plan > 0, ok=plan > 0)
    body = {'appId': a1, 'planId': plan, 'startDate': day(3)}
    status, b = http('POST', '/app/staff/insurance/apply', body, app_token=u['staff'][0])
    check('S2 普通员工不能提交投保（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/insurance/apply', body, app_token=u['far'][0])
    check('S2 别家主理人不能给本店报名投保（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/insurance/apply', dict(body, startDate=day(-1)), app_token=u['mgr'][0])
    check('S2 起保日期早于今天被拒', True, denied(b), ok=denied(b))
    check('S2 上面几次都没落库', 0, n("select count(*) from kg_insurance_apply where app_id=%s" % a1))
    res = parallel(lambda tok: http('POST', '/app/staff/insurance/apply', body, app_token=tok)[1], [u['mgr'][0], u['keeper'][0]] * 3, 6)
    check('S2 主理人与管家同时各点 3 次申请：只成功一份、在途申请 1 条', (1, 1),
          (sum(1 for b in res if ok(b)), n("select count(*) from kg_insurance_apply where app_id=%s and status in ('pending','effective')" % a1)))
    apply_id = n("select apply_id from kg_insurance_apply where app_id=%s" % a1)
    check('S2 候选人收到「门店已为你申请用工保险」', 1, msgs(u['talent'][1], '门店已为你申请用工保险'))
    status, b = http('POST', '/app/staff/insurance/apply-batch?storeId=%s' % A, {'appIds': [a1, a2], 'planId': plan}, app_token=u['mgr'][0])
    r = data(b) or {}
    check('S2 批量投保：报名一已有在途被跳过、报名二提交成功', (1, [a1]), (r.get('ok'), [f.get('appId') for f in r.get('failed') or []]))
    status, b = http('POST', '/app/staff/insurance/apply-batch?storeId=%s' % A, {'appIds': [a2], 'planId': plan}, app_token=u['staff'][0])
    check('S2 普通员工不能批量投保（403）', 403, code_of(b))

    status, b = http('PUT', '/kangaroo/staffops/insapply/%s/effect' % apply_id, {}, token=admin)
    check('S2 登记保单号必须填保单号', (True, 'pending'), (denied(b), scalar("select status from kg_insurance_apply where apply_id=%s" % apply_id)))
    status, b = http('PUT', '/kangaroo/staffops/insapply/%s/effect' % apply_id, {'reason': 'POL-%s' % RUN}, token=admin)
    check('S2 后台登记保单号 → effective', ('effective', 'POL-%s' % RUN),
          tuple(sql("select status, policy_no from kg_insurance_apply where apply_id=%s" % apply_id)[0]))
    status, b = http('PUT', '/kangaroo/staffops/insapply/%s/effect' % apply_id, {'reason': 'POL-2'}, token=admin)
    status2, b2 = http('PUT', '/kangaroo/staffops/insapply/%s/reject' % apply_id, {'reason': '晚了'}, token=admin)
    check('S2 已生效的不能再登记 / 驳回，保单号不变', (True, True, 'POL-%s' % RUN),
          (denied(b), denied(b2), scalar("select policy_no from kg_insurance_apply where apply_id=%s" % apply_id)))
    # 登记保单号只代表已出单（保障按保单起止日期），通知标题是「用工保险已出单」（KgInsuranceService）
    check('S2 候选人收到「用工保险已出单」', 1, msgs(u['talent'][1], '用工保险已出单'))
    status, b = http('GET', '/app/staff/insurance/policies?storeId=%s&status=effective' % A, app_token=u['staff'][0])
    rows = (data(b) or {}).get('rows') or []
    check('S2 普通员工在保单列表看到这份已生效保单（保单号一致）', ['POL-%s' % RUN], [r.get('policyNo') for r in rows if int(r.get('applyId')) == apply_id])
    status, b = http('GET', '/app/staff/insurance/policies?storeId=%s' % A, app_token=u['far'][0])
    check('S2 别家主理人查本店保单（403）', 403, code_of(b))
    status, b = http('GET', '/app/staff/insurance/policies', app_token=u['far'][0])
    leaked = [r for r in ((data(b) or {}).get('rows') or []) if int(r.get('applyId')) == apply_id]
    check('S2 别家主理人不带门店查全部：看不到本店保单', [], leaked)
    status, b = http('GET', '/app/staff/insurance/%s/notices' % apply_id, app_token=u['far'][0])
    check('S2 别家主理人查本店保单通知（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/insurance/apply', body, app_token=u['mgr'][0])
    check('S2 已生效后不能重复投保', True, denied(b), ok=denied(b))
    a2_apply = n("select apply_id from kg_insurance_apply where app_id=%s" % a2)
    status, b = http('PUT', '/kangaroo/staffops/insapply/%s/reject' % a2_apply, {'reason': '回归：信息有误'}, token=admin)
    check('S2 后台驳回 → rejected，之后可以重新申请', ('rejected', 200),
          (scalar("select status from kg_insurance_apply where apply_id=%s" % a2_apply),
           code_of(http('POST', '/app/staff/insurance/apply', {'appId': a2, 'planId': plan, 'startDate': day(2)}, app_token=u['keeper'][0])[1])))
    # ---- 主理人在小程序登记保单号（之前只能后台登记）：只认这条申请所在门店的主理人，其他角色只能看 ----
    # 用上面报名二重新提交的那条待处理申请；登记走的是和后台同一段逻辑（校验 / 并发兜底 / 通知）
    fresh = n("select apply_id from kg_insurance_apply where app_id=%s and status='pending' and del_flag='0' order by apply_id desc limit 1" % a2)
    check('S2 小程序登记准备：报名二重新申请后有一条待处理的投保申请', True, fresh > 0, ok=fresh > 0)
    reg = '/app/staff/insurance/applies/%s/policy' % fresh
    pol_no = 'MGR-%s' % RUN
    def state():
        return tuple(sql("select status, coalesce(policy_no,'-') from kg_insurance_apply where apply_id=%s" % fresh)[0])

    status, b = http('PUT', reg, {'policyNo': pol_no}, app_token=u['staff'][0])
    status2, b2 = http('PUT', reg + '?storeId=%s' % A, {'policyNo': pol_no}, app_token=u['keeper'][0])
    check('S2 普通员工 / 管家不能在小程序登记保单号（403），申请仍待处理', (403, 403, ('pending', '-')), (code_of(b), code_of(b2), state()))
    check('S2 员工 / 管家被拒的登记各记一条失败操作日志', 2,
          n("select count(*) from kg_staff_op_log where store_id=%s and action='insurance_effect' and ok='0' and app_user_id in (%s,%s)"
            % (A, u['staff'][1], u['keeper'][1])))
    status, b = http('PUT', reg, {'policyNo': pol_no}, app_token=u['far'][0])
    status2, b2 = http('PUT', reg + '?storeId=%s' % CTX['B'], {'policyNo': pol_no}, app_token=u['far'][0])
    check('S2 别家主理人登记本店保单号：不带门店 / 带自家门店都 403', (403, 403, ('pending', '-')), (code_of(b), code_of(b2), state()))
    status, b = http('PUT', reg, {'policyNo': '   '}, app_token=u['mgr'][0])
    status2, b2 = http('PUT', reg, {'policyNo': 'P' * 65}, app_token=u['mgr'][0])
    status3, b3 = http('PUT', reg, {}, app_token=u['mgr'][0])
    check('S2 主理人登记：空白 / 超 64 字 / 不填保单号都被拒，申请仍待处理', (True, True, True, ('pending', '-')),
          (denied(b), denied(b2), denied(b3), state()))
    store_before = msgs(u['staff'][1], '投保已出单')
    status, b = http('PUT', reg + '?storeId=%s' % A, {'policyNo': '  %s  ' % pol_no}, app_token=u['mgr'][0])
    check('S2 本店主理人在小程序登记保单号 → effective、保单号去空白后保存、处理人记为该员工', (200, 'effective', pol_no, 'staff:%s' % u['mgr'][1]),
          (code_of(b),) + tuple(sql("select status, policy_no, handled_by from kg_insurance_apply where apply_id=%s" % fresh)[0]))
    check('S2 主理人登记后被保险人收到「用工保险已出单」', 1, msgs(u['vol'][1], '用工保险已出单'))
    check('S2 主理人登记后门店员工收到「投保已出单」', 1, msgs(u['staff'][1], '投保已出单') - store_before)
    check('S2 主理人登记记一条成功的操作日志', 1,
          n("select count(*) from kg_staff_op_log where store_id=%s and action='insurance_effect' and ok='1' and app_user_id=%s" % (A, u['mgr'][1])))
    status, b = http('PUT', reg, {'policyNo': 'MGR-2'}, app_token=u['mgr'][0])
    status2, b2 = http('PUT', '/app/staff/insurance/applies/%s/policy' % apply_id, {'policyNo': 'MGR-3'}, app_token=u['mgr'][0])
    check('S2 已出单的申请不能再登记（409），两条保单号都不变', (409, 409, pol_no, 'POL-%s' % RUN),
          (code_of(b), code_of(b2), scalar("select policy_no from kg_insurance_apply where apply_id=%s" % fresh),
           scalar("select policy_no from kg_insurance_apply where apply_id=%s" % apply_id)))


# ================================================================ S3 调度

def s3_dispatch():
    section('S3 调度：名额调整 CAS / 撤销 / 到期恢复 / 未来生效 / 紧急标记 / 导出；角色边界')
    A, u = CTX['A'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    jid = CTX['job']
    hc = lambda: n("select headcount from kg_job where job_id=%s" % jid)
    h0 = hc()
    adj = lambda tok, **kw: http('POST', '/app/staff/dispatch/adjust', dict({'jobId': jid, 'delta': 2, 'validFrom': day(0), 'validTo': day(7), 'reason': '旺季加人'}, **kw), app_token=tok)[1]
    b = adj(u['staff'][0])
    check('S3 普通员工不能调整名额（403），名额不变', (403, h0), (code_of(b), hc()))
    b = adj(u['far'][0])
    check('S3 别家主理人不能调整本店名额（403）', (403, h0), (code_of(b), hc()))
    b = adj(mgr_tok, reason='x')
    check('S3 原因少于 2 字被拒', (True, h0), (denied(b), hc()))
    b = adj(mgr_tok, delta=-(h0 + 5))
    check('S3 调整后低于已录用人数被拒', (True, h0), (denied(b), hc()))
    b = adj(mgr_tok)
    log1 = (data(b) or {}).get('logId')
    check('S3 主理人 +2 立即生效：名额 +2、日志记录 before/after', (200, h0 + 2, True),
          (code_of(b), hc(), bool(scalar("select applied_time from kg_dispatch_log where log_id=%s" % (log1 or 0))) and scalar("select applied_time from kg_dispatch_log where log_id=%s" % (log1 or 0)) != 'NULL'))
    h1 = hc()
    res = parallel(lambda _: adj(u['keeper'][0], delta=1, reason='并发加人'), range(4), 4)
    succ = sum(1 for b in res if ok(b))
    check('S3 管家并发 4 次 +1：名额增量 = 成功次数 = 日志条数', (succ, succ),
          (hc() - h1, n("select count(*) from kg_dispatch_log where job_id=%s and op_type='adjust' and reason='并发加人'" % jid)))
    status, b = http('POST', '/app/staff/dispatch/logs/%s/undo' % log1, {}, app_token=u['keeper'][0])
    check('S3 管家不能撤销主理人的调整', True, denied(b), ok=denied(b))
    h2 = hc()
    status, b = http('POST', '/app/staff/dispatch/logs/%s/undo' % log1, {}, app_token=mgr_tok)
    check('S3 本人撤销 → 名额 -2、日志标记已恢复、记一条 undo', (200, h2 - 2, 1),
          (code_of(b), hc(), n("select count(*) from kg_dispatch_log where op_type='undo' and reason='撤销 #%s 的调整'" % log1)))
    status, b = http('POST', '/app/staff/dispatch/logs/%s/undo' % log1, {}, app_token=mgr_tok)
    check('S3 重复撤销被拒、名额不变', (True, h2 - 2), (denied(b), hc()))

    # 到期自动恢复：今天生效、今天到期；时间推移到"昨天到期"后打开日志页触发同步
    b = adj(mgr_tok, delta=3, validTo=day(0), reason='周末加人')
    log2 = (data(b) or {}).get('logId')
    h3 = hc()
    sql_exec("update kg_dispatch_log set valid_to=date_sub(curdate(), interval 1 day) where log_id=%s" % log2)
    http('GET', '/app/staff/dispatch/logs?storeId=%s' % A, app_token=mgr_tok)
    check('S3 有效期结束 → 同步后名额自动恢复 -3、记一条系统同步', (h3 - 3, True, 1),
          (hc(), scalar("select reverted_time from kg_dispatch_log where log_id=%s" % log2) not in (None, 'NULL'),
           n("select count(*) from kg_dispatch_log where op_type='sync' and reason='对应 #%s'" % log2)))
    # 未来生效：明天开始 → 先不改名额；时间推移到今天后同步才生效
    h4 = hc()
    b = adj(mgr_tok, delta=1, validFrom=day(1), validTo=day(5), reason='下周加人')
    log3 = (data(b) or {}).get('logId')
    check('S3 未来生效的调整：保存但名额不变', (200, False, h4), (code_of(b), (data(b) or {}).get('applied'), hc()))
    sql_exec("update kg_dispatch_log set valid_from=curdate() where log_id=%s" % log3)
    http('GET', '/app/staff/dispatch/logs?storeId=%s' % A, app_token=mgr_tok)
    check('S3 到了生效日同步后名额 +1', h4 + 1, hc())

    urg = lambda tok, **kw: http('POST', '/app/staff/dispatch/urgent', dict({'jobId': jid, 'level': 3, 'deadline': day(2) + ' 18:00', 'targets': ['staff'], 'push': False}, **kw), app_token=tok)[1]
    b = urg(u['staff'][0])
    check('S3 普通员工不能标记紧急（403）', 403, code_of(b))
    b = urg(mgr_tok, deadline=day(-1) + ' 10:00')
    check('S3 截止时间已过被拒', True, denied(b), ok=denied(b))
    before = msgs(u['staff'][1], '紧急招募%')
    b = urg(mgr_tok)
    check('S3 标记紧急 → urgent_level=3、本店其他员工收到通知', (200, '3', True),
          (code_of(b), scalar("select urgent_level from kg_job where job_id=%s" % jid), msgs(u['staff'][1], '紧急招募%') > before))
    b = urg(u['far'][0], jobId=jid)
    check('S3 别家主理人不能给本店岗位标紧急（403）', 403, code_of(b))

    status, b = http('GET', '/app/staff/dispatch/logs/export?storeId=%s' % A, app_token=mgr_tok)
    csv = (data(b) or {}).get('csv') or ''
    check('S3 主理人导出：CSV 行数 = 日志条数 + 表头', (200, (data(b) or {}).get('count', -1) + 1), (code_of(b), len(csv.strip().split('\n'))))
    check('S3 导出防公式注入：以 = 开头的操作人昵称被加上单引号', True, '"\'=回归主理人"' in csv and '"=回归主理人"' not in csv,
          ok='"\'=回归主理人"' in csv and '"=回归主理人"' not in csv)
    for tag in ('keeper', 'staff'):
        status, b = http('GET', '/app/staff/dispatch/logs/export?storeId=%s' % A, app_token=u[tag][0])
        check('S3 %s 没有导出权限（403）' % tag, 403, code_of(b))
    status, b = http('GET', '/app/staff/dispatch/logs?storeId=%s' % A, app_token=u['far'][0])
    check('S3 别家主理人查本店调度日志（403）', 403, code_of(b))
    status, b = http('GET', '/app/staff/dispatch/adjust?storeId=%s&jobId=%s' % (A, jid), app_token=u['staff'][0])
    check('S3 普通员工能看调整页但 canEdit=false', (200, False), (code_of(b), (data(b) or {}).get('canEdit')))
    status, b = http('GET', '/app/staff/dispatch/board', app_token=mgr_tok)
    check('S3 调度看板可打开', 200, code_of(b))


# ================================================================ S4 员工发岗与分享

def s4_jobs():
    section('S4 员工发岗：草稿 → 后台审核上架；分享码 / 访问 / 报名归因；批量暂停恢复')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    body = {'jobType': 'volunteer', 'title': '员工发岗-' + RUN, 'intro': '回归', 'work': '前台接待', 'hope': '热情', 'startDate': day(5),
            'pay': 'board', 'headcount': 2, 'keywords': []}
    status, b = http('POST', '/app/staff/jobs?storeId=%s' % A, body, app_token=u['staff'][0])
    check('S4 普通员工不能发岗（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/jobs?storeId=%s' % A, body, app_token=u['far'][0])
    check('S4 别家主理人不能往本店发岗（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/jobs?storeId=%s' % A, dict(body, title=''), app_token=mgr_tok)
    check('S4 标题为空被拒', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/jobs?storeId=%s' % A, body, app_token=mgr_tok)
    jid = (data(b) or {}).get('jobId')
    check('S4 主理人发岗 → 草稿（待平台审核）、来源 staff', ('draft', 'staff'), tuple(sql("select status, source from kg_job where job_id=%s" % (jid or 0))[0]))
    status, b = http('GET', '/app/job/%s' % jid, app_token=u['guest'][0])
    check('S4 审核前用户看不到这个岗位', True, denied(b), ok=denied(b))
    status, b = http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    check('S4 后台审核上架 → published，用户能看到', ('published', 200),
          (scalar("select status from kg_job where job_id=%s" % jid), code_of(http('GET', '/app/job/%s' % jid, app_token=u['guest'][0])[1])))

    s1 = data(http('POST', '/app/staff/jobs/%s/share' % jid, {'channel': 'link'}, app_token=mgr_tok)[1]) or {}
    s2 = data(http('POST', '/app/staff/jobs/%s/share' % jid, {'channel': 'link'}, app_token=mgr_tok)[1]) or {}
    code = s1.get('shareCode')
    check('S4 同一人同渠道分享码固定、分享次数累计 2', (True, '2'), (code == s2.get('shareCode') and bool(code), scalar("select shares from kg_job_share where share_code='%s'" % code)))
    status, b = http('POST', '/app/staff/jobs/%s/share' % jid, {'channel': 'link'}, app_token=u['far'][0])
    check('S4 别家主理人不能生成本店岗位分享码（403）', 403, code_of(b))
    v1 = data(http('POST', '/app/share/visit', {'code': code}, app_token=u['guest'][0])[1])
    v2 = data(http('POST', '/app/share/visit', {'code': code}, app_token=u['guest'][0])[1])
    v3 = data(http('POST', '/app/share/visit', {'code': code}, app_token=u['outsider'][0])[1])
    check('S4 访问去重：同一人当天只算 1 次，两人共 2 次', (True, False, True, '2'), (v1, v2, v3, scalar("select visits from kg_job_share where share_code='%s'" % code)))
    aid = apply(jid, u['outsider'][0], '路人甲甲', 's4', arrival=6)
    status, b = http('POST', '/app/share/bind', {'appId': aid, 'code': code}, app_token=u['guest'][0])
    check('S4 别人不能把我的报名绑到分享码上', (False, 'NULL'), (data(b), scalar("select coalesce(share_code,'NULL') from kg_application where app_id=%s" % aid)))
    status, b = http('POST', '/app/share/bind', {'appId': aid, 'code': code}, app_token=u['outsider'][0])
    check('S4 本人报名归因到分享码', (True, code), (data(b), scalar("select share_code from kg_application where app_id=%s" % aid)))
    st = data(http('GET', '/app/staff/jobs/share-stats?storeId=%s&jobId=%s&dim=staff' % (A, jid), app_token=mgr_tok)[1]) or {}
    tot = st.get('total') or {}
    rk = [(r.get('id'), r.get('share'), r.get('apply')) for r in st.get('allRanks') or []]
    check('S4 分享统计：合计访问 2、报名 1；分享榜主理人分享 2 次、带来报名 1', (2, 1, [(u['mgr'][1], 2, 1)]),
          (tot.get('visit'), tot.get('apply'), [(int(a or 0), b_, c) for a, b_, c in rk]))
    st = data(http('GET', '/app/staff/jobs/share-stats?storeId=%s&jobId=%s' % (A, jid), app_token=u['far'][0])[1])
    check('S4 别家主理人查本店岗位分享统计被拒', None, st)
    CTX['staffJob'] = jid

    status, b = http('POST', '/app/staff/jobs/batch?storeId=%s' % A, {'jobIds': [jid], 'action': 'pause'}, app_token=u['staff'][0])
    check('S4 普通员工不能批量暂停（403）', (403, 'published'), (code_of(b), scalar("select status from kg_job where job_id=%s" % jid)))
    status, b = http('POST', '/app/staff/jobs/batch?storeId=%s' % A, {'jobIds': [jid, CTX['jobB']], 'action': 'pause'}, app_token=mgr_tok)
    check('S4 主理人批量暂停：本店岗位 paused、混进来的别家岗位不受影响', (1, 'paused', 'published'),
          (data(b), scalar("select status from kg_job where job_id=%s" % jid), scalar("select status from kg_job where job_id=%s" % CTX['jobB'])))
    status, b = http('POST', '/app/staff/jobs/batch?storeId=%s' % A, {'jobIds': [jid], 'action': 'resume'}, app_token=mgr_tok)
    check('S4 恢复 → published', 'published', scalar("select status from kg_job where job_id=%s" % jid))
    return tot


# ================================================================ S5 评价运营

def s5_reviews():
    section('S5 评价运营：回复 / 精选不发积分 / 内部备注 / 提请平台；角色与跨店')
    A, u = CTX['A'], CTX['u']
    vol_tok, vol_id = u['vol']
    status, b = http('POST', '/app/reviews', {'bizType': 'application', 'bizId': CTX['a2'], 'dims': [5, 4, 5, 3, 4], 'content': '回归：氛围很好',
                                              'anonymous': 'false'}, app_token=vol_tok)
    check('S5 已入职义工写评价', 200, code_of(b))
    rid = n("select review_id from kg_review where biz_type='application' and biz_id=%s" % CTX['a2'])
    status, b = http('GET', '/app/staff/reviews/%s' % rid, app_token=u['far'][0])
    check('S5 别家主理人看不到本店评价', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/reviews/%s/reply' % rid, {'content': '欢迎加微信 abc12345678 联系'}, app_token=u['mgr'][0])
    check('S5 回复里留联系方式被拒（422）', (422, 'NULL'), (code_of(b), scalar("select coalesce(reply,'NULL') from kg_review where review_id=%s" % rid)))
    status, b = http('POST', '/app/staff/reviews/%s/reply' % rid, {'content': '谢谢你的认可，期待再相聚！'}, app_token=u['mgr'][0])
    check('S5 回复落库、作者收到通知', ('谢谢你的认可，期待再相聚！', 1),
          (scalar("select reply from kg_review where review_id=%s" % rid), msgs(vol_id, '门店回复了你的评价')))
    status, b = http('POST', '/app/staff/reviews/%s/reply' % rid, {'content': '越权回复'}, app_token=u['far'][0])
    check('S5 别家主理人不能回复', (True, '谢谢你的认可，期待再相聚！'), (denied(b), scalar("select reply from kg_review where review_id=%s" % rid)))
    fb = {'featured': True, 'reasons': ['内容真实有价值'], 'note': '回归'}
    status, b = http('POST', '/app/staff/reviews/%s/feature' % rid, fb, app_token=u['staff'][0])
    check('S5 普通员工不能设精选（403）', (403, '0'), (code_of(b), scalar("select coalesce(featured,'0') from kg_review where review_id=%s" % rid)))
    status, b = http('POST', '/app/staff/reviews/%s/feature' % rid, dict(fb, reasons=['乱写的理由']), app_token=u['mgr'][0])
    check('S5 精选理由不在清单里被拒（422）', 422, code_of(b))
    pts = lambda: n("select coalesce(sum(delta),0) from kg_point_entry where app_user_id=%s and biz_type='review_feature'" % vol_id)
    http('POST', '/app/staff/reviews/%s/feature' % rid, fb, app_token=u['keeper'][0])
    http('POST', '/app/staff/reviews/%s/feature' % rid, dict(fb, featured=False), app_token=u['mgr'][0])
    http('POST', '/app/staff/reviews/%s/feature' % rid, fb, app_token=u['mgr'][0])
    check('S5 精选 → 取消 → 再精选：featured=1、作者没有拿到积分（精选不发积分：以奖励换好评，设计 B10 / P11）', ('1', 0), (scalar("select featured from kg_review where review_id=%s" % rid), pts()))
    status, b = http('POST', '/app/staff/reviews/%s/feature' % rid, dict(fb, reasons=['表达积极正面']), app_token=u['mgr'][0])
    check('S5 「表达积极正面」不再是精选理由（422）', 422, code_of(b))
    status, b = http('PUT', '/app/staff/reviews/%s/note' % rid, {'note': '内部：已电话回访'}, app_token=u['keeper'][0])
    check('S5 管家写内部备注落库', '内部：已电话回访', scalar("select internal_note from kg_review where review_id=%s" % rid))
    d = data(http('GET', '/app/reviews/mine', app_token=vol_tok)[1])
    check('S5 作者自己看不到内部备注', True, '内部：已电话回访' not in json.dumps(d, ensure_ascii=False), ok='内部：已电话回访' not in json.dumps(d, ensure_ascii=False))
    status, b = http('POST', '/app/staff/reviews/%s/report' % rid, {'reason': '疑似刷评'}, app_token=u['staff'][0])
    status2, b2 = http('POST', '/app/staff/reviews/%s/report' % rid, {'reason': '疑似刷评'}, app_token=u['staff'][0])
    check('S5 员工提请平台处理：落一条 kg_report；重复提请 409', (200, 409, 1),
          (code_of(b), code_of(b2), n("select count(*) from kg_report where target_type='review' and target_id=%s" % rid)))
    status, b = http('GET', '/app/staff/reviews?storeId=%s' % A, app_token=u['far'][0])
    check('S5 别家主理人按本店 storeId 查评价列表（403）', 403, code_of(b))
    d = data(http('GET', '/app/staff/reviews/%s/draft?tone=%s' % (rid, urllib.parse.quote('真诚')), app_token=u['mgr'][0])[1])
    check('S5 回复草稿（模板）可生成', True, bool(d), ok=bool(d))
    status, b = http('GET', '/app/manage/dashboard/reviews?storeId=%s&period=7' % A, app_token=u['mgr'][0])
    k = dict((x.get('label'), x.get('value')) for x in ((data(b) or {}).get('kpis') or []))
    check('M5 看板评价统计：共 1 条、回复率 100%、精选 1', ('共 1 条评价', '100%', '1'), ((data(b) or {}).get('count'), k.get('回复率'), k.get('精选数量')))


# ================================================================ S6 人才库

def s6_talents():
    section('S6 人才库：脱敏 / 查看留痕 / 收藏 / 邀请去重；角色与跨店')
    A, u = CTX['A'], CTX['u']
    tid = u['talent'][1]
    status, b = http('POST', '/app/staff/talents/search?storeId=%s' % A, {}, app_token=u['mgr'][0])
    rows = (data(b) or {}).get('rows') or []
    me = [r for r in rows if int(r.get('userId')) == tid]
    raw = json.dumps(data(b), ensure_ascii=False)
    check('S6 人才池含曾向本店报名的候选人，姓名脱敏为「候*丙」', ['候*丙'], [r.get('name') for r in me])
    check('S6 列表里没有完整姓名与手机号', True, '候选人丙丙' not in raw and '13500135000' not in raw, ok='候选人丙丙' not in raw and '13500135000' not in raw)
    status, b = http('GET', '/app/staff/talents/%s?storeId=%s' % (tid, A), app_token=u['staff'][0])
    check('S6 普通员工不能看人才档案（403）', 403, code_of(b))
    before = n("select count(*) from kg_pii_access where viewer_id=%s and target_id=%s and biz_type='talent_profile'" % (u['keeper'][1], tid))
    status, b = http('GET', '/app/staff/talents/%s?storeId=%s' % (tid, A), app_token=u['keeper'][0])
    raw = json.dumps(data(b), ensure_ascii=False)
    check('S6 管家查看档案：脱敏、无手机号、kg_pii_access +1', (200, True, before + 1),
          (code_of(b), '候选人丙丙' not in raw and '13500135000' not in raw,
           n("select count(*) from kg_pii_access where viewer_id=%s and target_id=%s and biz_type='talent_profile'" % (u['keeper'][1], tid))))
    status, b = http('GET', '/app/staff/talents/%s?storeId=%s' % (tid, A), app_token=u['far'][0])
    check('S6 别家主理人按本店 storeId 看档案（403）', 403, code_of(b))
    status, b = http('GET', '/app/staff/talents/%s' % tid, app_token=u['far'][0])
    check('S6 别家主理人在自己门店的人才池里查不到本店候选人（403）', 403, code_of(b))
    check('S6 越权查看没有留痕', 0, n("select count(*) from kg_pii_access where viewer_id=%s and target_id=%s" % (u['far'][1], tid)))
    status, b = http('POST', '/app/staff/talents/%s/mark?storeId=%s' % (tid, A), {'what': 'favorite', 'on': True}, app_token=u['mgr'][0])
    check('S6 收藏落库', '1', scalar("select favorite from kg_talent_mark where store_id=%s and app_user_id=%s" % (A, tid)))
    status, b = http('POST', '/app/staff/talents/%s/invite?storeId=%s' % (tid, A), {'jobId': CTX['jobB']}, app_token=u['mgr'][0])
    check('S6 不能用别家岗位邀请', True, denied(b), ok=denied(b))
    jid = CTX.get('staffJob') or CTX['job']
    status, b = http('POST', '/app/staff/talents/%s/invite?storeId=%s' % (tid, A), {'jobId': jid}, app_token=u['mgr'][0])
    status2, b2 = http('POST', '/app/staff/talents/%s/invite?storeId=%s' % (tid, A), {'jobId': jid}, app_token=u['mgr'][0])
    check('S6 邀请：候选人收到消息；同岗位重复邀请 409、只落一条', (200, 409, 1, 1),
          (code_of(b), code_of(b2), n("select count(*) from kg_talent_invite where store_id=%s and app_user_id=%s" % (A, tid)),
           n("select count(*) from kg_message where recipient_id=%s and title like '%%邀请你报名'" % tid)))
    status, b = http('POST', '/app/staff/talents/%s/invite?storeId=%s' % (tid, A), {'jobId': jid}, app_token=u['staff'][0])
    check('S6 普通员工不能发邀请（403）', 403, code_of(b))


# ================================================================ S7 线上任务

def upload_named(path, app_token, filename, content, name, ctype='application/zip'):
    """multipart 上传：file + name（小程序上传时文件名是临时名，原文件名放在 name 字段里）"""
    boundary = '----kg' + uuid.uuid4().hex
    parts = [('--%s\r\nContent-Disposition: form-data; name="name"\r\n\r\n' % boundary).encode('utf-8'), name.encode('utf-8'), b'\r\n',
             ('--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\nContent-Type: %s\r\n\r\n'
              % (boundary, filename, ctype)).encode('utf-8'), content, ('\r\n--%s--\r\n' % boundary).encode('utf-8')]
    req = urllib.request.Request(BASE + path, data=b''.join(parts), method='POST',
                                 headers={'Content-Type': 'multipart/form-data; boundary=' + boundary, 'X-App-Token': app_token})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'raw': e.read().decode('utf-8', 'replace')}


def body_code(raw):
    """下载接口出错时回的是 {code, msg} JSON（HTTP 200）；是文件就返回 None"""
    try:
        return json.loads(raw.decode('utf-8')).get('code')
    except Exception:  # noqa: BLE001
        return None


def s7_material_files(app1, w_tok, w2_tok, u):
    """V41 素材附件：上传 zip（私有存储）→ 随素材发出 → 接单人下载字节一致 → 签名链接（3 天）能下、改了签名不能下 → 换个接单人 404"""
    keeper = u['keeper'][0]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('readme.txt', '回归素材 ' + RUN)
    archive = buf.getvalue()
    base = '/app/staff/online/assignments/%s/material-files' % app1
    b = upload_named(base, keeper, 'tmp_wx.zip', b'MZ' + b'\x00' * 64, '安装包.zip')
    check('S7 附件文件头和扩展名对不上被拒（422）', 422, code_of(b))
    b = upload_named(base, keeper, 'tmp_wx.exe', b'MZ' + b'\x00' * 64, '安装包.exe', 'application/octet-stream')
    check('S7 不在白名单的格式被拒（422）', 422, code_of(b))
    b = upload_named(base, u['staff'][0], 'tmp_wx.zip', archive, '原图.zip')
    check('S7 普通员工不能上传素材附件（403）', 403, code_of(b))
    b = upload_named(base, keeper, 'tmp_wx.zip', archive, '../拍摄原图 第1批.zip')
    fid = (data(b) or {}).get('fileId')
    check('S7 管家上传 zip → 未发出的附件（去掉路径的原名、私有路径、material_id 为空）', ('拍摄原图 第1批.zip', 'zip', str(len(archive)), True, 'NULL'),
          (scalar("select file_name from kg_online_material_file where file_id=%s" % (fid or 0)),
           scalar("select file_ext from kg_online_material_file where file_id=%s" % (fid or 0)),
           scalar("select file_size from kg_online_material_file where file_id=%s" % (fid or 0)),
           (scalar("select file_key from kg_online_material_file where file_id=%s" % (fid or 0)) or '').startswith('omat/%s/' % app1),
           scalar("select ifnull(material_id, 'NULL') from kg_online_material_file where file_id=%s" % (fid or 0))))
    status, ctype, raw = get_binary('/app/online/assignments/%s/material-files/%s' % (app1, fid), w_tok)
    check('S7 还没发出的附件接单人下载不到（404）', 404, body_code(raw))
    status, b = http('POST', '/app/staff/online/assignments/%s/materials' % app1, {'content': '原图在压缩包里', 'files': [fid]}, app_token=keeper)
    files = [f for m in ((data(b) or {}).get('materials') or []) for f in (m.get('files') or [])]
    check('S7 发素材带上附件 → 附件挂到这次素材、返回的素材里有文件', (200, True, ['拍摄原图 第1批.zip']),
          (code_of(b), scalar("select material_id from kg_online_material_file where file_id=%s" % fid) not in (None, 'NULL'), [f.get('name') for f in files]))
    status, b = http('POST', '/app/staff/online/assignments/%s/materials' % app1, {'content': '再发一次', 'files': [fid]}, app_token=keeper)
    check('S7 同一个附件不能再发一次（409）', 409, code_of(b))
    status, ctype, raw = get_binary('/app/online/assignments/%s/material-files/%s' % (app1, fid), w_tok)
    check('S7 接单人下载附件：字节一致、按 zip 返回', (200, 'application/zip', True), (status, ctype.split(';')[0], raw == archive))
    status, ctype, raw = get_binary('/app/online/assignments/%s/material-files/%s' % (app1, fid), w2_tok)
    check('S7 换一个接单人下载被拒（404）', 404, body_code(raw))
    status, ctype, raw = get_binary('%s/%s' % (base, fid), keeper)
    check('S7 门店下载附件字节一致', (200, True), (status, raw == archive))
    status, ctype, raw = get_binary('%s/%s' % (base, fid), u['far'][0])
    check('S7 别家门店下载附件被拒', True, body_code(raw) in (403, 404))
    link = data(http('GET', '/app/online/assignments/%s/material-files/%s/link' % (app1, fid), app_token=w_tok)[1]) or {}
    url = link.get('url') or ''
    check('S7 下载链接 3 天有效、指向匿名签名地址', (72, True), (link.get('hours'), url.startswith('/kangaroo/file/material?f=%s&e=' % fid)))
    status, ctype, raw = get_binary(url)
    check('S7 不登录用签名链接能下载，字节一致', (200, True), (status, raw == archive))
    bad = url[:-1] + ('1' if url[-1:] == '0' else '0')
    status, ctype, raw = get_binary(bad)
    check('S7 改过的签名链接被拒（403）', 403, status)
    status, b = http('GET', '/app/online/assignments/%s/material-files/%s/link' % (app1, fid), app_token=w2_tok)
    check('S7 别的接单人拿不到下载链接（404）', 404, code_of(b))
    # 没发出就移除：记成已清理，之后下载不到
    b = upload_named(base, keeper, 'tmp_wx.pdf', b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n', '要求.pdf', 'application/pdf')
    pid = (data(b) or {}).get('fileId')
    status, b = http('DELETE', '%s/%s' % (base, pid), app_token=keeper)
    check('S7 门店移除没发出的附件 → del_flag=2', (200, '2'), (code_of(b), scalar("select del_flag from kg_online_material_file where file_id=%s" % (pid or 0))))
    status, b = http('DELETE', '%s/%s' % (base, fid), app_token=keeper)
    check('S7 已经发出的附件不能移除（409）', 409, code_of(b))


def post_raw(path, app_token, content):
    """分段上传的一段：请求体就是原始字节（application/octet-stream）"""
    req = urllib.request.Request(BASE + path, data=content, method='POST', headers={'Content-Type': 'application/octet-stream', 'X-App-Token': app_token})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.loads(r.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return {'code': e.code, 'raw': e.read().decode('utf-8', 'replace')}


def s7_big_material_file(app1, w_tok, u):
    """2026-10-16 主人反馈：6 张原图打的包 30 多 MB，附件上限 10MB 传不上。改成单个 100MB，超过 8MB 的分段上传（每段 8MB）"""
    keeper, other = u['keeper'][0], u['mgr'][0]
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_STORED) as z:
        for i in range(6):
            z.writestr('原图%d.jpg' % (i + 1), os.urandom(6 * 1024 * 1024))
    archive = buf.getvalue()
    size, part = len(archive), 8 * 1024 * 1024
    base = '/app/staff/online/assignments/%s/material-files' % app1
    status, b = http('POST', base + '/parts', {'name': '六张原图.zip', 'size': 100 * 1024 * 1024 + 1}, app_token=keeper)
    check('S7 大文件：超过 100MB 开始时就被拒（422）', 422, code_of(b))
    status, b = http('POST', base + '/parts', {'name': '六张原图.exe', 'size': size}, app_token=keeper)
    check('S7 大文件：格式不在白名单开始时就被拒（422）', 422, code_of(b))
    status, b = http('POST', base + '/parts', {'name': '六张原图.zip', 'size': size}, app_token=u['staff'][0])
    check('S7 大文件：普通员工不能上传（403）', 403, code_of(b))
    status, b = http('POST', base + '/parts', {'name': '六张原图.zip', 'size': size}, app_token=keeper)
    d = data(b) or {}
    up = d.get('uploadId') or 'x'
    check('S7 大文件：%.1fMB 的压缩包开始分段上传（每段 8MB，共 %d 段）' % (size / 1048576.0, -(-size // part)), (200, part, -(-size // part)),
          (code_of(b), d.get('partBytes'), d.get('parts')))
    check('S7 大文件：跳过第 0 段先传第 1 段被拒（409）', 409, code_of(post_raw('%s/parts/%s/1' % (base, up), keeper, archive[part:2 * part])))
    check('S7 大文件：别人拿这个上传号传不了（404）', 404, code_of(post_raw('%s/parts/%s/0' % (base, up), other, archive[:part])))
    check('S7 大文件：没传完不能结束（409）', 409, code_of(http('POST', '%s/parts/%s/finish' % (base, up), {}, app_token=keeper)[1]))
    got = []
    for i in range(-(-size // part)):
        got.append(code_of(post_raw('%s/parts/%s/%d' % (base, up, i), keeper, archive[i * part:(i + 1) * part])))
        if i == 0:
            again = post_raw('%s/parts/%s/0' % (base, up), keeper, archive[:part])
            check('S7 大文件：网络重试又传了一次第 0 段，算成功、不重复写', (200, part), (code_of(again), (data(again) or {}).get('received')))
    check('S7 大文件：各段都收下', [200] * len(got), got)
    status, b = http('POST', '%s/parts/%s/finish' % (base, up), {}, app_token=keeper)
    fid = (data(b) or {}).get('fileId')
    check('S7 大文件：传完核对 → 成为未发出的附件（名字、大小对上）', (200, '六张原图.zip', str(size), 'NULL'),
          (code_of(b), scalar("select file_name from kg_online_material_file where file_id=%s" % (fid or 0)),
           scalar("select file_size from kg_online_material_file where file_id=%s" % (fid or 0)),
           scalar("select ifnull(material_id, 'NULL') from kg_online_material_file where file_id=%s" % (fid or 0))))
    check('S7 大文件：同一个上传号不能再结束一次（404）', 404, code_of(http('POST', '%s/parts/%s/finish' % (base, up), {}, app_token=keeper)[1]))
    status, b = http('POST', '/app/staff/online/assignments/%s/materials' % app1, {'content': '六张原图在压缩包里', 'files': [fid]}, app_token=keeper)
    check('S7 大文件：随素材发出', 200, code_of(b))
    status, ctype, raw = get_binary('/app/online/assignments/%s/material-files/%s' % (app1, fid), w_tok)
    check('S7 大文件：接单人下载回来字节和原文件一模一样', (200, True), (status, raw == archive))
    # 放弃：删掉已收的部分，之后再传这个上传号不认
    status, b = http('POST', base + '/parts', {'name': '放弃.zip', 'size': size}, app_token=keeper)
    gone = (data(b) or {}).get('uploadId') or 'x'
    post_raw('%s/parts/%s/0' % (base, gone), keeper, archive[:part])
    status, b = http('DELETE', '%s/parts/%s' % (base, gone), app_token=keeper)
    check('S7 大文件：放弃上传后这个上传号作废（404）', (200, 404), (code_of(b), code_of(post_raw('%s/parts/%s/1' % (base, gone), keeper, archive[part:2 * part]))))


def s7_online():
    section('S7 线上任务：发布 → 上架 → 报名录用 → 提交 → 退回 → 再提交 → 验收结算 → 线下付款 → 确认 / 申诉')
    admin, A, u = CTX['admin'], CTX['A'], CTX['u']
    mgr_tok = u['mgr'][0]
    body = {'title': '回归文案-' + RUN, 'taskType': '内容写作', 'deliverables': '800 字文案', 'criteria': '原创', 'description': '回归线上任务',
            'commissionFen': 12345, 'quota': 2, 'deadline': time.strftime('%Y-%m-%d %H:%M', time.localtime(time.time() + 3 * 86400))}
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body, app_token=u['staff'][0])
    check('S7 普通员工不能发布线上任务（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, dict(body, commissionFen=50), app_token=mgr_tok)
    check('S7 佣金低于 1 元被拒（422）', 422, code_of(b))
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, dict(body, deadline=time.strftime('%Y-%m-%d %H:%M')), app_token=mgr_tok)
    check('S7 截止时间不足 2 小时被拒（422）', 422, code_of(b))
    status, b = http('POST', '/app/staff/online/tasks?storeId=%s' % A, body, app_token=mgr_tok)
    jid = (data(b) or {}).get('jobId')
    check('S7 主理人发布 → 草稿 + 任务扩展（佣金 12345 分）', ('draft', 'online', '12345'),
          (scalar("select status from kg_job where job_id=%s" % (jid or 0)), scalar("select job_type from kg_job where job_id=%s" % (jid or 0)),
           scalar("select commission_fen from kg_online_task where job_id=%s" % (jid or 0))))
    http('PUT', '/kangaroo/job/changeStatus', {'jobId': jid, 'status': 'published'}, token=admin)
    status, b = http('GET', '/app/staff/online/tasks/%s?storeId=%s' % (jid, CTX['B']), app_token=u['far'][0])
    check('S7 别家主理人看不到本店线上任务', True, denied(b), ok=denied(b))

    w_tok, w_id = u['worker']
    w2_tok, w2_id = u['worker2']
    app1 = apply(jid, w_tok, '接单人甲', 'o1')
    app2 = apply(jid, w2_tok, '接单人乙', 'o2')
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1, {'link': 'https://example.com/a'}, app_token=w_tok)
    check('S7 录用前不能提交成果（409）', 409, code_of(b))
    status, b = http('POST', '/app/staff/applications/%s/audit' % app1, {'pass': True}, app_token=u['far'][0])
    check('S7 别家主理人不能审核本店报名', (True, 'submitted'), (denied(b), scalar("select status from kg_application where app_id=%s" % app1)))
    for a in (app1, app2):
        status, b = http('POST', '/app/staff/applications/%s/audit' % a, {'pass': True}, app_token=u['staff'][0])
        check('S7 普通员工只查看，审核被拒且报名状态不变 %s' % a, (403, 'submitted'),
              (code_of(b), scalar("select status from kg_application where app_id=%s" % a)))
        status, b = http('POST', '/app/staff/applications/%s/audit' % a, {'pass': True}, app_token=mgr_tok)
        check('S7 主理人审核通过 %s' % a, 200, code_of(b))
    check('S7 主理人审核通过 → 录用（review_passed）', ['review_passed', 'review_passed'],
          [scalar("select status from kg_application where app_id=%s" % a) for a in (app1, app2)])
    status, b = http('GET', '/app/online/assignments/%s' % app1, app_token=w2_tok)
    check('S7 别人看不到我的接单（404）', 404, code_of(b))
    # 2026-10-16 主人拍板：成果链接不再校验开头（分享口令整段粘贴也收），只限 500 字；超长仍被拒且不落成果
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1, {'link': '口令' * 251}, app_token=w_tok)
    check('S7 成果链接超过 500 字被拒（422），不落成果', (422, 0), (code_of(b), n("select count(*) from kg_online_submission where app_id=%s" % app1)))
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1, {'link': 'https://example.com/a'}, app_token=w2_tok)
    check('S7 别人不能替我提交', True, denied(b), ok=denied(b))
    res = parallel(lambda _: http('POST', '/app/online/assignments/%s/submit' % app1, {'link': 'https://example.com/r1', 'note': '第一版'}, app_token=w_tok)[1], range(3), 3)
    check('S7 并发提交 3 次：只落 1 轮成果', 1, n("select count(*) from kg_online_submission where app_id=%s" % app1))
    status, b = http('POST', '/app/staff/online/assignments/%s/return' % app1, {'reason': ''}, app_token=u['keeper'][0])
    check('S7 退回必须填原因（422）', 422, code_of(b))
    status, b = http('POST', '/app/staff/online/assignments/%s/return' % app1, {'reason': '字数不够'}, app_token=u['staff'][0])
    check('S7 普通员工不能退回（403）', 403, code_of(b))
    # V41：退回可以附最多 9 张截图（只认本平台上传的图片）、说明放宽到 5000 字
    status, b = http('POST', '/app/staff/online/assignments/%s/return' % app1, {'reason': '看图', 'images': ['https://evil.example.com/a.png']},
                     app_token=u['keeper'][0])
    check('S7 退回截图用外链图片被拒（422），这一轮仍待验收', (422, 'submitted'),
          (code_of(b), scalar("select status from kg_online_submission where app_id=%s and round=1" % app1)))
    status, b = http('POST', '/app/staff/online/assignments/%s/return' % app1, {'reason': '改' * 5001}, app_token=u['keeper'][0])
    check('S7 退回说明超过 5000 字被拒（422）', 422, code_of(b))
    shots = ['/profile/upload/2026/10/15/%032d.png' % i for i in range(3)]
    long_reason = ('字数不够，第 2 段要写清楚店里的招牌和价格。\n封面标题换成店名；具体位置见截图。' * 10)[:300]
    status, b = http('POST', '/app/staff/online/assignments/%s/return' % app1, {'reason': long_reason, 'images': shots}, app_token=u['keeper'][0])
    check('S7 管家退回 → 第 1 轮 returned、接单人收到通知', ('returned', 1),
          (scalar("select status from kg_online_submission where app_id=%s and round=1" % app1), msgs(w_id, '成果被退回修改')))
    check('S7 退回附 3 张截图 + 300 字说明落库（review_images / review_comment）', (','.join(shots), '300'),
          (scalar("select review_images from kg_online_submission where app_id=%s and round=1" % app1),
           scalar("select char_length(review_comment) from kg_online_submission where app_id=%s and round=1" % app1)))
    mine = data(http('GET', '/app/online/assignments/%s' % app1, app_token=w_tok)[1]) or {}
    first = (mine.get('submissions') or [{}])[0]
    check('S7 接单人交付页看到退回截图和完整原因', (shots, long_reason), (first.get('reviewImages'), first.get('comment')))
    check('S7 退回通知只放预览和截图数（不整段拼原因）', 1,
          n("select count(*) from kg_message where recipient_id=%s and title='成果被退回修改' and content like '%%（附 3 张截图）%%' and char_length(content) < 160" % w_id))
    s7_material_files(app1, w_tok, w2_tok, u)
    s7_big_material_file(app1, w_tok, u)
    # 2026-10-17 主人反馈：发了几篇帖子就分几栏交，一轮最多 3 个链接或口令（每个 500 字内）；超过 3 个被拒、不落成果
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1,
                     {'links': ['https://example.com/r2-%s' % i for i in range(4)], 'note': '第二版'}, app_token=w_tok)
    check('S7 一轮超过 3 个链接或口令被拒（422），不落第 2 轮', (422, 0),
          (code_of(b), n("select count(*) from kg_online_submission where app_id=%s and round=2" % app1)))
    # 3 个合起来超过 500 字（V50 把列放宽到 1600）；第 3 个里的换行并成空格，换行只当分隔符
    r2_links = ['https://example.com/r2', '小红书口令 ' + '字' * 450 + ' https://xhslink.cn/o/r2b', '7.43 复制打开抖音\n看看作品 https://v.douyin.com/r2c/']
    r2_want = r2_links[:2] + ['7.43 复制打开抖音 看看作品 https://v.douyin.com/r2c/']
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1, {'link': '\n'.join(r2_links), 'links': r2_links, 'note': '第二版'}, app_token=w_tok)
    check('S7 退回后可以再提交第 2 轮', (200, 'submitted'), (code_of(b), scalar("select status from kg_online_submission where app_id=%s and round=2" % app1)))
    check('S7 第 2 轮 3 个链接用换行连起来落库，合起来超过 500 字也放得下', ('|'.join(r2_want), str(len('\n'.join(r2_want)))),
          (scalar("select replace(link, '\\n', '|') from kg_online_submission where app_id=%s and round=2" % app1),
           scalar("select char_length(link) from kg_online_submission where app_id=%s and round=2" % app1)))
    mine = data(http('GET', '/app/online/assignments/%s' % app1, app_token=w_tok)[1]) or {}
    store = data(http('GET', '/app/staff/online/assignments/%s' % app1, app_token=mgr_tok)[1]) or {}
    check('S7 接单人交付页、门店验收页都拿到拆好的 3 个链接', (r2_want, r2_want),
          ((mine.get('latest') or {}).get('links'), (store.get('latest') or {}).get('links')))
    res = parallel(lambda _: http('POST', '/app/staff/online/assignments/%s/accept' % app1, {'comment': '通过'}, app_token=mgr_tok)[1], range(3), 3)
    check('S7 并发验收 3 次：只生成 1 张结算单，金额 = 佣金 12345 分', (1, '12345', 'pending_payout'),
          (n("select count(*) from kg_online_settlement where app_id=%s" % app1), scalar("select amount_fen from kg_online_settlement where app_id=%s" % app1),
           scalar("select status from kg_online_settlement where app_id=%s" % app1)))
    status, b = http('POST', '/app/online/assignments/%s/submit' % app1, {'link': 'https://example.com/r3'}, app_token=w_tok)
    check('S7 验收后不能再提交（409）', 409, code_of(b))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': True}, app_token=w_tok)
    check('S7 门店登记付款前不能确认收款（409）', 409, code_of(b))

    pay = {'method': '银行转账', 'ref': 'TX-%s' % RUN, 'voucher': '/profile/upload/voucher.png'}
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % app1, pay, app_token=u['keeper'][0])
    check('S7 管家不能登记付款（403）', (403, 'pending_payout'), (code_of(b), scalar("select status from kg_online_settlement where app_id=%s" % app1)))
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % app1, pay, app_token=u['far'][0])
    check('S7 别家主理人不能登记付款（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % app1, dict(pay, voucher=''), app_token=mgr_tok)
    check('S7 不上传凭证被拒（422）', 422, code_of(b))
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % app1, pay, app_token=mgr_tok)
    check('S7 主理人登记线下付款 → payout_registered、凭证落库、接单人收到通知', ('payout_registered', 'TX-%s' % RUN, 1),
          (scalar("select status from kg_online_settlement where app_id=%s" % app1), scalar("select payout_ref from kg_online_settlement where app_id=%s" % app1),
           msgs(w_id, '门店已登记线下付款')))
    status, b = http('POST', '/app/staff/online/assignments/%s/payout' % app1, pay, app_token=mgr_tok)
    check('S7 重复登记付款被拒（409）', 409, code_of(b))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': True}, app_token=w2_tok)
    check('S7 别人不能替我确认收款', (True, 'payout_registered'), (denied(b), scalar("select status from kg_online_settlement where app_id=%s" % app1)))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': True}, app_token=w_tok)
    check('S7 接单人确认收款 → confirmed', 'confirmed', scalar("select status from kg_online_settlement where app_id=%s" % app1))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app1, {'received': False, 'reason': '反悔'}, app_token=w_tok)
    check('S7 确认后不能再申诉', (True, 'confirmed'), (denied(b), scalar("select status from kg_online_settlement where app_id=%s" % app1)))

    # 申诉分支：第二位接单人
    http('POST', '/app/online/assignments/%s/submit' % app2, {'link': 'https://example.com/b'}, app_token=w2_tok)
    http('POST', '/app/staff/online/assignments/%s/accept' % app2, {}, app_token=u['keeper'][0])
    http('POST', '/app/staff/online/assignments/%s/payout' % app2, dict(pay, ref='TX2'), app_token=mgr_tok)
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app2, {'received': False, 'reason': ''}, app_token=w2_tok)
    check('S7 申诉必须说明情况（422）', 422, code_of(b))
    status, b = http('POST', '/app/online/assignments/%s/confirm' % app2, {'received': False, 'reason': '回归：没收到转账'}, app_token=w2_tok)
    check('S7 申诉 → disputed、生成一条平台申报', ('disputed', 1),
          (scalar("select status from kg_online_settlement where app_id=%s" % app2),
           n("select count(*) from kg_appeal where app_user_id=%s and biz_type='online_settlement' and biz_id=%s" % (w2_id, app2))))
    status, b = http('GET', '/app/staff/online/assignments/%s' % app2, app_token=u['far'][0])
    check('S7 别家主理人查本店接单（403）', 403, code_of(b))
    lst = data(http('GET', '/app/staff/online/tasks/%s/assignments?storeId=%s' % (jid, A), app_token=u['staff'][0])[1]) or []
    check('S7 员工端接单列表含两位接单人', 2, len(lst))
    d = data(http('GET', '/app/staff/online/assignments/%s' % app1, app_token=u['staff'][0])[1]) or {}
    check('S7 员工视角接单人姓名脱敏、看不到用户 ID', ('接*甲', False), (d.get('worker'), 'appUserId' in d))


# ================================================================ S8 门店与角色 / 面试 / 页面其余调用

def s8_team_and_pages():
    section('S8 门店与角色（授权 / 撤销 / 操作日志 / 上报）、面试日程、页面其余读接口')
    A, B, u = CTX['A'], CTX['B'], CTX['u']
    mgr_tok, mgr_id = u['mgr']
    newbie_tok, newbie_id = app_login('newbie', '新员工')

    status, b = http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': str(newbie_id), 'role': 'staff'}, app_token=u['keeper'][0])
    check('S8 管家不能授予角色（403）且记一条失败操作日志', (403, True),
          (code_of(b), n("select count(*) from kg_staff_op_log where store_id=%s and app_user_id=%s and action='role_grant' and ok='0'" % (A, u['keeper'][1])) >= 1))
    status, b = http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': str(newbie_id), 'role': 'manager'}, app_token=mgr_tok)
    check('S8 主理人不能授予主理人', (True, 0), (denied(b), n("select count(*) from kg_staff_grant where app_user_id=%s" % newbie_id)))
    status, b = http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': str(mgr_id), 'role': 'staff'}, app_token=mgr_tok)
    check('S8 不能给自己授权', True, denied(b), ok=denied(b))
    res = parallel(lambda _: http('POST', '/app/staff/team/grant?storeId=%s' % A, {'target': str(newbie_id), 'role': 'staff'}, app_token=mgr_tok)[1], range(3), 3)
    check('S8 并发授予同一人 3 次：只落 1 条授权', 1, n("select count(*) from kg_staff_grant where app_user_id=%s and store_id=%s" % (newbie_id, A)))
    check('S8 并发授予的失败请求返回业务提示而不是 500', [], [code_of(b) for b in res if code_of(b) == 500])
    status, b = http('GET', '/app/staff/insurance/plans', app_token=newbie_tok)
    check('S8 被授权人不用重新登录即获得员工身份（令牌授权已刷新）', 200, code_of(b))
    gid = n("select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s" % (newbie_id, A))
    status, b = http('POST', '/app/staff/team/revoke?storeId=%s' % B, {'grantId': gid}, app_token=u['far'][0])
    check('S8 别家主理人不能撤销本店授权（403）', (403, '0'), (code_of(b), scalar("select status from kg_staff_grant where grant_id=%s" % gid)))
    mgr_gid = n("select grant_id from kg_staff_grant where app_user_id=%s and store_id=%s" % (mgr_id, A))
    status, b = http('POST', '/app/staff/team/revoke?storeId=%s' % A, {'grantId': mgr_gid}, app_token=mgr_tok)
    check('S8 主理人授权不能在小程序里撤销', (True, '0'), (denied(b), scalar("select status from kg_staff_grant where grant_id=%s" % mgr_gid)))
    status, b = http('POST', '/app/staff/team/revoke?storeId=%s' % A, {'grantId': gid}, app_token=mgr_tok)
    check('S8 主理人撤销 → status=1', (200, '1'), (code_of(b), scalar("select status from kg_staff_grant where grant_id=%s" % gid)))
    status, b = http('GET', '/app/staff/insurance/plans', app_token=newbie_tok)
    check('S8 撤销后原令牌立即失去员工身份', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/team/revoke?storeId=%s' % A, {'grantId': gid}, app_token=mgr_tok)
    check('S8 重复撤销被拒', True, denied(b), ok=denied(b))

    status, b = http('POST', '/app/staff/team/switch', {'storeId': B}, app_token=mgr_tok)
    check('S8 不能切换到没有授权的门店（403）', 403, code_of(b))
    d = data(http('GET', '/app/staff/team/logs?storeId=%s&days=7' % A, app_token=u['staff'][0])[1]) or {}
    others = [r for r in d.get('rows') or [] if not str(r.get('person', '')).startswith('回归员工')]
    check('S8 普通员工的操作日志只看到本人的', ('仅本人', []), (d.get('scope'), others))
    d = data(http('GET', '/app/staff/team/logs?storeId=%s&days=7&category=fund' % A, app_token=mgr_tok)[1]) or {}
    fund = n("select count(*) from kg_staff_op_log where store_id=%s and category='fund' and create_time>=date_sub(now(), interval 7 day)" % A)
    check('S8 主理人看全店资金类操作：条数 = 库', min(fund, 100), len(d.get('rows') or []))
    lid = n("select log_id from kg_staff_op_log where store_id=%s order by log_id desc limit 1" % A)
    status, b = http('POST', '/app/staff/team/logs/%s/report' % lid, {'reason': '回归：异常导出'}, app_token=u['far'][0])
    check('S8 别家主理人不能上报本店操作（403）', 403, code_of(b))
    b1 = http('POST', '/app/staff/team/logs/%s/report' % lid, {'reason': '回归：异常导出'}, app_token=u['keeper'][0])[1]
    b2 = http('POST', '/app/staff/team/logs/%s/report' % lid, {'reason': '回归：异常导出'}, app_token=u['keeper'][0])[1]
    check('S8 上报异常操作落一条 kg_report；重复上报被拒', (200, True, 1),
          (code_of(b1), denied(b2), n("select count(*) from kg_report where target_type='staffop' and target_id=%s" % lid)))
    for path in ('/app/staff/team/hub?storeId=%s' % A, '/app/staff/team/stores?storeId=%s' % A, '/app/staff/team/roles?storeId=%s&role=staff' % A):
        status, b = http('GET', path, app_token=mgr_tok)
        check('S8 页面读接口 %s 可用' % path.split('?')[0], 200, code_of(b))
        status, b = http('GET', path.replace('storeId=%s' % A, 'storeId=%s' % A), app_token=u['far'][0])
        check('S8 别家主理人读 %s（本店）被拒' % path.split('?')[0], 403, code_of(b))
    roles = data(http('GET', '/app/staff/team/roles?storeId=%s&role=staff' % A, app_token=mgr_tok)[1]) or {}
    perm = dict((r.get('code'), r.get('values')) for r in roles.get('permissions') or [])
    check('S8 角色权限页展示的员工矩阵：财务全 no、岗位只有查看（与服务端校验同源）',
          (['no'] * 5, ['yes', 'no', 'no', 'no', 'no']), (perm.get('finance'), perm.get('jobs')))

    # 面试日程：普通员工看不到完整手机号（2026-09-24 确认的员工口径）
    iid = n("select interview_id from kg_interview where app_id=%s order by interview_id desc limit 1" % CTX['a1'])
    d = data(http('GET', '/app/staff/interviews/%s' % iid, app_token=u['staff'][0])[1]) or {}
    check('S8 普通员工看面试详情：手机号脱敏', True, bool(d) and '13500135000' not in json.dumps(d), ok=bool(d) and '13500135000' not in json.dumps(d))
    d = data(http('GET', '/app/staff/interviews/%s' % iid, app_token=mgr_tok)[1]) or {}
    check('S8 主理人看面试详情：完整手机号', '13500135000', d.get('phone'))
    status, b = http('GET', '/app/staff/interviews/%s' % iid, app_token=u['far'][0])
    check('S8 别家主理人看本店面试（403）', 403, code_of(b))
    status, b = http('POST', '/app/staff/interviews/%s/rate' % iid, {'rating': 9, 'note': '好'}, app_token=mgr_tok)
    check('S8 面试评分超出 1–5 被拒', True, denied(b), ok=denied(b))
    status, b = http('POST', '/app/staff/interviews/%s/rate' % iid, {'rating': 4, 'note': '沟通顺畅'}, app_token=mgr_tok)
    check('S8 候选人已完成面试后主理人评价落库', (200, '4'), (code_of(b), scalar("select staff_rating from kg_interview where interview_id=%s" % iid)))
    status, b = http('POST', '/app/staff/interviews/%s/rate' % iid, {'rating': 1, 'note': '越权'}, app_token=u['far'][0])
    check('S8 别家主理人不能评价本店面试', (True, '4'), (denied(b), scalar("select staff_rating from kg_interview where interview_id=%s" % iid)))
    status, b = http('POST', '/app/staff/interviews/%s/finish' % iid, {}, app_token=mgr_tok)
    check('S8 面试还没开始不能标记结束', True, denied(b), ok=denied(b))
    status, b = http('GET', '/app/staff/interviews?storeId=%s&range=week' % A, app_token=mgr_tok)
    check('S8 面试日程列表可用', 200, code_of(b))
    status, b = http('GET', '/app/staff/insurance/app/%s' % CTX['a1'], app_token=u['staff'][0])
    check('S8 投保弹窗数据：候选人可投保、默认起保日不早于今天', (200, True, True),
          (code_of(b), ((data(b) or {}).get('candidate') or {}).get('insurable'), str((data(b) or {}).get('defaultStart')) >= day(0)))
    status, b = http('GET', '/app/staff/insurance/app/%s' % CTX['a1'], app_token=u['far'][0])
    check('S8 别家主理人读本店候选人投保信息（403）', 403, code_of(b))

    # 页面其余读接口（每个页面实际调用的都至少跑一次，并对关键字段回库）
    d = data(http('GET', '/app/manage/weekly?mode=week&storeId=%s' % A, app_token=mgr_tok)[1]) or {}
    check('S8 周报列表（按周）可用且含本店', True, ('经营回归小院A-' + RUN) in json.dumps(d, ensure_ascii=False),
          ok=('经营回归小院A-' + RUN) in json.dumps(d, ensure_ascii=False))
    d = data(http('GET', '/app/manage/tasks?storeId=%s&weekStart=%s&tab=open' % (A, day(1)), app_token=u['vol'][0])[1]) or {}
    check('S8 义工的任务列表不含指定员工任务', [], d.get('staffTasks'))
    d = data(http('GET', '/app/manage/tasks?storeId=%s&weekStart=%s&tab=open' % (A, day(1)), app_token=u['outsider'][0])[1]) or {}
    check('S8 路人按本店 storeId 查任务：拿不到任何任务', ([], []), (d.get('staffTasks'), d.get('volunteerTasks')))
    for path, tok in (('/app/staff/insurance?storeId=%s&tab=toBuy' % A, mgr_tok), ('/app/staff/insurance/to-buy?storeId=%s' % A, mgr_tok),
                      ('/app/staff/jobs?storeId=%s' % A, u['staff'][0]), ('/app/staff/jobs/keywords', mgr_tok),
                      ('/app/staff/reviews?storeId=%s&tab=all' % A, u['staff'][0]), ('/app/staff/talents/tags?storeId=%s' % A, mgr_tok),
                      ('/app/staff/talents/jobs?storeId=%s' % A, mgr_tok), ('/app/staff/online/tasks?storeId=%s' % A, u['staff'][0]),
                      ('/app/staff/dispatch/urgent?jobId=%s' % CTX['job'], mgr_tok), ('/app/wallet/entries?type=stay', mgr_tok)):
        status, b = http('GET', path, app_token=tok)
        check('S8 页面读接口 %s 可用' % path.split('?')[0], 200, code_of(b))
    status, b = http('POST', '/app/staff/jobs/describe?storeId=%s' % A, {'jobType': 'volunteer', 'title': '前台', 'keywords': ['咖啡']}, app_token=mgr_tok)
    check('S8 岗位描述生成（模板）可用', 200, code_of(b))
    status, b = http('POST', '/app/staff/talents/search?storeId=%s&countOnly=true' % A, {}, app_token=mgr_tok)
    check('S8 人才池只计数：与列表条数一致', len((data(http('POST', '/app/staff/talents/search?storeId=%s' % A, {}, app_token=mgr_tok)[1]) or {}).get('rows') or []),
          (data(b) or {}).get('total'))
    rows = (data(http('GET', '/app/staff/jobs?storeId=%s' % A, app_token=mgr_tok)[1]) or {}).get('jobs') or \
        (data(http('GET', '/app/staff/jobs?storeId=%s' % A, app_token=mgr_tok)[1]) or {}).get('rows') or []
    ids = set(int(r.get('jobId')) for r in rows if r.get('jobId'))
    check('S8 员工岗位列表只含本店岗位（不含别家）', (True, False), (CTX['job'] in ids, CTX['jobB'] in ids))
    vcode = scalar("select verify_code from kg_shop_order where order_no='%s'" % CTX.get('shop_no'))
    status, b = http('POST', '/app/staff/stay/verify/resolve?storeId=%s' % A, {'code': vcode}, app_token=mgr_tok)
    check('S8 已核销的服务单再扫码：不可核销', (200, False), (code_of(b), (data(b) or {}).get('canVerify')))


# ================================================================ main

FIN2_USER = 'kg_fin2_' + RUN
FIN2_ROLE = 'kg_fin2_' + RUN


def make_second_finance():
    """第二个后台账号（另一位财务）：只持有结算相关的按钮权限。密码哈希复制 admin 的，不新增任何口令。
    四眼原则要求「登记打款的人不能是审批的人」，没有第二个账号就测不了这条。"""
    from test_restricted_role import login_as, PASSWORD_PLAIN
    pwd_hash = scalar("select password from sys_user where user_name='admin'")
    sql_exec("insert into sys_role (role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_by, create_time) "
             "values ('财务复核探针', '%s', 98, '1', 1, 1, '0', '0', 'e2e', sysdate())" % FIN2_ROLE)
    rid = scalar("select role_id from sys_role where role_key='%s'" % FIN2_ROLE)
    sql_exec("insert ignore into sys_role_menu (role_id, menu_id) select %s, menu_id from sys_menu where perms like 'kangaroo:withdraw:%%'" % rid)
    sql_exec("insert ignore into sys_role_menu (role_id, menu_id) select %s, parent_id from sys_menu where perms like 'kangaroo:withdraw:%%' and parent_id > 0" % rid)
    sql_exec("insert into sys_user (user_name, nick_name, user_type, status, del_flag, password, create_by, create_time) "
             "values ('%s', '财务复核探针', '00', '0', '0', '%s', 'e2e', sysdate())" % (FIN2_USER, pwd_hash))
    uid = scalar("select user_id from sys_user where user_name='%s'" % FIN2_USER)
    sql_exec("insert into sys_user_role (user_id, role_id) values (%s, %s)" % (uid, rid))
    status, body = login_as(FIN2_USER, PASSWORD_PLAIN)
    return body.get('token')


def drop_second_finance():
    sql_exec("delete from sys_user_role where user_id in (select user_id from sys_user where user_name='%s')" % FIN2_USER)
    sql_exec("delete from sys_user where user_name='%s'" % FIN2_USER)
    sql_exec("delete from sys_role_menu where role_id in (select role_id from sys_role where role_key='%s')" % FIN2_ROLE)
    sql_exec("delete from sys_role where role_key='%s'" % FIN2_ROLE)


def main():
    admin = admin_login()
    print('测试运行标识 RUN=%s' % RUN)
    m0_setup(admin)
    try:
        CTX['admin2'] = make_second_finance()
    except Exception as e:  # noqa: BLE001
        print('第二个后台账号建不出来：%s' % e)
        CTX['admin2'] = None
    guard('M1', m1_commission)
    guard('M2', m2_withdraw)
    guard('M3', m3_weekly)
    guard('M4', m4_daily)
    guard('M5', m5_dashboard)
    guard('S1', s1_setup)
    guard('M6', m6_tasks)
    guard('M7', m7_honor)
    guard('S2', s2_insurance)
    guard('S3', s3_dispatch)
    guard('S4', s4_jobs)
    guard('S5', s5_reviews)
    guard('S6', s6_talents)
    guard('S7', s7_online)
    guard('S8', s8_team_and_pages)
    drop_second_finance()

    section('汇总')
    print('通过 %d / 共 %d' % (len(PASSED), len(PASSED) + len(FAILED)))
    if FAILED:
        print('\n失败用例：')
        for f in FAILED:
            print('  - ' + f)
    print('\n测试数据标识 RUN=%s（账号 code 前缀 mgt-%s-，门店名含 %s）' % (RUN, RUN, RUN))
    return 1 if FAILED else 0


if __name__ == '__main__':
    sys.exit(main())
