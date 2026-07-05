package org.evilproject.evilkaraoke.server.platform;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class TickTaskScheduler {
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, ScheduledTask> tasks = new LinkedHashMap<>();
    private final Logger logger;

    public TickTaskScheduler(Logger logger) {
        this.logger = logger;
    }

    public int schedule(Runnable task, long delayTicks) {
        int id = ids.incrementAndGet();
        tasks.put(id, new ScheduledTask(Math.max(1L, delayTicks), task));
        return id;
    }

    public void cancel(int id) {
        tasks.remove(id);
    }

    public void tick() {
        Iterator<Map.Entry<Integer, ScheduledTask>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ScheduledTask> entry = iterator.next();
            ScheduledTask task = entry.getValue();
            task.remainingTicks--;
            if (task.remainingTicks <= 0L) {
                iterator.remove();
                try {
                    task.task.run();
                } catch (RuntimeException ex) {
                    logger.log(Level.WARNING, "Scheduled Evilkaraoke task failed", ex);
                }
            }
        }
    }

    private static final class ScheduledTask {
        private long remainingTicks;
        private final Runnable task;

        private ScheduledTask(long remainingTicks, Runnable task) {
            this.remainingTicks = remainingTicks;
            this.task = task;
        }
    }
}
