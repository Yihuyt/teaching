package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemDraft;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemSampleCommand;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public record ProblemDraftRequest(
        @NotBlank @Size(max = 255) String title,
        @NotNull String statementMarkdown,
        @NotNull ProblemDifficulty difficulty,
        @NotNull @Min(100) @Max(30_000) Integer timeLimitMs,
        @NotNull @Min(16) @Max(2048) Integer memoryLimitMb,
        @NotNull @Min(1) @Max(65_536) Integer outputLimitKb,
        @NotEmpty Set<@NotNull ProgrammingLanguage> languages,
        @NotNull @Size(max = 50) List<@Valid ProblemSampleRequest> samples
) {
    public ProblemDraft toDraft() {
        return new ProblemDraft(
                title, statementMarkdown, difficulty, timeLimitMs, memoryLimitMb, outputLimitKb, languages,
                samples.stream().map(sample -> new ProblemSampleCommand(sample.input(), sample.output())).toList());
    }

    public record ProblemSampleRequest(
            @NotNull @Size(max = 1_048_576) String input,
            @NotNull @Size(max = 1_048_576) String output
    ) {
    }
}
