package io.nikitoo0os.webmvc;

@FunctionalInterface
public interface OperationNameResolver {
    String resolve(RequestMetadata metadata);
}
