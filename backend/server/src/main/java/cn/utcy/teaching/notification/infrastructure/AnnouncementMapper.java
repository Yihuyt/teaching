package cn.utcy.teaching.notification.infrastructure;

import cn.utcy.teaching.notification.domain.Announcement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    @Select("SELECT * FROM announcement WHERE id = #{id} FOR UPDATE")
    Announcement selectForUpdate(@Param("id") long id);
}
