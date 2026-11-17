package com.ruoyi.kangaroo.ext.governance;

import static com.ruoyi.kangaroo.ext.KgJdbc.p;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KgBadgeRulesTest {
    Map<String,Object> eligibleUser() {
        return p("accountAvailable",true,"creditScore",100,"referralFraud",false,"falseContent",false,"hasViolation",false);
    }

    @Test void baseRecommendationHasTwoIndependentBranches() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("registeredPeople",10,"experiencedPeople",9));
        assertFalse(KgBadgeRules.referrer(metrics));
        metrics.put("experiencedPeople",10);
        assertTrue(KgBadgeRules.referrer(metrics));
        metrics.putAll(p("registeredPeople",20,"experiencedPeople",5));
        assertTrue(KgBadgeRules.referrer(metrics));
        metrics.put("experiencedPeople",4);
        assertFalse(KgBadgeRules.referrer(metrics));
        metrics.putAll(p("registeredPeople",19,"experiencedPeople",5));
        assertFalse(KgBadgeRules.referrer(metrics));
    }

    @Test void baseIntegrityAppliesToBothBranches() {
        for (Map<String,Object> branch : Arrays.asList(p("experiencedPeople",10),p("registeredPeople",20,"experiencedPeople",5))) {
            Map<String,Object> metrics = eligibleUser();
            metrics.putAll(branch);
            metrics.put("creditScore",80);
            assertTrue(KgBadgeRules.referrer(metrics));
            metrics.put("creditScore",79);
            assertFalse(KgBadgeRules.referrer(metrics));
            metrics.put("creditScore",null);
            assertFalse(KgBadgeRules.referrer(metrics));
            metrics.putAll(p("creditScore",100,"referralFraud",true));
            assertFalse(KgBadgeRules.referrer(metrics));
        }
    }

    @Test void goldRecommendationHasThreeIndependentBranches() {
        for (Map<String,Object> branch : Arrays.asList(p("experiencedPeople",50),
                p("quarterExperiencedPeople",20,"quarterRank",10),p("activeReferredWorkers",10))) {
            Map<String,Object> metrics = eligibleUser();
            metrics.putAll(branch);
            assertTrue(KgBadgeRules.goldReferrer(metrics));
            metrics.put("creditScore",89);
            assertFalse(KgBadgeRules.goldReferrer(metrics));
            metrics.putAll(p("creditScore",90,"hasViolation",true));
            assertFalse(KgBadgeRules.goldReferrer(metrics));
        }
    }

    @Test void quarterlyCountAndRankingAreBothRequired() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("quarterExperiencedPeople",20,"quarterRank",11));
        assertFalse(KgBadgeRules.goldReferrer(metrics));
        metrics.putAll(p("quarterExperiencedPeople",19,"quarterRank",1));
        assertFalse(KgBadgeRules.goldReferrer(metrics));
        metrics.putAll(p("quarterExperiencedPeople",20,"quarterRank",0));
        assertFalse(KgBadgeRules.goldReferrer(metrics));
        metrics.put("quarterRank",10);
        assertTrue(KgBadgeRules.goldReferrer(metrics));
    }

    @Test void lifetimeFiftyDoesNotBecomeANewQuarterQuota() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("experiencedPeople",50,"quarterExperiencedPeople",0,"quarterRank",0));
        assertTrue(KgBadgeRules.goldReferrer(metrics));
    }

    @Test void lawfulGoldDowngradeRetainsBaseWithItsIntegrityFloor() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("experiencedPeople",0,"registeredPeople",10,"downgradedFromGold",true,"creditScore",80));
        assertTrue(KgBadgeRules.referrer(metrics));
        metrics.put("creditScore",79);
        assertFalse(KgBadgeRules.referrer(metrics));
        metrics.putAll(p("creditScore",100,"referralFraud",true));
        assertFalse(KgBadgeRules.referrer(metrics));
    }

    @Test void jurorUsesActualPersonalAdoptionAndIntegerRateBoundary() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("participations",20,"qualityVotes",5));
        assertTrue(KgBadgeRules.goldJuror(metrics));
        metrics.putAll(p("participations",19,"qualityVotes",5));
        assertFalse(KgBadgeRules.goldJuror(metrics));
        metrics.putAll(p("participations",10,"qualityVotes",0,"adoptedVotes",7));
        assertTrue(KgBadgeRules.goldJuror(metrics));
        metrics.put("adoptedVotes",6);
        assertFalse(KgBadgeRules.goldJuror(metrics));
        metrics.putAll(p("participations",9,"adoptedVotes",9));
        assertFalse(KgBadgeRules.goldJuror(metrics));
        metrics.putAll(p("participations",11,"adoptedVotes",7));
        assertFalse(KgBadgeRules.goldJuror(metrics));
        metrics.put("adoptedVotes",8);
        assertTrue(KgBadgeRules.goldJuror(metrics));
    }

    @Test void jurorAbsenceSanctionAndSuspensionBlockBothBranches() {
        for (Map<String,Object> barrier : Arrays.asList(p("absenceCount",1),p("disqualified",true),
                p("suspended",true),p("accountAvailable",false))) {
            Map<String,Object> metrics = eligibleUser();
            metrics.putAll(p("participations",20,"qualityVotes",5,"adoptedVotes",20));
            metrics.putAll(barrier);
            assertFalse(KgBadgeRules.goldJuror(metrics));
        }
    }

    @Test void unrelatedMembershipViolationDoesNotAddAnUnspecifiedJurorBarrier() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("participations",20,"qualityVotes",5,"hasViolation",true));
        assertTrue(KgBadgeRules.goldJuror(metrics));
    }

    @Test void travelerAllowsEachOfTheThreeSpecifiedRoutes() {
        for (Map<String,Object> branch : Arrays.asList(
                p("completedExperiences",6,"qualityContents",10,"featuredContents",3),
                p("travelDays",60,"qualityContents",10,"featuredContents",3),
                p("completedExperiences",3,"officialFeaturedContents",3))) {
            Map<String,Object> metrics = eligibleUser();
            metrics.putAll(branch);
            metrics.put("creditScore",85);
            assertTrue(KgBadgeRules.traveler(metrics));
            metrics.put("creditScore",84);
            assertFalse(KgBadgeRules.traveler(metrics));
            metrics.putAll(p("creditScore",100,"falseContent",true));
            assertFalse(KgBadgeRules.traveler(metrics));
        }
    }

    @Test void travelerDoesNotDropTheContentOrExperienceAndCondition() {
        Map<String,Object> metrics = eligibleUser();
        metrics.putAll(p("completedExperiences",6,"travelDays",60,"qualityContents",9,"featuredContents",3));
        assertFalse(KgBadgeRules.traveler(metrics));
        metrics.putAll(p("qualityContents",10,"featuredContents",2));
        assertFalse(KgBadgeRules.traveler(metrics));
        metrics.putAll(p("completedExperiences",2,"officialFeaturedContents",3));
        assertFalse(KgBadgeRules.traveler(metrics));
    }

    @Test void quartersUseCalendarBoundariesAcrossTheYear() {
        assertEquals(LocalDate.of(2026,1,1),KgBadgeRules.quarterStart(LocalDate.of(2026,3,31)));
        assertEquals(LocalDate.of(2026,4,1),KgBadgeRules.quarterStart(LocalDate.of(2026,4,1)));
        assertEquals(LocalDate.of(2026,10,1),KgBadgeRules.quarterStart(LocalDate.of(2026,12,31)));
        assertEquals(LocalDate.of(2027,1,1),KgBadgeRules.quarterStart(LocalDate.of(2027,1,1)));
    }

    @Test void travelDaysDoNotDoubleCountOverlappingStaysOrJobs() {
        LocalDate start = LocalDate.of(2026,1,1);
        assertEquals(20,KgBadgeMetrics.unionDays(new ArrayList<>(Arrays.asList(
                new LocalDate[]{start.plusDays(5),start.plusDays(15)},
                new LocalDate[]{start,start.plusDays(10)},
                new LocalDate[]{start.plusDays(15),start.plusDays(20)},
                new LocalDate[]{start.plusDays(2),start.plusDays(4)}))));
        assertEquals(0,KgBadgeMetrics.unionDays(new ArrayList<>()));
    }
}
