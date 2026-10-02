package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.blockcoding.application.ChatViews.InsertView;
import cn.utcy.teaching.blockcoding.application.agent.Browser;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

final class EditorBridge implements Browser {
    private final SseSupport.EventSink sink;
    private final BrowserToolCalls calls;

    EditorBridge(SseSupport.EventSink sink, BrowserToolCalls calls) {
        this.sink = sink;
        this.calls = calls;
    }

    @Override
    public String createSprite(String name) {
        return call("create_sprite", Map.of("name", name)).path("name").asText(name);
    }

    @Override
    public String insertScript(Script script) {
        JsonNode result = call("insert_script", Map.of("script", InsertView.of(script)));
        if (!result.hasNonNull("blockId")) {
            throw new IllegalStateException("编辑器没有返回插入后的积木 id");
        }
        return result.get("blockId").asText();
    }

    @Override
    public void removeScript(String sprite, String blockId) {
        if (blockId == null) {
            throw new IllegalStateException("要删的脚本(角色 " + sprite + ")在编辑器里没有积木 id");
        }
        call("remove_script", Map.of("sprite", sprite, "blockId", blockId));
    }

    @Override
    public void deleteVariable(String sprite, String name, boolean list) {
        Map<String, Object> args = new java.util.HashMap<>();
        args.put("sprite", sprite);
        args.put("name", name);
        args.put("list", list);
        call("delete_variable", args);
    }

    @Override
    public void deleteSprite(String name) {
        call("delete_sprite", Map.of("name", name));
    }

    private JsonNode call(String name, Map<String, Object> args) {
        // 连接已断就没有人会执行:立刻报错,不空等超时
        if (sink.cancelled()) {
            throw new IllegalStateException("和编辑器的连接已断开");
        }
        String callId = calls.open();
        sink.emit(Map.of("type", "browser_tool", "callId", callId, "name", name, "args", args));
        return calls.await(callId);
    }
}
