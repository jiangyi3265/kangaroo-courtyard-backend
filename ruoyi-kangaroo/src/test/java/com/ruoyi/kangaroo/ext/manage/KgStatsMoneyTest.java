package com.ruoyi.kangaroo.ext.manage;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class KgStatsMoneyTest
{
    @ParameterizedTest
    @CsvSource(value = {
        "0|¥0", "1200|¥12", "-1200|-¥12", "1234|¥12.34", "-1234|-¥12.34",
        "1|¥0.01", "-1|-¥0.01", "1230|¥12.30", "123456789|¥1,234,567.89",
        "9223372036854775807|¥92,233,720,368,547,758.07",
        "-9223372036854775808|-¥92,233,720,368,547,758.08"
    }, delimiter = '|')
    void displayPreservesEveryCent(long fen, String expected)
    {
        assertEquals(expected, KgStats.yuanText(fen));
    }

    @ParameterizedTest
    @CsvSource(value = {
        "0|0.00", "1200|12.00", "1234|12.34", "-1|-0.01",
        "123456789|1,234,567.89", "-9223372036854775808|-92,233,720,368,547,758.08"
    }, delimiter = '|')
    void exportFormatPreservesEveryCent(long fen, String expected)
    {
        assertEquals(expected, KgStats.yuan2(fen));
    }

    @Test
    void missingAmountRemainsUnknown()
    {
        assertEquals("—", KgStats.yuanText(null));
    }

    @Test
    void moneyTextDoesNotDependOnMachineLocale()
    {
        Locale before = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("¥1,234.56", KgStats.yuanText(123456L));
            assertEquals("1,234.56", KgStats.yuan2(123456L));
        }
        finally
        {
            Locale.setDefault(before);
        }
    }
}
