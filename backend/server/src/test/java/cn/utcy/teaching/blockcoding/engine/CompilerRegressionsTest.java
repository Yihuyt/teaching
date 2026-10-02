package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Diagnostic;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerRegressionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BlockTable table = new BlockTable(MAPPER);
    private final SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), table));
    private final SbToText toText = new SbToText(table, MAPPER);

    private static Known variables(String... names) {
        return new Known(Set.of(names), Set.of(), List.of(), null);
    }

    @Test
    @DisplayName("if 行漏了结尾的 then 会被解析成旧版的 if:报错附上现在的写法")
    void ifWithoutThenSuggestsCurrentBlock() {
        Result result = engine.compile("when green flag clicked\nif <(score) > (10)>\n  say [hi]\nend", variables("score"));
        assertThat(result.ok()).isFalse();
        Diagnostic error = result.errors().getFirst();
        assertThat(error.message()).contains("编辑器里没有");
        assertThat(error.suggestions()).contains("if <> then");
    }

    @Test
    @DisplayName("define 的名字不能写成下拉 [x v]:报错说明写法,也不登记成自定义积木")
    void defineNameMustNotBeAMenu() {
        Result result = engine.compile("define [draw bars v]\nerase all", Known.empty());
        assertThat(result.ok()).isFalse();
        assertThat(result.errors().getFirst().message()).contains("不加方括号").contains("define draw bars");
        assertThat(engine.compile("define draw bars\nerase all", Known.empty()).ok()).isTrue();
    }

    @Test
    @DisplayName("注释行在前、C 形积木体里的第一行报错:行号按原文算(曾经差一行)")
    void lineNumbersInsideCBlocksAfterComments() {
        Result result = engine.compile("// 说明\nwhen green flag clicked\nrepeat (10)\n  move (10) stepz\n  turn cw (15) degreez\nend", Known.empty());
        assertThat(result.errors()).extracting(Diagnostic::line).containsExactly(4, 5);
    }

    @Test
    @DisplayName("和内置值积木同名的变量:(size :: variables) 读作变量,反向打印也带类别标记,往返不变形")
    void variableNamedLikeBuiltinReporter() {
        Result builtin = engine.compile("when green flag clicked\nsay (size)", variables("size"));
        assertThat(builtin.xml()).contains("looks_size").doesNotContain("data_variable");
        Result variable = engine.compile("when green flag clicked\nsay (size :: variables)", variables("size"));
        assertThat(variable.ok()).isTrue();
        assertThat(variable.xml()).contains("data_variable");
        String text = toText.toText(variable.xml());
        assertThat(text).contains("(size :: variables)");
        assertThat(engine.compile(text, variables("size")).xml()).contains("data_variable");
        assertThat(toText.toText(engine.compile("when green flag clicked\nsay (score)", variables("score")).xml())).contains("(score)");
    }

    @Test
    @DisplayName("类别标记只打给解析器认得的内置积木名:timer 要,counter(解析器不认识的老积木)不要")
    void markerOnlyForParserKnownNames() {
        String timer = toText.toText(engine.compile("when green flag clicked\nsay (timer :: variables)", variables("timer")).xml());
        assertThat(timer).contains("(timer :: variables)");
        String counter = toText.toText(engine.compile("when green flag clicked\nsay (counter)", variables("counter")).xml());
        assertThat(counter).contains("(counter)").doesNotContain("::");
    }

    @Test
    @DisplayName("文字里的 ]、\\ 和结尾的 \" v\":打印时转义,读回来还是原字")
    void textLiteralsRoundTrip() {
        for (String literal : List.of("a]b", "C:\\dir", "level v")) {
            Result compiled = engine.compile("when green flag clicked\nsay [" + SbToText.quoteText(literal) + "] for (2) seconds", Known.empty());
            assertThat(compiled.ok()).as(literal).isTrue();
            assertThat(compiled.xml()).contains(">" + literal + "<");
            String text = toText.toText(compiled.xml());
            Result again = engine.compile(text, Known.empty());
            assertThat(again.ok()).as(text).isTrue();
            assertThat(again.xml()).isEqualTo(compiled.xml());
        }
    }

    @Test
    @DisplayName("i 和 j、x 和 y 不是拼错:读到没创建过的短名字照常自动创建")
    void shortNamesAreNotTypos() {
        Result result = engine.compile("when green flag clicked\nsay (j)", variables("i"));
        assertThat(result.ok()).isTrue();
        assertThat(result.newVariables()).containsExactly("j");
    }

    @Test
    @DisplayName("数字槽里塞文字:编辑器会静默丢掉,编译时就报")
    void textInNumberSlot() {
        Result result = engine.compile("when green flag clicked\nmove [fast] steps", Known.empty());
        assertThat(result.errors()).singleElement().extracting(Diagnostic::message).asString().contains("要数字");
        assertThat(engine.compile("when green flag clicked\nmove (.5) steps\nmove (-2e3) steps", Known.empty()).ok()).isTrue();
    }

    @Test
    @DisplayName("布尔槽里放算术运算链:不是条件,编译时就报,不留到编辑器拒绝")
    void arithmeticChainInBooleanSlot() {
        Result result = engine.compile("when green flag clicked\nif <(a) + (b) * (c)> then\nend", variables("a", "b", "c"));
        assertThat(result.errors()).singleElement().extracting(Diagnostic::message).asString().contains("不是条件");
        // 记法里比较要自己加括号:<((a) + (b)) > (c)>;不加的话解析器会把 "> (c)" 吞掉,现在会当成值报出来
        assertThat(engine.compile("when green flag clicked\nif <((a) + (b)) > (c)> then\nend", variables("a", "b", "c")).ok()).isTrue();
    }

    @Test
    @DisplayName("括号里夹着运算符:不当成一个叫 \"a + b\" 的变量,报错让它各自加括号")
    void operatorInsideVariableName() {
        Result result = engine.compile("when green flag clicked\nsay (bar width + bar spacing)", variables("bar width", "bar spacing"));
        assertThat(result.errors()).singleElement().extracting(Diagnostic::message).asString().contains("运算符");
        assertThat(result.newVariables()).isEmpty();
    }

    @Test
    @DisplayName("自定义积木的布尔参数给了文字:报错,不静默丢掉")
    void literalInBooleanProcedureParameter() {
        Result result = engine.compile("define check <flag>\nsay (flag)\n\nwhen green flag clicked\ncheck [yes]", Known.empty());
        assertThat(result.errors()).singleElement().extracting(Diagnostic::message).asString().contains("要放条件");
    }

    @Test
    @DisplayName("调用别的角色里定义的自定义积木:点明它属于哪个角色、自定义积木不跨角色")
    void customBlockDefinedInAnotherSprite() {
        Known known = new Known(Set.of(), Set.of(), List.of(), null, java.util.Map.of("draw array", "角色1"));
        Result result = engine.compile("when green flag clicked\ndraw array", known);
        assertThat(result.errors()).singleElement().extracting(Diagnostic::message).asString()
                .contains("角色 角色1 里的自定义积木").contains("不跨角色");
    }

    @Test
    @DisplayName("成对的名字只差一个字符(snake x positions / snake y positions):都照常创建,只提醒,不当拼错拒绝")
    void pairedNamesAreNotTypos() {
        Result result = engine.compile("when green flag clicked\nsay (length of [snake x positions v])\nsay (length of [snake y positions v])\nsay (x pos)\nsay (y pos)", Known.empty());
        assertThat(result.errors()).isEmpty();
        assertThat(result.newLists()).containsExactly("snake x positions", "snake y positions");
        assertThat(result.newVariables()).containsExactly("x pos", "y pos");
        assertThat(result.notices()).extracting(Diagnostic::message).anyMatch(m -> m.contains("只差一两个字符"));
    }

    @Test
    @DisplayName("别的角色私有的变量 / 列表:这个角色读不到,报事实,不当新名字创建")
    void privateNamesOfOtherSpritesAreInvisible() {
        Known known = Known.empty().withPrivateElsewhere(java.util.Map.of("game over", "SnakeHead"), java.util.Map.of("snake x positions", "SnakeHead"));
        Result result = engine.compile("when green flag clicked\nsay (game over)\nsay (length of [snake x positions v])", known);
        assertThat(result.errors()).extracting(Diagnostic::message).allMatch(m -> m.contains("角色 SnakeHead 私有的"));
        assertThat(result.errors()).hasSize(2);
        assertThat(result.newVariables()).isEmpty();
        assertThat(result.newLists()).isEmpty();
    }

    @Test
    @DisplayName("读别的角色的属性写在下拉外面(x position of [蛇头 v]):报这个积木的写法,不把它猜成自己的 x position")
    void attributeOfAnotherSpriteWrittenOutsideTheDropdown() {
        Result wrong = engine.compile("when green flag clicked\ngo to x:(x position of [蛇头 v]) y:(y position of [蛇头 v])", Known.empty());
        assertThat(wrong.errors()).extracting(Diagnostic::message)
                .containsExactly("\"x position of [蛇头 v]\" 不是 Scratch 的积木;读别的角色或舞台的属性时,属性名在第一个下拉里:([x position v] of [蛇头 v])",
                        "\"y position of [蛇头 v]\" 不是 Scratch 的积木;读别的角色或舞台的属性时,属性名在第一个下拉里:([y position v] of [蛇头 v])");
        assertThat(wrong.errors()).extracting(Diagnostic::suggestions).allMatch(List::isEmpty);
        Result right = engine.compile("when green flag clicked\ngo to x:([x position v] of [蛇头 v]) y:([y position v] of [蛇头 v])", Known.empty());
        assertThat(right.errors()).isEmpty();
        assertThat(right.xml()).contains("sensing_of");
    }

    @Test
    @DisplayName("查不到的积木只列拼写差一两个字符的候选;差得多的(多写了几个词)不列,免得被当成改法")
    void suggestionsOnlyForNearTypos() {
        Result typo = engine.compile("when green flag clicked\nmvoe (10) steps", Known.empty());
        assertThat(typo.errors()).singleElement().satisfies(e -> assertThat(e.suggestions()).containsExactly("move () steps"));
        Result other = engine.compile("when green flag clicked\nmove (10) steps forward quickly", Known.empty());
        assertThat(other.errors()).singleElement().satisfies(e -> {
            assertThat(e.message()).contains("不是 Scratch 的积木");
            assertThat(e.suggestions()).isEmpty();
        });
    }

    @Test
    @DisplayName("贴着写的运算 (-height)、(j+1) 不当成变量名自动创建,报事实;high-score 这种连字符名照常是变量")
    void gluedOperatorsAreNotVariableNames() {
        Result result = engine.compile("when green flag clicked\nchange y by (-height)\nsay (item (j+1) of [numbers v])\nsay (high-score)", Known.empty());
        assertThat(result.errors()).extracting(Diagnostic::message)
                .containsExactly("\"-height\" 会被当成一个变量名;Scratch 没有负号积木,运算要写成两个槽的积木,如 ((0) - (height))、((j) + (1))",
                        "\"j+1\" 会被当成一个变量名;Scratch 没有负号积木,运算要写成两个槽的积木,如 ((0) - (height))、((j) + (1))");
        assertThat(result.newVariables()).containsExactly("high-score");
        assertThat(engine.compile("when green flag clicked\nchange y by ((0) - (height))\nsay (item ((j) + (1)) of [numbers v])", Known.empty()).errors()).isEmpty();
    }

    @Test
    @DisplayName("背景切换帽子的下拉对照作品里的背景名")
    void backdropHatChecksProjectNames() {
        Known known = new Known(Set.of(), Set.of(), List.of(),
                new ScriptCompiler.ProjectNames(Set.of("Cat"), Set.of("costume1"), Set.of("backdrop1", "night"), Set.of()));
        assertThat(engine.compile("when backdrop switches to [night v]\nsay [hi]", known).ok()).isTrue();
        assertThat(engine.compile("when backdrop switches to [day v]\nsay [hi]", known).errors())
                .singleElement().extracting(Diagnostic::message).asString().contains("背景");
    }
}
