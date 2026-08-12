package cloud.cholewa.commons.database;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("database")
public record DatabaseProperties(
    String host,
    Integer port,
    String name,
    String username,
    String password,
    @DefaultValue Pool pool
) {

    public record Pool(
        @DefaultValue("2") int initialSize,
        @DefaultValue("4") int maxSize,
        @DefaultValue("PT10s") Duration maxAcquireTime,
        @DefaultValue("PT5m") Duration maxIdleTime
    ) {
    }
}
