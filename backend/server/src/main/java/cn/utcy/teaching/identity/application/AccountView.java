package cn.utcy.teaching.identity.application;

import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.CredentialState;

import java.time.Instant;

public record AccountView(
        long id,
        String username,
        SystemRole role,
        CredentialState credentialState,
        String displayName,
        boolean enabled,
        boolean mustResetPassword,
        Instant createdAt,
        Instant updatedAt
) {
}
