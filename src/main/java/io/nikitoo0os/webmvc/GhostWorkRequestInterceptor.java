package io.nikitoo0os.webmvc;

import io.nikitoo0os.GhostWork;
import io.nikitoo0os.OperationHandle;
import io.nikitoo0os.OperationScope;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GhostWorkRequestInterceptor
        implements AsyncHandlerInterceptor {
    static final String PREFIX =
            GhostWorkRequestInterceptor.class.getName() + ".";
    static final String HANDLE = PREFIX + "handle";
    static final String METADATA = PREFIX + "metadata";
    static final String SCOPE = PREFIX + "scope";
    static final String ASYNC = PREFIX + "async";
    static final String EXCLUDED = PREFIX + "excluded";
    static final String CALLABLE_SCOPE = PREFIX + "callableScope";

    private final GhostWork ghostWork;
    private final OperationNameResolver nameResolver;
    private final GhostWorkWebMvcProperties properties;
    private final AntPathMatcher paths = new AntPathMatcher();

    public GhostWorkRequestInterceptor(
            GhostWork ghostWork,
            OperationNameResolver nameResolver,
            GhostWorkWebMvcProperties properties
    ) {
        this.ghostWork = Objects.requireNonNull(ghostWork);
        this.nameResolver = Objects.requireNonNull(nameResolver);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (Boolean.TRUE.equals(request.getAttribute(EXCLUDED))) {
            return true;
        }
        String template = uriTemplate(request);
        if (!included(template)) {
            request.setAttribute(EXCLUDED, true);
            return true;
        }

        OperationHandle handle =
                (OperationHandle) request.getAttribute(HANDLE);
        if (handle == null) {
            RequestMetadata metadata = metadata(request, template);
            handle = ghostWork.startOperation(
                    nameResolver.resolve(metadata),
                    metadata
            );
            request.setAttribute(HANDLE, handle);
            request.setAttribute(METADATA, metadata);
            WebAsyncUtils.getAsyncManager(request)
                    .registerCallableInterceptor(
                            CALLABLE_SCOPE,
                            new OperationCallableInterceptor(handle)
                    );
        }
        closeScope(request);
        request.setAttribute(SCOPE, handle.openScope());
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        closeScope(request);
        OperationHandle handle = handle(request);
        if (handle == null || !request.isAsyncStarted()) {
            return;
        }
        request.setAttribute(ASYNC, true);
        RequestMetadata metadata = metadata(request).asAsync();
        request.setAttribute(METADATA, metadata);
        handle.updateMetadata(metadata);
        request.getAsyncContext().addListener(
                new RequestAsyncListener(request, response, handle),
                request,
                response
        );
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception
    ) {
        closeScope(request);
        OperationHandle handle = handle(request);
        if (handle == null || Boolean.TRUE.equals(request.getAttribute(ASYNC))) {
            return;
        }
        completeMetadata(request, response, false);
        if (exception == null && response.getStatus() >= 500) {
            handle.fail(new IllegalStateException(
                    "HTTP response status " + response.getStatus()
            ));
        } else {
            finish(handle, exception);
        }
    }

    private void finish(OperationHandle handle, Throwable failure) {
        if (failure == null) {
            handle.complete();
        } else if (isClientAbort(failure)) {
            handle.abort(failure);
        } else {
            handle.fail(failure);
        }
    }

    private void completeMetadata(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean async
    ) {
        OperationHandle handle = handle(request);
        if (handle == null) {
            return;
        }
        RequestMetadata completed = metadata(request).complete(
                Instant.now(),
                response.getStatus(),
                async
        );
        request.setAttribute(METADATA, completed);
        handle.updateMetadata(completed);
    }

    private RequestMetadata metadata(
            HttpServletRequest request,
            String template
    ) {
        String configuredHeader = properties.getRequestIdHeader();
        String requestId = configuredHeader == null
                ? null
                : request.getHeader(configuredHeader);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        Principal principal = request.getUserPrincipal();
        var session = request.getSession(false);
        return new RequestMetadata(
                request.getMethod(),
                template,
                request.getRemoteAddr(),
                properties.isIncludeQueryString()
                        ? request.getQueryString()
                        : null,
                requestId,
                session == null ? null : session.getId(),
                principal == null ? null : principal.getName(),
                Instant.now(),
                null,
                null,
                null,
                false
        );
    }

    private static RequestMetadata metadata(HttpServletRequest request) {
        return (RequestMetadata) request.getAttribute(METADATA);
    }

    private static OperationHandle handle(HttpServletRequest request) {
        return (OperationHandle) request.getAttribute(HANDLE);
    }

    private static void closeScope(HttpServletRequest request) {
        OperationScope scope =
                (OperationScope) request.getAttribute(SCOPE);
        if (scope != null) {
            scope.close();
            request.removeAttribute(SCOPE);
        }
    }

    private static String uriTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE
        );
        return pattern == null ? request.getRequestURI() : pattern.toString();
    }

    private boolean included(String path) {
        boolean included = properties.getInclude().isEmpty()
                || properties.getInclude().stream()
                .anyMatch(pattern -> paths.match(pattern, path));
        return included && properties.getExclude().stream()
                .noneMatch(pattern -> paths.match(pattern, path));
    }

    private static boolean isClientAbort(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class OperationCallableInterceptor
            implements CallableProcessingInterceptor {
        private final OperationHandle handle;
        private final ThreadLocal<OperationScope> scope = new ThreadLocal<>();

        private OperationCallableInterceptor(OperationHandle handle) {
            this.handle = handle;
        }

        @Override
        public <T> void preProcess(
                NativeWebRequest request,
                Callable<T> task
        ) {
            closeScope();
            scope.set(handle.openScope());
        }

        @Override
        public <T> void postProcess(
                NativeWebRequest request,
                Callable<T> task,
                Object concurrentResult
        ) {
            closeScope();
        }

        private void closeScope() {
            OperationScope current = scope.get();
            if (current != null) {
                current.close();
                scope.remove();
            }
        }
    }

    private final class RequestAsyncListener implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final OperationHandle handle;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private RequestAsyncListener(
                HttpServletRequest request,
                HttpServletResponse response,
                OperationHandle handle
        ) {
            this.request = request;
            this.response = response;
            this.handle = handle;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            completeMetadata(request, response, true);
            if (response.getStatus() >= 500) {
                handle.fail(new IllegalStateException(
                        "HTTP response status " + response.getStatus()
                ));
            } else {
                handle.complete();
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            completeMetadata(request, response, true);
            handle.timeout();
        }

        @Override
        public void onError(AsyncEvent event) {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            completeMetadata(request, response, true);
            Throwable failure = event.getThrowable();
            if (isClientAbort(failure)) {
                handle.abort(failure);
            } else {
                handle.fail(failure);
            }
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(
                    this,
                    request,
                    response
            );
        }
    }
}
