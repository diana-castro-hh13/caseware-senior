package com.caseware.pendingupdates.fanout;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The background checker (one per region).
 *
 * <p>Its job: find engagements whose template version we don't know yet, and ask the other team's
 * service to check them, one engagement at a time per helper thread.</p>
 *
 * <p>Important: when a template is published, we do NOT re-check every engagement. For engagements
 * we already know about, the "update pending" flag is worked out by a quick database query.
 * This worker is only for the ones we don't know about yet (mostly during the first rollout),
 * or ones we suspect are out of date because a message was lost.</p>
 *
 * <ul>
 *   <li><b>Never overload the other team:</b> there are exactly {@code maxConcurrentCalls} helper
 *       threads, so there can never be more calls at once than we agreed.</li>
 *   <li><b>Back off when they are busy:</b> if they say "too busy", every helper pauses, and the job
 *       is put back in line without using up one of its retry attempts.</li>
 *   <li><b>Be fair:</b> {@link FairWorkQueue} takes turns between firms.</li>
 *   <li><b>No duplicate work:</b> before each call we reserve the engagement in the table, and we skip
 *       it if it was already checked. Receiving the same message twice is harmless.</li>
 *   <li><b>Nothing is lost on a crash:</b> the table remembers what is still unchecked.</li>
 * </ul>
 */
