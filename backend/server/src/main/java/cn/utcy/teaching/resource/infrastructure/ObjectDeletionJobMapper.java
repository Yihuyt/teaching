package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface ObjectDeletionJobMapper extends BaseMapper<ObjectDeletionJob> {

    @Select("SELECT * FROM object_deletion_job WHERE id = #{id} FOR UPDATE")
    ObjectDeletionJob selectForUpdate(@Param("id") String id);
}
