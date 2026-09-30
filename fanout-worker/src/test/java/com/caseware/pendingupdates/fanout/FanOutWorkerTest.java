package com.caseware.pendingupdates.fanout;

import static com.caseware.pendingupdates.fanout.DownstreamException.Kind.OVERLOADED;
import static com.caseware.pendingupdates.fanout.DownstreamException.Kind.PERMANENT;
import static com.caseware.pendingupdates.fanout.DownstreamException.Kind.TRANSIENT;
import static com.caseware.pendingupdates.fanout.InMemoryEngagementStateStore.Status.FAILED;
import static com.caseware.pendingupdates.fanout.InMemoryEngagementStateStore.Status.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Each test adds some files to the tracking table, starts the worker, waits for it to finish,
 * and checks what happened.
 */
class FanOutWorkerTest {

    private static final TemplatePublished PUBLISH = new TemplatePublished("pub-1", "audit", "CA", 7, Instant.now());
    private static final long PAUSE_MS = 300;

    private final InMemoryEngagementStateStore table = new InMemoryEngagementStateStore(Clock.systemUTC());
    private final FakeEngagementLoader service = new FakeEngagementLoader();
    private FanOutWorker worker;

    @AfterEach
    void stopWorker() {
        if (worker != null) worker.close();
    }

    @Test
    void checksEveryFileWithoutGoingOverTheCallLimit() throws Exception {
        addFiles(30);
        startWorker(3);

        waitUntil(() -> countOf(VERIFIED) == 30);

        assertEquals(30, service.totalCalls());
        assertTrue(service.mostCallsAtOnce() <= 3);
    }

    @Test
    void sameMessageTwiceDoesNotCheckAFileTwice() throws Exception {
        addFiles(5);
        startWorker(2);
        worker.onTemplatePublished(PUBLISH);
        worker.onTemplatePublished(PUBLISH);

        waitUntil(() -> countOf(VERIFIED) == 5);

        assertEquals(5, service.totalCalls());
    }

    @Test
    void skipsAFileThatWasCheckedWhileWaiting() throws Exception {
        addFiles(1);
        worker = newWorker(1);
        worker.sweep();                                                        // the file is now waiting in line
        table.recordFromEvent("file-0", version(6), Instant.now().plusSeconds(1)); // a user opens it meanwhile
        worker.start();

        waitUntil(() -> worker.metrics().skippedAlreadyVerified() == 1);

        assertEquals(0, service.totalCalls());
    }

    @Test
    void olderAnswerNeverReplacesNewerOne() {
        addFiles(1);
        Instant tenOClock = Instant.parse("2026-03-01T10:00:00Z");

        table.recordFromEvent("file-0", version(6), tenOClock.plusSeconds(60)); // user applied v6 at 10:01
        table.recordVerified("file-0", version(5), tenOClock);                  // slow check started at 10:00

        assertEquals(6, table.row("file-0").binding.version());
    }

    @Test
    void temporaryErrorIsRetried() throws Exception {
        addFiles(1);
        service.failFirstCalls("file-0", 2, TRANSIENT);
        startWorker(1);

        waitUntil(() -> countOf(VERIFIED) == 1);

        assertEquals(3, service.callsFor("file-0")); // 2 failures, then success
    }

    @Test
    void permanentErrorIsNotRetried() throws Exception {
        addFiles(1);
        service.alwaysFail("file-0", PERMANENT);
        startWorker(1);

        waitUntil(() -> countOf(FAILED) == 1);

        assertEquals(1, service.callsFor("file-0"));
    }

    @Test
    void givesUpAfterThreeTries() throws Exception {
        addFiles(1);
        service.alwaysFail("file-0", TRANSIENT);
        startWorker(1);

        waitUntil(() -> countOf(FAILED) == 1);

        assertEquals(3, service.callsFor("file-0"));
    }

    @Test
    void pausesWhenTheServiceIsTooBusy() throws Exception {
        addFiles(2);
        service.failFirstCalls("file-0", 1, OVERLOADED);
        startWorker(1);

        waitUntil(() -> countOf(VERIFIED) == 2);

        long gapBetweenFirstTwoCalls = service.callStartTimes().get(1) - service.callStartTimes().get(0);
        assertTrue(gapBetweenFirstTwoCalls >= PAUSE_MS);
    }

    @Test
    void beingToldTooBusyDoesNotUseUpTheRetries() throws Exception {
        addFiles(1);
        service.failFirstCalls("file-0", 4, OVERLOADED); // more "too busy" answers than the 3 allowed tries
        startWorker(1);

        waitUntil(() -> countOf(VERIFIED) == 1);

        assertEquals(0, countOf(FAILED));
        assertEquals(5, service.callsFor("file-0")); // 4 "too busy", then success
    }

    @Test
    void anotherWorkerTakesOverWhenTheFirstOneDiedHoldingTheReservation() throws Exception {
        addFiles(1);
        // Worker A reserves the file for 300 ms and then "crashes": it never saves an answer
        // and never releases the reservation.
        Instant now = Instant.now();
        table.tryClaim("file-0", "worker-A", now.plusMillis(300), now);

        // Worker B checks the table every 100 ms.
        FanOutConfig settings = new FanOutConfig("worker-B", 1, 1_000, 50,
                Duration.ofMillis(100), Duration.ofSeconds(30), 3,
                Duration.ofMillis(10), Duration.ofMillis(40), Duration.ofMillis(PAUSE_MS));
        worker = new FanOutWorker(settings, table, service, Clock.systemUTC());
        worker.start();

        Thread.sleep(150);
        assertEquals(0, service.totalCalls()); // the reservation is still valid, so B must not touch the file

        waitUntil(() -> countOf(VERIFIED) == 1); // once it expires, B finishes the job
        assertEquals(1, service.callsFor("file-0"));
    }

    // ---- helpers ----

    private void addFiles(int count) {
        for (int i = 0; i < count; i++) {
            table.addUnknown("file-" + i, "firm-" + (i % 3));
        }
    }

    private FanOutWorker newWorker(int helpers) {
        FanOutConfig settings = new FanOutConfig("test-worker", helpers, 1_000, 50,
                Duration.ofHours(1),           // look in the table on start only
                Duration.ofSeconds(30),        // reservation length
                3,                             // max tries
                Duration.ofMillis(10), Duration.ofMillis(40), // retry waits
                Duration.ofMillis(PAUSE_MS));  // pause when the service is too busy
        return new FanOutWorker(settings, table, service, Clock.systemUTC());
    }

    private void startWorker(int helpers) {
        worker = newWorker(helpers);
        worker.start();
    }

    private long countOf(InMemoryEngagementStateStore.Status status) {
        return table.countWithStatus(status);
    }

    private static TemplateBinding version(int number) {
        return new TemplateBinding("audit", "CA", number);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting");
            Thread.sleep(10);
        }
    }
}
