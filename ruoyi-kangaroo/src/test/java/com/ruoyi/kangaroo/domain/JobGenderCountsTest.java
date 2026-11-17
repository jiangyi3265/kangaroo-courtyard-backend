package com.ruoyi.kangaroo.domain;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

class JobGenderCountsTest
{
    private KgJob job(int total, Integer male, Integer female)
    {
        KgJob j = new KgJob(); j.setJobType("volunteer"); j.setHeadcount(total);
        j.setGenderMaleCount(male); j.setGenderFemaleCount(female); j.setGenderPref(""); return j;
    }
    @Test void mixedAndSingleGenderPlansDriveExistingFilters()
    {
        KgJob j = job(3, 1, 2); JobGenderCounts.apply(j,null);
        assertEquals("",j.getGenderPref()); assertTrue(j.getGenderCountsEnabled());
        j = job(3,0,3); JobGenderCounts.apply(j,null); assertEquals("F",j.getGenderPref());
        j = job(3,3,0); JobGenderCounts.apply(j,null); assertEquals("M",j.getGenderPref());
    }
    @Test void invalidPlansCannotChangeTotal()
    {
        for (KgJob j : new KgJob[]{job(2,1,0), job(1,0,0),job(100,50,50),job(2,-1,3),job(2,1,null)})
            assertThrows(ServiceException.class, () -> JobGenderCounts.apply(j,null));
        for (Object n : new Object[]{"1.5", -1, 100, true, "bad", ""})
            assertThrows(ServiceException.class, () -> JobGenderCounts.count(n));
    }
    @Test void oldClientsPreservePlanAndMustNotAccidentallyChangeItsTotal()
    {
        KgJob old = job(3,1,2), incoming=job(3,null,null);
        JobGenderCounts.apply(incoming,old); assertEquals(1,incoming.getGenderMaleCount());
        assertThrows(ServiceException.class, () -> JobGenderCounts.apply(job(4,null,null),old));
    }
    @Test void explicitDisableClearsPlanAndPreservesChosenPreference()
    {
        KgJob j=job(4,null,null); j.setGenderCountsEnabled(false); j.setGenderPref("F");
        JobGenderCounts.apply(j,job(3,1,2));
        assertNull(j.getGenderMaleCount()); assertNull(j.getGenderFemaleCount()); assertEquals("F",j.getGenderPref());
    }
    @Test void legacyJobsStayUnsplitAndOtherJobTypesDoNotCarryPlan()
    {
        KgJob j=job(2,null,null); JobGenderCounts.apply(j,null); assertFalse(j.getGenderCountsEnabled());
        j=job(2,1,1); j.setJobType("parttime"); JobGenderCounts.apply(j,null); assertNull(j.getGenderMaleCount());
    }
}
