package cloud.cholewa.commons.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
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
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

//Error messages are the same whatever the machine: the tests make the JVM - and the request -
//Polish, the way a developer machine is, and still expect the English wording. The validator is
//the one Spring Boot configures, so this also proves the customizer wins over the locale-aware
//interpolator Spring installs.
//src/test/resources carries two pairs of bundles for this class - ValidationMessages and
//validation-test-messages, each with a root file and a _pl file and deliberately no _en one.
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
            final MessageInterpolator interpolator = context.getBean(ValidatorFactory.class).getMessageInterpolator();

            assertThat(interpolator.interpolate("{jakarta.validation.constraints.NotBlank.message}", null, POLISH))
                .isEqualTo("must not be blank");
        });
    }

    @Test
    void should_leave_a_message_written_on_the_constraint_as_it_is() {
        contextRunner.run(context ->
            assertThat(message(context.getBean(Validator.class), new OwnMessage(null)))
                .isEqualTo("nazwa jest wymagana"));
    }

    //the consumer has ValidationMessages.properties and ValidationMessages_pl.properties and no _en:
    //asked for English, the bundle lookup would fall back to the Polish JVM default before the root
    @Test
    void should_take_a_consumer_bundle_message_from_the_root_bundle() {
        contextRunner.run(context ->
            assertThat(message(context.getBean(Validator.class), new FromConsumerBundle(null)))
                .isEqualTo("name from the root bundle"));
    }

    //Spring Boot resolves {keys} from the application's MessageSource first; pinning the locale
    //must not take that away, and must not let the Polish file win there either
    @Test
    void should_still_resolve_a_message_from_the_message_source() {
        contextRunner.withUserConfiguration(ConsumerMessageSource.class).run(context ->
            assertThat(message(context.getBean(Validator.class), new FromMessageSource(null)))
                .isEqualTo("name from the message source"));
    }

    //the way out for a consumer that wants its messages localized
    @Test
    void should_back_off_when_switched_off() {
        contextRunner.withPropertyValues("cholewa.validation.english-messages=false").run(context -> {
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

    //a consumer that installs an interpolator of its own keeps it: this one runs first
    @Test
    void should_let_a_consumer_customizer_have_the_last_word() {
        contextRunner.withUserConfiguration(ConsumerOwnInterpolator.class).run(context ->
            assertThat(messages(context.getBean(Validator.class))).containsExactly("the consumer's wording"));
    }

    //database-service had a customizer under this very name before this one moved into the
    //library; on the day it upgrades both exist, and its context still has to start
    @Test
    void should_coexist_with_a_consumer_customizer_of_the_former_name() {
        contextRunner.withUserConfiguration(ConsumerCustomizerOfTheFormerName.class).run(context -> {
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

    private static String message(final Validator validator, final Object invalid) {
        return validator.validate(invalid).iterator().next().getMessage();
    }

    private record Sample(@Max(99) int point, @NotBlank String name) {
    }

    private record OwnMessage(@NotBlank(message = "nazwa jest wymagana") String name) {
    }

    private record FromConsumerBundle(@NotBlank(message = "{sample.name.required}") String name) {
    }

    private record FromMessageSource(@NotBlank(message = "{sample.source.required}") String name) {
    }

    @Configuration
    static class ConsumerMessageSource {

        @Bean
        MessageSource messageSource() {
            final ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
            messageSource.setBasename("validation-test-messages");
            messageSource.setDefaultEncoding("UTF-8");
            return messageSource;
        }
    }

    @Configuration
    static class ConsumerOwnInterpolator {

        @Bean
        ValidationConfigurationCustomizer consumerInterpolator() {
            return configuration -> configuration.messageInterpolator(new MessageInterpolator() {

                @Override
                public String interpolate(final String messageTemplate, final Context context) {
                    return "the consumer's wording";
                }

                @Override
                public String interpolate(final String messageTemplate, final Context context, final Locale locale) {
                    return "the consumer's wording";
                }
            });
        }
    }

    @Configuration
    static class ConsumerCustomizerOfTheFormerName {

        //only the name matters here
        @Bean
        ValidationConfigurationCustomizer englishValidationMessages() {
            return configuration -> {
            };
        }
    }
}
