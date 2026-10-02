package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeResultDeadLetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 死信落库用独立事务:写入提交成功后监听器才推进 checkpoint;
 * checkpoint 重放撞唯一键视为已记录。写入本身失败按瞬时故障处理(调用方不推进)。
 */
@Component
class JudgeResultDeadLetterStore {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultDeadLetterStore.class);

    private final JudgeResultDeadLetterMapper deadLetters;

    JudgeResultDeadLetterStore(JudgeResultDeadLetterMapper deadLetters) {
        this.deadLetters = deadLetters;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String streamRecordId, String jobId, String payload, RuntimeException cause) {
        try {
            deadLetters.insert(JudgeResultDeadLetter.of(streamRecordId, jobId, payload, cause));
        } catch (DuplicateKeyException exception) {
            log.info("判题结果死信已存在，跳过重复写入：{}", streamRecordId);
        }
    }
}
