package cn.utcy.teaching.blockcoding.application;

import java.util.List;
import java.util.Map;

/**
 * 编辑器通信桥 context/harvest 的工作区快照。字段名与桥协议逐字对齐
 * (scratch-editor 仓库 src/lib/teaching-bridge/index.js 的 harvestContext)，
 * 前端原样转发、后端按此反序列化——改动需与桥同步。
 */
public record HarvestPayload(
        List<SpriteContext> sprites,
        List<String> globalVariables,
        List<String> globalLists,
        List<String> broadcasts,
        Map<String, List<ProcedureSignature>> procedures,
        String currentSprite,
        Map<String, String> workspaceXml
) {

    public record SpriteContext(
            String name,
            boolean isStage,
            List<String> costumes,
            List<String> sounds,
            List<String> localVariables,
            List<String> localLists
    ) {
    }

    /** argumentids 必须原样保留：编译器用它对齐工作区已有自定义积木的参数 id */
    public record ProcedureSignature(
            String proccode,
            List<String> argumentnames,
            List<String> argumentids,
            List<String> argumentdefaults
    ) {
    }
}
