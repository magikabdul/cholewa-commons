package cloud.cholewa.commons.database;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

//the connection details are all mandatory - ConnectionFactoryOptions rejects a null value with
//a message that does not name the property, so they are validated at bind time instead
@Validated
@ConfigurationProperties("database")
public record DatabaseProperties(
    @NotBlank String host,
    @NotNull Integer port,
    @NotBlank String name,
    @NotBlank String username,
    //an empty password is a legitimate setup, a missing one is not
    @NotNull String password,
    @DefaultValue("REQUIRE") String sslMode,
    @DefaultValue Pool pool
) {

    public record Pool(
        @DefaultValue("2") int initialSize,
        @DefaultValue("4") int maxSize,
        @DefaultValue("PT10S") Duration maxAcquireTime,
        @DefaultValue("PT5M") Duration maxIdleTime
    ) {
    }
}
