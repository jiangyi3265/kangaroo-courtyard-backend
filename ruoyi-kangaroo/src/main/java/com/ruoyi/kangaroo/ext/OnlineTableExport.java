package com.ruoyi.kangaroo.ext;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** Export the registered online-delivery/settlement fields as an actual XLSX workbook. */
public final class OnlineTableExport
{
    private OnlineTableExport() { }

    public static void write(ExtEntity entity, List<Map<String, Object>> rows, HttpServletResponse response) throws IOException
    {
        SXSSFWorkbook book = new SXSSFWorkbook(100);
        try
        {
            Sheet sheet = book.createSheet(entity.title);
            sheet.createFreezePane(0, 1);
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("编号");
            for (int i = 0; i < entity.fields.size(); i++)
            {
                ExtField f = entity.fields.get(i);
                head.createCell(i + 1).setCellValue(f.label + ("money".equals(f.type) ? "（元）" : ""));
                sheet.setColumnWidth(i + 1, 24 * 256);
            }
            int index = 1;
            for (Map<String, Object> data : rows)
            {
                Row row = sheet.createRow(index++);
                row.createCell(0).setCellValue(value(data.get(KgJdbc.toCamel(entity.pk))));
                for (int i = 0; i < entity.fields.size(); i++)
                {
                    ExtField f = entity.fields.get(i);
                    String v = value(data.get(f.name));
                    if ("money".equals(f.type) && !v.isEmpty()) v = new BigDecimal(v).movePointLeft(2).setScale(2).toPlainString();
                    if (f.options != null)
                    {
                        for (String option : f.options.split(","))
                        {
                            String[] pair = option.split(":", 2);
                            if (pair.length == 2 && pair[0].equals(v)) { v = pair[1]; break; }
                        }
                    }
                    // Always STRING cells: user-entered '=...' is never evaluated as a formula.
                    row.createCell(i + 1, CellType.STRING).setCellValue(v.length() > 32767 ? v.substring(0, 32767) : v);
                }
            }
            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader("Content-Disposition", "attachment; filename=" + entity.key + ".xlsx");
            book.write(response.getOutputStream());
        }
        finally { book.close(); book.dispose(); }
    }

    private static String value(Object value)
    {
        if (value == null) return "";
        if (value instanceof Date) return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) value);
        return String.valueOf(value);
    }
}
