package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import io.nikitoo0os.spring.GhostWorkAutoConfiguration;
import jakarta.servlet.Servlet;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@AutoConfiguration
@AutoConfigureAfter(GhostWorkAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Servlet.class, DispatcherServlet.class, GhostWork.class})
@ConditionalOnBean(GhostWork.class)
@ConditionalOnProperty(
        prefix = "ghostwork.web",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(GhostWorkWebMvcProperties.class)
public class GhostWorkWebMvcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OperationNameResolver ghostWorkOperationNameResolver() {
        return new DefaultOperationNameResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    GhostWorkRequestInterceptor ghostWorkRequestInterceptor(
            GhostWork ghostWork,
            OperationNameResolver resolver,
            GhostWorkWebMvcProperties properties
    ) {
        return new GhostWorkRequestInterceptor(
                ghostWork,
                resolver,
                properties
        );
    }

    @Bean
    WebMvcConfigurer ghostWorkWebMvcConfigurer(
            GhostWorkRequestInterceptor interceptor
    ) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
