package cloud.cholewa.commons.notification;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Where the notifications are published. The broker, the virtual host and the user are those of
 * {@code spring.rabbitmq.*}: the auto-configured publisher uses the connection of the application.
 *
 * @param enabled        the switch of the auto-configured publisher; off unless set
 * @param exchange       the headers exchange the notifications are published to
 * @param env            the value of the {@code env} header the exchange routes by, e.g. {@code prod}
 *                       or {@code dev}. No default on purpose: with one, a run on a workstation
 *                       would publish into the queues of production
 * @param confirmTimeout how long the confirmation of the broker is waited for; a bare number is
 *                       seconds. Bounded on both sides: without a unit a Duration binds as
 *                       milliseconds, and "10000" meant that way would be close to three hours
 */
@Validated
@ConfigurationProperties(prefix = "cholewa.notification")
public record NotificationProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("notification") @NotBlank String exchange,
    @NotBlank String env,
    @DefaultValue("10s") @DurationUnit(ChronoUnit.SECONDS) @NotNull
    @DurationMin(seconds = 1) @DurationMax(seconds = 60) Duration confirmTimeout
) {
}
