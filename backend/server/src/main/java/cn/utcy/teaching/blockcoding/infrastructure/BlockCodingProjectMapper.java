package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.blockcoding.domain.BlockCodingProject;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BlockCodingProjectMapper extends BaseMapper<BlockCodingProject> {

    @Select("""
            SELECT *
            FROM blockcoding_project
            WHERE id = #{id}
              AND owner_account_id = #{ownerAccountId}
            FOR UPDATE
            """)
    BlockCodingProject selectOwnedForUpdate(
            @Param("id") long id,
            @Param("ownerAccountId") long ownerAccountId);

    @Select("""
            SELECT *
            FROM blockcoding_project
            WHERE id = #{id}
              AND owner_account_id = #{ownerAccountId}
            """)
    BlockCodingProject selectOwned(
            @Param("id") long id,
            @Param("ownerAccountId") long ownerAccountId);

    @Select("""
            SELECT *
            FROM blockcoding_project
            WHERE course_id = #{courseId}
              AND owner_account_id = #{ownerAccountId}
            ORDER BY updated_at DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<BlockCodingProject> selectPageByCourseAndOwner(
            @Param("courseId") long courseId,
            @Param("ownerAccountId") long ownerAccountId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            SELECT COUNT(*)
            FROM blockcoding_project
            WHERE course_id = #{courseId}
              AND owner_account_id = #{ownerAccountId}
            """)
    long countByCourseAndOwner(@Param("courseId") long courseId, @Param("ownerAccountId") long ownerAccountId);
}
