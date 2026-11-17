package com.ruoyi.kangaroo.controller.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 日报 Excel：招募账不出现「总营收」和系统数据的字样，旅居账和原来一样 */
class AppManageDailyWorkbookTest
{
    private static Map<String, Object> m(Object... kv)
    {
        Map<String, Object> out = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2)
        {
            out.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return out;
    }

    private static String allText(byte[] bytes) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes)))
        {
            for (Row r : wb.getSheetAt(0))
            {
                for (Cell c : r)
                {
                    sb.append(c.getStringCellValue()).append('|');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @Test
    void recruitWorkbook() throws Exception
    {
        List<Object> items = new ArrayList<Object>(Arrays.asList(m("name", "保险收入", "amount", "¥100"), m("name", "其他支出", "amount", "¥5")));
        List<Object> rows = new ArrayList<Object>(Arrays.asList(m("name", "其他支出/交通费", "amount", "-¥5", "system", false, "note", "微信")));
        Map<String, Object> d = m("ledger", "recruit", "title", "换工招募日报", "sourceText", "手动录入", "storeName", "丽江小院", "dateText", "2026年10月30日（周五）",
                "date", "2026-10-30", "items", items, "rows", rows, "totals", new long[] { 10000, 500, 9500 });
        String text = allText(AppManageController.dailyWorkbook(d));
        assertTrue(text.startsWith("换工招募日报|"), text);
        assertTrue(text.contains("数据来源|手动录入|"), text);
        assertTrue(text.contains("保险收入|¥100|"), text);
        assertTrue(text.contains("其他支出/交通费|-¥5|手动|微信|"), text);
        assertTrue(text.contains("总收入|¥100|"), text);
        assertTrue(text.contains("收支结余|¥95|"), text);
        assertFalse(text.contains("总营收"), text);
        assertFalse(text.contains("系统自动"), text);
    }

    @Test
    void stayWorkbookIsUnchanged() throws Exception
    {
        List<Object> rows = new ArrayList<Object>(Arrays.asList(m("name", "订房收入", "amount", "¥100", "system", true, "note", "订单 1 笔")));
        Map<String, Object> d = m("ledger", "stay", "title", "每日经营报表", "storeName", "丽江小院", "dateText", "2026年10月30日（周五）",
                "date", "2026-10-30", "rows", rows, "totals", new long[] { 10000, 0, 10000 });
        String text = allText(AppManageController.dailyWorkbook(d));
        assertTrue(text.startsWith("每日经营报表|"), text);
        assertTrue(text.contains("数据来源|系统自动 + 手动录入|"), text);
        assertTrue(text.contains("总营收|¥100|"), text);
        assertFalse(text.contains("科目|"), text);
    }
}
