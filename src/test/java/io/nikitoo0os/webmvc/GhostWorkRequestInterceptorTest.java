package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import io.nikitoo0os.CancellationCause;
import io.nikitoo0os.entity.enums.OperationState;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhostWorkRequestInterceptorTest {
    private final GhostWork ghostWork =
            GhostWork.create(Executors.newSingleThreadExecutor());
    private final GhostWorkWebMvcProperties properties =
            new GhostWorkWebMvcProperties();
    private final GhostWorkRequestInterceptor interceptor =
            new GhostWorkRequestInterceptor(
                    ghostWork,
                    new DefaultOperationNameResolver(),
                    properties
            );

    @AfterEach
    void close() {
        ghostWork.executor().shutdownNow();
    }

    @Test
    void syncRequestShouldUseTemplateAndOwnSubmittedTasks()
            throws Exception {
        MockHttpServletRequest request = request("GET", "/orders/42");
        request.setAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/orders/{id}"
        );
        request.addHeader("X-Request-ID", "request-42");
        request.addHeader("X-Correlation-ID", "correlation-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        ghostWork.executor()
                .submit("LoadOrder", () -> {
                })
                .get(1, TimeUnit.SECONDS);
        response.setStatus(201);
        interceptor.afterCompletion(request, response, new Object(), null);

        var operation = ghostWork.operations().getFirst();
        assertEquals("GET /orders/{id}", operation.name());
        assertEquals(OperationState.COMPLETED, operation.state());
        assertEquals("correlation-42", operation.correlationId().value());
        assertEquals(1, ghostWork.tasks(operation.id()).size());
        RequestMetadata metadata = (RequestMetadata) ghostWork
                .operationDetails(operation.id())
                .metadata();
        assertEquals("request-42", metadata.requestId());
        assertEquals(201, metadata.responseStatus());
        assertFalse(metadata.async());
    }

    @Test
    void unsafeCorrelationHeaderShouldBeRejectedAndRegenerated()
            throws Exception {
        MockHttpServletRequest request = request("GET", "/orders");
        request.addHeader("X-Correlation-ID", "unsafe\nheader");
        request.addHeader("X-Request-ID", "request-safe");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(
                "request-safe",
                ghostWork.operations().getFirst().correlationId().value()
        );
    }

    @Test
    void excludedRequestShouldNotCreateOperation() throws Exception {
        MockHttpServletRequest request = request("GET", "/actuator/health");
        interceptor.preHandle(
                request,
                new MockHttpServletResponse(),
                new Object()
        );
        interceptor.afterCompletion(
                request,
                new MockHttpServletResponse(),
                new Object(),
                null
        );
        assertTrue(ghostWork.operations().isEmpty());
    }

    @Test
    void dashboardRequestsShouldNotCreateOperations() throws Exception {
        for (String path : List.of(
                "/ghostwork/",
                "/ghostwork/api/report",
                "/ghostwork/api/schedules",
                "/ghostwork/api/metrics",
                "/ghostwork/api/alerts",
                "/ghostwork/api/events"
        )) {
            MockHttpServletRequest request = request("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);
        }

        assertTrue(ghostWork.operations().isEmpty());
    }

    @Test
    void syncExceptionAndClientAbortShouldHaveDifferentStates()
            throws Exception {
        completeWithFailure(new IllegalStateException("boom"));
        completeWithFailure(new IOException("client disconnected"));

        assertTrue(ghostWork.operations().stream().anyMatch(operation ->
                operation.state() == OperationState.FAILED));
        assertTrue(ghostWork.operations().stream().anyMatch(operation ->
                operation.state() == OperationState.ABORTED));
    }

    @Test
    void resolvedServerErrorResponseShouldFailOperation() throws Exception {
        MockHttpServletRequest request = request("GET", "/failed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        response.setStatus(500);
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(
                OperationState.FAILED,
                ghostWork.operations().getFirst().state()
        );
    }

    @Test
    void asyncRequestShouldFinishOnlyOnCompleteCallback()
            throws Exception {
        AsyncFixture fixture = startAsync("/deferred");
        assertEquals(
                OperationState.RUNNING,
                ghostWork.operations().getFirst().state()
        );

        fixture.context.complete();

        var operation = ghostWork.operations().getFirst();
        assertEquals(OperationState.COMPLETED, operation.state());
        RequestMetadata metadata = (RequestMetadata) ghostWork
                .operationDetails(operation.id())
                .metadata();
        assertTrue(metadata.async());
    }

    @Test
    void asyncTimeoutAndClientAbortShouldBeClassified()
            throws Exception {
        AsyncFixture timeout = startAsync("/timeout");
        timeout.listener().onTimeout(new AsyncEvent(timeout.context));

        AsyncFixture abort = startAsync("/stream");
        abort.listener().onError(new AsyncEvent(
                abort.context,
                new IOException("broken pipe")
        ));

        assertTrue(ghostWork.operations().stream().anyMatch(operation ->
                operation.state() == OperationState.TIMED_OUT));
        assertTrue(ghostWork.operations().stream().anyMatch(operation ->
                operation.state() == OperationState.ABORTED));
    }

    @Test
    void asyncTimeoutShouldRequestCancellationForOwnedTask()
            throws Exception {
        ActiveTaskFixture fixture = startAsyncWithActiveTask("/timeout-task");

        fixture.async().listener().onTimeout(
                new AsyncEvent(fixture.async().context)
        );

        var operation = ghostWork.operations().getFirst();
        var task = ghostWork.tasks(operation.id()).getFirst();
        var cancellation = ghostWork.taskCancellation(task.id());
        assertTrue(cancellation.cancellationRequested());
        assertEquals(
                CancellationCause.OPERATION_TIMED_OUT,
                cancellation.cancellationCause()
        );
        fixture.release().countDown();
    }

    @Test
    void clientAbortShouldRequestCancellationWithAbortCause()
            throws Exception {
        ActiveTaskFixture fixture = startAsyncWithActiveTask("/abort-task");

        fixture.async().listener().onError(new AsyncEvent(
                fixture.async().context,
                new IOException("broken pipe")
        ));

        var operation = ghostWork.operations().getFirst();
        var task = ghostWork.tasks(operation.id()).getFirst();
        assertEquals(
                CancellationCause.CLIENT_ABORTED,
                ghostWork.taskCancellation(task.id()).cancellationCause()
        );
        fixture.release().countDown();
    }

    @Test
    void normalAsyncCompletionShouldNotRequestTaskCancellation()
            throws Exception {
        ActiveTaskFixture fixture = startAsyncWithActiveTask("/complete-task");
        fixture.release().countDown();
        fixture.async().context.complete();

        var operation = ghostWork.operations().getFirst();
        var task = ghostWork.tasks(operation.id()).getFirst();
        assertFalse(ghostWork.taskCancellation(task.id())
                .cancellationRequested());
    }

    @Test
    void completionAfterTimeoutShouldNotReplaceTerminalMetadata()
            throws Exception {
        AsyncFixture fixture = startAsync("/timeout");
        fixture.listener().onTimeout(new AsyncEvent(fixture.context));
        var operation = ghostWork.operations().getFirst();
        RequestMetadata timedOut = (RequestMetadata) ghostWork
                .operationDetails(operation.id())
                .metadata();

        fixture.response.setStatus(200);
        fixture.listener().onComplete(new AsyncEvent(fixture.context));

        assertEquals(OperationState.TIMED_OUT, operation.state());
        assertEquals(
                timedOut,
                ghostWork.operationDetails(operation.id()).metadata()
        );
    }

    @Test
    void onStartAsyncShouldRegisterListenerOnNewContext()
            throws Exception {
        AsyncFixture first = startAsync("/stream");
        MockAsyncContext second = new MockAsyncContext(
                first.request,
                first.response
        );

        first.listener().onStartAsync(new AsyncEvent(second));

        assertEquals(1, second.getListeners().size());
        second.complete();
        assertEquals(
                OperationState.COMPLETED,
                ghostWork.operations().getFirst().state()
        );
    }

    private void completeWithFailure(Exception failure) throws Exception {
        MockHttpServletRequest request =
                request("GET", "/" + failure.getClass().getSimpleName());
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), failure);
    }

    private AsyncFixture startAsync(String path) throws Exception {
        MockHttpServletRequest request = request("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        interceptor.preHandle(request, response, new Object());
        MockAsyncContext context =
                (MockAsyncContext) request.startAsync(request, response);
        interceptor.afterConcurrentHandlingStarted(
                request,
                response,
                new Object()
        );
        return new AsyncFixture(request, response, context);
    }

    private ActiveTaskFixture startAsyncWithActiveTask(String path)
            throws Exception {
        MockHttpServletRequest request = request("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAsyncSupported(true);
        interceptor.preHandle(request, response, new Object());

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ghostWork.executor().submit("OwnedTask", () -> {
            started.countDown();
            release.await();
            return null;
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        MockAsyncContext context =
                (MockAsyncContext) request.startAsync(request, response);
        interceptor.afterConcurrentHandlingStarted(
                request,
                response,
                new Object()
        );
        return new ActiveTaskFixture(
                new AsyncFixture(request, response, context),
                release
        );
    }

    private static MockHttpServletRequest request(
            String method,
            String path
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI(path);
        request.setRemoteAddr("127.0.0.1");
        return request;
    }

    private record AsyncFixture(
            MockHttpServletRequest request,
            MockHttpServletResponse response,
            MockAsyncContext context
    ) {
        jakarta.servlet.AsyncListener listener() {
            return context.getListeners().getFirst();
        }
    }

    private record ActiveTaskFixture(
            AsyncFixture async,
            CountDownLatch release
    ) {
    }
}
