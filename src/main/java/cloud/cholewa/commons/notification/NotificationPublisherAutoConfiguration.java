package cloud.cholewa.commons.notification;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

import java.util.Optional;

//The publisher for an application whose connection to the broker - the one spring.rabbitmq.*
//configures - already leads to the virtual host of the notifications. It publishes over the
//RabbitOperations of the context and declares neither a ConnectionFactory nor a RabbitOperations of
//its own: RabbitAutoConfiguration backs off from a bean of either type, and the application would
//lose its auto-configured connection together with everything spring.rabbitmq.* sets on it.
//An application that reaches the notifications over a second connection, to another virtual host,
//leaves this switched off and builds its publisher with NotificationConnection.
//Opt-in, because most applications with a broker do not publish notifications, and "env" has to be
//set by whoever does
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass({RabbitOperations.class, RabbitAutoConfiguration.class, Mono.class})
@ConditionalOnProperty(prefix = "cholewa.notification", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationPublisherAutoConfiguration {

    //The bean name is deliberately not "notificationPublisher": the services that carried a
    //publisher of their own before it moved here call theirs that.
    //No @ConditionalOnSingleCandidate(RabbitOperations.class): the publisher was switched on, and
    //a condition that quietly leaves it out would move the failure to whoever injects it, with a
    //message about a missing NotificationPublisher and no word about templates. The candidates are
    //counted here instead and the failure says what to do
    @Bean
    @ConditionalOnMissingBean
    NotificationPublisher cholewaNotificationPublisher(
        final ObjectProvider<RabbitOperations> rabbitOperations,
        final NotificationProperties properties
    ) {
        final RabbitOperations operations = theOneOf(rabbitOperations);
        requireDeliveryFeedback(operations);

        return new NotificationPublisher(
            operations, properties.exchange(), properties.env(), properties.confirmTimeout());
    }

    static RabbitOperations theOneOf(final ObjectProvider<RabbitOperations> rabbitOperations) {
        //the only bean of the type, or the primary one among several
        final RabbitOperations operations = rabbitOperations.getIfUnique();

        if (operations == null) {
            final long candidates = rabbitOperations.stream().count();
            throw new IllegalStateException(candidates == 0
                ? "The notification publisher is switched on (cholewa.notification.enabled) but there is no"
                    + " RabbitOperations bean to publish over - is spring.rabbitmq.* configured?"
                : "The notification publisher is switched on (cholewa.notification.enabled) but there are "
                    + candidates + " RabbitOperations beans and none is primary - mark one @Primary or"
                    + " declare a NotificationPublisher bean over the one that leads to the notifications");
        }
        return operations;
    }

    //The three settings the publisher cannot do without are the application's, on its own connection
    //and template, and nothing shows when one is missing: without correlated confirms every publish
    //ends in a timeout, and without returns or the mandatory flag a message that matches no queue is
    //confirmed and counted as delivered. Better not to start than to find out with the first alert
    static void requireDeliveryFeedback(final RabbitOperations rabbitOperations) {
        //Spring AMQP declares the connection factory as never null, and of a real template it is not.
        //A mocked one - in a context test of a consumer, most likely - answers null all the same
        final ConnectionFactory connectionFactory = Optional.ofNullable(rabbitOperations.getConnectionFactory())
            .orElseThrow(() -> misconfigured("a RabbitOperations with a connection factory, and this one has none"));

        if (!connectionFactory.isPublisherConfirms()) {
            throw misconfigured("correlated publisher confirms (spring.rabbitmq.publisher-confirm-type: correlated)");
        }
        if (!connectionFactory.isPublisherReturns()) {
            throw misconfigured("publisher returns (spring.rabbitmq.publisher-returns: true)");
        }
        if (rabbitOperations instanceof RabbitTemplate rabbitTemplate
            && !Boolean.TRUE.equals(rabbitTemplate.isMandatoryFor(new Message(new byte[0])))) {
            throw misconfigured("the mandatory flag (spring.rabbitmq.template.mandatory: true)");
        }
    }

    private static IllegalStateException misconfigured(final String missing) {
        return new IllegalStateException("The notification publisher needs " + missing
            + " - without it a notification that reached no queue would be reported as sent");
    }
}
