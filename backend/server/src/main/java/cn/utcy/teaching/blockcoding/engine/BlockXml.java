package cn.utcy.teaching.blockcoding.engine;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class BlockXml {
    private BlockXml() {
    }

    public static Document parse(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            return null;
        }
    }

    public static String serialize(Element element) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(writer));
            return writer.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("序列化积木 XML 失败", exception);
        }
    }

    public static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                out.add(element);
            }
        }
        return out;
    }

    public static List<Element> children(Element parent, String tag) {
        return children(parent).stream().filter(e -> tag.equals(e.getTagName())).toList();
    }

    public static Element child(Element parent, String tag) {
        return children(parent).stream().filter(e -> tag.equals(e.getTagName())).findFirst().orElse(null);
    }

    public static Element childNamed(Element parent, String tag, String name) {
        return children(parent).stream().filter(e -> tag.equals(e.getTagName()) && name.equals(e.getAttribute("name"))).findFirst().orElse(null);
    }

    /**
     * 名字为 name 的槽:<value name=…> 或 <statement name=…>。Blockly 把子脚本槽写成 statement,
     * scratch-vm 导出(编辑器快照、拖出的积木)一律写成 value,两种都要认
     */
    public static Element input(Element block, String name) {
        return children(block).stream()
                .filter(e -> ("value".equals(e.getTagName()) || "statement".equals(e.getTagName())) && name.equals(e.getAttribute("name")))
                .findFirst().orElse(null);
    }

    public static String fieldText(Element block, String name) {
        Element field = childNamed(block, "field", name);
        return field == null ? "" : field.getTextContent();
    }

    /** 值槽里菜单影子块的选项:<value name=X><shadow><field name=X>…</field></shadow></value> */
    public static String menuValue(Element block, String name) {
        Element value = childNamed(block, "value", name);
        Element shadow = value == null ? null : child(value, "shadow");
        return shadow == null ? "" : fieldText(shadow, name);
    }

    public static Element nextBlock(Element block) {
        Element next = child(block, "next");
        return next == null ? null : child(next, "block");
    }

    /** 元素的属性;没有返回 null(DOM 对缺失属性返回空串,分不清"没有"和"空") */
    public static String attribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }
}
