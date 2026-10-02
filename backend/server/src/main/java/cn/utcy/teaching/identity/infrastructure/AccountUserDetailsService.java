package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.ActorPrincipal;
import cn.utcy.teaching.identity.domain.UserAccount;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountMapper mapper;

    AccountUserDetailsService(UserAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        UserAccount account = mapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, normalized));
        if (account == null) {
            throw new UsernameNotFoundException("账户不存在");
        }
        if (account.getCredentialState()
                == cn.utcy.teaching.identity.domain.CredentialState.RESET_REQUIRED) {
            throw new CredentialsExpiredException("账户需要由管理员设置初始密码");
        }
        return new ActorPrincipal(
                account.getId(),
                account.getUsername(),
                account.getPasswordHash(),
                account.getRole(),
                account.isEnabled(),
                account.isMustResetPassword());
    }
}
