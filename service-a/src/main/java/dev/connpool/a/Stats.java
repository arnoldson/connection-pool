package dev.connpool.a;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-second counters for A, printed as CSV lines prefixed with {@code stats,} so the
 * run script can grep them out of the container log.
 *
 * <p>LongAdder rather than AtomicLong: many request threads increment and only the
 * reporter reads, once a second. LongAdder spreads increments across cells so threads
 * don't all contend on one memory location.
 */
public final class Stats {

    public static final String HEADER = "stats,window_end_epoch,ok,errors,distinct_local_ports,errors_by_type";

    private final LongAdder ok = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> errors = new ConcurrentHashMap<>();
    private final AtomicReference<Set<Integer>> localPorts = new AtomicReference<>(ConcurrentHashMap.newKeySet());
    private final PrintStream out;

    public Stats(PrintStream out) {
        this.out = out;
    }

    public void recordOk() {
        ok.increment();
    }

    public void recordLocalPort(int port) {
        localPorts.get().add(port);
    }

    public void recordError(Throwable e) {
        String key = (e.getClass().getName() + ": " + e.getMessage()).replaceAll("[,|=\\r\\n]", " ");
        LongAdder counter = errors.get(key);
        if (counter == null) {
            LongAdder fresh = new LongAdder();
            // putIfAbsent returns null only for the one thread that inserted it, so exactly
            // one stack trace is printed per error type.
            counter = errors.putIfAbsent(key, fresh);
            if (counter == null) {
                counter = fresh;
                out.println("error-first," + key);
                e.printStackTrace(out);
            }
        }
        counter.increment();
    }

    /** Prints one line per wall-clock second until the JVM exits. */
    public void startReporting() {
        out.println(HEADER);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("stats").factory());
        long delayToNextSecond = 1000 - System.currentTimeMillis() % 1000;
        timer.scheduleAtFixedRate(this::report, delayToNextSecond, 1000, TimeUnit.MILLISECONDS);
    }

    private void report() {
        long epoch = Math.round(System.currentTimeMillis() / 1000.0);
        long okCount = ok.sumThenReset();
        int distinctPorts = localPorts.getAndSet(ConcurrentHashMap.newKeySet()).size();

        long errorCount = 0;
        StringJoiner byType = new StringJoiner("|");
        for (Map.Entry<String, LongAdder> entry : new TreeMap<>(errors).entrySet()) {
            long n = entry.getValue().sumThenReset();
            if (n > 0) {
                errorCount += n;
                byType.add(entry.getKey() + "=" + n);
            }
        }
        out.println("stats," + epoch + "," + okCount + "," + errorCount + "," + distinctPorts + "," + byType);
    }
}
