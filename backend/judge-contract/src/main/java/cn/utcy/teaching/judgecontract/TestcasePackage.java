package cn.utcy.teaching.judgecontract;

import java.math.BigDecimal;
import java.util.List;

public record TestcasePackage(List<Testcase> cases) {

    public record Testcase(
            String id,
            byte[] input,
            byte[] expectedOutput,
            BigDecimal score
    ) {
    }
}
