package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class GhostWorkWebMvcAutoConfigurationTest {
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            GhostWorkWebMvcAutoConfiguration.class
                    ));

    @Test
    void shouldConfigureWhenGhostWorkExists() {
        runner.withBean(GhostWork.class, this::ghostWork)
                .run(context -> {
                    assertThat(context)
                            .hasSingleBean(GhostWorkRequestInterceptor.class)
                            .hasSingleBean(OperationNameResolver.class)
                            .hasSingleBean(GhostWorkWebMvcProperties.class);
                    context.getBean(GhostWork.class)
                            .executor()
                            .shutdownNow();
                });
    }

    @Test
    void shouldBackOffWhenDisabledOrGhostWorkMissing() {
        runner.run(context -> assertThat(context)
                .doesNotHaveBean(GhostWorkRequestInterceptor.class));
        runner.withBean(GhostWork.class, this::ghostWork)
                .withPropertyValues("ghostwork.web.enabled=false")
                .run(context -> {
                    assertThat(context)
                            .doesNotHaveBean(GhostWorkRequestInterceptor.class);
                    context.getBean(GhostWork.class)
                            .executor()
                            .shutdownNow();
                });
    }

    @Test
    void userOperationNameResolverShouldWin() {
        OperationNameResolver resolver = metadata -> "custom";
        runner.withBean(GhostWork.class, this::ghostWork)
                .withBean(OperationNameResolver.class, () -> resolver)
                .run(context -> {
                    assertThat(context.getBean(OperationNameResolver.class))
                            .isSameAs(resolver);
                    context.getBean(GhostWork.class)
                            .executor()
                            .shutdownNow();
                });
    }

    private GhostWork ghostWork() {
        return GhostWork.create(Executors.newSingleThreadExecutor());
    }
}
