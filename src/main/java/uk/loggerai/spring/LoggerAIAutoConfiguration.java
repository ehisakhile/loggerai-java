package uk.loggerai.spring;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import uk.loggerai.Client;
import uk.loggerai.LoggerAI;
import uk.loggerai.Options;
import uk.loggerai.jdbc.LoggerAIDataSource;
import uk.loggerai.servlet.LoggerAIFilter;

/**
 * Spring Boot 3: add the dependency and set LOGGERAI_KEY (or loggerai.key).
 * Every request is timed under its route pattern, JDBC queries (and so JPA)
 * and RestTemplate/RestClient calls are timed, warnings and errors logged
 * through SLF4J are sent, and exceptions are reported with their stack.
 *
 * <p>Settings (application.properties or the environment): loggerai.key,
 * loggerai.service (default: spring.application.name), loggerai.environment,
 * loggerai.release, loggerai.enabled, loggerai.host-metrics, loggerai.logs-level,
 * loggerai.app-packages (default: your @SpringBootApplication's package).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "loggerai", name = "enabled", matchIfMissing = true)
public class LoggerAIAutoConfiguration {

    @Bean
    public LoggerAIStarter loggerAIStarter(Environment env, BeanFactory beans) {
        Options o = new Options();
        set(env.getProperty("loggerai.key"), o::key);
        set(env.getProperty("loggerai.endpoint"), o::endpoint);
        set(env.getProperty("loggerai.service", env.getProperty("spring.application.name", "")), o::service);
        set(env.getProperty("loggerai.environment"), o::environment);
        set(env.getProperty("loggerai.release"), o::release);
        String host = env.getProperty("loggerai.host-metrics");
        if (host != null) o.hostMetrics(!host.equalsIgnoreCase("false"));
        String level = env.getProperty("loggerai.logs-level");
        if (level != null) o.logsLevel(level.equalsIgnoreCase("none") ? null : level.toUpperCase());
        String pkgs = env.getProperty("loggerai.app-packages");
        if (pkgs != null && !pkgs.isBlank()) {
            o.appPackages(Arrays.stream(pkgs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        } else if (AutoConfigurationPackages.has(beans)) {
            List<String> base = AutoConfigurationPackages.get(beans);
            o.appPackages(base.stream().map(p -> p + ".").toList());
        }
        return new LoggerAIStarter(LoggerAI.init(o));
    }

    private static void set(String v, java.util.function.Consumer<String> to) {
        if (v != null && !v.isBlank()) to.accept(v);
    }

    /** Holds the client for the app's lifetime and sends what's left when the context closes. */
    public static class LoggerAIStarter implements org.springframework.beans.factory.DisposableBean {
        private final Client client;

        LoggerAIStarter(Client client) {
            this.client = client;
            if (client.isEnabled()) LogbackSetup.attach();
        }

        public Client client() {
            return client;
        }

        @Override
        public void destroy() {
            LoggerAI.flush(java.time.Duration.ofSeconds(2));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "jakarta.servlet.Filter")
    static class Web {
        @Bean
        public FilterRegistrationBean<LoggerAIFilter> loggerAIFilter(LoggerAIStarter started) {
            FilterRegistrationBean<LoggerAIFilter> reg = new FilterRegistrationBean<>(new LoggerAIFilter());
            reg.setName("loggerAIFilter");
            reg.addUrlPatterns("/*");
            reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 5); // first, so the whole request is timed
            return reg;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.web.server.WebFilter")
    static class Reactive {
        @Bean
        public uk.loggerai.reactive.LoggerAIWebFilter loggerAIWebFilter(LoggerAIStarter started) {
            return new uk.loggerai.reactive.LoggerAIWebFilter();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.web.reactive.function.client.WebClientCustomizer")
    static class WebClients {
        @Bean
        public org.springframework.boot.web.reactive.function.client.WebClientCustomizer loggerAIWebClient() {
            return b -> b.filter(new uk.loggerai.reactive.LoggerAIExchangeFilter());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataSource.class)
    static class Jdbc {
        /** Wraps the app's DataSource so each query is timed. */
        @Bean
        public static BeanPostProcessor loggerAIDataSourceWrapper() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    return bean instanceof DataSource ds && !(bean instanceof LoggerAIDataSource) ? LoggerAIDataSource.wrap(ds) : bean;
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.web.client.RestTemplateCustomizer")
    static class RestTemplates {
        @Bean
        public org.springframework.boot.web.client.RestTemplateCustomizer loggerAIRestTemplate() {
            return rt -> rt.getInterceptors().add(new LoggerAIHttpInterceptor());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.web.client.RestClientCustomizer")
    static class RestClients {
        @Bean
        public org.springframework.boot.web.client.RestClientCustomizer loggerAIRestClient() {
            return b -> b.requestInterceptor(new LoggerAIHttpInterceptor());
        }
    }

    /** Separate, so Logback's classes are only loaded when Logback is there. */
    static final class LogbackSetup {
        static void attach() {
            try {
                Class.forName("ch.qos.logback.classic.LoggerContext");
            } catch (ClassNotFoundException e) {
                return;
            }
            LogbackAttach.run();
        }
    }

    static final class LogbackAttach {
        static void run() {
            if (!(org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext ctx)) return;
            ch.qos.logback.classic.Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (root.getAppender("LOGGERAI") != null) return;
            uk.loggerai.logback.LoggerAIAppender a = new uk.loggerai.logback.LoggerAIAppender();
            a.setName("LOGGERAI");
            a.setContext(ctx);
            a.start();
            root.addAppender(a);
        }
    }
}
