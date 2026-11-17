package com.ruoyi.kangaroo.ext.stay;

import java.util.Map;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.ext.KgJdbc;

/** 所有容量写入与下单先锁房型，再访问房态。调用者必须持有事务。 */
final class KgRoomInventory
{
    private KgRoomInventory() { }

    static Map<String, Object> lockRoom(KgJdbc db, Object id)
    {
        return db.must("select room_type_id,stock from kg_stay_room_type where room_type_id=:id for update", KgJdbc.p("id", id), "房型不存在");
    }

    static void checkDefault(KgJdbc db, Object id, Object value)
    {
        lockRoom(db, id);
        if (value == null) throw new ServiceException("默认库存不能为空", 422);
        long stock = Long.parseLong(String.valueOf(value));
        for (Map<String, Object> row : db.list("select occupied from kg_stay_calendar where room_type_id=:id and stock is null for update", KgJdbc.p("id", id)))
        {
            if (stock < ((Number) row.get("occupied")).longValue())
                throw new ServiceException("默认库存不能低于使用默认库存日期的已占用数量", 409);
        }
    }

    static void checkCalendar(KgJdbc db, Object id, Object value)
    {
        Map<String, Object> cal = db.must("select room_type_id from kg_stay_calendar where cal_id=:id", KgJdbc.p("id", id), "房态记录不存在");
        Map<String, Object> room = lockRoom(db, cal.get("roomTypeId"));
        Map<String, Object> row = db.must("select occupied from kg_stay_calendar where cal_id=:id for update", KgJdbc.p("id", id), "房态记录不存在");
        long stock = value == null ? ((Number) room.get("stock")).longValue() : Long.parseLong(String.valueOf(value));
        if (stock < ((Number) row.get("occupied")).longValue())
            throw new ServiceException("库存不能低于已占用数量；恢复默认库存也必须保留已售房量", 409);
    }
}
