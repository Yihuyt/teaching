package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.ActorPrincipal;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
class SecurityCurrentActor implements CurrentActor {

    @Override
    public Actor require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ActorPrincipal principal)) {
            throw new UnauthorizedException("请先登录");
        }
        return new Actor(principal.userId(), principal.username(), principal.role());
    }
}
