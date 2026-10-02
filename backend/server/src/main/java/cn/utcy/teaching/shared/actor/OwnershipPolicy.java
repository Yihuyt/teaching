package cn.utcy.teaching.shared.actor;

import cn.utcy.teaching.shared.error.ForbiddenOperationException;

import org.springframework.stereotype.Component;

@Component
public class OwnershipPolicy {

    public boolean isContentManager(Actor actor, long ownerId) {
        return actor.role().canManageTeachingContent()
                && (actor.role() != SystemRole.TEACHER || actor.userId() == ownerId);
    }

    public void requireContentManager(Actor actor, long ownerId) {
        if (!actor.role().canManageTeachingContent()) {
            throw new ForbiddenOperationException("当前角色不能管理教学内容");
        }
        if (actor.role() == SystemRole.TEACHER && actor.userId() != ownerId) {
            throw new ForbiddenOperationException("只能管理自己负责的教学内容");
        }
    }

    public void requirePlatformAdministrator(Actor actor) {
        if (!actor.role().isPlatformAdministrator()) {
            throw new ForbiddenOperationException("此操作仅限平台管理员");
        }
    }

    public void requireRoot(Actor actor) {
        if (actor.role() != SystemRole.ROOT) {
            throw new ForbiddenOperationException("此操作仅限 root");
        }
    }
}
