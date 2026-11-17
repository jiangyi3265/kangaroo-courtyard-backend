package com.ruoyi.kangaroo.ext.staff;

import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KgOnlineRecipientValidationTest {
    @Test void rejectsMultilineAndControlCharactersInName() {
        for (String name : new String[]{"张\n\n三", "张\r\n三", "张\t三", "张\u0085三", "张\u2028三", "张\u2029三", "张<三>"}) {
            ServiceException error=assertThrows(ServiceException.class, () -> KgOnlineRecipient.realName(name), name);
            assertEquals(422,error.getCode());
        }
    }
    @Test void acceptsOrdinaryAndInternationalNamesWithTrimmedPadding() {
        assertEquals("张三",KgOnlineRecipient.realName("  张三  "));
        assertEquals("Anne-Marie O'Neil",KgOnlineRecipient.realName("Anne-Marie O'Neil"));
        assertEquals("阿依古丽·艾山",KgOnlineRecipient.realName("阿依古丽·艾山"));
    }
    @Test void rejectsMissingOrOversizedNames() {
        for (String name : new String[]{null,"","张",new String(new char[51]).replace('\0','张')}) {
            assertThrows(ServiceException.class, () -> KgOnlineRecipient.realName(name));
        }
    }
    @Test void rejectsMissingOrInvalidAccountsWithValidationError() {
        for (String account : new String[]{null,"","123","worker\n@example.com","worker@example.com\n\nother@example.com"}) {
            ServiceException error=assertThrows(ServiceException.class, () -> KgOnlineRecipient.account(account));
            assertEquals(422,error.getCode());
        }
        assertEquals("worker@example.com",KgOnlineRecipient.account(" worker@example.com "));
        assertEquals("13800138000",KgOnlineRecipient.account("13800138000"));
    }
}
