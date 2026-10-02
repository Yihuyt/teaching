package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.infrastructure.ProblemPackageReader;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.TestcaseObjectValidator;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.programming.infrastructure.TransactionalTestcaseObjectWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemPackageImportApplicationServiceTest {
    @Test
    void nonManagerCannotImportIntoCourse() {
        Actor student = new Actor(9L, "student", SystemRole.STUDENT);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(student);
        CourseAccess courseAccess = mock(CourseAccess.class);
        doThrow(new ForbiddenOperationException("无权管理该课程"))
                .when(courseAccess).requireManagementAccess(6L, student);
        ProblemPackageReader packageReader = mock(ProblemPackageReader.class);
        ProblemPackageImportApplicationService service =
                new ProblemPackageImportApplicationService(
                        packageReader,
                        mock(TestcasePackageWriter.class),
                        mock(TestcasePackageReader.class),
                        mock(ProgrammingProblemMapper.class),
                        mock(ProgrammingProblemSampleMapper.class),
                        mock(ProgrammingProblemProvenanceMapper.class),
                        mock(ProgrammingProblemApplicationService.class),
                        currentActor,
                        new ObjectMapper(),
                        new TestcaseOssProperties("testcases"),
                        mock(ObjectStorageIntegrityVerifier.class),
                        mock(TestcaseObjectValidator.class),
                        mock(TransactionalTestcaseObjectWriter.class),
                        mock(TransactionTemplate.class),
                        courseAccess);

        assertThatThrownBy(() -> service.importPackage(6L, mock(MultipartFile.class), ProblemDifficulty.EASY))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("无权管理该课程");

        verify(packageReader, never()).read(any(), any());
    }
}
