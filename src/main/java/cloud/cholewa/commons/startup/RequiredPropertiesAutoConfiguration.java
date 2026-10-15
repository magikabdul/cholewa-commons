package cloud.cholewa.commons.startup;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

//Refuses the start of an application that lacks a value it must not run without - the password of
//a broker, typically - before the first bean is created. An application lists those properties
//under cholewa.required-properties; without the list nothing is checked, so no consumer is touched
//by the upgrade.
//Why Spring Boot alone does not do it: a property bound from a placeholder it cannot resolve keeps
//the placeholder as its text, so "${rabbitmq-password}" becomes the password, passes @NotBlank, the
//pod turns Ready and the broker refuses the first message (HAS-217 in heating-service). The
//environment itself is strict about the same placeholder, so asking it is the whole check. Made
//before the first bean, a refused start has opened no connection and run no migration.
//Never put the exception of the environment, or the value, into the message: both quote what was
//resolved so far, which for a password holding "${" is the password. For the same reason this is a
//plain exception and not Boot's InvalidConfigurationPropertyValueException, whose failure analysis
//prints the configured value. Moved here from heating-, boiler- and blinds-service (HAS-235)
@AutoConfiguration
public class RequiredPropertiesAutoConfiguration {

    static final String PREFIX = "cholewa.required-properties";

    //static: a BeanFactoryPostProcessor declared by an instance method would create this class
    //early, and Spring warns about it
    @Bean
    static BeanFactoryPostProcessor cholewaRequiredPropertiesCheck(final Environment environment) {
        return beanFactory -> check(environment);
    }

    static void check(final Environment environment) {
        final List<RequiredProperty> required = Binder.get(environment)
            .bind(PREFIX, Bindable.listOf(RequiredProperty.class))
            .orElse(List.of());

        final List<String> missing = new ArrayList<>();
        for (int i = 0; i < required.size(); i++) {
            final RequiredProperty entry = required.get(i);
            if (entry == null || !StringUtils.hasText(entry.property())) {
                throw new IllegalStateException(PREFIX + "[" + i + "].property names no property");
            }
            if (!hasValue(environment, entry.property())) {
                missing.add(entry.describe());
            }
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("A required property has no value: " + String.join(", ", missing));
        }
    }

    private static boolean hasValue(final Environment environment, final String property) {
        try {
            return StringUtils.hasText(environment.getProperty(property));
        } catch (PlaceholderResolutionException e) {
            return false;
        }
    }

    /**
     * A property the application does not start without, and optionally the variable it is read
     * from - named in the refusal, so that whoever reads the log of the pod knows what to set.
     *
     * @param property the name of the property, as the environment knows it
     * @param variable the variable to set, or {@code null}
     */
    public record RequiredProperty(String property, String variable) {

        private String describe() {
            return StringUtils.hasText(variable) ? property + " (set " + variable + ")" : property;
        }
    }
}
