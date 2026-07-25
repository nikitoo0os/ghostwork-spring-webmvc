package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import io.nikitoo0os.entity.enums.OperationState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebMvcAsyncControllerIntegrationTest {
    private final GhostWork ghostWork =
            GhostWork.create(Executors.newFixedThreadPool(2));
    private final GhostWorkRequestInterceptor interceptor =
            new GhostWorkRequestInterceptor(
                    ghostWork,
                    new DefaultOperationNameResolver(),
                    new GhostWorkWebMvcProperties()
            );
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AsyncController(ghostWork))
            .addInterceptors(interceptor)
            .setAsyncRequestTimeout(2_000)
            .build();

    @AfterEach
    void close() {
        ghostWork.executor().shutdownNow();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/async/deferred",
            "/async/callable",
            "/async/callable-nested",
            "/async/web-task",
            "/async/stream",
            "/async/sse"
    })
    void asyncControllerTypeShouldCompleteAtServletLifecycle(String path)
            throws Exception {
        MvcResult initial = mockMvc.perform(get(path))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(initial))
                .andExpect(status().isOk());

        assertEquals(1, ghostWork.operations().size());
        var operation = ghostWork.operations().getFirst();
        assertEquals(OperationState.COMPLETED, operation.state());
        RequestMetadata metadata = (RequestMetadata) ghostWork
                .operationDetails(operation.id())
                .metadata();
        assertTrue(metadata.async());
        if (path.equals("/async/callable-nested")) {
            assertEquals(1, ghostWork.tasks(operation.id()).size());
            assertEquals(
                    "NestedCallableTask",
                    ghostWork.tasks(operation.id()).getFirst().name()
            );
        }
    }

    @RestController
    static class AsyncController {
        private final GhostWork ghostWork;

        AsyncController(GhostWork ghostWork) {
            this.ghostWork = ghostWork;
        }

        @GetMapping("/async/deferred")
        DeferredResult<String> deferred() {
            DeferredResult<String> result = new DeferredResult<>();
            result.setResult("deferred");
            return result;
        }

        @GetMapping("/async/callable")
        Callable<String> callable() {
            return () -> "callable";
        }

        @GetMapping("/async/callable-nested")
        Callable<String> callableWithNestedTask() {
            return () -> ghostWork.executor()
                    .submit("NestedCallableTask", () -> "nested")
                    .get();
        }

        @GetMapping("/async/web-task")
        WebAsyncTask<String> webAsyncTask() {
            return new WebAsyncTask<>(() -> "web-task");
        }

        @GetMapping("/async/stream")
        StreamingResponseBody stream() {
            return output -> output.write("stream".getBytes());
        }

        @GetMapping("/async/sse")
        SseEmitter sse() throws Exception {
            SseEmitter emitter = new SseEmitter();
            emitter.send("event");
            emitter.complete();
            return emitter;
        }
    }
}
