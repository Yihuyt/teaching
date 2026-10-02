package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.programming.application.JudgeDeadLetterApplicationService;
import cn.utcy.teaching.programming.application.JudgeDeadLetterApplicationService.DeadLetterView;
import cn.utcy.teaching.programming.application.JudgeDeadLetterApplicationService.ReplayResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/judge/dead-letters")
public class JudgeAdminController {
    private final JudgeDeadLetterApplicationService deadLetters;

    public JudgeAdminController(JudgeDeadLetterApplicationService deadLetters) {
        this.deadLetters = deadLetters;
    }

    @GetMapping
    public List<DeadLetterView> list() {
        return deadLetters.list();
    }

    @PostMapping("/{deadLetterId}/replays")
    public ReplayResult replay(@PathVariable long deadLetterId) {
        return deadLetters.replay(deadLetterId);
    }

    @DeleteMapping("/{deadLetterId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable long deadLetterId) {
        deadLetters.discard(deadLetterId);
    }
}
