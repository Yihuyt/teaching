package cn.utcy.teaching.blockcoding.engine;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.util.List;
import java.util.TreeMap;

/**
 * 积木 XML 的结构指纹:opcode 树 + 字段值 + 字面量,忽略属性顺序、xmlns、参数 id 拼法,
 * 以及套着积木的影子块里的默认值(编辑器里看不见)。
 */
final class XmlShape {
    private XmlShape() {
    }

    static String of(String xml) {
        Document document = BlockXml.parse(xml);
        StringBuilder sb = new StringBuilder();
        if (document != null) {
            write(document.getDocumentElement(), sb, 0);
        }
        return sb.toString();
    }

    private static void write(Element node, StringBuilder sb, int depth) {
        if (!"xml".equals(node.getTagName())) {
            sb.append("  ".repeat(depth)).append(node.getTagName());
            TreeMap<String, String> attrs = new TreeMap<>();
            NamedNodeMap attributes = node.getAttributes();
            for (int i = 0; i < attributes.getLength(); i++) {
                attrs.put(attributes.item(i).getNodeName(), attributes.item(i).getNodeValue());
            }
            attrs.remove("xmlns");
            attrs.remove("id");
            attrs.remove("x");
            attrs.remove("y");
            attrs.replaceAll((k, v) -> v.replaceAll("arg_[A-Za-z0-9_]+", "ARG"));
            attrs.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            String text = ownText(node);
            if (!text.isEmpty()) {
                sb.append(" text=").append("field".equals(node.getTagName()) ? text.toLowerCase(java.util.Locale.ROOT) : text);
            }
            sb.append('\n');
        }
        List<Element> children = BlockXml.children(node);
        boolean valueWithBlock = "value".equals(node.getTagName()) && children.stream().anyMatch(c -> "block".equals(c.getTagName()));
        for (Element child : children) {
            if (valueWithBlock && "shadow".equals(child.getTagName())) {
                sb.append("  ".repeat(depth + 1)).append("shadow(hidden) type=").append(child.getAttribute("type")).append('\n');
                continue;
            }
            write(child, sb, depth + 1);
        }
    }

    private static String ownText(Element node) {
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String text = child.getNodeValue();
                if (!text.trim().isEmpty()) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }
}
