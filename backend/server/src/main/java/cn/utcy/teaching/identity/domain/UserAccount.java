package cn.utcy.teaching.identity.domain;

import cn.utcy.teaching.shared.actor.SystemRole;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("user_account")
public class UserAccount {

    @TableId
    private Long id;
    private String username;
    private String passwordHash;
    private CredentialState credentialState;
    private SystemRole role;
    private String displayName;
    private boolean enabled;
    private boolean mustResetPassword;
    private Instant createdAt;
    private Instant updatedAt;

    protected UserAccount() {
    }

    public UserAccount(
            Long id,
            String username,
            String passwordHash,
            CredentialState credentialState,
            SystemRole role,
            String displayName,
            boolean enabled,
            boolean mustResetPassword,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.credentialState = credentialState;
        this.role = role;
        this.displayName = displayName;
        this.enabled = enabled;
        this.mustResetPassword = mustResetPassword;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static UserAccount create(
            String username,
            String passwordHash,
            SystemRole role,
            String displayName,
            boolean mustResetPassword
    ) {
        Instant now = Instant.now();
        return new UserAccount(null, username, passwordHash, CredentialState.ACTIVE, role, displayName, true,
                mustResetPassword, now, now);
    }

    public void changePassword(String encodedPassword, boolean mustReset) {
        passwordHash = encodedPassword;
        credentialState = CredentialState.ACTIVE;
        mustResetPassword = mustReset;
        updatedAt = Instant.now();
    }

    public void changeProfile(String displayName) {
        this.displayName = displayName;
        updatedAt = Instant.now();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public CredentialState getCredentialState() {
        return credentialState;
    }

    public SystemRole getRole() {
        return role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isMustResetPassword() {
        return mustResetPassword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
