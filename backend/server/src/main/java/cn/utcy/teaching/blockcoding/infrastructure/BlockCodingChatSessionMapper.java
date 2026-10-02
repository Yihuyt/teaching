package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlockCodingChatSessionMapper extends BaseMapper<BlockCodingChatSession> {

    @Select("""
            SELECT *
            FROM blockcoding_chat_session
            WHERE id = #{id}
              AND account_id = #{accountId}
            FOR UPDATE
            """)
    BlockCodingChatSession selectOwnedForUpdate(
            @Param("id") long id,
            @Param("accountId") long accountId);

    @Select("""
            SELECT *
            FROM blockcoding_chat_session
            WHERE id = #{id}
              AND account_id = #{accountId}
            """)
    BlockCodingChatSession selectOwned(
            @Param("id") long id,
            @Param("accountId") long accountId);

    @Select("""
            SELECT *
            FROM blockcoding_chat_session
            WHERE project_id = #{projectId}
              AND account_id = #{accountId}
            ORDER BY updated_at DESC
            """)
    List<BlockCodingChatSession> selectByProject(
            @Param("projectId") long projectId,
            @Param("accountId") long accountId);
}
