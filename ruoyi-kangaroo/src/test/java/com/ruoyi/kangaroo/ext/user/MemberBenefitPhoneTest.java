package com.ruoyi.kangaroo.ext.user;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import static org.junit.jupiter.api.Assertions.*;

class MemberBenefitPhoneTest {
    private Map<String,Object> value(String phone) {
        Map<String,Object> cols=new HashMap<>(); cols.put("phone",phone); return cols;
    }
    @Test void acceptsDialableNumbersAndOptionalEmptyPhoneWithoutChangingFormatting() {
        for(String phone:new String[]{null,"","  ","13800138000","0591-88888888","+44 20 1234 5678","12345","12345678901234567890"}) {
            Map<String,Object> cols=value(phone);
            assertDoesNotThrow(()->KgHomeMemberModule.checkBenefitPhone(cols));
            assertEquals(phone,cols.get("phone"));
        }
        assertDoesNotThrow(()->KgHomeMemberModule.checkBenefitPhone(new HashMap<>()));
    }
    @Test void rejectsNonDialableAndOutOfBoundaryValuesWithFieldSpecificValidationError() {
        for(String phone:new String[]{"bad-number","1234","123456789012345678901","++8613800138000","13800138000<script>","电话13800138000","1.2"}) {
            ServiceException failure=assertThrows(ServiceException.class,()->KgHomeMemberModule.checkBenefitPhone(value(phone)));
            assertEquals(422,failure.getCode()); assertTrue(failure.getMessage().contains("店铺电话"));
        }
    }
}
