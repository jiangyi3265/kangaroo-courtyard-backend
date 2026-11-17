package com.ruoyi.kangaroo.ext.user;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.str;
import static com.ruoyi.kangaroo.ext.user.KgMemberWallet.num;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

@Service
public class KgPointMallService {
    @Autowired private KgJdbc db;
    @Autowired private KgPointLedger ledger;
    @Autowired private KgMemberWallet wallet;
    @Autowired(required = false) private com.ruoyi.kangaroo.service.IKgMessageService messages;

    private void notifyOwner(Long owner, Long orderId, String title, String content) {
        if (messages != null && owner != null)
            messages.send(owner, "user", "system", title, content, "point_mall", orderId, "/pages/profile/point-mall");
    }

    public Map<String, Object> goods(boolean admin, int page) {
        return db.page("select goods_id,title,cover,description,points_cost,stock,delivery_mode,pickup_place,status,version "
                + "from kg_point_goods " + (admin ? "" : "where status='online' ") + "order by goods_id desc", p(), page, 20);
    }

    public Map<String, Object> orders(Long uid, String status, int page) {
        Map<String, Object> args = p("u", uid, "status", status);
        return db.page("select * from kg_point_goods_order where 1=1 "
                + (uid == null ? "" : "and app_user_id=:u ")
                + (status.isEmpty() ? "" : "and status=:status ") + "order by order_id desc", args, page, 20);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> balance(Long uid) {
        return p("balance", ledger.lockedTotals(uid).balance());
    }

    public static int integer(Object value, int min, int max, String label) {
        int number;
        try { number = new BigDecimal(str(value)).intValueExact(); }
        catch (NumberFormatException | ArithmeticException e) { throw new ServiceException("请填写有效的" + label, 422); }
        if (number < min || number > max) throw new ServiceException(label + "超出允许范围", 422);
        return number;
    }

    public static String text(Object value, int min, int max, String label) {
        String text = str(value).trim();
        if (text.length() < min || text.length() > max) throw new ServiceException(label + "请填写" + min + "至" + max + "字", 422);
        return text;
    }

    @Transactional(rollbackFor = Exception.class)
    public long save(Long id, Map<String, Object> b, String actor) {
        String mode = str(b.get("deliveryMode")), status = str(b.get("status"));
        if (!Arrays.asList("shipping", "pickup").contains(mode) || !Arrays.asList("online", "offline").contains(status))
            throw new ServiceException("请选择有效的配送方式与上下架状态", 422);
        String cover = str(b.get("cover"));
        if (!cover.isEmpty() && !KgJdbc.isPlatformImage(cover)) throw new ServiceException("请使用平台上传的商品图片", 422);
        Map<String, Object> cols = p("title", text(b.get("title"), 1, 80, "商品名称"), "cover", cover,
                "description", text(b.get("description"), 1, 2000, "兑换说明"),
                "points_cost", integer(b.get("pointsCost"), 1, 10000000, "兑换积分"),
                "stock", integer(b.get("stock"), 0, 1000000, "库存"), "delivery_mode", mode,
                "pickup_place", text(b.get("pickupPlace"), "pickup".equals(mode) ? 1 : 0, 300, "领取地点"),
                "status", status, "update_by", actor);
        if (id == null) return db.insert("kg_point_goods", cols);
        Map<String, Object> old = db.must("select * from kg_point_goods where goods_id=:id for update", p("id", id), "商品不存在");
        int version = integer(b.get("version"), 0, Integer.MAX_VALUE - 1, "版本");
        if (num(old.get("version")) != version) throw new ServiceException("商品或库存已变化，请刷新后再保存", 409);
        cols.put("version", version + 1);
        db.updateRow("kg_point_goods", "goods_id", id, cols);
        return id;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> redeem(Long uid, Long goodsId, Map<String, Object> b) {
        String key = KgMemberWallet.key(b.get("requestKey"));
        // Account -> goods is the same lock order for redemption and cancellation.
        wallet.active(uid);
        // The account lock serializes this user's requests. A missing-key locking read here
        // would gap-lock other users' first orders and deadlock their inventory updates.
        Map<String, Object> old = db.one("select * from kg_point_goods_order where app_user_id=:u and request_key=:k", p("u", uid, "k", key));
        if (old != null) {
            if (num(old.get("goodsId")) != goodsId) throw new ServiceException("请求编号已用于其他商品，请刷新后重试", 409);
            return old;
        }
        Map<String, Object> goods = db.must("select * from kg_point_goods where goods_id=:id for update", p("id", goodsId), "商品不存在");
        if (!"online".equals(goods.get("status")) || num(goods.get("stock")) < 1) throw new ServiceException("商品已下架或库存不足", 409);
        int cost = (int) num(goods.get("pointsCost"));
        if (integer(b.get("pointsCost"), 1, 10000000, "兑换积分") != cost) throw new ServiceException("兑换积分已调整，请刷新后确认", 409);
        if (ledger.lockedTotals(uid).balance() < cost) throw new ServiceException("可用积分不足", 409);
        boolean shipping = "shipping".equals(goods.get("deliveryMode"));
        String phone = text(b.get("phone"), 7, 24, "联系电话");
        if (!phone.matches("[+0-9 ()-]{7,24}")) throw new ServiceException("请填写有效的联系电话", 422);
        long id = db.insert("kg_point_goods_order", p("app_user_id", uid, "request_key", key, "goods_id", goodsId,
                "title", goods.get("title"), "cover", goods.get("cover"), "points_cost", cost,
                "delivery_mode", goods.get("deliveryMode"), "pickup_place", goods.get("pickupPlace"),
                "recipient", text(b.get("recipient"), 1, 40, "领取人姓名"), "phone", phone,
                "address", text(b.get("address"), shipping ? 5 : 0, 300, "收货地址")));
        db.update("update kg_point_goods set stock=stock-1,version=version+1 where goods_id=:id", p("id", goodsId));
        ledger.spend(uid, cost, "兑换商品「" + goods.get("title") + "」", "mall_redeem", id);
        return db.must("select * from kg_point_goods_order where order_id=:id", p("id", id), "兑换记录不存在");
    }

    @Transactional(rollbackFor = Exception.class)
    public void command(Long id, Long uid, String action, Map<String, Object> b, String actor) {
        Map<String, Object> record = db.one("select app_user_id from kg_point_goods_order where order_id=:id", p("id", id));
        if (record == null || uid != null && !uid.equals(KgJdbc.asLong(record.get("appUserId")))) throw new ServiceException("兑换记录不存在", 404);
        Long owner = KgJdbc.asLong(record.get("appUserId"));
        // Serialize with redemption before locking points, orders or inventory.
        db.one("select user_id from kg_app_user where user_id=:u for update", p("u", owner));
        // A frozen or closed account can still receive a refund on its existing order.
        ledger.lockedTotals(owner);
        Map<String, Object> order = db.must("select * from kg_point_goods_order where order_id=:id for update", p("id", id), "兑换记录不存在");
        String state = str(order.get("status"));
        if (uid != null && !Arrays.asList("cancel", "receive").contains(action)) throw new ServiceException("没有操作权限", 403);
        if ("cancel".equals(action)) {
            if ("cancelled".equals(state)) return;
            if (!"pending".equals(state)) throw new ServiceException("商品已经发出或领取，请联系平台处理", 409);
            String note = uid == null ? text(b.get("note"), 2, 500, "取消原因") : "用户取消兑换";
            db.update("update kg_point_goods set stock=stock+1,version=version+1 where goods_id=:g", p("g", order.get("goodsId")));
            ledger.award(owner, (int)num(order.get("pointsCost")), "取消商品兑换，退还积分", "mall_refund", id);
            db.updateRow("kg_point_goods_order", "order_id", id, p("status", "cancelled", "handle_note", note, "handled_by", actor));
            // 平台取消要告诉用户（用户自己取消的不用）
            if (uid == null) notifyOwner(owner, id, "积分兑换已取消", "你兑换的「" + order.get("title") + "」已由平台取消，" + num(order.get("pointsCost")) + " 积分已退回。原因：" + note);
            return;
        }
        if ("ship".equals(action) && uid == null && "pending".equals(state) && "shipping".equals(order.get("deliveryMode"))) {
            String carrier = text(b.get("carrier"), 1, 40, "快递公司"), tracking = text(b.get("trackingNo"), 1, 80, "快递单号");
            db.updateRow("kg_point_goods_order", "order_id", id, p("status", "shipped", "carrier", carrier,
                    "tracking_no", tracking, "handled_by", actor));
            // 发货后要用户自己点「确认收货」才完成，之前不发消息，用户不知道已经发了
            notifyOwner(owner, id, "积分兑换已发货", "你兑换的「" + order.get("title") + "」已发货：" + carrier + " " + tracking + "。收到后请在积分商城点「确认收货」。");
            return;
        }
        boolean pickup = "pickup".equals(action) && uid == null && "pickup".equals(order.get("deliveryMode")) && "pending".equals(state);
        boolean receive = "receive".equals(action) && uid != null && "shipped".equals(state);
        if (pickup || receive) {
            String note = pickup ? text(b.get("note"), 2, 500, "领取核验记录") : "用户确认收货";
            db.update("update kg_point_goods_order set status='completed',handle_note=:n,handled_by=:a,fulfilled_time=now() where order_id=:id", p("n", note, "a", actor, "id", id));
            return;
        }
        if ("completed".equals(state) && Arrays.asList("pickup", "receive").contains(action)) return;
        throw new ServiceException("兑换状态已变化，请刷新后操作", 409);
    }
}
