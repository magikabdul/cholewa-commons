package cloud.cholewa.commons.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Publishes a notification for the household: a plain text on a headers exchange, which routes it by
 * the headers {@code category} and {@code env}; the third header, {@code level}, tells the consumer
 * how to show it. The names and values of the headers are the wire contract with that consumer.
 * <p>
 * A publish completes only when the broker has taken the message into a queue - confirmed and not
 * handed back - so a caller may record the notification as sent once the {@code Mono} completes.
 * Every other outcome is an error of the {@code Mono}: nothing is retried and nothing is swallowed
 * here, both are the caller's to decide.
 * <p>
 * The {@link RabbitOperations} have to publish with correlated confirms, publisher returns and the
 * mandatory flag. Without the confirms every publish ends in the timeout; without the other two a
 * message that matches no queue is reported as delivered.
 */
@Slf4j
public class NotificationPublisher {

    public static final Duration DEFAULT_CONFIRM_TIMEOUT = Duration.ofSeconds(10);

    static final String CATEGORY_HEADER = "category";
    static final String ENV_HEADER = "env";
    static final String LEVEL_HEADER = "level";
    static final String CATEGORY_ALERT = "alert";
    static final String CATEGORY_INFO = "info";
    static final String LEVEL_ERROR = "error";
    static final String LEVEL_WARN = "warn";
    static final String LEVEL_INFO = "info";

    private final RabbitOperations rabbitOperations;
    private final String exchange;
    private final String env;
    private final Duration confirmTimeout;

    public NotificationPublisher(final RabbitOperations rabbitOperations, final String exchange, final String env) {
        this(rabbitOperations, exchange, env, DEFAULT_CONFIRM_TIMEOUT);
    }

    /**
     * @param exchange       the headers exchange the notifications are published to
     * @param env            the value of the {@code env} header the exchange routes by
     * @param confirmTimeout how long the confirmation of the broker is waited for
     */
    public NotificationPublisher(
        final RabbitOperations rabbitOperations,
        final String exchange,
        final String env,
        final Duration confirmTimeout
    ) {
        Assert.notNull(rabbitOperations, "RabbitOperations must not be null");
        Assert.hasText(exchange, "The notification exchange must not be blank");
        Assert.hasText(env, "The notification env must not be blank");
        Assert.isTrue(
            confirmTimeout != null && confirmTimeout.isPositive(), "The confirm timeout must be positive");

        this.rabbitOperations = rabbitOperations;
        this.exchange = exchange;
        this.env = env;
        this.confirmTimeout = confirmTimeout;
    }

    /** Something went wrong: category {@code alert}, level {@code error}. */
    public Mono<Void> publishAlert(final String text) {
        return publish(CATEGORY_ALERT, LEVEL_ERROR, text);
    }

    /** It is still wrong: the same queue as the alert, level {@code warn}. */
    public Mono<Void> publishReminder(final String text) {
        return publish(CATEGORY_ALERT, LEVEL_WARN, text);
    }

    /** It is fine again: category {@code info}, level {@code info}. */
    public Mono<Void> publishInfo(final String text) {
        return publish(CATEGORY_INFO, LEVEL_INFO, text);
    }

    //a headers exchange: the routing key is ignored, the queue is chosen by category and env.
    //The level is not routed by - the consumer reads it to pick how the message looks.
    //Plain text, because the consumer reads the message as a String - sent through a JSON converter
    //it would arrive quoted and as application/json, so the message is built here and the converter
    //of the template is never asked
    private Mono<Void> publish(final String category, final String level, final String text) {
        return Mono.fromCallable(() -> {
                final CorrelationData correlationData = new CorrelationData();
                rabbitOperations.send(exchange, "", toMessage(category, level, text), correlationData);
                return correlationData;
            })
            //the send blocks on the broker connection
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(correlationData -> Mono.fromFuture(correlationData.getFuture())
                .timeout(confirmTimeout)
                .onErrorMap(TimeoutException.class, timeout -> new NotificationPublishException(
                    "Notification not confirmed by the broker within " + confirmTimeout, timeout))
                .flatMap(confirm -> verifyDelivery(correlationData, confirm)))
            .doOnSuccess(unused -> log.info("Published {} notification ({}): {}", category, level, text));
    }

    private Mono<Void> verifyDelivery(final CorrelationData correlationData, final CorrelationData.Confirm confirm) {
        if (!confirm.ack()) {
            return Mono.error(
                new NotificationPublishException("Notification refused by the broker: " + confirm.reason()));
        }
        //a message matching no binding is confirmed all the same - the broker hands it back first
        if (correlationData.getReturned() != null) {
            return Mono.error(new NotificationPublishException(
                "Notification was not routed to any queue: " + correlationData.getReturned().getReplyText()));
        }
        return Mono.empty();
    }

    private Message toMessage(final String category, final String level, final String text) {
        return MessageBuilder.withBody(text.getBytes(StandardCharsets.UTF_8))
            .setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .setContentEncoding(StandardCharsets.UTF_8.name())
            .setHeader(CATEGORY_HEADER, category)
            .setHeader(LEVEL_HEADER, level)
            .setHeader(ENV_HEADER, env)
            .build();
    }
}
