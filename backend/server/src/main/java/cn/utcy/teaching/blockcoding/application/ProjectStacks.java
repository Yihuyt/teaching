package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.application.agent.Script;
import cn.utcy.teaching.blockcoding.engine.BlockXml;
import cn.utcy.teaching.blockcoding.engine.ProcedureXml;
import cn.utcy.teaching.blockcoding.engine.SbToText;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ProjectStacks {
    private final SbToText sbToText;

    public ProjectStacks(SbToText sbToText) {
        this.sbToText = sbToText;
    }

    public List<Script> of(HarvestPayload harvest) {
        List<Script> out = new ArrayList<>();
        if (harvest == null || harvest.workspaceXml() == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : harvest.workspaceXml().entrySet()) {
            for (Element top : topLevelBlocks(entry.getValue())) {
                Script script = stack(out.size() + 1, entry.getKey(), top);
                if (script != null) {
                    out.add(script);
                }
            }
        }
        return out;
    }

    public Script fromXml(String sprite, String xml) {
        List<Element> tops = topLevelBlocks(xml);
        return tops.isEmpty() ? null : stack(0, sprite, tops.getFirst());
    }

    private Script stack(int id, String sprite, Element top) {
        String xml = "<xml>" + BlockXml.serialize(top) + "</xml>";
        String text = sbToText.toText(xml);
        if (text == null || text.isBlank() || "No blocks found.".equals(text)) {
            return null;
        }
        String blockId = top.getAttribute("id");
        if (blockId.isBlank()) {
            return null;
        }
        int blockCount = top.getElementsByTagName("block").getLength() + 1;
        List<String> procedures = ProcedureXml.definitions(xml).stream().map(Procedure::proccode).distinct().toList();
        return Script.preexisting(id, sprite, text.strip(), xml, blockCount, procedures, blockId);
    }

    /** 快照 XML 来自编辑器本身;解析失败只意味着这个角色的现有脚本不进登记簿 */
    private static List<Element> topLevelBlocks(String workspaceXml) {
        Document document = BlockXml.parse(workspaceXml);
        return document == null ? List.of() : BlockXml.children(document.getDocumentElement(), "block");
    }
}
