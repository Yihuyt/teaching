package cn.utcy.teaching.identity.application;

import cn.utcy.teaching.shared.actor.SystemRole;

import java.util.Optional;

public interface AccountDirectory {

    AccountSummary require(long accountId);

    AccountSummary requireByUsername(String username);

    Optional<AccountSummary> findByUsername(String username);

    record AccountSummary(long id, String username, String displayName, SystemRole role, boolean enabled) {

        public String name() {
            return displayName == null || displayName.isBlank() ? username : displayName;
        }
    }
}
