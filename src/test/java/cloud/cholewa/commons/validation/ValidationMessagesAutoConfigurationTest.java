package cloud.cholewa.commons.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

//Error messages are English whatever the machine: the tests make the JVM - and the request -
//Polish, the way a developer machine is, and still expect the English wording. The validator is
//the one Spring Boot configures, so this also proves the customizer wins over the locale-aware
//interpolator Spring installs.
class ValidationMessagesAutoConfigurationTest {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final String[] ENGLISH_MESSAGES = {"must be less than or equal to 99", "must not be blank"};

    private final ApplicationContextRunner validationOnly = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class));

    private final ApplicationContextRunner contextRunner = validationOnly
        .withConfiguration(AutoConfigurations.of(ValidationMessagesAutoConfiguration.class));

    private Locale original;

    @BeforeEach
    void makeTheJvmPolish() {
        original = Locale.getDefault();
        Locale.setDefault(POLISH);
        LocaleContextHolder.setLocale(POLISH);
    }

    @AfterEach
    void restoreTheLocale() {
        Locale.setDefault(original);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(ValidationMessagesAutoConfiguration.class.getName());
    }

    @Test
    void should_word_a_violated_constraint_in_english_on_a_polish_jvm() {
        contextRunner.run(context ->
            assertThat(messages(context.getBean(Validator.class))).containsExactlyInAnyOrder(ENGLISH_MESSAGES));
    }

    //the reason the auto-configuration exists: without it the same violations read in Polish here.
    //If this fails, the test above proves nothing - the JVM was English anyway
    @Test
    void should_follow_the_locale_without_the_auto_configuration() {
        validationOnly.run(context ->
            assertThat(messages(context.getBean(Validator.class))).doesNotContain(ENGLISH_MESSAGES));
    }

    //a locale handed in explicitly is ignored as well: it is how the request's locale arrives
    @Test
    void should_ignore_a_locale_passed_to_the_interpolator() {
        contextRunner.run(context -> {
            final MessageInterpolator interpolator =
                context.getBean(jakarta.validation.ValidatorFactory.class).getMessageInterpolator();

            assertThat(interpolator.interpolate("{jakarta.validation.constraints.NotBlank.message}", null, POLISH))
                .isEqualTo("must not be blank");
        });
    }

    @Test
    void should_leave_a_message_written_on_the_constraint_as_it_is() {
        contextRunner.run(context ->
            assertThat(context.getBean(Validator.class).validate(new OwnMessage(null)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("nazwa jest wymagana"));
    }

    //the way out for a consumer that wants its messages localized
    @Test
    void should_back_off_when_switched_off() {
        contextRunner.withPropertyValues("validation.english-messages=false").run(context -> {
            assertThat(context).doesNotHaveBean(ValidationMessagesAutoConfiguration.class);
            assertThat(messages(context.getBean(Validator.class))).doesNotContain(ENGLISH_MESSAGES);
        });
    }

    @Test
    void should_back_off_without_spring_boot_validation() {
        contextRunner
            .withClassLoader(new FilteredClassLoader(ValidationConfigurationCustomizer.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(ValidationMessagesAutoConfiguration.class);
            });
    }

    //database-service had this very bean, under this very name, before the customizer moved into
    //the library; on the day it upgrades both exist, and its context still has to start
    @Test
    void should_coexist_with_a_consumer_customizer_of_the_former_name() {
        contextRunner.withUserConfiguration(ConsumerOwnCustomizer.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ValidationConfigurationCustomizer.class)).hasSize(2);
            assertThat(messages(context.getBean(Validator.class))).containsExactlyInAnyOrder(ENGLISH_MESSAGES);
        });
    }

    private static Set<String> messages(final Validator validator) {
        return validator.validate(new Sample(100, " ")).stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.toSet());
    }

    private record Sample(@Max(99) int point, @NotBlank String name) {
    }

    private record OwnMessage(@NotBlank(message = "nazwa jest wymagana") String name) {
    }

    @Configuration
    static class ConsumerOwnCustomizer {

        @Bean
        ValidationConfigurationCustomizer englishValidationMessages() {
            return configuration -> {
                final MessageInterpolator interpolator = configuration.getDefaultMessageInterpolator();

                configuration.messageInterpolator(new MessageInterpolator() {

                    @Override
                    public String interpolate(final String messageTemplate, final Context context) {
                        return interpolator.interpolate(messageTemplate, context, Locale.ENGLISH);
                    }

                    @Override
                    public String interpolate(
                        final String messageTemplate, final Context context, final Locale locale
                    ) {
                        return interpolator.interpolate(messageTemplate, context, Locale.ENGLISH);
                    }
                });
            };
        }
    }
}
