package io.nikitoo0os.webmvc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("ghostwork.web")
public class GhostWorkWebMvcProperties {
    private boolean enabled = true;
    private boolean includeQueryString;
    private String requestIdHeader = "X-Request-ID";
    private String correlationIdHeader = "X-Correlation-ID";
    private boolean generateCorrelationId = true;
    private List<String> include = new ArrayList<>();
    private List<String> exclude = new ArrayList<>(List.of(
            "/actuator/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/favicon.ico"
    ));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isIncludeQueryString() { return includeQueryString; }
    public void setIncludeQueryString(boolean value) { includeQueryString = value; }
    public String getRequestIdHeader() { return requestIdHeader; }
    public void setRequestIdHeader(String value) { requestIdHeader = value; }
    public String getCorrelationIdHeader() { return correlationIdHeader; }
    public void setCorrelationIdHeader(String value) { correlationIdHeader = value; }
    public boolean isGenerateCorrelationId() { return generateCorrelationId; }
    public void setGenerateCorrelationId(boolean value) { generateCorrelationId = value; }
    public List<String> getInclude() { return List.copyOf(include); }
    public void setInclude(List<String> value) {
        include = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
    public List<String> getExclude() { return List.copyOf(exclude); }
    public void setExclude(List<String> value) {
        exclude = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
