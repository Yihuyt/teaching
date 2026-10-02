package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 形状层完备性:编辑器加载 XML 时会拒绝的情况有限且写在它的源码里(scratch-blocks core/xml.js、block.js、connection.js),
 * 这里一条对一条,保证凡是编辑器拼不上的,我们先报出来。改坏任何一条,这个文件先红。
 */
class EditorLoaderRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SbEngine ENGINE = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), new BlockTable(MAPPER)));

    private static String firstError(String code) {
        Result result = ENGINE.compile("```\n" + code + "\n```", null);
        return result.errors().isEmpty() ? "" : result.errors().getFirst().message();
    }

    @Test
    @DisplayName("block.js:178 未知积木类型 → 查表查不到就报")
    void unknownBlockType() {
        assertThat(firstError("when green flag clicked\nmvoe (10) steps")).contains("不是 Scratch 的积木");
    }

    @Test
    @DisplayName("xml.js next 断言:封口积木没有下接口 → 任何一条链里封口后接积木都报")
    void nothingAfterCapBlock() {
        assertThat(firstError("when green flag clicked\nstop [all v]\nsay [hi]")).contains("已经结束了脚本");
        assertThat(firstError("when I start as a clone\nif <touching [edge v]?> then\n  delete this clone\n  say [hi]\nend")).contains("已经结束了脚本");
        assertThat(firstError("when green flag clicked\nforever\n  move (1) steps\nend\nsay [hi]")).contains("已经结束了脚本");
    }

    @Test
    @DisplayName("xml.js next 断言:接在下面的积木必须有上接口 → 值积木当语句报,帽子处解析器另起一段")
    void nextChildNeedsPreviousConnection() {
        assertThat(firstError("when green flag clicked\n(x position)")).contains("值");
        assertThat(firstError("when green flag clicked\nmove (10) steps\nwhen [space v] key pressed\nsay [hi]")).isEmpty();
    }

    @Test
    @DisplayName("xml.js value 输入:槽里的积木要有输出接口 → 语句塞进槽里报")
    void slotChildNeedsOutput() {
        assertThat(firstError("when green flag clicked\nsay (move (10) steps)")).contains("是一条语句,不能放进槽里");
    }

    @Test
    @DisplayName("connection.js:346 接口类型不兼容 → 圆积木进条件槽报;条件进圆槽编辑器允许,我们也放行")
    void connectionTypesMustMatch() {
        assertThat(firstError("when green flag clicked\nwait until (x position)")).contains("不是条件");
        assertThat(firstError("when green flag clicked\nif <(x position)> then\n  say [hi]\nend")).contains("只有一个值积木,不是条件");
        assertThat(firstError("when green flag clicked\nsay <touching [edge v]?>")).isEmpty();
    }

    @Test
    @DisplayName("xml.js 槽名/字段名不存在只是警告:名字全部来自积木表,合法程序一个警告都不该有")
    void namesComeFromTheTable() {
        Result result = ENGINE.compile("```\nwhen green flag clicked\nglide (1) secs to x: (0) y: (0)\nswitch costume to (costume1 v)\n```", null);
        assertThat(result.ok()).isTrue();
        assertThat(result.xml()).contains("name=\"SECS\"").contains("name=\"COSTUME\"");
    }
}
