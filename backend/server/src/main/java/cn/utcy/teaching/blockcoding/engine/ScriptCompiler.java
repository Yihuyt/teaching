package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.BlockTable.Arg;
import cn.utcy.teaching.blockcoding.engine.BlockTable.ArgKind;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Shape;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Template;
import cn.utcy.teaching.blockcoding.engine.SbNode.Block;
import cn.utcy.teaching.blockcoding.engine.SbNode.Input;
import cn.utcy.teaching.blockcoding.engine.SbNode.Label;
import cn.utcy.teaching.blockcoding.engine.SbNode.Script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * scratchblocks 文本 → 编辑器积木 XML。三步:scratchblocks 解析成树(带行号)→ 按模板绑槽 → 按模板写 XML。
 * 所有"什么是合法积木"的知识都在 {@link BlockTable} 里;这里只做连接和三种树里没有的东西:
 * 读变量、自定义积木(定义与调用)、三元以上的运算符链。
 * <p>
 * 错误只有一种形状(行号 + 一句话 + 候选),只从两处冒出来:没有模板能匹配这一行、模板匹配了但某个槽绑不上。
 * 一段里的错误全部收齐再报;有错的整段不生成 XML。
 */
@org.springframework.stereotype.Component
public final class ScriptCompiler {
    /**
     * 作品里已有的名字与自定义积木;names 为 null 表示不校验作品名(离线用);
     * proceduresElsewhere 是别的角色里定义的自定义积木(名字 → 角色),只用来把"自定义积木不跨角色"说清楚
     */
    public record Known(Set<String> variables, Set<String> lists, List<Procedure> procedures, ProjectNames names,
                        Map<String, String> proceduresElsewhere, Set<String> declaredLocalVariables, Set<String> declaredLocalLists,
                        Map<String, String> privateVariablesElsewhere, Map<String, String> privateListsElsewhere) {
        public Known(Set<String> variables, Set<String> lists, List<Procedure> procedures, ProjectNames names) {
            this(variables, lists, procedures, names, Map.of(), Set.of(), Set.of(), Map.of(), Map.of());
        }

        public Known(Set<String> variables, Set<String> lists, List<Procedure> procedures, ProjectNames names,
                     Map<String, String> proceduresElsewhere) {
            this(variables, lists, procedures, names, proceduresElsewhere, Set.of(), Set.of(), Map.of(), Map.of());
        }

        /** 这段脚本声明为"仅当前角色"的名字:第一次出现时建成私有的,而不是全局的 */
        public Known withDeclaredLocals(Set<String> localVariables, Set<String> localLists) {
            return new Known(variables, lists, procedures, names, proceduresElsewhere, localVariables, localLists,
                    privateVariablesElsewhere, privateListsElsewhere);
        }

        /** 别的角色私有的名字(名字 → 角色):这个角色看不到它们,读到就是错,不能当新名字创建 */
        public Known withPrivateElsewhere(Map<String, String> variables, Map<String, String> lists) {
            return new Known(this.variables, this.lists, procedures, names, proceduresElsewhere, declaredLocalVariables, declaredLocalLists,
                    variables, lists);
        }

        public static Known empty() {
            return new Known(Set.of(), Set.of(), List.of(), null);
        }
    }

    public record ProjectNames(Set<String> sprites, Set<String> costumes, Set<String> backdrops, Set<String> sounds) {
    }

    public record Procedure(String proccode, List<String> argumentNames, List<String> argumentIds) {
        List<Boolean> booleanArgs() {
            List<Boolean> out = new ArrayList<>();
            var m = PROC_SLOT.matcher(proccode);
            while (m.find()) {
                out.add("%b".equals(m.group()));
            }
            return out;
        }
    }

    public record Diagnostic(int line, String message, List<String> suggestions) {
    }

