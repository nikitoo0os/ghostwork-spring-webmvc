package io.nikitoo0os.webmvc;

public final class DefaultOperationNameResolver
        implements OperationNameResolver {
    @Override
    public String resolve(RequestMetadata metadata) {
        return metadata.httpMethod() + " " + metadata.uriTemplate();
    }
}
