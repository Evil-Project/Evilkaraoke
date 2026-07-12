package org.evilproject.evilkaraoke.server.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

class TickTaskSchedulerTest {
    @Test
    void callbackCanScheduleAnotherTaskWhileOtherTasksRemainPending() {
        TickTaskScheduler scheduler = new TickTaskScheduler(Logger.getLogger("test"));
        List<String> events = new ArrayList<>();
        scheduler.schedule(() -> {
            events.add("first");
            scheduler.schedule(() -> events.add("rescheduled"), 1L);
        }, 1L);
        scheduler.schedule(() -> events.add("second"), 2L);

        assertDoesNotThrow(scheduler::tick);
        assertEquals(List.of("first"), events);

        scheduler.tick();
        assertEquals(List.of("first", "second", "rescheduled"), events);
    }

    @Test
    void callbackCanCancelAnotherPendingTask() {
        TickTaskScheduler scheduler = new TickTaskScheduler(Logger.getLogger("test"));
        List<String> events = new ArrayList<>();
        int cancelled = scheduler.schedule(() -> events.add("cancelled"), 2L);
        scheduler.schedule(() -> {
            events.add("canceller");
            scheduler.cancel(cancelled);
        }, 1L);

        assertDoesNotThrow(scheduler::tick);
        scheduler.tick();

        assertEquals(List.of("canceller"), events);
    }
}
