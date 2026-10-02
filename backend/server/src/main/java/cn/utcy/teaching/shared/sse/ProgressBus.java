package cn.utcy.teaching.shared.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 后台任务进度总线:任务与 SSE attach 解耦——任务只管 publish,浏览器随时 attach
 * (先重放已发生的事件快照,再续接实时流),断线重连不影响任务。
 * 通道按字符串键区分(教材构建、知识库入库/重建共用);事件以 JSON 文本缓存(内存,任务终结后清理);
 * attach 前无任务时由调用方按持久化状态回答。
 */
@Component
public class ProgressBus {

    private static final int MAX_SNAPSHOT_EVENTS = 500;

    private final ObjectMapper objectMapper;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public ProgressBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static final class Channel {
        final ReentrantLock lock = new ReentrantLock();
        final List<String> snapshot = new ArrayList<>();
        final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    }

    public void open(String key) {
        channels.put(key, new Channel());
    }

    public void publish(String key, Object event) {
        Channel channel = channels.get(key);
        if (channel == null) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (IOException exception) {
            throw new IllegalStateException("进度事件序列化失败", exception);
        }
        // 任务可能从多个工作线程 publish;SseEmitter.send 不是线程安全的,按通道串行
        // (显式锁而非 synchronized:发送方可能是虚拟线程,阻塞写不能钉住载体线程)
        channel.lock.lock();
        try {
            if (channel.snapshot.size() < MAX_SNAPSHOT_EVENTS) {
                channel.snapshot.add(json);
            }
            for (SseEmitter emitter : channel.emitters) {
                try {
                    emitter.send(SseEmitter.event().data(json));
                } catch (IOException | IllegalStateException exception) {
                    channel.emitters.remove(emitter);
                }
            }
        } finally {
            channel.lock.unlock();
        }
    }

    /** 任务终结(done/error 事件已 publish):通知在场 emitter 关闭并回收通道 */
    public void terminate(String key) {
        Channel channel = channels.remove(key);
        if (channel == null) {
            return;
        }
        for (SseEmitter emitter : channel.emitters) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // 客户端已断开
            }
        }
    }

    /**
     * attach:重放快照后续流。任务不在运行(无通道)时返回 null,
     * 由调用方按持久化状态直接回放终态事件。
     */
    public SseEmitter attach(String key) {
        Channel channel = channels.get(key);
        if (channel == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(0L);
        List<String> replay;
        channel.lock.lock();
        try {
            replay = new ArrayList<>(channel.snapshot);
            channel.emitters.add(emitter);
        } finally {
            channel.lock.unlock();
        }
        try {
            for (String json : replay) {
                emitter.send(SseEmitter.event().data(json));
            }
        } catch (IOException | IllegalStateException exception) {
            channel.emitters.remove(emitter);
            return emitter;
        }
        emitter.onCompletion(() -> channel.emitters.remove(emitter));
        emitter.onError(error -> channel.emitters.remove(emitter));
        emitter.onTimeout(() -> channel.emitters.remove(emitter));
        return emitter;
    }
}
