package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.domain.BlockCodingProject;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingProjectMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingStorage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BlockCodingProjectPurger {
    private final BlockCodingProjectMapper projects;
    private final BlockCodingStorage storage;
    private final BlockCodingChatPurger chats;

    BlockCodingProjectPurger(BlockCodingProjectMapper projects, BlockCodingStorage storage, BlockCodingChatPurger chats) {
        this.projects = projects;
        this.storage = storage;
        this.chats = chats;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(BlockCodingProject project) {
        if (project.getOssObjectKey() != null) {
            storage.enqueueDeletion(project.getOssObjectKey());
        }
        chats.purgeSessionsOfProject(project.getId());
        projects.deleteById(project.getId());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeCourse(long courseId) {
        for (BlockCodingProject project : projects.selectList(new LambdaQueryWrapper<BlockCodingProject>()
                .eq(BlockCodingProject::getCourseId, courseId))) {
            purge(project);
        }
    }
}
