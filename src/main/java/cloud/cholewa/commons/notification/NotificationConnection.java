package cloud.cholewa.commons.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
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
 * Nothing is opened here: the connection is made by the first publish. {@link #close()} is final: a
 * publish after it fails instead of opening a connection nothing would close.
 * <p>
 * Not covered: TLS and a list of broker addresses - one host and port, without encryption.
 */
@Slf4j
public final class NotificationConnection implements AutoCloseable {

    /** What the name of this connection adds to the name of the first one of the same pod. */
    public static final String CONNECTION_NAME_SUFFIX = "/notification";

    private final CachingConnectionFactory connectionFactory;
    private final RabbitTemplate rabbitTemplate;
    private final ApplicationContext applicationContext;

    private NotificationConnection(
        final CachingConnectionFactory connectionFactory,
        final RabbitTemplate rabbitTemplate,
        final ApplicationContext applicationContext
    ) {
        this.connectionFactory = connectionFactory;
        this.rabbitTemplate = rabbitTemplate;
        this.applicationContext = applicationContext;
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
        //the context the factory takes the closing signal for - see close()
        connectionFactory.setApplicationContext(applicationContext);
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

        return new NotificationConnection(connectionFactory, rabbitTemplate, applicationContext);
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

    /**
     * Closes the connection for good. As a bean this is the destroy method Spring finds by itself.
     */
    //destroy() alone only resets the connection - the next publish would open a new one, which
    //nothing closes any more. A CachingConnectionFactory stops for good, and shuts the executor of
    //its channels down, only when it was told that its application context closed. As a bean it
    //hears that from the context; built by hand it hears nothing, so it is told here
    @Override
    public void close() {
        connectionFactory.onApplicationEvent(new ContextClosedEvent(applicationContext));
        connectionFactory.destroy();
    }

    /**
     * @param connectionTimeout  how long opening the connection may take; {@code null} leaves the
     *                           default of the client
     * @param requestedHeartbeat the heartbeat asked of the broker, in whole seconds; zero switches it
     *                           off, {@code null} leaves the default
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
            Assert.isTrue(port >= 1 && port <= 65535, "The broker port must be between 1 and 65535");
            //the client takes milliseconds and seconds as an int, and zero means something else to
            //it: no limit for the timeout, no heartbeat at all. A value that would be cut down to
            //zero must not get there by accident
            Assert.isTrue(
                connectionTimeout == null
                    || connectionTimeout.toMillis() >= 1 && connectionTimeout.toMillis() <= Integer.MAX_VALUE,
                "The connection timeout must be at least a millisecond");
            Assert.isTrue(
                requestedHeartbeat == null || requestedHeartbeat.isZero()
                    || !requestedHeartbeat.isNegative() && requestedHeartbeat.toSeconds() >= 1
                        && requestedHeartbeat.toSeconds() <= Integer.MAX_VALUE,
                "The requested heartbeat must be zero or at least a second");
        }

        //a record prints its components, and one of them is the password
        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + ", virtualHost=" + virtualHost
                + ", username=" + username + "]";
        }
    }
}
