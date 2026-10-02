package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.programming.application.JudgeResultApplicationService.Completion;
import cn.utcy.teaching.programming.domain.JudgeResultDeadLetter;
import cn.utcy.teaching.programming.infrastructure.JudgeResultDeadLetterMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class JudgeDeadLetterApplicationService {
    private static final int LIST_LIMIT = 100;

    private final JudgeResultDeadLetterMapper deadLetters;
    private final JudgeResultApplicationService results;
    private final CurrentActor currentActor;
    private final OwnershipPolicy ownership;
    private final ObjectMapper objectMapper;

    public JudgeDeadLetterApplicationService(
            JudgeResultDeadLetterMapper deadLetters,
            JudgeResultApplicationService results,
            CurrentActor currentActor,
            OwnershipPolicy ownership,
            ObjectMapper objectMapper
    ) {
        this.deadLetters = deadLetters;
        this.results = results;
        this.currentActor = currentActor;
        this.ownership = ownership;
        this.objectMapper = objectMapper;
    }

    public record DeadLetterView(
            long id,
            String streamRecordId,
            @Schema(nullable = true) String jobId,
            String errorType,
            String errorMessage,
            Instant createdAt
    ) {
    }

    public record ReplayResult(String outcome) {
    }

    @Transactional(readOnly = true)
    public List<DeadLetterView> list() {
        ownership.requirePlatformAdministrator(currentActor.require());
        return deadLetters.selectList(new LambdaQueryWrapper<JudgeResultDeadLetter>()
                        .orderByDesc(JudgeResultDeadLetter::getId)
                        .last("LIMIT " + LIST_LIMIT))
                .stream()
                .map(letter -> new DeadLetterView(
                        letter.getId(),
                        letter.getStreamRecordId(),
                        letter.getJobId(),
                        letter.getErrorType(),
                        letter.getErrorMessage(),
                        letter.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ReplayResult replay(long deadLetterId) {
        ownership.requirePlatformAdministrator(currentActor.require());
        JudgeResultDeadLetter letter = requireLetter(deadLetterId);
        JudgeResult result;
        try {
            result = objectMapper.readValue(letter.getPayload(), JudgeResult.class);
        } catch (Exception exception) {
            throw new ConflictException("死信 payload 无法解析为判题结果，只能丢弃");
        }
        Completion completion = results.complete(result);
        deadLetters.deleteById(deadLetterId);
        return new ReplayResult(completion.outcome().name());
    }

    @Transactional
    public void discard(long deadLetterId) {
        ownership.requirePlatformAdministrator(currentActor.require());
        requireLetter(deadLetterId);
        deadLetters.deleteById(deadLetterId);
    }

    private JudgeResultDeadLetter requireLetter(long deadLetterId) {
        JudgeResultDeadLetter letter = deadLetters.selectById(deadLetterId);
        if (letter == null) {
            throw new NotFoundException("判题死信不存在");
        }
        return letter;
    }
}
