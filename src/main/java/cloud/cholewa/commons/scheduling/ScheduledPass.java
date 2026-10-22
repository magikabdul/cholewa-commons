package cloud.cholewa.commons.scheduling;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One pass of a reactive {@code @Scheduled} method, bounded in time (HAS-300). Spring blocks a thread
 * of the task scheduler until the publisher of a fixed-delay task completes, so a pass that never
 * completes - a call through a connection that died, a device that never answers - would hold that
 * thread for good and the task would never run again. Every reactive {@code @Scheduled} method of a
 * service goes through here:
 *
 * <pre>{@code
 * @Scheduled(fixedDelayString = "PT1M")
 * Mono<Void> pass() {
 *     return ScheduledPass.bounded("blinds state poll", Duration.ofSeconds(50), this::pollAll);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>the pass is built anew for every run ({@link Mono#defer}): Spring subscribes again and again
 *       to the one {@code Mono} the method returned, so anything read while building it - "now"
 *       included - would otherwise be frozen at the start of the pod;</li>
 *   <li>a pass longer than {@code limit} is cancelled - what it waited for is cancelled with it - and
 *       a WARN names the task; a pass that fails is a WARN with its cause. Either way the returned
 *       {@code Mono} completes, so the next run happens on schedule;</li>
 *   <li>the limit belongs to the task: below its fixed delay, above what a healthy pass takes. A pass
 *       with commands that must not be cut off halfway keeps them outside the bounded part.</li>
 * </ul>
 * With the pool of {@link SchedulingPoolEnvironmentPostProcessor}, a pass that hangs until its limit
 * holds one thread while the other tasks keep their schedule.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ScheduledPass {

    /**
     * The pass of {@code task}, built by {@code pass} for every run, ended at {@code limit}.
     *
     * @param task  what the log calls the task, e.g. {@code "blinds schedule engine"}
     * @param limit how long one pass may take; more than zero
     * @param pass  builds the pass - called once for every run
     * @return a {@code Mono} that completes after every pass, however it ended
     * @throws IllegalArgumentException when the task has no name or the limit is not positive
     */
    public static Mono<Void> bounded(final String task, final Duration limit, final Supplier<? extends Mono<?>> pass) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("a scheduled pass needs the name of its task");
        }
        if (limit == null || limit.isNegative() || limit.isZero()) {
            throw new IllegalArgumentException("the limit of the scheduled pass [" + task + "] must be more than zero");
        }
        Objects.requireNonNull(pass, "pass");
        return Mono.defer(pass)
            //a marker of its own: a TimeoutException from inside the pass (a client's timeout) is a
            //failure of the pass, not its limit
            .timeout(limit, Mono.error(LimitReached::new))
            .then()
            .onErrorResume(failure -> {
                if (failure instanceof LimitReached) {
                    log.warn("Scheduled pass [{}] did not complete within {} and was cancelled; the next one runs on schedule",
                        task, limit);
                } else {
                    log.warn("Scheduled pass [{}] failed; the next one runs on schedule: {}", task, failure.toString());
                }
                return Mono.empty();
            });
    }

    //the limit of the pass was reached; never leaves this class, so it needs no stack trace
    private static final class LimitReached extends RuntimeException {

        private LimitReached() {
            super(null, null, false, false);
        }
    }
}
