package cn.utcy.teaching.judgecontract;

import java.math.BigDecimal;
import java.util.List;

public record TestcaseManifest(
        int schemaVersion,
        long problemId,
        List<TestcaseDefinition> cases
) {

    public record TestcaseDefinition(
            String id,
            String input,
            String output,
            String inputSha256,
            String outputSha256,
            BigDecimal score
    ) {
    }
}
