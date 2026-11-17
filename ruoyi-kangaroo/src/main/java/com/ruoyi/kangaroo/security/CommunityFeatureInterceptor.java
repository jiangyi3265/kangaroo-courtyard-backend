package com.ruoyi.kangaroo.security;
import java.util.Arrays;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import com.ruoyi.kangaroo.controller.app.*;
import com.ruoyi.kangaroo.controller.app.AppActivityController;
import com.ruoyi.kangaroo.controller.app.AppTripChatController;
import com.ruoyi.kangaroo.ext.community.KgCommunityFeature;

/** Gate resolved handlers, not raw URL strings, so aliases/encoded paths cannot bypass it. */
@Component
public class CommunityFeatureInterceptor implements HandlerInterceptor {
    private final KgCommunityFeature community;
    public CommunityFeatureInterceptor(KgCommunityFeature community) { this.community=community; }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if (!(handler instanceof HandlerMethod)) return true;
        HandlerMethod method=(HandlerMethod)handler;
        Class<?> type=method.getBeanType();String name=method.getMethod().getName();
        boolean recruitmentBlocked=AppJobController.class.isAssignableFrom(type) || AppJobReviewController.class.isAssignableFrom(type);
        recruitmentBlocked |= AppApplicationController.class.isAssignableFrom(type) && "submit".equals(name);
        recruitmentBlocked |= AppUserExtController.class.isAssignableFrom(type) && Arrays.asList("match","resume","basic","skills","reuse","addExp","editExp","opportunities","team","joinTeam","addSub","editSub","search","followStore").contains(name);
        recruitmentBlocked |= AppFollowIncomeController.class.isAssignableFrom(type) && Arrays.asList("follows","storeJobs").contains(name);
        recruitmentBlocked |= AppStaffOpsController.class.isAssignableFrom(type) && Arrays.asList("jobKeywords","describe","createJob","batchJobs","shareJob","adjust","urgent").contains(name);
        recruitmentBlocked |= AppStaffExtController.class.isAssignableFrom(type) && Arrays.asList("talentTags","talentSearch","talentJobs","talent","mark","invite","onlineCreate").contains(name);
        recruitmentBlocked |= AppMerchantController.class.isAssignableFrom(type) && "apply".equals(name);
        recruitmentBlocked |= AppStaffMerchantRequestController.class.isAssignableFrom(type) && Arrays.asList("intakeSubmit","intakeForward","merchantSubmit","merchantResubmit","dispatchedPublish","servicePublish").contains(name);
        recruitmentBlocked |= AppWorkbenchController.class.isAssignableFrom(type) && Arrays.asList("forward","forwardStores","sharedResume","resumePoster").contains(name);
        if(recruitmentBlocked) { response.setHeader("Cache-Control","no-store");community.requireRecruitment(); }
        // Preserve historical order, cancellation/refund, payment callbacks and staff handlers.
        boolean stayBlocked=AppStayController.class.isAssignableFrom(type)
                && Arrays.asList("cities","areas","districts","list","promos","detail","roomTypes","addons","calendar","recommend","stayVlogs","openVlog","trackVlog","likeVlog","vlogComments","commentVlog","favorite","favorites","quote","create").contains(name);
        stayBlocked |= AppStayDiaryController.class.isAssignableFrom(type) || AppStayReviewController.class.isAssignableFrom(type);
        stayBlocked |= AppStayCommentController.class.isAssignableFrom(type) && Arrays.asList("list","me","submit").contains(name);
        if(stayBlocked) { response.setHeader("Cache-Control","no-store");community.requireStayBooking(); }
        boolean blocked=AppCommunityController.class.isAssignableFrom(type)
                && !Arrays.asList("status","rules","consent","dmSetting","saveDmSetting","block","report").contains(name);
        blocked |= AppActivityController.class.isAssignableFrom(type) && Arrays.asList("create","syncCommunity","edit").contains(name);
        blocked |= AppTripChatController.class.isAssignableFrom(type) && Arrays.asList("publishPlan","matches","person").contains(name);
        boolean privateBlocked=AppCommunityController.class.isAssignableFrom(type) && Arrays.asList("follow","following").contains(name);
        privateBlocked |= AppTripChatController.class.isAssignableFrom(type) && Arrays.asList("publishPlan","matches","person").contains(name);
        if (privateBlocked) { response.setHeader("Cache-Control","no-store");community.requirePrivateChat(); }
        if (blocked) { response.setHeader("Cache-Control","no-store");community.requireEnabled(); }
        return true;
    }
}
