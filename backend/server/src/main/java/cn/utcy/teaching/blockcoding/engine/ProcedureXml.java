package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;

/**
 * 从积木 XML 里读出自定义积木的定义(procedures_prototype 影子块的 mutation):名字、参数名、参数 id。
 * 参数 id 必须原样带走:调用积木的输入按定义的 id 对齐,id 对不上时参数在运行时全部取默认值(空),程序静默出错。
 */
public final class ProcedureXml {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProcedureXml() {
    }

    public static List<Procedure> definitions(String xml) {
        List<Procedure> out = new ArrayList<>();
        Document document = BlockXml.parse(xml);
        if (document == null) {
            return out;
        }
        NodeList shadows = document.getElementsByTagName("shadow");
        for (int i = 0; i < shadows.getLength(); i++) {
            Element shadow = (Element) shadows.item(i);
            if (!"procedures_prototype".equals(shadow.getAttribute("type"))) {
                continue;
            }
            Element mutation = BlockXml.child(shadow, "mutation");
            if (mutation == null || !mutation.hasAttribute("proccode")) {
                continue;
            }
            out.add(new Procedure(mutation.getAttribute("proccode"),
                    strings(mutation.getAttribute("argumentnames")), strings(mutation.getAttribute("argumentids"))));
        }
        return out;
    }

    private static List<String> strings(String jsonArray) {
        List<String> out = new ArrayList<>();
        if (jsonArray == null || jsonArray.isBlank()) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(jsonArray);
            if (node.isArray()) {
                node.forEach(item -> out.add(item.asText()));
            }
        } catch (Exception ignored) {
            // 编辑器写的 mutation 不会是坏 JSON;坏了就当没有参数
        }
        return out;
    }
}
