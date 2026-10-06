package cloud.cholewa.commons.validation;

import jakarta.validation.MessageInterpolator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.validation.MessageInterpolatorFactory;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Locale;

//Error messages read the same on every machine. Bean Validation words a violated constraint in the
//locale of the JVM (or of the request), so the same request would be answered in Polish on a
//developer machine and in English in a cluster. The interpolator is replaced by one that ignores
//whatever locale it is handed; a constraint with its own message = "..." is not affected either way.
//The property is the way out for a consumer that wants its messages localized
@AutoConfiguration
@ConditionalOnClass({ValidationConfigurationCustomizer.class, MessageInterpolator.class})
@ConditionalOnProperty(
    prefix = "cholewa.validation", name = "english-messages", havingValue = "true", matchIfMissing = true)
public class ValidationMessagesAutoConfiguration {

    //The root locale, not Locale.ENGLISH: a bundle lookup for "en" that finds no _en file falls
    //back to the default locale of the JVM before it reaches the root bundle, so a consumer with
    //ValidationMessages.properties and ValidationMessages_pl.properties would get Polish on a
    //Polish machine all the same. Asking for the root bundle has no such detour, and the root
    //bundle of the built-in constraints is the English one
    static final Locale MESSAGE_LOCALE = Locale.ROOT;

    //It has to be a customizer: Spring installs its own locale-aware interpolator, which is the one
    //this has to replace, and LocalValidatorFactoryBean runs the customizers after it.
    //First among the customizers, so that a consumer installing an interpolator of its own still
    //has the last word.
    //The bean name is deliberately not "englishValidationMessages": database-service had a bean of
    //that name doing the same before this moved here, and two definitions of one name would stop
    //its context from starting on the day it upgrades
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ValidationConfigurationCustomizer cholewaEnglishValidationMessages(final ApplicationContext applicationContext) {
        return configuration -> {
            //the same interpolator Spring Boot gives its validator - resolving {keys} from the
            //application's MessageSource, and falling back to parameter-only interpolation when
            //there is no Expression Language implementation - so pinning the locale takes nothing
            //else away
            final MessageInterpolator interpolator = new MessageInterpolatorFactory(applicationContext).getObject();

            configuration.messageInterpolator(new MessageInterpolator() {

                @Override
                public String interpolate(final String messageTemplate, final Context context) {
                    return interpolator.interpolate(messageTemplate, context, MESSAGE_LOCALE);
                }

                @Override
                public String interpolate(final String messageTemplate, final Context context, final Locale locale) {
                    return interpolator.interpolate(messageTemplate, context, MESSAGE_LOCALE);
                }
            });
        };
    }
}