public final class FanOutWorker implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(FanOutWorker.class.getName());
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(200);

    private final FanOutConfig config;
    private final EngagementStateStore store;
    private final EngagementLoaderClient loader;
    private final Clock clock;

    private final FairWorkQueue queue;
    /** Engagements this process is already handling (waiting, in progress, or waiting to retry). */
    private final Set<String> tracked = ConcurrentHashMap.newKeySet();
    private final FanOutMetrics metrics = new FanOutMetrics();
    private final AtomicLong pausedUntilMillis = new AtomicLong(0);
    private final AtomicInteger inFlight = new AtomicInteger();

    private final ReentrantLock sweepLock = new ReentrantLock();
    private String sweepCursor; // guarded by sweepLock

    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public FanOutWorker(FanOutConfig config, EngagementStateStore store, EngagementLoaderClient loader, Clock clock) {
        this.config = config;
        this.store = store;
        this.loader = loader;
        this.clock = clock;
        this.queue = new FairWorkQueue(config.queueCapacity());
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(config.maxConcurrentCalls(),
                r -> named(r, "fanout-worker-" + n.incrementAndGet()));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> named(r, "fanout-scheduler"));
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        for (int i = 0; i < config.maxConcurrentCalls(); i++) {
            workers.execute(this::workLoop);
        }
        // Look in the table regularly, in case a message was lost or a job was dropped.
        scheduler.scheduleWithFixedDelay(this::sweepSafely, 0,
                config.sweepInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Called when a template is published. It returns immediately. It only asks for a look in the
     * table for engagements we still need to check. It is safe to call twice for the same publish.
     */
    public void onTemplatePublished(TemplatePublished event) {
        metrics.publishesReceived.incrementAndGet();
        LOG.info(() -> "publish " + event.publishId() + " " + event.templateId() + "@" + event.branch()
                + "/v" + event.version() + " -> triggering verification sweep");
        if (running) {
            scheduler.execute(this::sweepSafely);
        }
    }

    /** Reads unchecked engagements from the table into the waiting line, until the line is full. */
    int sweep() {
        if (!sweepLock.tryLock()) {
            return 0; // another look is already running and will do the same work
        }
        try {
            Instant requestedAt = clock.instant();
            int added = 0;
            while (true) {
                int room = config.queueCapacity() - queue.size();
                if (room <= 0) {
                    return added;
                }
                List<EngagementRef> page = store.findUnverified(sweepCursor, Math.min(room, config.sweepPageSize()));
                if (page.isEmpty()) {
                    sweepCursor = null; // reached the end; next time start from the beginning
                    return added;
                }
                for (EngagementRef ref : page) {
                    if (tracked.add(ref.engagementId())) {
                        if (!queue.offer(new WorkItem(ref, requestedAt, 0))) {
                            tracked.remove(ref.engagementId());
                            return added; // line is full; next time continue from here
                        }
                        added++;
                        metrics.enqueued.incrementAndGet();
                    }
                    sweepCursor = ref.engagementId();
                }
            }
        } finally {
            sweepLock.unlock();
        }
    }

    private void sweepSafely() {
        try {
            sweep();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "sweep failed; will retry on next interval", e);
        }
    }

    private void workLoop() {
        while (running) {
            try {
                WorkItem item = queue.poll(POLL_TIMEOUT);
                if (item == null) {
                    continue;
                }
                waitWhilePaused();
                process(item);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "unexpected error in worker loop", e); // keep the helper alive
            }
        }
    }

    void process(WorkItem item) {
        String id = item.engagementId();
        Instant startedAt = clock.instant();

        boolean claimed;
        try {
            claimed = store.tryClaim(id, config.workerId(), startedAt.plus(config.lease()), item.requestedAt());
        } catch (RuntimeException e) {
            scheduleRetry(item, "store unavailable on claim: " + e.getMessage());
            return;
        }
        if (!claimed) {
            // Already checked another way, reserved by someone else, or marked FAILED. Skip it.
            metrics.skippedAlreadyVerified.incrementAndGet();
            tracked.remove(id);
            return;
        }

        try {
            metrics.downstreamCalls.incrementAndGet();
            inFlight.incrementAndGet();
            TemplateBinding binding;
            try {
                binding = loader.readTemplateBinding(id); // takes about 1 minute
            } finally {
                inFlight.decrementAndGet();
            }
            // We stamp the answer with the time the call STARTED. If the user applied an update
            // during that minute, their newer information wins and our older answer is ignored.
            store.recordVerified(id, binding, startedAt);
            metrics.verified.incrementAndGet();
            tracked.remove(id);
        } catch (DownstreamException e) {
            releaseQuietly(id);
            switch (e.kind()) {
                case PERMANENT -> fail(item, e.getMessage());
                case OVERLOADED -> {
                    // Being told "too busy" is not this engagement's fault, so it must not use up
                    // one of its attempts. We pause everyone and put the same job back in line.
                    metrics.overloadSignals.incrementAndGet();
                    pauseAll();
                    requeueWithoutPenalty(item);
                }
                case TRANSIENT -> scheduleRetry(item, e.getMessage());
            }
        } catch (RuntimeException e) {
            releaseQuietly(id);
            scheduleRetry(item, "unexpected: " + e);
        }
    }

    /** Puts the job back in line with the same attempt count. Used when the other team is too busy. */
    private void requeueWithoutPenalty(WorkItem item) {
        try {
            scheduler.schedule(() -> {
                if (!running || !queue.offer(item)) {
                    tracked.remove(item.engagementId()); // not lost: still unchecked in the table
                }
            }, config.retryBaseDelay().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { // we are shutting down
            tracked.remove(item.engagementId());
        }
    }

    private void scheduleRetry(WorkItem item, String reason) {
        WorkItem next = item.nextAttempt();
        if (next.attempt() >= config.maxAttempts()) {
            fail(item, "gave up after " + next.attempt() + " attempts: " + reason);
            return;
        }
        metrics.retriesScheduled.incrementAndGet();
        long delayMs = backoffMillis(next.attempt());
        try {
            scheduler.schedule(() -> {
                if (!running || !queue.offer(next)) {
                    tracked.remove(next.engagementId()); // not lost: still unchecked in the table
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) { // we are shutting down
            tracked.remove(next.engagementId());
        }
    }

    private void fail(WorkItem item, String reason) {
        try {
            store.recordPermanentFailure(item.engagementId(), reason);
            metrics.permanentFailures.incrementAndGet();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "could not mark FAILED; next sweep will retry " + item.engagementId(), e);
        } finally {
            tracked.remove(item.engagementId());
        }
    }

    /** Wait time before a retry: doubles each attempt, with some randomness so retries don't all arrive together. */
    long backoffMillis(int attempt) {
        long base = config.retryBaseDelay().toMillis();
        long max = config.retryMaxDelay().toMillis();
        long exp = base << Math.min(attempt - 1, 20);
        long capped = Math.min(Math.max(exp, 1), max);
        return ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
    }

    private void pauseAll() {
        long until = clock.millis() + config.overloadPause().toMillis();
        pausedUntilMillis.accumulateAndGet(until, Math::max);
    }

    private void waitWhilePaused() throws InterruptedException {
        long remaining;
        while (running && (remaining = pausedUntilMillis.get() - clock.millis()) > 0) {
            Thread.sleep(Math.min(remaining, 1_000));
        }
    }

    private void releaseQuietly(String engagementId) {
        try {
            store.releaseClaim(engagementId, config.workerId());
        } catch (RuntimeException e) {
            // Fine to ignore: the reservation expires on its own.
            LOG.log(Level.FINE, "release failed for " + engagementId, e);
        }
    }

    public FanOutMetrics.Snapshot metrics() {
        return metrics.snapshot(queue.size());
    }

    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void close() {
        running = false;
        scheduler.shutdownNow();
        workers.shutdownNow(); // unfinished reservations expire and get picked up again later
        try {
            workers.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread named(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
