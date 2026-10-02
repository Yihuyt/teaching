package cn.utcy.teaching.blockcoding.application.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.List;

final class ToolDefinitions {
    private static final String ONE_SCRIPT = "一段脚本 = 一个帽子积木开头的完整脚本(自定义积木的 define 也算),只做一件事;长了就拆成几段,用广播或自定义积木串起来。";

    private ToolDefinitions() {
    }

    static List<ToolSpecification> of(Workspace workspace, String replyGuidance) {
        String howToReply = replyGuidance == null || replyGuidance.isBlank() ? "" : "\n回复的写法要求:\n" + replyGuidance.strip() + "\n";
        JsonArraySchema names = JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build();
        List<ToolSpecification> all = List.of(
                tool(ScratchTools.READ_SKILL, "读一份技能的正文:该类积木的精确写法表和已编译通过的片段。讲或写某一类积木前先读。",
                        JsonObjectSchema.builder().addStringProperty("name", "技能名,见系统提示里的技能清单").required("name")),
                tool(ScratchTools.WRITE_SCRIPT, "把一段脚本编译并登记为本轮对作品的改动(final_answer 时和其他改动一起写进作品)。通过就返回新建的变量;不通过返回带行号的错误,改好再写。" + ONE_SCRIPT,
                        JsonObjectSchema.builder()
                                .addStringProperty("sprite", "脚本所属角色名,必须是作品里已有的角色(或 Stage;舞台不能移动、画画、说话、换造型)")
                                .addStringProperty("code", "scratchblocks 文本,一行一个积木")
                                .addIntegerProperty("replaces", "可选:要改写的脚本编号(作品里已有的脚本和本轮写的都有编号);给了就用这段取代它,不给就是新增一段")
                                .addProperty("localVariables", JsonArraySchema.builder().items(JsonStringSchema.builder().build())
                                        .description("可选:这段脚本新建的变量里,哪些是仅当前角色的(克隆体各自一份)。变量默认是全局的,和这段脚本属于哪个角色无关;只有写在这里的才归当前角色").build())
                                .addProperty("localLists", JsonArraySchema.builder().items(JsonStringSchema.builder().build())
                                        .description("可选:这段脚本新建的列表里,哪些是仅当前角色的;没写的都是全局的,和脚本属于哪个角色无关").build())
                                .required("sprite", "code")),
                tool(ScratchTools.LIST_PROJECT, "列出作品现在的全部内容:每个角色的造型、声音、私有变量、私有列表、自定义积木和脚本(编号、首行、本轮状态),以及全局变量、列表、广播。", JsonObjectSchema.builder()),
                tool(ScratchTools.CREATE_SPRITE, "登记一个新角色(final_answer 时建进作品,造型和声音复制自作品里的第一个角色,用户之后可以自己换)。"
                                + "任务需要的角色作品里没有时用它,不要为此反问;角色名用用户说的名字或任务里的名字。",
                        JsonObjectSchema.builder().addStringProperty("name", "新角色的名字").required("name")),
                tool(ScratchTools.DELETE_SCRIPT, "登记删除一段脚本(作品里已有的或本轮写的),按编号,final_answer 时从作品里删掉。要改一段脚本不要删了重写,直接 write_script 带 replaces。",
                        JsonObjectSchema.builder().addIntegerProperty("id", "要删的脚本编号,见「作品」一节或 list_project").required("id")),
                tool(ScratchTools.DELETE_SPRITE, "删掉一个角色连同它的全部脚本和私有变量。作品里原有的角色删了以后,它的造型、声音回退时恢复不了。",
                        JsonObjectSchema.builder().addStringProperty("name", "要删的角色名").required("name")),
                tool(ScratchTools.DELETE_VARIABLE, "删掉一个没有任何脚本再用的变量(全局的或某个角色私有的);还有脚本在用会拒绝并列出是哪几段。",
                        JsonObjectSchema.builder().addStringProperty("name", "变量名")
                                .addStringProperty("sprite", "可选:同名变量同时是全局的和某些角色私有的时,指明删哪个角色私有的那份;Stage 指全局的那份").required("name")),
                tool(ScratchTools.DELETE_LIST, "删掉一个没有任何脚本再用的列表;还有脚本在用会拒绝并列出是哪几段。",
                        JsonObjectSchema.builder().addStringProperty("name", "列表名")
                                .addStringProperty("sprite", "可选:同名列表同时是全局的和某些角色私有的时,指明删哪个角色私有的那份;Stage 指全局的那份").required("name")),
                tool(ScratchTools.FINAL_ANSWER, "把结果告诉用户并结束本轮。text 是用户唯一能看到的文字。" + howToReply
                                + "讲到积木时,把那几行积木按 scratchblocks 记法写在 ```scratchblocks 围栏里就行——"
                                + "用户看到的不是文本,是平台自动渲染出来的积木图,所以不用再用文字描述积木长什么样。",
                        JsonObjectSchema.builder().addStringProperty("text", "给用户看的回复,简体中文;可含 ```scratchblocks 围栏的积木片段").required("text")),
                tool(ScratchTools.ASK_USER, "向用户提一个问题并结束本轮(比如作品里没有要用的角色、需求怎么做都行时二选一)。"
                                + "只在不问就会做错时用,一次只问一件事,能给选项就给选项;用户的回答会作为下一条消息到达。",
                        JsonObjectSchema.builder()
                                .addStringProperty("question", "问题正文,简短")
                                .addProperty("options", JsonArraySchema.builder().description("可选:供用户点选的几个选项")
                                        .items(JsonStringSchema.builder().description("一个选项").build()).build())
                                .required("question")));
        return all.stream().filter(spec -> workspace.tools().contains(spec.name())).toList();
    }

    private static ToolSpecification tool(String name, String description, JsonObjectSchema.Builder parameters) {
        return ToolSpecification.builder().name(name).description(description)
                .parameters(parameters.additionalProperties(false).build()).build();
    }
}
