package com.caseware.pendingupdates.fanout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the other team's service. It answers in 10 ms instead of 1 minute,
 * and can be told to fail on purpose.
 */
final class FakeEngagementLoader implements EngagementLoaderClient {

    private final Map<String, Integer> callsPerFile = new ConcurrentHashMap<>();
    private final Map<String, Integer> failuresLeft = new ConcurrentHashMap<>();
    private final Map<String, DownstreamException.Kind> failureKind = new ConcurrentHashMap<>();
    private final AtomicInteger runningNow = new AtomicInteger();
    private final AtomicInteger mostAtOnce = new AtomicInteger();
    private final List<Long> callStartTimes = new CopyOnWriteArrayList<>();

    void failFirstCalls(String file, int times, DownstreamException.Kind kind) {
        failuresLeft.put(file, times);
        failureKind.put(file, kind);
    }

    void alwaysFail(String file, DownstreamException.Kind kind) {
        failFirstCalls(file, Integer.MAX_VALUE, kind);
    }

    @Override
    public TemplateBinding readTemplateBinding(String file) throws DownstreamException {
        callStartTimes.add(System.currentTimeMillis());
        callsPerFile.merge(file, 1, Integer::sum);
        mostAtOnce.accumulateAndGet(runningNow.incrementAndGet(), Math::max);
        try {
            Thread.sleep(10);
            if (failuresLeft.getOrDefault(file, 0) > 0) {
                failuresLeft.merge(file, -1, Integer::sum);
                throw new DownstreamException(failureKind.get(file), "failing on purpose");
            }
            return new TemplateBinding("audit", "CA", 5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(DownstreamException.Kind.TRANSIENT, "interrupted");
        } finally {
            runningNow.decrementAndGet();
        }
    }

    int callsFor(String file) {
        return callsPerFile.getOrDefault(file, 0);
    }

    int totalCalls() {
        return callsPerFile.values().stream().mapToInt(Integer::intValue).sum();
    }

    int mostCallsAtOnce() {
        return mostAtOnce.get();
    }

    List<Long> callStartTimes() {
        return callStartTimes;
    }
}
