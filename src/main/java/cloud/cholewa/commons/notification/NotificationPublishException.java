package cloud.cholewa.commons.notification;

/**
 * The broker did not take a notification into a queue: it refused the message, handed it back as
 * unroutable or did not confirm it in time. A broker that cannot be reached at all is signalled
 * with the exception of Spring AMQP, as it comes.
 */
public class NotificationPublishException extends RuntimeException {

    public NotificationPublishException(final String message) {
        super(message);
    }

    public NotificationPublishException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
