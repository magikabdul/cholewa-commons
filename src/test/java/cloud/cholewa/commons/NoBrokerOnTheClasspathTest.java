package cloud.cholewa.commons;

import cloud.cholewa.commons.notification.NotificationPublisherAutoConfiguration;
import cloud.cholewa.commons.rabbit.RabbitConnectionNameAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

//Most consumers of this library have no RabbitMQ: spring-boot-amqp is a provided dependency and does
//not reach them. For them the two auto-configurations must cost nothing - above all no
//NoClassDefFoundError at their start.
//A FilteredClassLoader cannot show that: it hides classes from the conditions, but the classes of this
//library are loaded by the class loader of the test all the same, and that one has the jars - a
//reference to a type of Spring AMQP that is resolved too early would go unnoticed. So the context
//here loads the classes of this library through a class loader that defines them itself and has no
//Spring AMQP and no spring-boot-amqp at all, the way the class loader of such a consumer does.
//The proof that still outranks this one is the build of a consumer without the jars against the
//library - see CLAUDE.md
class NoBrokerOnTheClasspathTest {

    private static final String LIBRARY = "cloud.cholewa.commons.";
    private static final String[] BROKER_PACKAGES = {"org.springframework.amqp.", "org.springframework.boot.amqp."};

    @ParameterizedTest(name = "with the properties [{0}]")
    @ValueSource(strings = {
        //the consumer as it is: nothing about a broker in its configuration
        "",
        //and with every switch of the two auto-configurations set
        "cholewa.notification.enabled=true,cholewa.notification.env=dev,cholewa.rabbit.pod-connection-name=true"
    })
    void should_start_and_contribute_nothing_without_spring_amqp(final String properties) throws Exception {
        final ClassLoader withoutBroker = new WithoutBrokerClassLoader(getClass().getClassLoader());

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setClassLoader(withoutBroker);
            TestPropertyValues.of("spring.application.name=database-service").applyTo(context);
            if (!properties.isEmpty()) {
                TestPropertyValues.of(properties.split(",")).applyTo(context);
            }
            context.register(withoutBroker.loadClass(Consumer.class.getName()));
            context.refresh();

            //the configuration really came from the other class loader, or nothing was proven
            assertThat(context.getBean("consumer").getClass().getClassLoader()).isSameAs(withoutBroker);
            assertThat(context.containsBean("cholewaPodConnectionNameStrategy")).isFalse();
            assertThat(context.containsBean("cholewaNotificationPublisher")).isFalse();
            assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.contains("RabbitConnectionNameAutoConfiguration"))
                .noneMatch(name -> name.contains("NotificationPublisherAutoConfiguration"));
        }
    }

    //the control: this class loader does fail a class that needs Spring AMQP, so the test above
    //would have noticed one that needed it too early
    @Test
    void should_be_unable_to_use_a_class_that_needs_spring_amqp() throws Exception {
        final ClassLoader withoutBroker = new WithoutBrokerClassLoader(getClass().getClassLoader());
        final Class<?> configuration = withoutBroker.loadClass(RabbitConnectionNameAutoConfiguration.class.getName());

        assertThat(configuration.getClassLoader()).isSameAs(withoutBroker);
        assertThatThrownBy(configuration::getDeclaredMethods)
            .isInstanceOf(NoClassDefFoundError.class)
            .hasMessageContaining("org/springframework/amqp");
    }

    @Configuration(value = "consumer", proxyBeanMethods = false)
    @ImportAutoConfiguration({RabbitConnectionNameAutoConfiguration.class, NotificationPublisherAutoConfiguration.class})
    static class Consumer {
    }

    //defines the classes of this library - main and test - itself, from the same class files, and
    //knows nothing of the two broker packages; everything else comes from the class loader of the test
    private static final class WithoutBrokerClassLoader extends ClassLoader {

        private WithoutBrokerClassLoader(final ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            for (final String brokerPackage : BROKER_PACKAGES) {
                if (name.startsWith(brokerPackage)) {
                    throw new ClassNotFoundException(name);
                }
            }
            if (!name.startsWith(LIBRARY)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = define(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> define(final String name) throws ClassNotFoundException {
            try (InputStream classFile = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (classFile == null) {
                    throw new ClassNotFoundException(name);
                }
                final byte[] bytes = classFile.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
