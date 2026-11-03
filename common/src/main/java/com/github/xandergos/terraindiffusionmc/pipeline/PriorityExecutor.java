package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// A fixed pool that runs its most urgent task first; a task waits at the urgency of whoever waits for it
final class PriorityExecutor {

    // The main thread is stalled, so the game is visibly frozen until this lands
    static final int URGENT = 0;
    // A chunk worker is waiting for it
    static final int BLOCKING = 5;
    // Nobody is waiting yet: explorer water, river regions built ahead of generation
    static final int BACKGROUND = 10;

    private static final AtomicLong SEQ = new AtomicLong();
    private static final ThreadLocal<PriorityExecutor> POOL = new ThreadLocal<>();
    // The task this thread is running, so work it hands on keeps its urgency
    private static final ThreadLocal<Task<?>> RUNNING = new ThreadLocal<>();

    private final ThreadPoolExecutor pool;

    PriorityExecutor(String threadName, int threads) {
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<Runnable>(),
                r -> {
                    Thread t = new Thread(() -> {
                        POOL.set(this);
                        r.run();
                    }, threadName);
                    t.setDaemon(true);
                    return t;
                });
    }

    private record Wait(PriorityExecutor pool, Task<?> task) {}

    static final class Task<T> extends FutureTask<T> implements Comparable<Task<?>> {
        private volatile int priority;
        private final long seq = SEQ.incrementAndGet();
        // What this running task is itself waiting for, so a raise reaches it too
        private volatile Wait awaiting;

        Task(Callable<T> task, int priority) {
            super(task);
            this.priority = priority;
        }

        @Override
        public void run() {
            Task<?> outer = RUNNING.get();
            RUNNING.set(this);
            try {
                super.run();
            } finally {
                RUNNING.set(outer);
            }
        }

        @Override
        public int compareTo(Task<?> other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(seq, other.seq);
        }
    }

    <T> Task<T> submit(Callable<T> task, int priority) {
        Task<T> queued = new Task<>(task, priority);
        execute(queued);
        return queued;
    }

    void execute(Task<?> task) {
        pool.execute(task);
    }

    // Runs task here at the caller's urgency and waits; runs it directly when the caller is this pool's thread
    <T> T call(Callable<T> task) {
        if (POOL.get() == this) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Task<T> queued = submit(task, callerPriority());
        Task<?> caller = RUNNING.get();
        if (caller != null) caller.awaiting = new Wait(this, queued);
        try {
            return join(queued);
        } finally {
            if (caller != null) caller.awaiting = null;
        }
    }

    // Lifts a task to the given urgency; a queued task moves up, a running one passes it on to what it awaits
    void raise(Task<?> task, int priority) {
        if (task.isDone() || task.priority <= priority) return;
        if (pool.getQueue().remove(task)) {
            task.priority = priority;
            pool.execute(task);
            return;
        }
        task.priority = priority;
        Wait awaiting = task.awaiting;
        if (awaiting != null) awaiting.pool.raise(awaiting.task, priority);
    }

    boolean isCurrent() {
        return POOL.get() == this;
    }

    boolean hasQueued() {
        return !pool.getQueue().isEmpty();
    }

    // How urgently the current thread needs what it asks for
    static int callerPriority() {
        Task<?> running = RUNNING.get();
        if (running != null) return running.priority;
        String name = Thread.currentThread().getName();
        return name.equals("Server thread") || name.equals("Render thread") ? URGENT : BLOCKING;
    }

    static <T> T join(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
