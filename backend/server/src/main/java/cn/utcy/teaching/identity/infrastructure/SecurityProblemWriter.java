package cn.utcy.teaching.identity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import cn.utcy.teaching.shared.web.RequestIdFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

final class SecurityProblemWriter {

    private static final URI TYPE = URI.create("urn:teaching:problem:security-error");

    private SecurityProblemWriter() {
    }

    static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String title,
            String detail
    ) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(TYPE);
        problem.setTitle(title);
        String requestId = RequestIdFilter.current();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
