package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingProblemProvenance;
import cn.utcy.teaching.programming.domain.ProgrammingProblemSample;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.JudgeStreamReclaimer;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgrammingPurgerTest {
    private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
    private final ProgrammingProblemSampleMapper samples = mock(ProgrammingProblemSampleMapper.class);
    private final ProgrammingProblemProvenanceMapper provenance = mock(ProgrammingProblemProvenanceMapper.class);
    private final ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
    private final SubmissionCaseResultMapper caseResults = mock(SubmissionCaseResultMapper.class);
    private final JudgeJobMapper jobs = mock(JudgeJobMapper.class);
    private final ObjectStorageDeletionQueue deletionQueue = mock(ObjectStorageDeletionQueue.class);
    private final ProgrammingPurger purger = new ProgrammingPurger(
            problems, samples, provenance, submissions, caseResults, jobs, mock(JudgeStreamReclaimer.class),
            deletionQueue, new TestcaseOssProperties("testcases"));

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProgrammingProblem.class);
        TableInfoHelper.initTableInfo(assistant, ProgrammingProblemSample.class);
        TableInfoHelper.initTableInfo(assistant, ProgrammingProblemProvenance.class);
        TableInfoHelper.initTableInfo(assistant, ProgrammingSubmission.class);
        TableInfoHelper.initTableInfo(assistant, SubmissionCaseResult.class);
        TableInfoHelper.initTableInfo(assistant, JudgeJob.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesChildRowsBeforeTheProblemRow() {
        ProgrammingSubmission submission = mock(ProgrammingSubmission.class);
        when(submission.getId()).thenReturn(31L);
        when(submissions.selectList(any(Wrapper.class))).thenReturn(List.of(submission));
        when(jobs.selectList(any(Wrapper.class))).thenReturn(List.of());

        purger.purgeProblems(List.of(8L));

        InOrder order = inOrder(caseResults, jobs, submissions, samples, provenance, problems);
        order.verify(caseResults).delete(any(Wrapper.class));
        order.verify(jobs).delete(any(Wrapper.class));
        order.verify(submissions).delete(any(Wrapper.class));
        order.verify(samples).delete(any(Wrapper.class));
        order.verify(provenance).delete(any(Wrapper.class));
        order.verify(problems).delete(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void problemWithoutSubmissionsSkipsSubmissionTables() {
        when(submissions.selectList(any(Wrapper.class))).thenReturn(List.of());

        purger.purgeProblems(List.of(8L));

        verify(caseResults, never()).delete(any(Wrapper.class));
        verify(jobs, never()).delete(any(Wrapper.class));
        verify(submissions, never()).delete(any(Wrapper.class));
        verify(problems).delete(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enqueuesTestcaseAndPackageObjectsOfTheProblem() {
        ProgrammingProblem problem = mock(ProgrammingProblem.class);
        when(problem.getId()).thenReturn(8L);
        when(problem.hasConfirmedTestcase()).thenReturn(true);
        when(problem.getTestcaseSha256()).thenReturn("abc");
        when(problems.selectList(any(Wrapper.class))).thenReturn(List.of(problem));
        ProgrammingProblemProvenance packageMetadata = mock(ProgrammingProblemProvenance.class);
        when(packageMetadata.getSourcePackageObjectKey()).thenReturn("packages/8.zip");
        when(provenance.selectList(any(Wrapper.class))).thenReturn(List.of(packageMetadata));
        when(submissions.selectList(any(Wrapper.class))).thenReturn(List.of());

        purger.purgeProblems(List.of(8L));

        verify(deletionQueue).enqueue("testcases", TestcaseObjectKey.of(8L, "abc"));
        verify(deletionQueue).enqueue("testcases", "packages/8.zip");
    }

    @Test
    void nothingHappensForNoProblems() {
        purger.purgeProblems(List.of());

        verify(submissions, never()).selectList(any());
        verify(problems, never()).delete(any());
    }
}
