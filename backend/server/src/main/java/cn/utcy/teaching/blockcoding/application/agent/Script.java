package cn.utcy.teaching.blockcoding.application.agent;

import java.util.List;

/**
 * 登记簿里的一段脚本。blockId 是它在编辑器里的顶层积木 id:作品里原有的是抓快照时的 id,本轮写入的是插入后编辑器给的 id,
 * preexisting = 开局就有的(作品里原有),本轮没动过。
 * variables … definedProcedures 是这段脚本自己创建 / 定义的名字。
 */
public record Script(int id, String sprite, String code, String xml, int blockCount,
                     List<String> variables, List<String> lists,
                     List<String> localVariables, List<String> localLists,
                     List<String> broadcasts, List<String> definedProcedures,
                     String blockId, boolean preexisting) {
    public static Script preexisting(int id, String sprite, String code, String xml, int blockCount,
                                     List<String> definedProcedures, String blockId) {
        return new Script(id, sprite, code, xml, blockCount, List.of(), List.of(), List.of(), List.of(),
                List.of(), definedProcedures, blockId, true);
    }
}
