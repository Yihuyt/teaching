package cn.utcy.teaching.course.infrastructure;

import cn.utcy.teaching.course.domain.CourseMember;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CourseMemberMapper extends BaseMapper<CourseMember> {
}
