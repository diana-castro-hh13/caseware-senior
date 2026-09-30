package com.caseware.pendingupdates.fanout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class FairWorkQueueTest {

    @Test
    void takesTurnsBetweenFirms() throws Exception {
        FairWorkQueue line = new FairWorkQueue(10);
        line.offer(job("big-1", "big"));
        line.offer(job("big-2", "big"));
        line.offer(job("big-3", "big"));
        line.offer(job("small-1", "small"));

        // The small firm gets the second turn instead of waiting behind all of the big firm's jobs.
        assertEquals(List.of("big", "small", "big", "big"),
                List.of(nextFirm(line), nextFirm(line), nextFirm(line), nextFirm(line)));
    }

    @Test
    void refusesJobsWhenFull() {
        FairWorkQueue line = new FairWorkQueue(1);
        line.offer(job("a-1", "a"));

        assertFalse(line.offer(job("b-1", "b")));
    }

    private static WorkItem job(String file, String firm) {
        return new WorkItem(new EngagementRef(file, firm), Instant.now(), 0);
    }

    private static String nextFirm(FairWorkQueue line) throws InterruptedException {
        return line.poll(Duration.ofMillis(10)).firmId();
    }
}
