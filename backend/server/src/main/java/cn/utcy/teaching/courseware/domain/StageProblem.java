package cn.utcy.teaching.courseware.domain;

import java.util.List;

/**
 * 一条课件内容问题。forModel 带字段路径和改法,是生成重试时给模型的反馈;forTeacher 指明第几个内容块、错在哪。
 * forTeacher 为 null 的问题编辑界面不会产生,教师编辑时遇到即是前端缺陷或绕过界面的请求。
 */
public record StageProblem(String forModel, String forTeacher) {

    static StageProblem structural(String forModel) {
        return new StageProblem(forModel, null);
    }

    public boolean teacherFacing() {
        return forTeacher != null;
    }

    public static List<String> forModel(List<StageProblem> problems) {
        return problems.stream().map(StageProblem::forModel).toList();
    }
}
