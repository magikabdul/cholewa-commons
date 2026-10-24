package cloud.cholewa.commons.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

//The header names, their values, the content type and the three error texts are what the services
//published before this class moved here - a consumer on the other side of the broker reads them
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class NotificationPublisherTest {

    @Mock
    private RabbitOperations rabbitOperations;
    @Captor
    private ArgumentCaptor<Message> messageCaptor;

    private NotificationPublisher sut;

    @BeforeEach
    void setUp() {
        sut = new NotificationPublisher(rabbitOperations, "notification", "dev");
    }

    @Test
    void should_publish_alert_as_plain_text_routed_by_headers() {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        sut.publishAlert("Sensor in room óffice is not reporting").as(StepVerifier::create).verifyComplete();

        verify(rabbitOperations).send(eq("notification"), eq(""), messageCaptor.capture(), any(CorrelationData.class));

        final Message message = messageCaptor.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8))
            .isEqualTo("Sensor in room óffice is not reporting");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("text/plain");
        assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(message.getMessageProperties().getHeaders())
            .containsOnlyKeys("category", "level", "env")
            .containsEntry("category", "alert")
            .containsEntry("level", "error")
            .containsEntry("env", "dev");
    }

    //the same queue as the alert, another color on the other side
    @Test
    void should_publish_reminder_as_alert_with_warn_level() {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        sut.publishReminder("Sensor is still not reporting").as(StepVerifier::create).verifyComplete();

        verify(rabbitOperations).send(eq("notification"), eq(""), messageCaptor.capture(), any(CorrelationData.class));

        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
            .containsEntry("category", "alert")
            .containsEntry("level", "warn")
            .containsEntry("env", "dev");
    }

    @Test
    void should_publish_info_with_info_category() {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        sut.publishInfo("Sensor is back").as(StepVerifier::create).verifyComplete();

        verify(rabbitOperations).send(eq("notification"), eq(""), messageCaptor.capture(), any(CorrelationData.class));

        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
            .containsEntry("category", "info")
            .containsEntry("level", "info")
            .containsEntry("env", "dev");
    }

    //HAS-277: the household category of notification-service - a JSON body it reads into the
    //HouseholdNotification of smart-home-sdk, sent as it was given, never through a converter
    @Test
    void should_publish_a_household_notification_as_json_with_the_household_category() {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));
        final String json = "{\"event\":\"appliance finished\",\"subject\":\"Zmywarka\",\"deviceId\":64,"
            + "\"occurredAt\":\"2026-10-24T19:40:00\",\"reminder\":0}";

        sut.publishHousehold(json).as(StepVerifier::create).verifyComplete();

        verify(rabbitOperations).send(eq("notification"), eq(""), messageCaptor.capture(), any(CorrelationData.class));

        final Message message = messageCaptor.getValue();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(json);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
        assertThat(message.getMessageProperties().getHeaders())
            .containsOnlyKeys("category", "level", "env")
            .containsEntry("category", "household")
            .containsEntry("level", "info")
            .containsEntry("env", "dev");
    }

    //the delivery feedback of the household notification is that of the others: a message no queue
    //takes (a broker without the household queues) is an error, not a success
    @Test
    void should_signal_error_when_no_queue_takes_a_household_notification() {
        brokerAnswers(correlationData -> {
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0]), 312, "NO_ROUTE", "notification", ""));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        sut.publishHousehold("{}")
            .as(StepVerifier::create)
            .expectErrorSatisfies(error -> assertThat(error)
                .isInstanceOf(NotificationPublishException.class)
                .hasMessage("Notification was not routed to any queue: NO_ROUTE"))
            .verify();
    }

    @Test
    void should_publish_to_the_exchange_and_env_it_was_given() {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        new NotificationPublisher(rabbitOperations, "household", "prod")
            .publishInfo("Sensor is back").as(StepVerifier::create).verifyComplete();

        verify(rabbitOperations).send(eq("household"), eq(""), messageCaptor.capture(), any(CorrelationData.class));
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders()).containsEntry("env", "prod");
    }

    //nothing is sent by calling a publish method - only by subscribing to what it returns
    @Test
    void should_send_nothing_until_subscribed() {
        final Mono<Void> publish = sut.publishAlert("Sensor is not reporting");

        assertThat(publish).isNotNull();
        verifyNoInteractions(rabbitOperations);
    }

    //the failure is a signal of the Mono, as the broker gave it - the caller decides whether it
    //is swallowed, and the method itself never throws
    @Test
    void should_signal_error_when_broker_is_unreachable() {
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
            .when(rabbitOperations).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        sut.publishAlert("Sensor is not reporting")
            .as(StepVerifier::create)
            .verifyError(AmqpConnectException.class);
    }

    //the way the callers use it: an error swallowed downstream ends the chain quietly
    @Test
    void should_let_the_caller_swallow_a_failed_publish() {
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
            .when(rabbitOperations).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        sut.publishAlert("Sensor is not reporting")
            .onErrorResume(throwable -> Mono.empty())
            .as(StepVerifier::create)
            .verifyComplete();
    }

    @Test
    void should_signal_error_when_broker_refuses_the_message() {
        brokerAnswers(correlationData ->
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "exchange not found")));

        sut.publishAlert("Sensor is not reporting")
            .as(StepVerifier::create)
            .verifyErrorMatches(throwable -> throwable instanceof NotificationPublishException
                && throwable.getMessage().equals("Notification refused by the broker: exchange not found"));
    }

    //the broker confirms an unroutable message as well - only the return says it reached no queue
    @Test
    void should_signal_error_when_message_matches_no_queue() {
        brokerAnswers(correlationData -> {
            correlationData.setReturned(new ReturnedMessage(
                new Message(new byte[0]), 312, "NO_ROUTE", "notification", ""));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
        });

        sut.publishAlert("Sensor is not reporting")
            .as(StepVerifier::create)
            .verifyErrorMatches(throwable -> throwable instanceof NotificationPublishException
                && throwable.getMessage().equals("Notification was not routed to any queue: NO_ROUTE"));
    }

    @Test
    void should_signal_error_when_broker_never_confirms() {
        StepVerifier.withVirtualTime(() -> sut.publishAlert("Sensor is not reporting"))
            .thenAwait(NotificationPublisher.DEFAULT_CONFIRM_TIMEOUT.plus(Duration.ofSeconds(1)))
            .verifyErrorMatches(throwable -> throwable instanceof NotificationPublishException
                && throwable.getMessage().equals("Notification not confirmed by the broker within PT10S")
                && throwable.getCause() instanceof TimeoutException);
    }

    @Test
    void should_wait_for_the_confirmation_as_long_as_it_was_told() {
        final NotificationPublisher patient =
            new NotificationPublisher(rabbitOperations, "notification", "dev", Duration.ofSeconds(25));

        StepVerifier.withVirtualTime(() -> patient.publishAlert("Sensor is not reporting"))
            .expectSubscription()
            .expectNoEvent(Duration.ofSeconds(24))
            .thenAwait(Duration.ofSeconds(2))
            .verifyErrorMatches(throwable -> throwable instanceof NotificationPublishException
                && throwable.getMessage().equals("Notification not confirmed by the broker within PT25S"));
    }

    //the known limit: a confirm that comes after the timeout changes nothing any more. The publish
    //has failed and has given the confirmation up - the future is cancelled, so the late answer of
    //the broker is not even taken - and a caller that repeats the publish sends the notification a
    //second time
    @Test
    void should_stay_failed_when_the_confirmation_comes_after_the_timeout(final CapturedOutput output) {
        final AtomicReference<CorrelationData> sent = new AtomicReference<>();
        brokerAnswers(sent::set);

        StepVerifier.withVirtualTime(() -> sut.publishAlert("Sensor is not reporting"))
            .thenAwait(NotificationPublisher.DEFAULT_CONFIRM_TIMEOUT.plus(Duration.ofSeconds(1)))
            .verifyError(NotificationPublishException.class);

        assertThat(sent.get().getFuture()).isCancelled();
        assertThat(sent.get().getFuture().complete(new CorrelationData.Confirm(true, null))).isFalse();
        assertThat(output.getAll()).doesNotContain("Published alert notification");
    }

    //the control of the assertion on the log above: a publish that worked does leave the line
    @Test
    void should_log_a_notification_that_was_published(final CapturedOutput output) {
        brokerAnswers(correlationData -> correlationData.getFuture().complete(new CorrelationData.Confirm(true, null)));

        sut.publishAlert("Sensor is not reporting").as(StepVerifier::create).verifyComplete();

        assertThat(output.getAll()).contains("Published alert notification (error): Sensor is not reporting");
    }

    //Spring AMQP completes the confirmation on the thread that reads the frames of the connection,
    //inside the lock of the channel. What a caller chains after a publish must not run there: a slow
    //step would hold up the heartbeats and every other confirm of the connection
    @Test
    void should_not_continue_on_the_thread_that_delivered_the_confirmation() {
        brokerAnswersFromTheConnectionThread(new CorrelationData.Confirm(true, null));

        sut.publishAlert("Sensor is not reporting")
            .then(Mono.fromSupplier(() -> Thread.currentThread().getName()))
            .as(StepVerifier::create)
            .assertNext(thread -> assertThat(thread).startsWith("boundedElastic-"))
            .verifyComplete();
    }

    @Test
    void should_not_fail_on_the_thread_that_delivered_the_refusal() {
        brokerAnswersFromTheConnectionThread(new CorrelationData.Confirm(false, "exchange not found"));

        sut.publishAlert("Sensor is not reporting")
            .then(Mono.just("not reached"))
            .onErrorResume(throwable -> Mono.just(Thread.currentThread().getName()))
            .as(StepVerifier::create)
            .assertNext(thread -> assertThat(thread).startsWith("boundedElastic-"))
            .verifyComplete();
    }

    //the timeout fires on a timer thread of Reactor, which is not the caller's to keep busy either
    @Test
    void should_not_fail_on_the_timer_thread_when_the_confirmation_never_comes() {
        new NotificationPublisher(rabbitOperations, "notification", "dev", Duration.ofMillis(50))
            .publishAlert("Sensor is not reporting")
            .then(Mono.just("not reached"))
            .onErrorResume(NotificationPublishException.class, throwable -> Mono.just(Thread.currentThread().getName()))
            .as(StepVerifier::create)
            .assertNext(thread -> assertThat(thread).startsWith("boundedElastic-"))
            .verifyComplete();
    }

    @Test
    void should_refuse_to_be_built_without_an_exchange_an_env_or_a_timeout() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationPublisher(rabbitOperations, " ", "dev"))
            .withMessage("The notification exchange must not be blank");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationPublisher(rabbitOperations, "notification", ""))
            .withMessage("The notification env must not be blank");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationPublisher(rabbitOperations, "notification", "dev", Duration.ZERO))
            .withMessage("The confirm timeout must be positive");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationPublisher(null, "notification", "dev"))
            .withMessage("RabbitOperations must not be null");
    }

    //the future is completed by a thread named like the one of the RabbitMQ client. The test control:
    //without the hand-over in the publisher the assertions on the thread fail with this name
    private void brokerAnswersFromTheConnectionThread(final CorrelationData.Confirm confirm) {
        final Executor connectionThread = task -> new Thread(task, "AMQP Connection test").start();

        //some time after the send, when the publisher has subscribed to the future - completed
        //before that, the future would answer on the subscribing thread and the test would prove
        //nothing
        brokerAnswers(correlationData -> CompletableFuture
            .delayedExecutor(200, TimeUnit.MILLISECONDS, connectionThread)
            .execute(() -> correlationData.getFuture().complete(confirm)));
    }

    private void brokerAnswers(final Consumer<CorrelationData> outcome) {
        doAnswer(invocation -> {
            outcome.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitOperations).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
