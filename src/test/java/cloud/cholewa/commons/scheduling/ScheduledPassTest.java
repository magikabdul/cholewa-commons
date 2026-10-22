package cloud.cholewa.commons.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//HAS-300: one pass of a reactive @Scheduled method - built anew for every run, ended at its limit,
//and always completing, so the next run happens
class ScheduledPassTest {

    private static final Duration LIMIT = Duration.ofMillis(200);
    private static final Duration WITHIN = Duration.ofSeconds(5);

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureTheLog() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(ScheduledPass.class)).addAppender(logged);
    }

    @AfterEach
    void releaseTheLog() {
        ((Logger) LoggerFactory.getLogger(ScheduledPass.class)).detachAppender(logged);
    }

    @Test
    void should_complete_after_a_pass_that_completes_and_log_nothing() {
        ScheduledPass.bounded("state poll", LIMIT, () -> Mono.just("read"))
            .as(StepVerifier::create)
            .expectComplete()
            .verify(WITHIN);

        assertThat(logged.list).isEmpty();
    }

    //Spring subscribes again to the one Mono the method returned: what the pass reads has to be read
    //at every run, not when the Mono was built
    @Test
    void should_build_the_pass_anew_for_every_run() {
        final AtomicInteger built = new AtomicInteger();
        final Mono<Void> scheduled = ScheduledPass.bounded("state poll", LIMIT, () -> {
            built.incrementAndGet();
            return Mono.empty();
        });

        assertThat(built).hasValue(0);
        scheduled.as(StepVerifier::create).expectComplete().verify(WITHIN);
        scheduled.as(StepVerifier::create).expectComplete().verify(WITHIN);

        assertThat(built).hasValue(2);
    }

    //blinds-service, 2026-10-22: a pass that never completed held the scheduler for 1.5 hours
    @Test
    void should_end_a_pass_that_never_completes_at_its_limit_and_cancel_what_it_waited_for() {
        final AtomicBoolean cancelled = new AtomicBoolean();

        ScheduledPass.bounded("blinds schedule engine", LIMIT,
                () -> Mono.never().doOnCancel(() -> cancelled.set(true)))
            .as(StepVerifier::create)
            .expectComplete()
            .verify(WITHIN);

        assertThat(cancelled).isTrue();
        assertThat(logged.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage())
                .isEqualTo("Scheduled pass [blinds schedule engine] did not complete within PT0.2S and was cancelled;"
                    + " the next one runs on schedule");
        });
    }

    @Test
    void should_complete_after_a_pass_that_fails_and_name_the_cause() {
        ScheduledPass.bounded("registry refresh", LIMIT,
                () -> Mono.error(new IllegalStateException("registry unavailable")))
            .as(StepVerifier::create)
            .expectComplete()
            .verify(WITHIN);

        assertThat(logged.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).isEqualTo("Scheduled pass [registry refresh] failed; the next one"
                + " runs on schedule: java.lang.IllegalStateException: registry unavailable");
        });
    }

    //a timeout of a client inside the pass is the pass failing, not the limit of the pass being reached
    @Test
    void should_tell_a_timeout_from_inside_the_pass_from_its_limit() {
        ScheduledPass.bounded("state poll", LIMIT, () -> Mono.error(new TimeoutException("device did not answer")))
            .as(StepVerifier::create)
            .expectComplete()
            .verify(WITHIN);

        assertThat(logged.list).singleElement()
            .extracting(ILoggingEvent::getFormattedMessage)
            .asString()
            .startsWith("Scheduled pass [state poll] failed;")
            .contains("device did not answer");
    }

    @Test
    void should_refuse_a_pass_without_a_name_or_a_positive_limit() {
        assertThatThrownBy(() -> ScheduledPass.bounded(" ", LIMIT, Mono::empty))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("a scheduled pass needs the name of its task");
        assertThatThrownBy(() -> ScheduledPass.bounded("state poll", Duration.ZERO, Mono::empty))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("the limit of the scheduled pass [state poll] must be more than zero");
        assertThatThrownBy(() -> ScheduledPass.bounded("state poll", Duration.ofSeconds(-1), Mono::empty))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledPass.bounded("state poll", null, Mono::empty))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScheduledPass.bounded("state poll", LIMIT, null))
            .isInstanceOf(NullPointerException.class);
    }
}
