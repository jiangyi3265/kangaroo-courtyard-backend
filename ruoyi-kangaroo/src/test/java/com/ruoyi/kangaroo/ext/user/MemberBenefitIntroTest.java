package com.ruoyi.kangaroo.ext.user;

import com.ruoyi.kangaroo.ext.ExtEntity;
import com.ruoyi.kangaroo.ext.ExtField;
import com.ruoyi.kangaroo.ext.KgJdbc;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** V40 店铺介绍：后台能编辑（多行、1000 字、列表里不显示），小程序详情接口带上它，列表接口不带。 */
class MemberBenefitIntroTest {
    private List<Map<String,Object>> levels() {
        return Arrays.asList(p("levelId",1L,"level",0,"levelName","幼崽鼠","minPoints",0L),p("levelId",2L,"level",1,"levelName","小袋鼠","minPoints",100L));
    }
    @Test void adminFormHasOptionalMultiLineIntroHiddenFromListAfterDiscount() {
        KgHomeMemberModule module=new KgHomeMemberModule(mock(KgJdbc.class),mock(KgMemberService.class),mock(KgCityService.class));
        ExtEntity benefits=module.entities().stream().filter(e->"memberbenefit".equals(e.key)).findFirst().orElseThrow(AssertionError::new);
        ExtField intro=benefits.field("intro");
        assertNotNull(intro,"会员店铺权益要登记店铺介绍");
        assertEquals("店铺介绍",intro.label);
        assertEquals("intro",intro.column);
        assertEquals("textarea",intro.type,"后台要多行输入");
        assertEquals(1000,intro.maxLen,"与 kg_member_benefit.intro varchar(1000) 一致");
        assertFalse(intro.inList,"列表里不显示，表格不变宽");
        assertFalse(intro.required,"老权益没有介绍也能保存");
        assertTrue(intro.editable);
        int at=benefits.fields.indexOf(intro);
        assertEquals("discountText",benefits.fields.get(at-1).name,"表单里跟在折扣说明后面，和小程序详情的顺序一致");
    }
    @Test void detailSelectsIntroButListStaysLean() {
        KgJdbc db=mock(KgJdbc.class);
        when(db.list(anyString(),anyMap())).thenReturn(levels());
        when(db.must(anyString(),anyMap(),anyString())).thenAnswer(i->p("benefitId",7L,"minLevel",0L,"intro","海景房\n步行到沙滩 3 分钟"));
        Map<String,Object> page=new HashMap<>(); page.put("rows",new ArrayList<Map<String,Object>>());
        when(db.page(anyString(),anyMap(),anyInt(),anyInt())).thenReturn(page);
        KgMemberService svc=new KgMemberService(db);
        Map<String,Object> detail=svc.benefit(null,7L);
        assertEquals("海景房\n步行到沙滩 3 分钟",detail.get("intro"),"换行原样返回，由小程序按纯文本显示");
        ArgumentCaptor<String> detailSql=ArgumentCaptor.forClass(String.class);
        verify(db).must(detailSql.capture(),anyMap(),anyString());
        assertTrue(detailSql.getValue().matches("(?s)select .*\\bintro\\b.* from kg_member_benefit .*"),detailSql.getValue());
        svc.benefits(null,null,null,null,1);
        ArgumentCaptor<String> listSql=ArgumentCaptor.forClass(String.class);
        verify(db).page(listSql.capture(),anyMap(),anyInt(),anyInt());
        assertFalse(listSql.getValue().matches("(?s).*\\bintro\\b.*"),"列表每页 20 条，不捎带长文本："+listSql.getValue());
    }
}
