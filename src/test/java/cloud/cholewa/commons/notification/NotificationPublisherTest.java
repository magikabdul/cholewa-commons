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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
@ExtendWith(MockitoExtension.class)
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

    //the known limit: a confirm that comes after the timeout changes nothing any more - the publish
    //has failed, and a caller that repeats it sends the notification a second time
    @Test
    void should_stay_failed_when_the_confirmation_comes_after_the_timeout() {
        final AtomicReference<CorrelationData> sent = new AtomicReference<>();
        brokerAnswers(sent::set);

        StepVerifier.withVirtualTime(() -> sut.publishAlert("Sensor is not reporting"))
            .thenAwait(NotificationPublisher.DEFAULT_CONFIRM_TIMEOUT.plus(Duration.ofSeconds(1)))
            .verifyError(NotificationPublishException.class);

        sent.get().getFuture().complete(new CorrelationData.Confirm(true, null));
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

    private void brokerAnswers(final Consumer<CorrelationData> outcome) {
        doAnswer(invocation -> {
            outcome.accept(invocation.getArgument(3));
            return null;
        }).when(rabbitOperations).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
}
