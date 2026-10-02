package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class SbEngine {
    private static final Pattern FENCE_HEAD = Pattern.compile("^\\s*```[^\\n]*\\n?");
    private static final Pattern FENCE_TAIL = Pattern.compile("\\n?```\\s*$");

    private final ScriptCompiler compiler;

    public SbEngine(ScriptCompiler compiler) {
        this.compiler = compiler;
    }

    /** 编译一段脚本(要带帽子) */
    public Result compile(String fenceText, Known known) {
        return compiler.compile(cleanCodeChunk(fenceText), known, false);
    }

    /** 只检查不写:讲解用的积木片段,不带帽子也行 */
    public Result check(String fenceText, Known known) {
        return compiler.compile(cleanCodeChunk(fenceText), known, true);
    }

    public static String cleanCodeChunk(String codeChunk) {
        String out = FENCE_HEAD.matcher(String.valueOf(codeChunk)).replaceFirst("");
        out = FENCE_TAIL.matcher(out).replaceFirst("");
        return out.trim();
    }
}
