package cn.utcy.teaching.platform.infrastructure;

import cn.utcy.teaching.platform.domain.PlatformSetting;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PlatformSettingMapper extends BaseMapper<PlatformSetting> {

    @Select("SELECT * FROM platform_setting WHERE id = #{id} FOR UPDATE")
    PlatformSetting selectForUpdate(@Param("id") long id);
}
