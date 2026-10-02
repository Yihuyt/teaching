package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.identity.domain.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("SELECT * FROM user_account WHERE id = #{id} FOR UPDATE")
    UserAccount selectForUpdate(@Param("id") long id);
}
