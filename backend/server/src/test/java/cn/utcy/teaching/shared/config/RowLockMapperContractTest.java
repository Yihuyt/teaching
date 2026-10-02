package cn.utcy.teaching.shared.config;

import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingProjectMapper;
import cn.utcy.teaching.courseware.infrastructure.CoursewareMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionAttemptMapper;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantMapper;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionMapper;
import cn.utcy.teaching.resource.infrastructure.CourseMaterialMapper;
import cn.utcy.teaching.identity.infrastructure.UserAccountMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import cn.utcy.teaching.notification.infrastructure.AnnouncementMapper;
import cn.utcy.teaching.platform.infrastructure.PlatformSettingMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RowLockMapperContractTest {

    @Test
    void programmingProblemMutationLockUsesSelectForUpdate() throws Exception {
        assertSelectForUpdate(
                ProgrammingProblemMapper.class.getMethod("selectForUpdate", long.class),
                "programming_problem");
    }

    @Test
    void courseMaterialMutationLockUsesSelectForUpdate() throws Exception {
        assertSelectForUpdate(
                CourseMaterialMapper.class.getMethod(
                        "selectForUpdate", long.class, long.class),
                "course_material");
    }

    @Test
    void userAccountMutationLockUsesSelectForUpdate() throws Exception {
        assertSelectForUpdate(
                UserAccountMapper.class.getMethod("selectForUpdate", long.class),
                "user_account");
    }

    @Test
    void aggregateMutationLocksUseSelectForUpdate() throws Exception {
        assertSelectForUpdate(
                CourseMapper.class.getMethod("selectForUpdate", long.class),
                "course");
        assertSelectForUpdate(
                CourseMapper.class.getMethod("selectByJoinCodeForUpdate", String.class),
                "course");
        assertSelectForUpdate(
                CourseQuestionMapper.class.getMethod("selectForUpdate", long.class, long.class),
                "course_question");
        assertSelectForUpdate(
                KnowledgeGraphMapper.class.getMethod(
                        "selectForUpdate", long.class, long.class),
                "knowledge_graph");
        assertSelectForUpdate(
                AnnouncementMapper.class.getMethod("selectForUpdate", long.class),
                "announcement");
        assertSelectForUpdate(
                PlatformSettingMapper.class.getMethod("selectForUpdate", long.class),
                "platform_setting");
    }

    /** 数据库不设外键后,删父行 / 写子行的服务都靠这些行锁串行化 */
    @Test
    void childRowOwnersLockWithSelectForUpdate() throws Exception {
        assertSelectForUpdate(TutorAssistantMapper.class.getMethod("selectForUpdate", long.class), "tutor_assistant");
        assertSelectForUpdate(TutorSessionMapper.class.getMethod("selectForUpdate", long.class), "tutor_session");
        assertSelectForUpdate(KnowledgeBaseMapper.class.getMethod("selectForUpdate", long.class), "knowledge_base");
        assertSelectForUpdate(KbDocumentMapper.class.getMethod("selectForUpdate", long.class), "knowledge_base_document");
        assertSelectForUpdate(KnowledgeGraphBuildMapper.class.getMethod("selectForUpdate", long.class), "knowledge_graph_build");
        assertSelectForUpdate(CoursewareMapper.class.getMethod("selectForUpdate", long.class), "courseware");
        assertSelectForUpdate(CourseQuestionAttemptMapper.class.getMethod("selectForUpdate", long.class), "course_question_attempt");
        assertSelectForUpdate(JudgeJobMapper.class.getMethod("selectForUpdate", String.class), "judge_job");
        assertSelectForUpdate(BlockCodingProjectMapper.class.getMethod("selectOwnedForUpdate", long.class, long.class), "blockcoding_project");
        assertSelectForUpdate(BlockCodingChatSessionMapper.class.getMethod("selectOwnedForUpdate", long.class, long.class), "blockcoding_chat_session");
    }

    private void assertSelectForUpdate(Method method, String table) {
        Select select = method.getAnnotation(Select.class);
        assertThat(select).isNotNull();
        assertThat(String.join(" ", select.value()).toLowerCase())
                .contains("from " + table)
                .contains("for update");
    }
}
