package cloud.cholewa.commons.validation;

import jakarta.validation.MessageInterpolator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;

import java.util.Locale;

//Error messages are English, always. Bean Validation words a violated constraint in the locale of
//the JVM (or of the request), so the same request would be answered in Polish on a developer
//machine and in English in a cluster. The interpolator is replaced by one that ignores whatever
//locale it is handed; a constraint with its own message = "..." is not affected either way.
//The property is the way out for a consumer that wants its messages localized
@AutoConfiguration
@ConditionalOnClass({ValidationConfigurationCustomizer.class, MessageInterpolator.class})
@ConditionalOnProperty(prefix = "validation", name = "english-messages", havingValue = "true", matchIfMissing = true)
public class ValidationMessagesAutoConfiguration {

    static final Locale MESSAGE_LOCALE = Locale.ENGLISH;

    //It has to be a customizer: Spring installs its own locale-aware interpolator, which is the one
    //this has to replace, and LocalValidatorFactoryBean runs the customizers after it.
    //The bean name is deliberately not "englishValidationMessages": database-service had a bean of
    //that name doing the same before this moved here, and two definitions of one name would stop
    //its context from starting on the day it upgrades
    @Bean
    ValidationConfigurationCustomizer cholewaEnglishValidationMessages() {
        return configuration -> {
            final MessageInterpolator interpolator = configuration.getDefaultMessageInterpolator();

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
