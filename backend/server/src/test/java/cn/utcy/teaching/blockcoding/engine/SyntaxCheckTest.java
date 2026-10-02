package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntaxCheckTest {

    @Test
    void wellFormedLinesPass() {
        String code = String.join("\n",
                "when green flag clicked",
                "// 注释里 ( 什么都可以",
                "say [a (b) <c] for (2) seconds // 行尾 ] 注释",
                "say [带 \\] 转义的 ] 文本]",
                "if <(a) < (b)> then",
                "if <<(a) > (b)> and <not <mouse down?>>> then",
                "set [x v] to ((1) + (2))",
                "when [loudness v] > (10)",
                "say [（哈哈）]",
                "end");
        assertEquals(List.of(), SyntaxCheck.check(code));
    }

    @Test
    void missingCloser() {
        List<Diagnostic> errors = SyntaxCheck.check("when green flag clicked\nmove (10 steps");
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).line());
        assertEquals("第 2 行第 6 个字符的 ( 少了配对的 )", errors.get(0).message());
    }

    @Test
    void extraCloser() {
        List<Diagnostic> errors = SyntaxCheck.check("move (10) steps)");
        assertEquals("第 1 行第 16 个字符:多了一个 ),前面没有和它配对的 (", errors.get(0).message());
    }

    @Test
    void misnested() {
        List<Diagnostic> errors = SyntaxCheck.check("if <(a) > (b> then");
        assertEquals("第 1 行第 13 个字符:这里的 > 对不上前面第 11 个字符的 (;括号要成对嵌套", errors.get(0).message());
    }

    @Test
    void unclosedString() {
        List<Diagnostic> errors = SyntaxCheck.check("say [hello");
        assertEquals("第 1 行第 5 个字符的 [ 没有配对的 ]", errors.get(0).message());
    }

    @Test
    void fullWidthOutsideStrings() {
        List<Diagnostic> errors = SyntaxCheck.check("when green flag clicked\n移动 （１０） 步");
        assertEquals(1, errors.size());
        assertEquals("第 2 行第 4 个字符是全角的 （,要用半角的 (", errors.get(0).message());
    }

    @Test
    void eachBadLineReportedOnce() {
        List<Diagnostic> errors = SyntaxCheck.check("move (10 steps\nturn right (15 degrees");
        assertEquals(2, errors.size());
        assertTrue(errors.stream().allMatch(e -> e.message().contains("少了配对的 )")));
    }
}
