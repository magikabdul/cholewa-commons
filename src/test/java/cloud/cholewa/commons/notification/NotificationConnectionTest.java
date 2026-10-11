package cloud.cholewa.commons.notification;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpApplicationContextClosedException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

//No test here opens a connection: the factory connects with the first publish, and nothing is published
class NotificationConnectionTest {

    private static final NotificationConnection.Settings SETTINGS = new NotificationConnection.Settings(
        "broker.test", 5673, "/notification", "notification", "secret", Duration.ofSeconds(7), Duration.ofSeconds(9));

    @Test
    void should_connect_where_and_as_whom_it_was_told() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> "heating-service-69bccdf7f9-xzfxt")) {
            final CachingConnectionFactory connectionFactory = connectionFactoryOf(connection);

            assertThat(connectionFactory.getHost()).isEqualTo("broker.test");
            assertThat(connectionFactory.getPort()).isEqualTo(5673);
            assertThat(connectionFactory.getVirtualHost()).isEqualTo("/notification");
            assertThat(connectionFactory.getUsername()).isEqualTo("notification");
            assertThat(connectionFactory.getRabbitConnectionFactory().getPassword()).isEqualTo("secret");
            assertThat(connectionFactory.getRabbitConnectionFactory().getConnectionTimeout()).isEqualTo(7000);
            assertThat(connectionFactory.getRabbitConnectionFactory().getRequestedHeartbeat()).isEqualTo(9);
        }
    }

    @Test
    void should_leave_the_defaults_of_the_client_when_no_timeouts_are_given() {
        final com.rabbitmq.client.ConnectionFactory defaults = new com.rabbitmq.client.ConnectionFactory();
        final NotificationConnection.Settings settings = new NotificationConnection.Settings(
            "broker.test", 5673, "/notification", "notification", "secret", null, null);

        try (NotificationConnection connection = connection(settings, factory -> "pod")) {
            final com.rabbitmq.client.ConnectionFactory client =
                connectionFactoryOf(connection).getRabbitConnectionFactory();

            assertThat(client.getConnectionTimeout()).isEqualTo(defaults.getConnectionTimeout());
            assertThat(client.getRequestedHeartbeat()).isEqualTo(defaults.getRequestedHeartbeat());
        }
    }

    //the second connection of the same pod: the name of the first, with what this one is for
    @Test
    void should_name_the_connection_after_the_first_one_of_the_pod() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> "heating-service-69bccdf7f9-xzfxt")) {
            assertThat(connectionNameOf(connection)).isEqualTo("heating-service-69bccdf7f9-xzfxt/notification");
        }
    }

    @Test
    void should_leave_the_connection_unnamed_when_the_first_one_has_no_name() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> null)) {
            assertThat(connectionNameOf(connection)).isNull();
        }
    }

    //what the publisher cannot do without: the outcome of each send, and a message that matches no
    //queue handed back instead of dropped
    @Test
    void should_ask_the_broker_for_confirms_and_returns() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> "pod")) {
            final CachingConnectionFactory connectionFactory = connectionFactoryOf(connection);
            final RabbitTemplate rabbitTemplate = (RabbitTemplate) connection.rabbitOperations();

            assertThat(connectionFactory.isPublisherConfirms()).isTrue();
            assertThat(connectionFactory.isPublisherReturns()).isTrue();
            assertThat(rabbitTemplate.isMandatoryFor(new Message(new byte[0]))).isTrue();
            //the same check the auto-configured publisher makes on the template of an application
            NotificationPublisherAutoConfiguration.requireDeliveryFeedback(rabbitTemplate);
        }
    }

    //spring.rabbitmq.template.observation-enabled does not reach a template built by hand
    @Test
    void should_publish_under_the_trace_of_what_raised_the_notification() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> "pod")) {
            assertThat(ReflectionTestUtils.getField(connection.rabbitOperations(), "observationEnabled"))
                .isEqualTo(true);
        }
    }

    @Test
    void should_give_a_publisher_over_its_own_template() {
        try (NotificationConnection connection = connection(SETTINGS, factory -> "pod")) {
            final NotificationPublisher publisher = connection.publisher("notification", "dev");
            final NotificationPublisher patient = connection.publisher("notification", "dev", Duration.ofSeconds(20));

            assertThat(ReflectionTestUtils.getField(publisher, "rabbitOperations"))
                .isSameAs(connection.rabbitOperations());
            assertThat(ReflectionTestUtils.getField(publisher, "confirmTimeout"))
                .isEqualTo(NotificationPublisher.DEFAULT_CONFIRM_TIMEOUT);
            assertThat(ReflectionTestUtils.getField(patient, "confirmTimeout")).isEqualTo(Duration.ofSeconds(20));
        }
    }

    //the reason this class exists: the application keeps the connection spring.rabbitmq.* configures.
    //A ConnectionFactory or a RabbitOperations bean for the notifications would have taken it away
    @Test
    void should_leave_the_auto_configured_connection_of_the_application_alone() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(SecondConnectionConfiguration.class)
            .withPropertyValues("spring.rabbitmq.virtual-host=/temperature", "spring.rabbitmq.username=temperature")
            .run(context -> {
                assertThat(context).hasNotFailed()
                    .hasSingleBean(ConnectionFactory.class)
                    .hasSingleBean(RabbitOperations.class)
                    .hasSingleBean(NotificationPublisher.class);

                final CachingConnectionFactory first = context.getBean(CachingConnectionFactory.class);
                final CachingConnectionFactory second =
                    connectionFactoryOf(context.getBean(NotificationConnection.class));

                assertThat(first.getVirtualHost()).isEqualTo("/temperature");
                assertThat(second).isNotSameAs(first);
                assertThat(second.getVirtualHost()).isEqualTo("/notification");
                assertThat(connectionNameOf(context.getBean(NotificationConnection.class)))
                    .isEqualTo("heating-service-local/notification");
            });
    }

    //destroy() of the factory alone only resets the connection, and the next publish would open a
    //new one that nothing closes. Closed here means closed: the factory refuses, before any attempt
    //to reach a broker
    @Test
    void should_refuse_to_connect_after_it_was_closed() {
        final NotificationConnection connection = connection(SETTINGS, factory -> "pod");
        final CachingConnectionFactory connectionFactory = connectionFactoryOf(connection);

        connection.close();

        assertThatThrownBy(connectionFactory::createConnection)
            .isInstanceOf(AmqpApplicationContextClosedException.class);
        connection.publisher("notification", "dev").publishAlert("Sensor is not reporting")
            .as(StepVerifier::create)
            .verifyError(AmqpApplicationContextClosedException.class);
    }

    //the same through Spring: the bean is closed with the context, by the close() Spring finds itself
    @Test
    void should_be_closed_for_good_with_the_application_context() {
        final AtomicReference<NotificationConnection> closedWithTheContext = new AtomicReference<>();

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(SecondConnectionConfiguration.class)
            .run(context -> closedWithTheContext.set(context.getBean(NotificationConnection.class)));

        assertThatThrownBy(connectionFactoryOf(closedWithTheContext.get())::createConnection)
            .isInstanceOf(AmqpApplicationContextClosedException.class);
    }

    @Test
    void should_refuse_a_port_and_durations_the_client_would_misread() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(0, null, null))
            .withMessage("The broker port must be between 1 and 65535");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(65536, null, null))
            .withMessage("The broker port must be between 1 and 65535");
        //zero would mean no limit, and anything below a millisecond is cut down to it
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(5672, Duration.ZERO, null))
            .withMessage("The connection timeout must be at least a millisecond");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(5672, Duration.ofNanos(500), null))
            .withMessage("The connection timeout must be at least a millisecond");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(5672, Duration.ofSeconds(-1), null))
            .withMessage("The connection timeout must be at least a millisecond");
        //half a second is cut down to zero, and zero asks the broker for no heartbeat at all
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(5672, null, Duration.ofMillis(500)))
            .withMessage("The requested heartbeat must be zero or at least a second");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> settings(5672, null, Duration.ofSeconds(-5)))
            .withMessage("The requested heartbeat must be zero or at least a second");
    }

    //zero is a setting of its own: the heartbeat switched off by whoever wrote it
    @Test
    void should_take_a_heartbeat_of_zero_as_switched_off() {
        try (NotificationConnection connection = connection(settings(5672, null, Duration.ZERO), factory -> "pod")) {
            assertThat(connectionFactoryOf(connection).getRabbitConnectionFactory().getRequestedHeartbeat()).isZero();
        }
    }

    @Test
    void should_refuse_settings_that_cannot_connect() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationConnection.Settings("", 5672, "/notification", "user", "secret", null, null))
            .withMessage("The broker host must not be blank");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationConnection.Settings("broker.test", 5672, " ", "user", "secret", null, null))
            .withMessage("The virtual host must not be blank");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationConnection.Settings("broker.test", 5672, "/notification", "", "secret", null, null))
            .withMessage("The username must not be blank");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new NotificationConnection.Settings("broker.test", 5672, "/notification", "user", null, null, null))
            .withMessage("The password must not be null");
    }

    @Test
    void should_not_print_the_password() {
        assertThat(SETTINGS.toString())
            .contains("broker.test", "/notification")
            .doesNotContain("secret");
    }

    private static NotificationConnection.Settings settings(
        final int port, final Duration connectionTimeout, final Duration requestedHeartbeat
    ) {
        return new NotificationConnection.Settings(
            "broker.test", port, "/notification", "notification", "secret", connectionTimeout, requestedHeartbeat);
    }

    private static NotificationConnection connection(
        final NotificationConnection.Settings settings,
        final ConnectionNameStrategy connectionNameStrategy
    ) {
        return NotificationConnection.create(settings, connectionNameStrategy, mock(ApplicationContext.class));
    }

    private static CachingConnectionFactory connectionFactoryOf(final NotificationConnection connection) {
        return (CachingConnectionFactory) connection.rabbitOperations().getConnectionFactory();
    }

    private static String connectionNameOf(final NotificationConnection connection) {
        final CachingConnectionFactory connectionFactory = connectionFactoryOf(connection);
        final ConnectionNameStrategy strategy = (ConnectionNameStrategy)
            ReflectionTestUtils.getField(connectionFactory, "connectionNameStrategy");

        assertThat(strategy).isNotNull();
        return strategy.obtainNewConnectionName(connectionFactory);
    }

    //the shape of a consumer with a listener on one virtual host and the notifications on another
    @Configuration(proxyBeanMethods = false)
    static class SecondConnectionConfiguration {

        @Bean
        ConnectionNameStrategy connectionNameStrategy() {
            return connectionFactory -> "heating-service-local";
        }

        @Bean
        NotificationConnection notificationConnection(
            final RabbitProperties rabbitProperties,
            final ConnectionNameStrategy connectionNameStrategy,
            final ApplicationContext applicationContext
        ) {
            return NotificationConnection.create(
                new NotificationConnection.Settings(
                    rabbitProperties.determineHost(),
                    rabbitProperties.determinePort(),
                    "/notification",
                    "notification",
                    "secret",
                    rabbitProperties.getConnectionTimeout(),
                    rabbitProperties.getRequestedHeartbeat()
                ),
                connectionNameStrategy,
                applicationContext
            );
        }

        @Bean
        NotificationPublisher notificationPublisher(final NotificationConnection notificationConnection) {
            return notificationConnection.publisher("notification", "dev");
        }
    }
}
