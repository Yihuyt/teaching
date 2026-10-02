package cn.utcy.teaching.shared.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 有界并行:一组任务提交到执行器(虚拟线程,阻塞等待不占真实线程),在途 ≤ permits,
 * 提交方拿到许可才提交。任一任务失败即整体失败(首个失败原样抛出);
 * 取消后不再提交新任务,在途的跑完后抛「任务已取消」。平台内所有"并行跑一批、取消即停"的扇出都走这里。
 */
public final class BoundedParallel {

    public static final String CANCELLED = "任务已取消";

    private BoundedParallel() {
    }

    public static <T> List<T> run(Executor executor, int permits, List<? extends Supplier<T>> tasks,
                           BooleanSupplier cancelled) {
        Semaphore semaphore = new Semaphore(permits);
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        for (Supplier<T> task : tasks) {
            if (cancelled.getAsBoolean() || !acquire(semaphore, cancelled)) {
                break;
            }
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } finally {
                    semaphore.release();
                }
            }, executor));
        }
        List<T> results = new ArrayList<>(futures.size());
        RuntimeException failure = null;
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException exception) {
                if (failure == null) {
                    failure = exception.getCause() instanceof RuntimeException cause ? cause : exception;
                }
            }
        }
        requireNotCancelled(cancelled);
        if (failure != null) {
            throw failure;
        }
        return results;
    }

    public static void requireNotCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new IllegalStateException(CANCELLED);
        }
    }

    private static boolean acquire(Semaphore semaphore, BooleanSupplier cancelled) {
        try {
            while (!semaphore.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("任务线程被中断", exception);
        }
    }
}
