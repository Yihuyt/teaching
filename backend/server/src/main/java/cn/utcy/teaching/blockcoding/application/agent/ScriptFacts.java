package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.engine.BlockXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从一段脚本编译好的 XML 里读出的事实:用了哪些积木(opcode)、给哪些变量赋过值、往哪些列表放过内容、清空过哪些列表、
 * 克隆了谁、broadcast 后面是不是紧跟 stop all。收尾检查与舞台限制都看这里,不用正则猜积木文本。
 */
record ScriptFacts(String hatOpcode, List<String> opcodes, Set<String> writtenVariables, Set<String> addedLists,
                   Set<String> writtenLists, Set<String> clearedLists, Set<String> cloneTargets, boolean broadcastThenStopAll,
                   Set<String> calledProcedures, Set<String> variables, Set<String> lists) {
    private static final Set<String> VARIABLE_WRITERS = Set.of("data_setvariableto", "data_changevariableby");
    private static final Set<String> LIST_WRITERS = Set.of("data_addtolist", "data_insertatlist", "data_replaceitemoflist");
    /** 舞台没有的能力:运动、画笔、说话 / 造型 / 大小、碰撞与距离、克隆、被点击 */
    private static final Set<String> SPRITE_ONLY = Set.of("looks_say", "looks_sayforsecs", "looks_think", "looks_thinkforsecs",
            "looks_show", "looks_hide", "looks_changesizeby", "looks_setsizeto", "looks_switchcostumeto", "looks_nextcostume",
            "looks_size", "looks_costumenumbername", "sensing_touchingobject", "sensing_touchingcolor", "sensing_coloristouchingcolor",
            "sensing_distanceto", "control_create_clone_of", "control_delete_this_clone", "control_start_as_clone",
            "event_whenthisspriteclicked");

    /** 读不出来的 XML 按"没有事实"处理 */
    static ScriptFacts of(String xml, String ownSprite) {
        List<String> opcodes = new ArrayList<>();
        Set<String> writtenVariables = new LinkedHashSet<>();
        Set<String> addedLists = new LinkedHashSet<>();
        Set<String> writtenLists = new LinkedHashSet<>();
        Set<String> clearedLists = new LinkedHashSet<>();
        Set<String> cloneTargets = new LinkedHashSet<>();
        Set<String> calledProcedures = new LinkedHashSet<>();
        Set<String> variables = new LinkedHashSet<>();
        Set<String> lists = new LinkedHashSet<>();
        boolean broadcastThenStop = false;
        String hat = null;
        Document document = BlockXml.parse(xml);
        if (document != null) {
            NodeList blocks = document.getElementsByTagName("block");
            for (int i = 0; i < blocks.getLength(); i++) {
                Element block = (Element) blocks.item(i);
                String opcode = block.getAttribute("type");
                opcodes.add(opcode);
                if (hat == null && block.getParentNode() == document.getDocumentElement()) {
                    hat = opcode;
                }
                String variable = BlockXml.fieldText(block, "VARIABLE");
                if (!variable.isEmpty()) {
                    variables.add(variable);
                }
                String list = BlockXml.fieldText(block, "LIST");
                if (!list.isEmpty()) {
                    lists.add(list);
                }
                if (VARIABLE_WRITERS.contains(opcode)) {
                    writtenVariables.add(BlockXml.fieldText(block, "VARIABLE"));
                }
                if (LIST_WRITERS.contains(opcode)) {
                    writtenLists.add(BlockXml.fieldText(block, "LIST"));
                }
                if ("data_addtolist".equals(opcode)) {
                    addedLists.add(BlockXml.fieldText(block, "LIST"));
                }
                if ("data_deletealloflist".equals(opcode)) {
                    clearedLists.add(BlockXml.fieldText(block, "LIST"));
                }
                if ("procedures_call".equals(opcode)) {
                    Element mutation = BlockXml.child(block, "mutation");
                    if (mutation != null && mutation.hasAttribute("proccode")) {
                        calledProcedures.add(mutation.getAttribute("proccode"));
                    }
                }
                if ("control_create_clone_of".equals(opcode)) {
                    String target = BlockXml.menuValue(block, "CLONE_OPTION");
                    cloneTargets.add("_myself_".equals(target) ? ownSprite : target);
                }
                Element next = BlockXml.nextBlock(block);
                if ("event_broadcast".equals(opcode) && next != null && "control_stop".equals(next.getAttribute("type"))
                        && "all".equals(BlockXml.fieldText(next, "STOP_OPTION"))) {
                    broadcastThenStop = true;
                }
            }
        }
        return new ScriptFacts(hat, List.copyOf(opcodes), writtenVariables, addedLists, writtenLists, clearedLists,
                cloneTargets, broadcastThenStop, calledProcedures, variables, lists);
    }

    String spriteOnlyCategory() {
        for (String opcode : opcodes) {
            if (opcode.startsWith("motion_") || opcode.startsWith("pen_") || SPRITE_ONLY.contains(opcode)) {
                return opcode.substring(0, opcode.indexOf('_'));
            }
        }
        return null;
    }

    boolean startsAsClone() {
        return "control_start_as_clone".equals(hatOpcode);
    }

    boolean startsWithGreenFlag() {
        return "event_whenflagclicked".equals(hatOpcode);
    }
}