    public record Result(String xml, int blockCount, List<Diagnostic> errors, List<Diagnostic> notices,
                         List<String> newVariables, List<String> newLists, List<String> usedVariables,
                         List<String> usedLists, List<String> broadcasts, List<String> definedProcedures,
                         List<String> newLocalVariables, List<String> newLocalLists) {
        public Result(String xml, int blockCount, List<Diagnostic> errors, List<Diagnostic> notices,
                      List<String> newVariables, List<String> newLists, List<String> usedVariables,
                      List<String> usedLists, List<String> broadcasts, List<String> definedProcedures) {
            this(xml, blockCount, errors, notices, newVariables, newLists, usedVariables, usedLists, broadcasts, definedProcedures,
                    List.of(), List.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private static final Pattern PROC_SLOT = Pattern.compile("%[sb]");
    private static final Pattern NUMBER = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    /** 编辑器数字槽认的写法(FieldNumber 用 Number() 判):整数、小数、.5、科学计数 */
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$");
    /** 变量名里夹着运算符:(a + b) 被解析成一个叫 "a + b" 的变量 */
    private static final Pattern OPERATOR_WORD = Pattern.compile("(^|\\s)([-+*/<>=]|mod|and|or)(\\s|$)");
    /** 贴着写的运算:开头的负号(-height)、乘除号、名字后面紧跟加减数字(j+1、x-1);high-score 这种连字符名不算 */
    private static final Pattern GLUED_OPERATOR = Pattern.compile("^[-+*/]|[*/]|[-+]\\d+(\\.\\d+)?$");
    private static final Set<String> BOOLEAN_OPERATORS = Set.of("operator_equals", "operator_lt", "operator_gt", "operator_and", "operator_or", "operator_not");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern SLOT_TOKEN = Pattern.compile("%\\d+");
    private static final Map<String, String> OPERATOR_IDS = Map.ofEntries(
            Map.entry("+", "OPERATORS_ADD"), Map.entry("-", "OPERATORS_SUBTRACT"), Map.entry("*", "OPERATORS_MULTIPLY"),
            Map.entry("/", "OPERATORS_DIVIDE"), Map.entry("mod", "OPERATORS_MOD"), Map.entry("<", "OPERATORS_LT"),
            Map.entry(">", "OPERATORS_GT"), Map.entry("=", "OPERATORS_EQUALS"), Map.entry("and", "OPERATORS_AND"),
            Map.entry("or", "OPERATORS_OR"));
    private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry("or", 1), Map.entry("and", 2), Map.entry("=", 3), Map.entry("<", 3), Map.entry(">", 3),
            Map.entry("+", 4), Map.entry("-", 4), Map.entry("*", 5), Map.entry("/", 5), Map.entry("mod", 5));
    /** 这些积木用到变量/列表时会创建它;其余积木读一个不存在的名字是错误 */
    private static final Set<String> CREATORS = Set.of("data_setvariableto", "data_changevariableby", "data_addtolist",
            "data_deleteoflist", "data_deletealloflist", "data_insertatlist", "data_replaceitemoflist");
    private static final String STOP_OTHER_SCRIPTS = "other scripts in sprite";

    private final SbParser parser;
    private final BlockTable table;

    public ScriptCompiler(SbParser parser, BlockTable table) {
        this.parser = parser;
        this.table = table;
    }

    public Result compile(String code, Known known) {
        return compile(code, known, false);
    }

    /**
     * @param fragment 只画图不写进作品的片段:允许不带帽子(一串语句、单独一个值都行),其余规则照查
     */
    public Result compile(String code, Known known, boolean fragment) {
        String text = code == null ? "" : code;
        List<Diagnostic> syntax = SyntaxCheck.check(text);
        if (!syntax.isEmpty()) {
            return new Result("", 0, syntax, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return new Run(known == null ? Known.empty() : known, fragment).compile(parser.parse(text));
    }

    // ---- 一次编译的状态 ---------------------------------------------------------

    private final class Run {
        private final Known known;
        private final boolean fragment;
        private final List<Diagnostic> errors = new ArrayList<>();
        private final List<Diagnostic> notices = new ArrayList<>();
        private final Set<String> newVariables = new LinkedHashSet<>();
        private final Set<String> newLists = new LinkedHashSet<>();
        private final Set<String> usedVariables = new LinkedHashSet<>();
        private final Set<String> usedLists = new LinkedHashSet<>();
        private final Set<String> broadcasts = new LinkedHashSet<>();
        private final Map<String, Procedure> procedures = new LinkedHashMap<>();
        private final List<String> defined = new ArrayList<>();
        private Map<String, Boolean> procedureArgs = Map.of();

        Run(Known known, boolean fragment) {
            this.known = known;
            this.fragment = fragment;
            for (Procedure p : known.procedures()) {
                procedures.put(p.proccode(), p);
            }
        }

        Result compile(List<Script> scripts) {
            // 先登记全部 define,调用写在定义前面也行(Scratch 本来就不分先后)
            for (Script script : scripts) {
                Block first = firstBlock(script);
                if (first != null && first.isDefine()) {
                    registerDefinition(first);
                }
            }
            List<Xml> tops = new ArrayList<>();
            Block previousEnd = null;
            for (Script script : scripts) {
                Block first = firstBlock(script);
                if (first == null) {
                    continue;
                }
                Xml top = compileScript(script, first, previousEnd);
                if (top != null) {
                    tops.add(top);
                }
                previousEnd = lastBlock(script);
            }
            String xml = "";
            int count = 0;
            if (errors.isEmpty()) {
                Xml root = new Xml("xml").attr("xmlns", "http://www.w3.org/1999/xhtml");
                tops.forEach(root::add);
                xml = root.toString();
                count = root.count("block");
            }
            // 声明为私有的名字从新建名单里分出来:编辑器按作用域建变量
            List<String> localVariables = newVariables.stream().filter(known.declaredLocalVariables()::contains).toList();
            List<String> localLists = newLists.stream().filter(known.declaredLocalLists()::contains).toList();
            return new Result(xml, count, List.copyOf(errors), List.copyOf(notices),
                    newVariables.stream().filter(name -> !localVariables.contains(name)).toList(),
                    newLists.stream().filter(name -> !localLists.contains(name)).toList(),
                    List.copyOf(usedVariables), List.copyOf(usedLists), List.copyOf(broadcasts), List.copyOf(defined),
                    localVariables, localLists);
        }

        private Xml compileScript(Script script, Block first, Block previousEnd) {
            List<Block> blocks = blocksOf(script);
            Xml head;
            if (first.stray() != null) {
                error(first, "多余的 " + first.stray() + ":前面没有需要它关闭的 repeat / forever / if");
                compileChain(blocks, 1);
                return null;
            }
            if (first.isDefine()) {
                head = definition(first);
                procedureArgs = argsOf(first);
            } else if (first.isHat()) {
                head = statement(first);
            } else if (first.isValueShape()) {
                if (fragment) {
                    // 画图时单独一个值积木也行:只查它本身写得对不对
                    reporter(first, false);
                    compileChain(blocks, 1);
                    return null;
                }
                error(first, "这一行是一个值(圆形或六边形积木),不能单独成一行;把它放进别的积木的槽里");
                return null;
            } else if (previousEnd != null && isCap(previousEnd)) {
                error(first, "第 " + previousEnd.lineNo() + " 行的 " + firstWord(previousEnd) + " 已经结束了脚本,后面接不上任何积木");
                compileChain(blocks, 0);
                return null;
            } else if (fragment) {
                return compileChain(blocks, 0);
            } else {
                error(first, "这段脚本没有帽子积木(when … / define …),永远不会运行");
                compileChain(blocks, 0);
                return null;
            }
            Xml chain = compileChain(blocks, 1);
            if (head != null && chain != null) {
                head.add(new Xml("next").add(chain));
            }
            procedureArgs = Map.of();
            return head;
        }

        private Xml compileChain(List<Block> blocks, int from) {
            Xml headOfChain = null;
            Xml tail = null;
            Block cap = null; // 链里出现过的封口积木(stop / delete this clone / forever):后面接不了任何积木
            for (int i = from; i < blocks.size(); i++) {
                Block block = blocks.get(i);
                if (block.stray() != null) {
                    error(block, "多余的 " + block.stray() + ":前面没有需要它关闭的 repeat / forever / if");
                    continue;
                }
                if (cap != null) {
                    error(block, "第 " + cap.lineNo() + " 行的 " + firstWord(cap) + " 已经结束了脚本,后面接不上任何积木");
                    continue;
                }
                if (block.isHat() || block.isDefine()) {
                    error(block, "\"" + firstWord(block) + "\" 是帽子积木,上面接不了别的积木,只能放在一段脚本的最上面;要新起一段就空一行再写");
                    continue;
                }
                if (isCap(block)) {
                    cap = block;
                }
                Xml element = statement(block);
                if (element == null) {
                    continue;
                }
                if (headOfChain == null) {
                    headOfChain = element;
                } else {
                    tail.add(new Xml("next").add(element));
                }
                tail = element;
            }
            return headOfChain;
        }

        // ---- 语句 ----------------------------------------------------------------

        private Xml statement(Block block) {
            if (block.isValueShape()) {
                error(block, "这是一个值积木,不能当作一条语句;把它放进别的积木的槽里");
                return null;
            }
            Template template = templateOf(block);
            if (template == null) {
                Xml call = procedureCall(block);
                if (call != null) {
                    return call;
                }
                if (block.info().id() != null && !"PROCEDURES_CALL".equals(block.info().id())) {
                    error(block, "\"" + describeLine(block) + "\" 这个积木编辑器里没有(属于没启用的扩展或旧版 Scratch)",
                            currentEquivalents(shapeOf(block), false));
                    return null;
                }
                String owner = definedElsewhere(block);
                if (owner != null) {
                    error(block, "\"" + describeLine(block) + "\" 是角色 " + owner + " 里的自定义积木;自定义积木不跨角色,这个角色里没有它的定义");
                    return null;
                }
                unknown(block, block.isHat() ? "帽子积木" : "积木");
                return null;
            }
            if (template.isValue()) {
                error(block, "\"" + firstWord(block) + "\" 是一个值积木,不能当作一条语句");
                return null;
            }
            if (block.unclosed()) {
                error(block, "第 " + block.lineNo() + " 行的 " + firstWord(block) + " 没有对应的 end:C 形积木的最后要单独一行 end");
            }
            return bind(block, template);
        }

        private Template templateOf(Block block) {
            Template template = table.bySbId(block.info().id());
            if (template != null && "control_if".equals(template.opcode()) && block.scripts().size() > 1) {
                return table.byOpcode("control_if_else");
            }
            return template;
        }

        private Xml bind(Block block, Template template) {
            Xml element = new Xml("block").attr("type", template.opcode());
            List<SbNode> slots = block.slots();
            List<Arg> inline = template.inlineArgs();
            if (slots.size() != inline.size()) {
                error(block, "\"" + displayOf(template) + "\" 有 " + inline.size() + " 个槽,这一行给了 " + slots.size() + " 个");
                return element;
            }
            for (int i = 0; i < inline.size(); i++) {
                Xml bound = bindArg(block, template, inline.get(i), slots.get(i));
                if (bound != null) {
                    element.add(bound);
                }
            }
            List<Script> scripts = block.scripts();
            List<Arg> substacks = template.substacks();
            for (int i = 0; i < substacks.size(); i++) {
                Xml statementEl = new Xml("statement").attr("name", substacks.get(i).name());
                if (i < scripts.size()) {
                    Xml chain = compileChain(blocksOf(scripts.get(i)), 0);
                    if (chain != null) {
                        statementEl.add(chain);
                    }
                }
                element.add(statementEl);
            }
            if ("control_stop".equals(template.opcode()) && STOP_OTHER_SCRIPTS.equals(element.fieldValue("STOP_OPTION"))) {
                element.children.addFirst(new Xml("mutation").attr("hasnext", "true"));
            }
            return element;
        }

        private Xml bindArg(Block block, Template template, Arg arg, SbNode slot) {
            return switch (arg.kind()) {
                case INPUT -> arg.booleanInput() ? booleanInput(block, arg, slot) : valueInput(block, template, arg, slot);
                case MENU -> menuField(block, template, arg, slot);
                case VARIABLE -> variableField(block, template, arg, slot);
                case LABEL, FIELD -> new Xml("field").attr("name", arg.name()).text(literal(slot));
                default -> null;
            };
        }

        private Xml booleanInput(Block block, Arg arg, SbNode slot) {
            Xml value = new Xml("value").attr("name", arg.name());
            if (slot instanceof Block inner && inner.isValueShape()) {
                Xml condition = reporter(inner, true);
                if (condition != null) {
                    value.add(condition);
                }
            } else if (!(slot instanceof Input input && "boolean".equals(input.shape()) && (input.value() == null || input.value().isEmpty()))) {
                error(block, "\"" + firstWord(block) + "\" 的这个槽要放条件(六边形积木,如 <(a) > (b)>、<touching [edge v]?>),给的是 " + describe(slot));
            }
            return value.children.isEmpty() ? null : value;
        }

        private Xml valueInput(Block block, Template template, Arg arg, SbNode slot) {
            Xml value = new Xml("value").attr("name", arg.name());
            BlockTable.Shadow shadow = arg.shadow() != null ? arg.shadow() : new BlockTable.Shadow("text", "TEXT", "");
            Arg menu = table.menuOf(shadow.type());
            if (menu != null) {
                return menuShadow(block, arg, shadow, menu, slot, value);
            }
            Xml shadowEl = new Xml("shadow").attr("type", shadow.type());
            if (shadow.field() == null) {
                shadow = new BlockTable.Shadow(shadow.type(), shadowFieldName(shadow.type()), shadow.value());
            }
            if (slot instanceof Input input) {
                String text = input.value() == null ? "" : input.value();
                if ("color".equals(input.shape()) || "colour_picker".equals(shadow.type())) {
                    if (!COLOR.matcher(text).matches()) {
                        error(block, "颜色要写成 [#rrggbb] 的十六进制,给的是 " + describe(slot));
                    }
                }
                // 数字槽里的文字编辑器会静默丢掉(留下默认值),这里就得拦
                if (shadow.type().startsWith("math_") && !text.isEmpty() && !NUMERIC_LITERAL.matcher(text).matches()) {
                    error(block, "\"" + firstWord(block) + "\" 的这个槽要数字,给的是 " + describe(slot) + ";要放变量或积木就用圆括号,如 (speed)");
                }
                if (arg.options() != null) {
                    String resolved = arg.resolveOption(text);
                    if (resolved == null) {
                        error(block, "\"" + firstWord(block) + "\" 的下拉里没有 [" + text + " v];可选:" + String.join(" | ", arg.optionLabels()));
                    } else {
                        text = resolved;
                    }
                }
                if (shadow.field() != null) {
                    shadowEl.add(new Xml("field").attr("name", shadow.field()).text(text));
                }
                value.add(shadowEl);
            } else if (slot instanceof Block inner) {
                if (shadow.field() != null) {
                    shadowEl.add(new Xml("field").attr("name", shadow.field()).text(shadow.value() == null ? "" : shadow.value()));
                }
                value.add(shadowEl);
                Xml nested = reporter(inner, false);
                if (nested != null) {
                    value.add(nested);
                }
            }
            return value;
        }

        /** 工具箱里 <shadow type="colour_picker"/> 这类不带字段的影子块:字段名取影子块自己定义的第一个字段 */
        private String shadowFieldName(String shadowType) {
            Template shadowBlock = table.byOpcode(shadowType);
            return shadowBlock == null || shadowBlock.args().isEmpty() ? "TEXT" : shadowBlock.args().getFirst().name();
        }

        private Xml menuShadow(Block block, Arg arg, BlockTable.Shadow shadow, Arg menu, SbNode slot, Xml value) {
            Xml shadowEl = new Xml("shadow").attr("type", shadow.type());
            value.add(shadowEl);
            if (slot instanceof Block inner) {
                shadowEl.add(new Xml("field").attr("name", menu.name()).text(shadow.value() == null ? "" : shadow.value()));
                Xml nested = reporter(inner, false);
                if (nested != null) {
                    value.add(nested);
                }
                return value;
            }
            String text = literal(slot);
            if (menu.kind() == ArgKind.VARIABLE && "broadcast_msg".equals(menu.variableType())) {
                broadcasts.add(text);
                shadowEl.add(new Xml("field").attr("name", menu.name()).attr("variabletype", "broadcast_msg").text(text));
                return value;
            }
            String resolved = resolveMenuValue(block, shadow.type(), menu, text);
            shadowEl.add(new Xml("field").attr("name", menu.name()).text(resolved));
            return value;
        }

        private Xml menuField(Block block, Template template, Arg arg, SbNode slot) {
            if (!(slot instanceof Input)) {
                error(block, "\"" + template.text() + "\" 的 " + arg.name() + " 是下拉选项,只能写 [选项 v],不能放积木");
                return new Xml("field").attr("name", arg.name());
            }
            String text = literal(slot);
            if (arg.dynamicOptions()) {
                // 选项来自作品(如 when backdrop switches to 的背景):有作品名单就对照
                Set<String> names = projectNamesForField(arg.name());
                if (names != null && !names.contains(text)) {
                    error(block, "作品里没有叫 \"" + text + "\" 的" + ("BACKDROP".equals(arg.name()) ? "背景" : "COSTUME".equals(arg.name()) ? "造型" : "声音")
                            + ";可选:" + String.join(" | ", names));
                }
                return new Xml("field").attr("name", arg.name()).text(text);
            }
            String resolved = arg.resolveOption(text);
            if (resolved == null) {
                error(block, "\"" + firstWord(block) + "\" 的下拉里没有 [" + text + " v];可选:" + String.join(" | ", arg.optionLabels()));
                resolved = text;
            }
            return new Xml("field").attr("name", arg.name()).text(resolved);
        }

        private String resolveMenuValue(Block block, String menuType, Arg menu, String text) {
            boolean projectMenu = BlockTable.SPRITE_MENUS.contains(menuType) || BlockTable.COSTUME_MENU.equals(menuType)
                    || BlockTable.BACKDROP_MENU.equals(menuType) || BlockTable.SOUND_MENU.equals(menuType);
            String resolved = menu.resolveOption(text);
            if (resolved != null && (!projectMenu || resolved.startsWith("_"))) {
                return resolved;
            }
            if ("stage".equalsIgnoreCase(text) && BlockTable.SPRITE_MENUS.contains(menuType)) {
                return "_stage_";
            }
            if (!projectMenu) {
                error(block, "\"" + firstWord(block) + "\" 的下拉里没有 [" + text + " v];可选:" + String.join(" | ", menu.optionLabels()));
                return text;
            }
            if (table.isSpecialWord(text)) {
                error(block, "\"" + firstWord(block) + "\" 的下拉不接受 [" + text + " v];可选:" + String.join(" | ", menu.optionLabels()) + " | 角色名");
                return text;
            }
            Set<String> names = projectNamesFor(menuType);
            if (names == null || names.contains(text)) {
                return text;
            }
            String kind = BlockTable.COSTUME_MENU.equals(menuType) ? "造型" : BlockTable.BACKDROP_MENU.equals(menuType) ? "背景"
                    : BlockTable.SOUND_MENU.equals(menuType) ? "声音" : "角色";
            List<String> allowed = new ArrayList<>(menu.optionLabels().stream().filter(l -> !l.startsWith("costume") && !l.startsWith("backdrop") && !"Sprite1".equals(l)).toList());
            allowed.addAll(names);
            error(block, "作品里没有叫 \"" + text + "\" 的" + kind + ";可选:" + String.join(" | ", allowed));
            return text;
        }

        private Set<String> projectNamesForField(String fieldName) {
            ProjectNames names = known.names();
            if (names == null) {
                return null;
            }
            return switch (fieldName) {
                case "BACKDROP" -> names.backdrops();
                case "COSTUME" -> names.costumes();
                case "SOUND_MENU" -> names.sounds();
                default -> null;
            };
        }

        private Set<String> projectNamesFor(String menuType) {
            ProjectNames names = known.names();
            if (names == null) {
                return null;
            }
            if (BlockTable.SPRITE_MENUS.contains(menuType)) {
                return names.sprites();
            }
            if (BlockTable.COSTUME_MENU.equals(menuType)) {
                return names.costumes();
            }
            if (BlockTable.BACKDROP_MENU.equals(menuType)) {
                return names.backdrops();
            }
            if (BlockTable.SOUND_MENU.equals(menuType)) {
                return names.sounds();
            }
            return null;
        }

        private Xml variableField(Block block, Template template, Arg arg, SbNode slot) {
            String name = literal(slot);
            String type = arg.variableType() == null ? "" : arg.variableType();
            Xml field = new Xml("field").attr("name", arg.name()).attr("variabletype", type).text(name);
            switch (type) {
                case "list" -> useList(block, template, name);
                case "broadcast_msg" -> broadcasts.add(name);
                default -> useVariable(block, template, name);
            }
            return field;
        }

        private void useVariable(Block block, Template template, String name) {
            if (known.variables().contains(name) || newVariables.contains(name)) {
                usedVariables.add(name);
                return;
            }
            if (template != null && CREATORS.contains(template.opcode())) {
                newVariables.add(name);
                return;
            }
            if (known.lists().contains(name) || newLists.contains(name)) {
                error(block, "\"" + name + "\" 是列表不是变量;读列表的项用 (item (1) of [" + name + " v])");
                return;
            }
            if (OPERATOR_WORD.matcher(name).find()) {
                error(block, "\"" + name + "\" 里有运算符,它会被当成一个变量名;每个运算要各自加括号,如 ((bar width) + (bar spacing))");
                return;
            }
            if (GLUED_OPERATOR.matcher(name).find()) {
                error(block, "\"" + name + "\" 会被当成一个变量名;Scratch 没有负号积木,运算要写成两个槽的积木,如 ((0) - (height))、((j) + (1))");
                return;
            }
            String owner = known.privateVariablesElsewhere().get(name);
            if (owner != null) {
                error(block, "变量 \"" + name + "\" 是角色 " + owner + " 私有的(仅适用于当前角色),这个角色看不到它");
                return;
            }
            List<String> blockAlike = didYouMeanValue(name);
            if (!blockAlike.isEmpty() && isTypoOf(name, blockAlike.getFirst())) {
                error(block, "\"" + name + "\" 不是已有的变量,也不是 Scratch 的积木", blockAlike);
                return;
            }
            newVariables.add(name);
            // 名字只差一两个字符的多半是拼错,但 x pos / y pos 这类成对的名字也只差一个字符:只提醒,照常创建,收尾时"只读没写"会兜住真拼错的
            String similar = similarName(name, known.variables(), newVariables);
            notices.add(new Diagnostic(block.lineNo(), "第 " + block.lineNo() + " 行读取的变量 \"" + name
                    + "\" 还没有任何脚本创建它,这里自动创建" + (similar == null ? "" : ";它和已有的 \"" + similar + "\" 只差一两个字符,如果是同一个,名字要逐字一致"), List.of()));
        }

        /** 已有名字里和它只差一两个字符的:多半是拼错,不该另建一个 */
        private static String similarName(String name, Set<String> knownNames, Set<String> created) {
            List<String> pool = new ArrayList<>(knownNames);
            pool.addAll(created);
            for (String candidate : pool) {
                if (!candidate.equals(name) && isTypoOf(name, candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private static boolean isTypoOf(String name, String candidate) {
            // i / j、x / y 这样的短名字本来就只差一个字母,不是拼错
            if (name.length() < 3 || candidate.length() < 3) {
                return false;
            }
            int cap = name.length() >= 5 ? 2 : 1;
            return distance(name.toLowerCase(Locale.ROOT), candidate.toLowerCase(Locale.ROOT), cap) <= cap;
        }

        private void useList(Block block, Template template, String name) {
            if (known.lists().contains(name) || newLists.contains(name)) {
                usedLists.add(name);
                return;
            }
            if (template != null && CREATORS.contains(template.opcode())) {
                newLists.add(name);
                return;
            }
            String owner = known.privateListsElsewhere().get(name);
            if (owner != null) {
                error(block, "列表 \"" + name + "\" 是角色 " + owner + " 私有的(仅适用于当前角色),这个角色看不到它");
                return;
            }
            newLists.add(name);
            String similar = similarName(name, known.lists(), newLists);
            notices.add(new Diagnostic(block.lineNo(), "第 " + block.lineNo() + " 行读取的列表 \"" + name
                    + "\" 还没有任何脚本创建它,这里自动创建" + (similar == null ? "" : ";它和已有的 \"" + similar + "\" 只差一两个字符,如果是同一个,名字要逐字一致"), List.of()));
        }

        // ---- 值 ------------------------------------------------------------------

        private Xml reporter(Block block, boolean wantBoolean) {
            String selector = block.info().selector();
            // (size :: variables):和内置积木同名的变量,记法里用类别标记点明
            boolean namedVariable = ("variables".equals(block.info().category()) || "list".equals(block.info().category()))
                    && !block.info().categoryIsDefault() && block.slots().isEmpty();
            if ("readVariable".equals(selector) || namedVariable
                    || (block.info().id() == null && block.slots().isEmpty() && block.labels().size() == 1 && !wantBoolean)) {
                return nameReporter(block, String.join(" ", block.labels()));
            }
            if ("getParam".equals(selector)) {
                return argumentReporter(block, String.join(" ", block.labels()), "boolean".equals(block.info().shape()));
            }
            Template template = block.info().id() == null ? null : table.bySbId(block.info().id());
            if (template == null) {
                Xml chain = operatorChain(block);
                if (chain != null) {
                    if (wantBoolean && "block".equals(chain.tag) && !BOOLEAN_OPERATORS.contains(chain.attrs.get("type"))) {
                        error(block, "\"" + describeLine(block) + "\" 算出来是一个值,不是条件;条件要用比较,比如 <(((a) + (b)) * (c)) > (0)>");
                        return null;
                    }
                    return chain;
                }
                Template statement = exactStatement(shapeOf(block));
                if (statement != null) {
                    error(block, "\"" + statement.text() + "\" 是一条语句,不能放进槽里当值用");
                    return null;
                }
                if (block.info().id() != null) {
                    error(block, "\"" + String.join(" ", block.labels()) + "\" 这个积木编辑器里没有(属于没启用的扩展或旧版 Scratch)",
                            currentEquivalents(shapeOf(block), true));
                    return null;
                }
                unknown(block, "积木");
                return null;
            }
            if (!template.isValue()) {
                error(block, "\"" + template.text() + "\" 是一条语句,不能放进槽里当值用");
                return null;
            }
            if (wantBoolean && template.shape() != Shape.BOOLEAN) {
                error(block, "\"" + template.text() + "\" 报告的是一个值,不是条件;条件要用六边形积木,比如 <(" + firstWord(block) + ") = (1)>");
                return null;
            }
            return bind(block, template);
        }

        private Xml nameReporter(Block block, String name) {
            Boolean procArg = procedureArgs.get(name);
            if (procArg != null) {
                return argumentReporter(block, name, procArg);
            }
            if (known.lists().contains(name) || newLists.contains(name)) {
                usedLists.add(name);
                return new Xml("block").attr("type", "data_listcontents")
                        .add(new Xml("field").attr("name", "LIST").attr("variabletype", "list").text(name));
            }
            useVariable(block, null, name);
            return new Xml("block").attr("type", "data_variable")
                    .add(new Xml("field").attr("name", "VARIABLE").attr("variabletype", "").text(name));
        }

        private Xml argumentReporter(Block block, String name, boolean bool) {
            if (!procedureArgs.containsKey(name)) {
                error(block, "\"" + name + "\" 不是当前自定义积木的参数;参数只能在它的 define 脚本里用");
            }
            return new Xml("block").attr("type", bool ? "argument_reporter_boolean" : "argument_reporter_string_number")
                    .add(new Xml("field").attr("name", "VALUE").text(name));
        }

        /** scratchblocks 只认两元运算:(a) + (b) * (c) 这样的链在树里是一块带三个槽的未知积木,按先乘除后加减重排成嵌套 */
        private Xml operatorChain(Block block) {
            List<SbNode> parts = block.children();
            if (parts.size() < 5 || parts.size() % 2 == 0) {
                return null;
            }
            List<SbNode> operands = new ArrayList<>();
            List<String> operators = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                SbNode part = parts.get(i);
                if (i % 2 == 0) {
                    if (part instanceof Label label) {
                        if (!NUMBER.matcher(label.value()).matches()) {
                            return null;
                        }
                        part = new Input("number", label.value(), null);
                    }
                    operands.add(part);
                } else {
                    if (!(part instanceof Label label) || !OPERATOR_IDS.containsKey(label.value())) {
                        return null;
                    }
                    operators.add(label.value());
                }
            }
            long comparisons = operators.stream().filter(op -> PRECEDENCE.get(op) == 3).count();
            if (comparisons > 1) {
                error(block, "\"" + describeLine(block) + "\" 连着比较了两次:Scratch 没有 a < b < c 这种写法,用 <<(a) < (b)> and <(b) < (c)>>");
                return new Xml("literal").text("");
            }
            Xml tree = chain(block, operands, operators, 0, operands.size() - 1);
            notices.add(new Diagnostic(block.lineNo(), "第 " + block.lineNo() + " 行的 " + operators.size() + " 个运算按先乘除后加减自动加了括号;想控制顺序请自己两两加括号", List.of()));
            return tree;
        }

        private Xml chain(Block block, List<SbNode> operands, List<String> operators, int from, int to) {
            if (from == to) {
                return operandXml(block, operands.get(from));
            }
            int split = from;
            for (int i = from; i < to; i++) {
                if (PRECEDENCE.get(operators.get(i)) <= PRECEDENCE.get(operators.get(split))) {
                    split = i;
                }
            }
            Template template = table.bySbId(OPERATOR_IDS.get(operators.get(split)));
            Xml element = new Xml("block").attr("type", template.opcode());
            List<Arg> args = template.inlineArgs();
            element.add(operandValue(args.get(0), chain(block, operands, operators, from, split)));
            element.add(operandValue(args.get(1), chain(block, operands, operators, split + 1, to)));
            return element;
        }

        private Xml operandValue(Arg arg, Xml operand) {
            Xml value = new Xml("value").attr("name", arg.name());
            if ("literal".equals(operand.tag)) {
                if (arg.shadow() != null) {
                    value.add(new Xml("shadow").attr("type", arg.shadow().type())
                            .add(new Xml("field").attr("name", arg.shadow().field()).text(operand.textContent)));
                }
                return value;
            }
            if (arg.shadow() != null) {
                value.add(new Xml("shadow").attr("type", arg.shadow().type())
                        .add(new Xml("field").attr("name", arg.shadow().field()).text(arg.shadow().value() == null ? "" : arg.shadow().value())));
            }
            value.add(operand);
            return value;
        }

        private Xml operandXml(Block block, SbNode operand) {
            if (operand instanceof Input input) {
                return new Xml("literal").text(input.value() == null ? "" : input.value());
            }
            Xml nested = reporter((Block) operand, false);
            return nested == null ? new Xml("literal").text("") : nested;
        }

        // ---- 自定义积木 -----------------------------------------------------------

        private Map<String, Boolean> argsOf(Block define) {
            Map<String, Boolean> args = new LinkedHashMap<>();
            Block outline = outlineOf(define);
            if (outline != null) {
                for (SbNode child : outline.children()) {
                    if (child instanceof Block param && "custom-arg".equals(param.info().category())) {
                        args.put(String.join(" ", param.labels()), "boolean".equals(param.info().argument()));
                    }
                }
            }
            return args;
        }

        private Block outlineOf(Block define) {
            for (SbNode child : define.children()) {
                if (child instanceof Block block && "outline".equals(block.info().shape())) {
                    return block;
                }
            }
            return null;
        }

        /** define 的名字里写了 [x v] 这样的下拉:那不是名字的一部分,登记了也对不上调用 */
        private static boolean outlineHasMenu(Block outline) {
            return outline.children().stream().anyMatch(child -> child instanceof Input);
        }

        private void registerDefinition(Block define) {
            Block outline = outlineOf(define);
            if (outline == null || outlineHasMenu(outline)) {
                return;
            }
            StringBuilder proccode = new StringBuilder();
            List<String> names = new ArrayList<>();
            for (SbNode child : outline.children()) {
                if (child instanceof Label label) {
                    proccode.append(proccode.isEmpty() ? "" : " ").append(label.value());
                } else if (child instanceof Block param) {
                    proccode.append(proccode.isEmpty() ? "" : " ").append("boolean".equals(param.info().argument()) ? "%b" : "%s");
                    names.add(String.join(" ", param.labels()));
                }
            }
            String code = proccode.toString();
            Procedure existing = procedures.get(code);
            List<String> ids = existing != null && existing.argumentIds().size() == names.size() ? existing.argumentIds()
                    : argumentIds(code, names.size());
            procedures.put(code, new Procedure(code, names, ids));
            defined.add(code);
        }

        private Xml definition(Block define) {
            Block outline = outlineOf(define);
            if (outline == null) {
                error(define, "define 后面要跟自定义积木的名字,比如 define draw square (size)");
                return null;
            }
            if (outlineHasMenu(outline)) {
                error(define, "define 后面直接写自定义积木的名字,不加方括号,比如 define draw bars;要带参数就写 (name)");
                return null;
            }
            String code = proccodeOf(outline);
            Procedure procedure = procedures.get(code);
            Xml prototype = new Xml("shadow").attr("type", "procedures_prototype")
                    .add(new Xml("mutation").attr("proccode", code)
                            .attr("argumentids", jsonArray(procedure.argumentIds()))
                            .attr("argumentnames", jsonArray(procedure.argumentNames()))
                            .attr("argumentdefaults", jsonArray(procedure.booleanArgs().stream().map(b -> b ? "false" : "").toList()))
                            .attr("warp", "false"));
            List<Boolean> bools = procedure.booleanArgs();
            for (int i = 0; i < procedure.argumentIds().size(); i++) {
                prototype.add(new Xml("value").attr("name", procedure.argumentIds().get(i))
                        .add(new Xml("shadow").attr("type", bools.get(i) ? "argument_reporter_boolean" : "argument_reporter_string_number")
                                .add(new Xml("field").attr("name", "VALUE").text(procedure.argumentNames().get(i)))));
            }
            return new Xml("block").attr("type", "procedures_definition")
                    .add(new Xml("statement").attr("name", "custom_block").add(prototype));
        }

        private String proccodeOf(Block outline) {
            StringBuilder proccode = new StringBuilder();
            for (SbNode child : outline.children()) {
                if (child instanceof Label label) {
                    proccode.append(proccode.isEmpty() ? "" : " ").append(label.value());
                } else if (child instanceof Block param) {
                    proccode.append(proccode.isEmpty() ? "" : " ").append("boolean".equals(param.info().argument()) ? "%b" : "%s");
                }
            }
            return proccode.toString();
        }

        /** 未知的语句积木按"词形"和已知自定义积木比对:词相同、槽位对应 */
        private Xml procedureCall(Block block) {
            String shape = shapeOf(block);
            for (Procedure procedure : procedures.values()) {
                if (!shape.equals(shapeOfProccode(procedure.proccode()))) {
                    continue;
                }
                List<Boolean> bools = procedure.booleanArgs();
                List<String> ids = procedure.argumentIds().size() == bools.size() ? procedure.argumentIds()
                        : argumentIds(procedure.proccode(), bools.size());
                Xml call = new Xml("block").attr("type", "procedures_call")
                        .add(new Xml("mutation").attr("proccode", procedure.proccode())
                                .attr("argumentids", jsonArray(ids)).attr("warp", "false"));
                List<SbNode> slots = block.slots();
                for (int i = 0; i < ids.size() && i < slots.size(); i++) {
                    Xml value = new Xml("value").attr("name", ids.get(i));
                    SbNode slot = slots.get(i);
                    if (bools.get(i)) {
                        if (slot instanceof Block inner) {
                            Xml nested = reporter(inner, true);
                            if (nested != null) {
                                value.add(nested);
                            }
                        } else if (!(slot instanceof Input input && "boolean".equals(input.shape()) && (input.value() == null || input.value().isEmpty()))) {
                            error(block, "自定义积木 \"" + procedure.proccode().replace("%s", "()").replace("%b", "<>") + "\" 的第 " + (i + 1)
                                    + " 个参数要放条件(六边形积木),给的是 " + describe(slot));
                        }
                    } else if (slot instanceof Input input) {
                        value.add(new Xml("shadow").attr("type", "text").add(new Xml("field").attr("name", "TEXT").text(input.value() == null ? "" : input.value())));
                    } else if (slot instanceof Block inner) {
                        value.add(new Xml("shadow").attr("type", "text").add(new Xml("field").attr("name", "TEXT").text("")));
                        Xml nested = reporter(inner, false);
                        if (nested != null) {
                            value.add(nested);
                        }
                    }
                    call.add(value);
                }
                return call;
            }
            return null;
        }

        private String definedElsewhere(Block block) {
            String shape = shapeOf(block);
            for (Map.Entry<String, String> entry : known.proceduresElsewhere().entrySet()) {
                if (shape.equals(shapeOfProccode(entry.getKey()))) {
                    return entry.getValue();
                }
            }
            return null;
        }

        // ---- 错误与候选 -----------------------------------------------------------

        private void unknown(Block block, String what) {
            if (block.labels().isEmpty()) {
                if ("boolean".equals(block.info().shape()) && block.slots().size() == 1 && block.slots().getFirst() instanceof Block inner) {
                    error(block, "<" + describeLine(inner) + "> 里只有一个值积木,不是条件;条件要用六边形积木,比如 <(" + describeLine(inner) + ") > (0)>");
                    return;
                }
                error(block, "这一行没有积木名,只有槽");
                return;
            }
            // "x position of [蛇头 v]":读别的角色的属性,属性名写在了下拉外面;按词形找候选只会找到自己的 x position,那是另一个积木
            if (block.isValueShape() && block.labels().size() >= 2 && "of".equalsIgnoreCase(block.labels().getLast())
                    && block.slots().size() == 1 && block.slots().getFirst() instanceof Input target) {
                String attribute = String.join(" ", block.labels().subList(0, block.labels().size() - 1));
                if (OF_ATTRIBUTES.contains(attribute.toLowerCase(Locale.ROOT)) || known.variables().contains(attribute)) {
                    error(block, "\"" + describeLine(block) + "\" 不是 Scratch 的积木;读别的角色或舞台的属性时,属性名在第一个下拉里:([" + attribute
                            + " v] of [" + target.value() + " v])");
                    return;
                }
            }
            List<String> suggestions = didYouMean(shapeOf(block), block.isValueShape());
            String hint = block.isValueShape() ? "" : ";如果它是你自己起名的自定义积木,要先写它的 define 脚本";
            error(block, "\"" + describeLine(block) + "\" 不是 Scratch 的" + what + "(积木名要和技能里的写法逐字一致)" + hint, suggestions);
        }

        private List<String> didYouMeanValue(String name) {
            return didYouMean(name.toLowerCase(Locale.ROOT), true);
        }

        /** 槽里写了一条语句(解析器把它当成了值):按词形找一模一样的语句积木 */
        private Template exactStatement(String shape) {
            for (Template template : table.all()) {
                if (!template.isValue() && (template.msg() != null || template.extension()) && shapeOfText(template).equals(shape)) {
                    return template;
                }
            }
            return null;
        }

        /**
         * 旧版 / 未启用积木在现在的编辑器里对应的写法:词序相同、最多多两个词的现役积木(如 "if _" → "if _ then")。
         * 和 didYouMean 的"像手误"不是一回事:这里的差别本来就不止一两个字符。
         */
        private List<String> currentEquivalents(String shape, boolean valueShape) {
            List<String> words = List.of(shape.split(" "));
            List<String> found = new ArrayList<>();
            for (Template template : table.all()) {
                if ((template.msg() == null && !template.extension()) || valueShape != template.isValue()) {
                    continue;
                }
                List<String> candidate = List.of(shapeOfText(template).split(" "));
                if (candidate.size() > words.size() + 2 || !containsInOrder(candidate, words)) {
                    continue;
                }
                String display = displayOf(template);
                if (!found.contains(display)) {
                    found.add(display);
                }
            }
            return found.size() > 2 ? found.subList(0, 2) : found;
        }

        private static boolean containsInOrder(List<String> haystack, List<String> needle) {
            int i = 0;
            for (String word : haystack) {
                if (i < needle.size() && word.equals(needle.get(i))) {
                    i++;
                }
            }
            return i == needle.size();
        }

        private List<String> didYouMean(String shape, boolean valueShape) {
            record Candidate(int distance, String display) {
            }
            List<Candidate> candidates = new ArrayList<>();
            // 只找像手误的(差一两个字符):差得更多的多半是另一个积木,列出来会被当成改法
            int cap = shape.length() >= 5 ? 2 : 1;
            for (Template template : table.all()) {
                if (template.msg() == null && !template.extension()) {
                    continue;
                }
                if (valueShape != template.isValue()) {
                    continue;
                }
                String candidateShape = shapeOfText(template);
                int distance = distance(shape, candidateShape, cap);
                if (distance <= cap) {
                    candidates.add(new Candidate(distance, displayOf(template)));
                }
            }
            if (!valueShape) {
                for (Procedure procedure : procedures.values()) {
                    int distance = distance(shape, shapeOfProccode(procedure.proccode()), cap);
                    if (distance <= cap) {
                        candidates.add(new Candidate(distance, procedure.proccode().replace("%s", "()").replace("%b", "<>")));
                    }
                }
            }
            candidates.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
            return candidates.stream().map(Candidate::display).distinct().limit(3).toList();
        }

        private void error(Block block, String message) {
            error(block, message, List.of());
        }

        private void error(Block block, String message, List<String> suggestions) {
            errors.add(new Diagnostic(block.lineNo(), message, suggestions));
        }
    }

    /** ([x position v] of [Sprite v]) 第一个下拉里的内置属性 */
    private static final Set<String> OF_ATTRIBUTES = Set.of("x position", "y position", "direction", "costume #", "costume name", "size",
            "volume", "backdrop #", "backdrop name");

    // ---- 词形:标签保留、每个槽一个 _,不分大小写 ---------------------------------------

    static String shapeOf(Block block) {
        List<String> parts = new ArrayList<>();
        for (SbNode child : block.children()) {
            if (child instanceof Label label) {
                parts.add(label.value().toLowerCase(Locale.ROOT));
            } else if (child instanceof Input || child instanceof Block) {
                parts.add("_");
            }
        }
        return String.join(" ", parts);
    }

    /** 参数 id 只由积木名和参数序号决定:定义和调用不管写在哪一段,算出来都一样 */
    static List<String> argumentIds(String proccode, int count) {
        String stem = "arg_" + proccode.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT) + "_";
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(stem + (i + 1));
        }
        return ids;
    }

    private static String shapeOfProccode(String proccode) {
        return PROC_SLOT.matcher(proccode).replaceAll("_").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String shapeOfText(Template template) {
        return SLOT_TOKEN.matcher(imageWords(template)).replaceAll("_").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String displayOf(Template template) {
        String text = imageWords(template);
        List<Arg> inline = template.inlineArgs();
        StringBuilder out = new StringBuilder();
        var m = SLOT_TOKEN.matcher(text);
        int i = 0;
        while (m.find()) {
            Arg arg = i < inline.size() ? inline.get(i) : null;
            i++;
            String slot = arg == null ? "()" : arg.kind() == ArgKind.MENU || arg.kind() == ArgKind.VARIABLE ? "[ v]"
                    : arg.booleanInput() ? "<>" : "()";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(slot));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    static String imageWords(Template template) {
        String text = template.text();
        int k = 0;
        StringBuilder out = new StringBuilder();
        var m = SLOT_TOKEN.matcher(text);
        List<Arg> args = template.args().stream().filter(a -> a.kind() != ArgKind.SUBSTACK).toList();
        while (m.find()) {
            Arg arg = k < args.size() ? args.get(k) : null;
            k++;
            String replacement = m.group();
            if (arg != null && arg.kind() == ArgKind.IMAGE) {
                replacement = switch (arg.image() == null ? "" : arg.image()) {
                    case "green-flag.svg" -> "green flag";
                    case "rotate-right.svg" -> "right";
                    case "rotate-left.svg" -> "left";
                    default -> "";
                };
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    static int distance(String a, String b, int cap) {
        if (Math.abs(a.length() - b.length()) > cap) {
            return cap + 1;
        }
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > cap) {
                return cap + 1;
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    // ---- 小工具 --------------------------------------------------------------------

    private static Block firstBlock(Script script) {
        List<Block> blocks = blocksOf(script);
        return blocks.isEmpty() ? null : blocks.getFirst();
    }

    private static Block lastBlock(Script script) {
        List<Block> blocks = blocksOf(script);
        return blocks.isEmpty() ? null : blocks.getLast();
    }

    private static List<Block> blocksOf(Script script) {
        List<Block> out = new ArrayList<>();
        for (SbNode node : script.blocks()) {
            if (node instanceof Block block) {
                out.add(block);
            }
        }
        return out;
    }

    private boolean isCap(Block block) {
        Template template = block.info().id() == null ? null : table.bySbId(block.info().id());
        if (template == null) {
            return false;
        }
        if ("control_stop".equals(template.opcode())) {
            return block.slots().stream().noneMatch(s -> s instanceof Input input && STOP_OTHER_SCRIPTS.equalsIgnoreCase(input.value()));
        }
        return template.shape() == Shape.CAP;
    }

    private static String literal(SbNode slot) {
        if (slot instanceof Input input) {
            return input.value() == null ? "" : input.value();
        }
        if (slot instanceof Block block) {
            return String.join(" ", block.labels());
        }
        return "";
    }

    private static String describe(SbNode slot) {
        if (slot instanceof Input input) {
            String value = input.value() == null ? "" : input.value();
            return "number".equals(input.shape()) ? "数字 (" + value + ")" : "文字 [" + value + "]";
        }
        if (slot instanceof Block block) {
            return "积木 " + describeLine(block);
        }
        return "空";
    }

    private static String firstWord(Block block) {
        List<String> labels = block.labels();
        return labels.isEmpty() ? "这块积木" : labels.getFirst();
    }

    static String describeLine(Block block) {
        StringBuilder sb = new StringBuilder();
        for (SbNode child : block.children()) {
            String part = switch (child) {
                case Label label -> label.value();
                case Input input -> "number".equals(input.shape()) ? "(" + input.value() + ")"
                        : "dropdown".equals(input.shape()) || "number-dropdown".equals(input.shape()) ? "[" + input.value() + " v]"
                        : "boolean".equals(input.shape()) ? "<>" : "[" + input.value() + "]";
                case Block inner -> inner.isValueShape() && "boolean".equals(inner.info().shape()) ? "<" + describeLine(inner) + ">" : "(" + describeLine(inner) + ")";
                default -> null;
            };
            if (part != null) {
                sb.append(sb.isEmpty() ? "" : " ").append(part);
            }
        }
        return sb.toString();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            sb.append(i == 0 ? "" : ",").append('"')
                    .append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    // ---- 极简 XML 元素:只写不读 -------------------------------------------------------

    static final class Xml {
        final String tag;
        final Map<String, String> attrs = new LinkedHashMap<>();
        final List<Xml> children = new ArrayList<>();
        String textContent = "";

        Xml(String tag) {
            this.tag = tag;
        }

        Xml attr(String name, String value) {
            attrs.put(name, value);
            return this;
        }

        Xml text(String value) {
            textContent = value == null ? "" : value;
            return this;
        }

        Xml add(Xml child) {
            if (child != null) {
                children.add(child);
            }
            return this;
        }

        String fieldValue(String name) {
            for (Xml child : children) {
                if ("field".equals(child.tag) && name.equals(child.attrs.get("name"))) {
                    return child.textContent;
                }
            }
            return null;
        }

        int count(String tagName) {
            int n = tag.equals(tagName) ? 1 : 0;
            for (Xml child : children) {
                n += child.count(tagName);
            }
            return n;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            write(sb);
            return sb.toString();
        }

        private void write(StringBuilder sb) {
            sb.append('<').append(tag);
            attrs.forEach((k, v) -> sb.append(' ').append(k).append("=\"").append(escape(v)).append('"'));
            sb.append('>').append(escape(textContent));
            children.forEach(child -> child.write(sb));
            sb.append("</").append(tag).append('>');
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        }
    }
}
