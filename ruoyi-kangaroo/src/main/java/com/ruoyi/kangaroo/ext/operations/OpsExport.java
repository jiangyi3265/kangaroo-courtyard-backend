package com.ruoyi.kangaroo.ext.operations;

import static com.ruoyi.kangaroo.ext.operations.OpsRules.*;
import java.io.*;
import java.util.*;
import java.net.URLEncoder;
import javax.servlet.http.HttpServletResponse;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/** Excel text cells (never formulas), and searchable Chinese PDF with an embedded OFL font. */
@Component
public class OpsExport {
    private static final String[][] MANUAL={{"tomorrowPlan","明日重点"},{"agreedShops","约定店铺及事项"},{"issues","今日问题"},{"facilities","设施故障"},{"shortages","物料缺货"},{"yesterdayIssues","昨日问题／特殊情况"},{"activityPlan","未来两天活动计划"},{"activityNote","活动备注"},{"offlineIncome","线下收入（元）"}};
    public List<List<String>> rows(Map<String,Object> report){
        List<List<String>> out=new ArrayList<>();row(out,"报表",report.containsKey("summary")?"月度总结":"每日工作报告");row(out,"地区 / 人员",report.get("cityName")+" / "+report.get("ownerName"));row(out,"身份","keeper".equals(report.get("roleCode"))?"小院主理人":"换工主理人");row(out,"日期",report.containsKey("summary")?report.get("monthKey"):report.get("bizDate"));
        if(report.containsKey("auto")){
            Map<?,?> auto=(Map<?,?>)report.get("auto");row(out,"统计窗口",report.get("windowStart")+" 至 "+report.get("dataCutoff"));row(out,"状态","submitted".equals(report.get("status"))?"已提交":"草稿");row(out,"统计口径",auto.get("dataSourceNote"));
            fields(out,auto.get("business"));for(Object value:list(auto.get("workforce"))){Map<?,?> line=(Map<?,?>)value;row(out,line.get("title"),"");fields(out,line.get("fields"));}
            if(auto.get("weekActivityCount")!=null)row(out,"每周活动",auto.get("weekActivityCount")+" 场；距3场还差 "+auto.get("weekActivityMissing"));for(Object value:list(auto.get("activities"))){Map<?,?> a=(Map<?,?>)value;row(out,"活动",a.get("title"),a.get("startTime"),"报名 "+a.get("signed"),a.get("status"));}
            Map<?,?> manual=(Map<?,?>)report.get("manual");if(report.get("form") instanceof Map){for(Map<String,Object> f:OpsForms.fields((Map<String,Object>)report.get("form")))if(Boolean.TRUE.equals(f.get("enabled")))row(out,f.get("label"),manual.get(f.get("key")));if(manual.containsKey("offlineIncome"))row(out,"线下收入（元）",manual.get("offlineIncome"));}else for(String[] f:MANUAL)if(manual.containsKey(f[0]))row(out,f[1],manual.get(f[0]));
            if(report.get("correctedAt")!=null)row(out,"后台修订",report.get("correctedAt"),report.get("correctedBy"),report.get("correctionReason"));
            for(Object value:list(manual.get("expenses"))){Map<?,?> e=(Map<?,?>)value;row(out,"线下支出",e.get("date"),e.get("item"),e.get("amount"),e.get("handler"));}
            if(report.get("totals") instanceof Map){Map<?,?> t=(Map<?,?>)report.get("totals");row(out,"今日总营收（元）",java.math.BigDecimal.valueOf(number(t.get("totalIncomeFen")),2));row(out,"今日总支出（元）",java.math.BigDecimal.valueOf(number(t.get("totalExpenseFen")),2));}
            progress(out,report.get("progress"));if(manual.get("remedies") instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)manual.get("remedies")).entrySet())row(out,"补救计划 / 目标 "+entry.getKey(),entry.getValue());row(out,"今日小结",report.get("summaryText"));
        }else{
            Map<?,?> summary=(Map<?,?>)report.get("summary");row(out,"统计窗口",summary.get("windowStart")+" 至 "+summary.get("windowEnd"));row(out,"生成时间",report.get("generatedAt"));row(out,"已提交 / 草稿日报",summary.get("submittedReports")+" / "+summary.get("draftReports"));row(out,"统计口径",summary.get("note"));progress(out,summary.get("progress"));
            Map<?,?> counts=(Map<?,?>)summary.get("counts");for(Map<String,Object> metric:OpsCatalog.metrics())if(counts.containsKey(metric.get("code")))row(out,metric.get("label"),counts.get(metric.get("code")),metric.get("unit"));
            row(out,"本期新增 / 已闭环 / 未闭环异常",counts.get("cases"),counts.get("closedCases"),counts.get("openCases"));
            for(String key:Arrays.asList("strengths","gaps","suggestions")){String label="strengths".equals(key)?"做得好的地方":"gaps".equals(key)?"差距":"下月建议";for(Object v:list(summary.get(key)))row(out,label,v);}
            for(Object value:list(summary.get("actions"))){Map<?,?> a=(Map<?,?>)value;row(out,"行动记录",a.get("bizDate"),a.get("ownerName"),a.get("plan"),a.get("issues"));if(a.get("remedies") instanceof Map)for(Object plan:((Map<?,?>)a.get("remedies")).values())row(out,"补救计划",plan);}
        }return out;
    }
    private List<?> list(Object value){return value instanceof List?(List<?>)value:Collections.emptyList();}
    private void fields(List<List<String>> out,Object value){for(Object field:list(value)){Map<?,?> f=(Map<?,?>)field;row(out,f.get("label"),f.get("value")==null?"不适用":f.get("value"),f.get("unit"),f.get("source"));}}
    private void progress(List<List<String>> out,Object value){row(out,"月度任务","目标","已完成","差额（负数为超额）","完成率 / 状态");for(Object target:list(value)){Map<?,?> t=(Map<?,?>)target;row(out,t.get("label"),t.get("target"),t.get("completed"),t.get("missing"),t.get("completionRate")+"% / "+t.get("statusLabel"));}}
    private void row(List<List<String>> out,Object... values){List<String> row=new ArrayList<>();for(Object value:values)row.add(value==null?"—":text(value));out.add(row);}
    public byte[] excel(List<List<String>> data)throws IOException{
        try(XSSFWorkbook book=new XSSFWorkbook();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            Sheet sheet=book.createSheet("工作报表");CellStyle wrap=book.createCellStyle();wrap.setWrapText(true);wrap.setVerticalAlignment(VerticalAlignment.TOP);Font font=book.createFont();font.setFontName("Microsoft YaHei");font.setFontHeightInPoints((short)11);wrap.setFont(font);
            int i=0;for(List<String> values:data){
                List<List<String>> columns=new ArrayList<>();int lines=1;
                for(int c=0;c<values.size();c++){List<String> column=cellLines(values.get(c),c==0?21:38);columns.add(column);lines=Math.max(lines,column.size());}
                // Excel row height is limited to 409 points. Split long notes into continuation rows.
                for(int start=0;start<lines;start+=22){Row row=sheet.createRow(i++);int visible=Math.min(22,lines-start);row.setHeightInPoints(Math.max(30,visible*16+8));
                    for(int c=0;c<columns.size();c++){List<String> column=columns.get(c);String value=start<column.size()?String.join("\n",column.subList(start,Math.min(start+22,column.size()))):"";if(start>0&&c==0&&value.isEmpty())value="（续）";Cell cell=row.createCell(c,CellType.STRING);cell.setCellValue(value);cell.setCellStyle(wrap);}
                }
            }
            for(int c=0;c<6;c++)sheet.setColumnWidth(c,(c==0?24:42)*256);sheet.createFreezePane(1,1);sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);sheet.getPrintSetup().setLandscape(true);sheet.setFitToPage(true);book.write(bytes);return bytes.toByteArray();
        }
    }
    private List<String> cellLines(String value,int capacity){List<String> out=new ArrayList<>();for(String paragraph:text(value).replace("\r","").split("\n",-1)){StringBuilder line=new StringBuilder();int width=0;for(int cp:paragraph.codePoints().toArray()){int next=cp>255?2:1;if(width+next>capacity&&width>0){out.add(line.toString());line.setLength(0);width=0;}line.appendCodePoint(cp);width+=next;}out.add(line.toString());}return out;}
    public byte[] pdf(List<List<String>> data)throws IOException{
        try(PDDocument doc=new PDDocument();InputStream stream=OpsExport.class.getResourceAsStream("/fonts/kg-reports-sc.ttf");ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(stream==null)throw new IOException("报表中文字体缺失");PDType0Font font=PDType0Font.load(doc,stream,true);
            PDPageContentStream canvas=null;float y=0;try{
                for(List<String> row:data){String joined=String.join("  |  ",row).replace('\r',' ');for(String paragraph:joined.split("\n",-1)){
                    StringBuilder line=new StringBuilder();float width=0;
                    for(int cp:paragraph.codePoints().toArray()){String ch=new String(Character.toChars(cp));float w;try{w=font.getStringWidth(ch)/1000*10;}catch(IllegalArgumentException e){ch="?";w=font.getStringWidth(ch)/1000*10;}
                        if(width+w>499&&!line.toString().isEmpty()){if(y<55){if(canvas!=null)canvas.close();PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);canvas=new PDPageContentStream(doc,page);y=795;}draw(canvas,font,line.toString(),y);y-=16;line.setLength(0);width=0;}line.append(ch);width+=w;
                    }
                    if(y<55){if(canvas!=null)canvas.close();PDPage page=new PDPage(PDRectangle.A4);doc.addPage(page);canvas=new PDPageContentStream(doc,page);y=795;}draw(canvas,font,line.toString(),y);y-=19;
                }}
            }finally{if(canvas!=null)canvas.close();}
            for(int i=0;i<doc.getNumberOfPages();i++){try(PDPageContentStream footer=new PDPageContentStream(doc,doc.getPage(i),PDPageContentStream.AppendMode.APPEND,true)){draw(footer,font,"袋鼠工作报表 · 第 "+(i+1)+" / "+doc.getNumberOfPages()+" 页",29);}}
            doc.save(out);return out.toByteArray();
        }
    }
    private void draw(PDPageContentStream out,PDType0Font font,String text,float y)throws IOException{out.beginText();out.setFont(font,10);out.newLineAtOffset(48,y);out.showText(text);out.endText();}
    public void respond(HttpServletResponse response,String format,List<List<String>> rows,String name)throws IOException{
        require(Arrays.asList("xlsx","pdf").contains(format),"导出格式仅支持xlsx或pdf");byte[] bytes="pdf".equals(format)?pdf(rows):excel(rows);
        response.setContentType("pdf".equals(format)?"application/pdf":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode(name+"."+format,"UTF-8").replace("+","%20"));response.setHeader("Cache-Control","no-store");response.setContentLength(bytes.length);response.getOutputStream().write(bytes);
    }
}
