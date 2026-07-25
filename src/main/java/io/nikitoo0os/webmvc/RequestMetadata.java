package io.nikitoo0os.webmvc;

import io.nikitoo0os.OperationMetadata;

import java.time.Duration;
import java.time.Instant;

public record RequestMetadata(
        String httpMethod,
        String uriTemplate,
        String remoteAddress,
        String queryString,
        String requestId,
        String sessionId,
        String principalName,
        Instant startedAt,
        Instant completedAt,
        Duration duration,
        Integer responseStatus,
        boolean async
) implements OperationMetadata {

    public RequestMetadata complete(
            Instant completedAt,
            int responseStatus,
            boolean async
    ) {
        return new RequestMetadata(
                httpMethod,
                uriTemplate,
                remoteAddress,
                queryString,
                requestId,
                sessionId,
                principalName,
                startedAt,
                completedAt,
                Duration.between(startedAt, completedAt),
                responseStatus,
                async
        );
    }

    public RequestMetadata asAsync() {
        return new RequestMetadata(
                httpMethod, uriTemplate, remoteAddress, queryString,
                requestId, sessionId, principalName, startedAt,
                completedAt, duration, responseStatus, true
        );
    }
}
