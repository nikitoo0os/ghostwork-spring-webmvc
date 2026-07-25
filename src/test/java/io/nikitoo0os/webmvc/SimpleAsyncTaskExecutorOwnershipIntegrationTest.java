package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import io.nikitoo0os.entity.enums.OperationState;
import io.nikitoo0os.entity.enums.TaskState;
import io.nikitoo0os.spring.GhostWorkAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SimpleAsyncTaskExecutorOwnershipIntegrationTest {

    @Test
    void simpleAsyncTaskExecutorShouldPreserveHttpOperationOwnership()
            throws Exception {
        try (AnnotationConfigWebApplicationContext context =
                     new AnnotationConfigWebApplicationContext()) {
            context.register(TestConfiguration.class);
            context.setServletContext(
                    new org.springframework.mock.web.MockServletContext()
            );
            context.refresh();
            MockMvc mockMvc = MockMvcBuilders
                    .webAppContextSetup(context)
                    .build();

            MvcResult initial = mockMvc.perform(get("/inventory"))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            mockMvc.perform(asyncDispatch(initial))
                    .andExpect(status().isOk());

            GhostWork ghostWork = context.getBean(GhostWork.class);
            assertEquals(1, ghostWork.operations().size());
            var operation = ghostWork.operations().getFirst();
            assertEquals("GET /inventory", operation.name());
            assertEquals(OperationState.COMPLETED, operation.state());
            assertEquals(1, ghostWork.tasks(operation.id()).size());
            assertEquals(
                    "InventoryService.load",
                    ghostWork.tasks(operation.id()).getFirst().name()
            );
            assertEquals(
                    TaskState.COMPLETED,
                    ghostWork.tasks(operation.id()).getFirst().state()
            );
            ghostWork.executor().shutdownNow();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAsync
    @ImportAutoConfiguration({
            GhostWorkAutoConfiguration.class,
            GhostWorkWebMvcAutoConfiguration.class
    })
    static class TestConfiguration {
        @Bean
        GhostWork ghostWork() {
            return GhostWork.create(Executors.newSingleThreadExecutor());
        }

        @Bean(
                name = {"applicationTaskExecutor", "taskExecutor"},
                destroyMethod = "close"
        )
        SimpleAsyncTaskExecutor applicationTaskExecutor() {
            return new SimpleAsyncTaskExecutor("http-simple-owner-");
        }

        @Bean
        InventoryService inventoryService() {
            return new InventoryService();
        }

        @Bean
        InventoryController inventoryController(InventoryService service) {
            return new InventoryController(service);
        }
    }

    static class InventoryService {
        @Async
        public CompletableFuture<String> load() {
            return CompletableFuture.completedFuture("inventory");
        }
    }

    @RestController
    static class InventoryController {
        private final InventoryService service;

        InventoryController(InventoryService service) {
            this.service = service;
        }

        @GetMapping("/inventory")
        DeferredResult<String> inventory() {
            DeferredResult<String> result = new DeferredResult<>();
            service.load().whenComplete((value, failure) -> {
                if (failure == null) {
                    result.setResult(value);
                } else {
                    result.setErrorResult(failure);
                }
            });
            return result;
        }
    }
}
