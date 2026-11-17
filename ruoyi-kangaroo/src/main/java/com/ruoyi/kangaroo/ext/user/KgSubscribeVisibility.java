package com.ruoyi.kangaroo.ext.user;

import java.util.List;
import java.util.Collections;
import org.springframework.stereotype.Component;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.kangaroo.mapper.KgMessageMapper;
import com.ruoyi.kangaroo.security.*;

/** Background workers have no HTTP identity. Rebuild the recipient's current scope before sending. */
@Component
public class KgSubscribeVisibility {
    private final AppIdentityLoader identities;
    private final KgMessageMapper messages;
    public KgSubscribeVisibility(AppIdentityLoader identities, KgMessageMapper messages) {
        this.identities=identities; this.messages=messages;
    }
    public String authorizedScope(Long uid,Long msgId,String mode) {
        AppLoginUser prior=AppUserContext.get();List<Long> stores=AppUserContext.messageStores();
        String role=AppUserContext.messageRole(),chat=AppUserContext.chatMode();
        try {
            AppLoginUser recipient=new AppLoginUser();recipient.setUserId(uid);identities.reload(recipient);
            AppUserContext.set(recipient);
            if(!"staff".equals(mode)) {
                AppUserContext.messageScope(null,"user");
                return messages.selectVisibleMessage(msgId,uid,"user")==null?null:"user";
            }
            for(StaffGrantView grant:recipient.getStaffGrants()) {
                if(!Boolean.TRUE.equals(grant.getActive()))continue;
                String cityScope=ServiceCityMessageScope.key(grant);
                AppUserContext.messageScope(cityScope==null?Collections.singletonList(grant.getStoreId()==null?-1L:grant.getStoreId()):ServiceCityMessageScope.stores(recipient,cityScope),grant.getRoleCode());
                if(messages.selectVisibleMessage(msgId,uid,"staff")!=null)
                    return cityScope!=null?cityScope:(grant.getStoreId()==null?"city:"+grant.getCityId():"store:"+grant.getStoreId())+":"+grant.getRoleCode();
            }
            return null;
        } catch(ServiceException e) { return null; }
        finally {
            AppUserContext.remove();if(prior!=null)AppUserContext.set(prior);
            AppUserContext.messageScope(stores,role);AppUserContext.chatMode(chat);
        }
    }
}
