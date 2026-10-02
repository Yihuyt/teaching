package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.error.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在学生浏览器里执行的工具调用的等待簿:服务端把调用经 SSE 推给前端,前端经编辑器桥执行后回传结果,
 * 等在这里的虚拟线程被唤醒。单实例部署,内存即可;超时即视为浏览器没有响应。
 */
@Component
public class BrowserToolCalls {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    public String open() {
        String callId = UUID.randomUUID().toString();
        pending.put(callId, new CompletableFuture<>());
        return callId;
    }

    public JsonNode await(String callId) {
        CompletableFuture<JsonNode> future = pending.get(callId);
        try {
            return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("编辑器没有响应");
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception.getCause().getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待编辑器时被中断");
        } finally {
            pending.remove(callId);
        }
    }

    public void complete(String callId, boolean ok, JsonNode result, String error) {
        CompletableFuture<JsonNode> future = pending.get(callId);
        if (future == null) {
            throw new NotFoundException("这次调用已经结束或不存在");
        }
        if (ok) {
            future.complete(result);
        } else {
            future.completeExceptionally(new IllegalStateException(error == null || error.isBlank() ? "编辑器执行失败" : error));
        }
    }
}
