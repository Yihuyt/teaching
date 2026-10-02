package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlockCodingChatMessageMapper extends BaseMapper<BlockCodingChatMessage> {

    @Select("""
            SELECT *
            FROM blockcoding_chat_message
            WHERE session_id = #{sessionId}
            ORDER BY seq
            """)
    List<BlockCodingChatMessage> selectBySession(@Param("sessionId") long sessionId);
}
