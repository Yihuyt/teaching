package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * vendored scratchblocks 解析器的 JVM 封装。
 *
 * 复用的现成库(classpath 资源 scratchblocks-bundle.js，scratchblocks 解析器的 esbuild 单文件)在 GraalJS 里求值，
 * 暴露唯一函数 ScratchblocksJson.parse(code, languages) → 解析树 JSON。
 * 树在 JS 侧序列化，Java 侧物化成 SbNode——审计/生成层(纯 Java)不接触
 * polyglot 对象；救援规则的"改行重解析"就是再调一次 parse。
 *
 * Context 非线程安全：固定大小池，编译请求借还。
 */
@Component
public class SbParser implements AutoCloseable {
    public static final String PARSE_LANGUAGES = "en,zh-cn";
    private static final int POOL_SIZE = 4;

    private final Engine engine;
    private final BlockingQueue<Context> pool;
    private final ObjectMapper objectMapper;

    public SbParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.engine = Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        this.pool = new ArrayBlockingQueue<>(POOL_SIZE);
        Source bundle = loadBundle();
        for (int i = 0; i < POOL_SIZE; i++) {
            Context context = Context.newBuilder("js").engine(engine).build();
            context.eval(bundle);
            pool.add(context);
        }
    }

    public List<SbNode.Script> parse(String code) {
        Context context;
        try {
            context = pool.take();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待解析器实例时线程被中断", exception);
        }
        try {
            Value fn = context.getBindings("js")
                    .getMember("ScratchblocksJson")
                    .getMember("parse");
            String json = fn.execute(code, PARSE_LANGUAGES).asString();
            JsonNode root = objectMapper.readTree(json);
            return SbNode.scriptsFrom(root);
        } catch (IOException exception) {
            throw new IllegalStateException("解析树 JSON 反序列化失败", exception);
        } finally {
            pool.add(context);
        }
    }

    private static Source loadBundle() {
        try (InputStream stream =
                     SbParser.class.getResourceAsStream("/blockcoding/scratchblocks-bundle.js")) {
            if (stream == null) {
                throw new IllegalStateException("缺少解析器资源 blockcoding/scratchblocks-bundle.js");
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return Source.newBuilder("js", source, "scratchblocks-bundle.js").build();
        } catch (IOException exception) {
            throw new IllegalStateException("读取解析器资源失败", exception);
        }
    }

    @Override
    public void close() {
        pool.forEach(Context::close);
        engine.close();
    }
}
