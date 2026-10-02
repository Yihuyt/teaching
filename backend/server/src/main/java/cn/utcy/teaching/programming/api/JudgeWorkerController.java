package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.programming.infrastructure.JudgeWorkerRegistry;
import cn.utcy.teaching.programming.infrastructure.JudgeWorkerRegistry.JudgeWorkerView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/judge/workers")
public class JudgeWorkerController {
    private final JudgeWorkerRegistry registry;

    public JudgeWorkerController(JudgeWorkerRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<JudgeWorkerView> list() {
        return registry.workers();
    }
}
