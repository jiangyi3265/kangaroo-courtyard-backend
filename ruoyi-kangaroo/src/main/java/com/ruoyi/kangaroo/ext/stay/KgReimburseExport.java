package com.ruoyi.kangaroo.ext.stay;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import com.ruoyi.kangaroo.domain.JobImages;
import com.ruoyi.kangaroo.ext.KgJdbc;

/**
 * 采购报销表格导出（V66）：真的 xlsx。金额、数量是数字格子（主人要在表格里求和），别的一律文字格子，用户填的「=1+1」不会被当公式算。
 * 最后两行是合计：全部行、去掉驳回的（驳回的不用付钱）。
 */
final class KgReimburseExport
{
    private static final String[] HEADERS = { "编号", "门店", "提交人", "品名", "数量", "金额（元）", "购买日期", "状态", "提交时间", "审核人", "审核时间", "打款时间", "驳回理由", "备注", "凭证张数", "凭证地址" };
    private static final int QUANTITY_COL = 4;
    private static final int AMOUNT_COL = 5;

    private KgReimburseExport()
    {
    }

    static void write(List<Map<String, Object>> rows, HttpServletResponse response) throws IOException
    {
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        try
        {
            CellStyle moneyStyle = book.createCellStyle();
            moneyStyle.setDataFormat(book.createDataFormat().getFormat("0.00"));
            Sheet sheet = book.createSheet("采购报销");
            sheet.createFreezePane(0, 1);
            Row head = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++)
            {
                head.createCell(i, CellType.STRING).setCellValue(HEADERS[i]);
                sheet.setColumnWidth(i, (i == 3 || i >= 12 ? 30 : 16) * 256);
            }
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal valid = BigDecimal.ZERO;
            int index = 1;
            for (Map<String, Object> r : rows)
            {
                List<String> receipts = JobImages.read(text(r.get("receipts")));
                Row row = sheet.createRow(index++);
                text(row, 0, r.get("reimburseId"));
                text(row, 1, r.get("storeName"));
                text(row, 2, r.get("submitterName"));
                text(row, 3, r.get("itemName"));
                row.createCell(QUANTITY_COL, CellType.NUMERIC).setCellValue(KgJdbc.asLong(r.get("quantity")).doubleValue());
                BigDecimal amount = new BigDecimal(KgReimburseService.money(r.get("amount")));
                money(row, moneyStyle, amount);
                text(row, 6, r.get("purchaseDate"));
                text(row, 7, KgReimburseService.statusText(r.get("status")));
                text(row, 8, r.get("submitTime"));
                text(row, 9, r.get("auditBy"));
                text(row, 10, r.get("auditTime"));
                text(row, 11, r.get("paidTime"));
                text(row, 12, r.get("rejectReason"));
                text(row, 13, r.get("remark"));
                text(row, 14, receipts.size());
                text(row, 15, String.join("\n", receipts));
                sum = sum.add(amount);
                if (!KgReimburseService.REJECTED.equals(text(r.get("status"))))
                {
                    valid = valid.add(amount);
                }
            }
            Row total = sheet.createRow(index++);
            text(total, 3, "合计（全部 " + rows.size() + " 笔）");
            money(total, moneyStyle, sum);
            Row effective = sheet.createRow(index);
            text(effective, 3, "合计（不含已驳回）");
            money(effective, moneyStyle, valid);
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-Disposition", "attachment; filename=reimburse.xlsx");
            book.write(response.getOutputStream());
        }
        finally
        {
            book.close();
            book.dispose();
        }
    }

    private static String text(Object v)
    {
        return v == null ? "" : String.valueOf(v);
    }

    private static void text(Row row, int col, Object v)
    {
        String s = text(v);
        row.createCell(col, CellType.STRING).setCellValue(s.length() > 32767 ? s.substring(0, 32767) : s);
    }

    private static void money(Row row, CellStyle style, BigDecimal amount)
    {
        Cell cell = row.createCell(AMOUNT_COL, CellType.NUMERIC);
        cell.setCellStyle(style);
        cell.setCellValue(amount.doubleValue());
    }
}
