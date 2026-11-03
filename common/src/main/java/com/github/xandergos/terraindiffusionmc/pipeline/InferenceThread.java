package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// The one thread that owns the ONNX sessions and the tensor caches; every other thread hands it work
public final class InferenceThread {

    // The main thread is stalled, so the game is visibly frozen until this lands
    static final int PRIORITY_URGENT = 0;
    static final int PRIORITY_BLOCKING = 5;
    // Explorer water runs only when nothing else is waiting
    static final int PRIORITY_BACKGROUND = 10;

    private static final AtomicLong TASK_SEQ = new AtomicLong();
    private static volatile Thread thread;

    // Ordered by priority so a caller waiting on a result is served before speculative work
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<Runnable>(),
            r -> {
                Thread t = new Thread(r, "terrain-diffusion-inference");
                t.setDaemon(true);
                thread = t;
                return t;
            });

    static final class Task<T> extends FutureTask<T> implements Comparable<Task<?>> {
        private final int priority;
        private final long seq = TASK_SEQ.incrementAndGet();

        Task(Callable<T> task, int priority) {
            super(task);
            this.priority = priority;
        }

        @Override
        public int compareTo(Task<?> other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(seq, other.seq);
        }
    }

    private InferenceThread() {
    }

    // Runs task there at the caller's priority and waits for it; runs it directly when already there
    public static <T> T call(Callable<T> task) {
        try {
            if (Thread.currentThread() == thread) {
                return task.call();
            }
            return submit(task, waitingPriority()).get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new RuntimeException(e.getCause());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static <T> Task<T> submit(Callable<T> task, int priority) {
        Task<T> queued = new Task<>(task, priority);
        EXECUTOR.execute(queued);
        return queued;
    }

    // A stalled main thread outranks a chunk worker, which outranks work nobody is waiting on yet
    private static int waitingPriority() {
        String name = Thread.currentThread().getName();
        if (name.equals("Server thread") || name.equals("Render thread")) return PRIORITY_URGENT;
        return name.equals(RiverHydrology.PREFETCH_THREAD) ? PRIORITY_BACKGROUND : PRIORITY_BLOCKING;
    }
}
