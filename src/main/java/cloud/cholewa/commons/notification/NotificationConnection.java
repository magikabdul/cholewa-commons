package cloud.cholewa.commons.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.util.Assert;

import java.time.Duration;

/**
 * A connection of its own for the notifications, for an application whose connection - the one
 * {@code spring.rabbitmq.*} configures - leads to another virtual host: a connection belongs to
 * exactly one virtual host, so publishing the notifications takes a second one.
 * <p>
 * The connection factory and the template are built by hand and kept inside this object <b>on
 * purpose</b>: {@code RabbitAutoConfiguration} backs off from any {@code ConnectionFactory} or
 * {@code RabbitOperations} bean, and the listeners of the application would lose the auto-configured
 * connection together with everything {@code spring.rabbitmq.*} sets on it (acknowledge mode,
 * prefetch, observation). Declare this object as a bean, or the publisher it gives - never what
 * {@link #rabbitOperations()} returns.
 * <p>
 * Nothing is opened here: the connection is made by the first publish.
 */
@Slf4j
public final class NotificationConnection implements AutoCloseable {

    /** What the name of this connection adds to the name of the first one of the same pod. */
    public static final String CONNECTION_NAME_SUFFIX = "/notification";

    private final CachingConnectionFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;

    private NotificationConnection(final CachingConnectionFactory connectionFactory, final RabbitTemplate rabbitTemplate) {
        this.connectionFactory = connectionFactory;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * @param settings               where to connect and as whom
     * @param connectionNameStrategy the strategy naming the first connection of the application; this
     *                               one gets the same name with {@link #CONNECTION_NAME_SUFFIX}
     * @param applicationContext     where the template finds the {@code ObservationRegistry}, so that
     *                               a published notification carries the trace of what raised it
     */
    public static NotificationConnection create(
        final Settings settings,
        final ConnectionNameStrategy connectionNameStrategy,
        final ApplicationContext applicationContext
    ) {
        Assert.notNull(settings, "Settings must not be null");
        Assert.notNull(connectionNameStrategy, "ConnectionNameStrategy must not be null");
        Assert.notNull(applicationContext, "ApplicationContext must not be null");

        final CachingConnectionFactory connectionFactory =
            new CachingConnectionFactory(settings.host(), settings.port());
        connectionFactory.setVirtualHost(settings.virtualHost());
        connectionFactory.setUsername(settings.username());
        connectionFactory.setPassword(settings.password());
        connectionFactory.setConnectionNameStrategy(factory -> {
            final String name = connectionNameStrategy.obtainNewConnectionName(factory);
            return name == null ? null : name + CONNECTION_NAME_SUFFIX;
        });
        //a message matching no binding is dropped by the broker without a word; with returns it
        //comes back, and with correlated confirms the publisher learns the outcome of each send
        connectionFactory.setPublisherReturns(true);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        if (settings.connectionTimeout() != null) {
            connectionFactory.setConnectionTimeout(Math.toIntExact(settings.connectionTimeout().toMillis()));
        }
        if (settings.requestedHeartbeat() != null) {
            connectionFactory.setRequestedHeartBeat(Math.toIntExact(settings.requestedHeartbeat().toSeconds()));
        }

        final RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMandatory(true);
        //the publisher fails the send on a returned message; this is the line that says why
        rabbitTemplate.setReturnsCallback(returned -> log.error(
            "Notification was not routed to any queue: {} - {}", returned.getReplyCode(), returned.getReplyText()));
        //spring.rabbitmq.template.observation-enabled reaches only the auto-configured template;
        //this one finds the ObservationRegistry through the application context, so it needs both
        rabbitTemplate.setObservationEnabled(true);
        rabbitTemplate.setApplicationContext(applicationContext);
        rabbitTemplate.afterPropertiesSet();

        return new NotificationConnection(connectionFactory, rabbitTemplate);
    }

    /** A publisher over this connection, waiting {@link NotificationPublisher#DEFAULT_CONFIRM_TIMEOUT}. */
    public NotificationPublisher publisher(final String exchange, final String env) {
        return new NotificationPublisher(rabbitTemplate, exchange, env);
    }

    public NotificationPublisher publisher(final String exchange, final String env, final Duration confirmTimeout) {
        return new NotificationPublisher(rabbitTemplate, exchange, env, confirmTimeout);
    }

    /** The template of this connection. Must not be exposed as a bean - see the class comment. */
    public RabbitOperations rabbitOperations() {
        return rabbitTemplate;
    }

    /** Closes the connection. As a bean this is the destroy method Spring finds by itself. */
    @Override
    public void close() {
        connectionFactory.destroy();
    }

    /**
     * @param connectionTimeout  how long opening the connection may take; {@code null} leaves the
     *                           default of the client
     * @param requestedHeartbeat the heartbeat asked of the broker; {@code null} leaves the default
     */
    public record Settings(
        String host,
        int port,
        String virtualHost,
        String username,
        String password,
        Duration connectionTimeout,
        Duration requestedHeartbeat
    ) {

        public Settings {
            Assert.hasText(host, "The broker host must not be blank");
            Assert.hasText(virtualHost, "The virtual host must not be blank");
            Assert.hasText(username, "The username must not be blank");
            Assert.notNull(password, "The password must not be null");
        }

        //a record prints its components, and one of them is the password
        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost
                + ", username=" + username + "]";
        }
    }
}
