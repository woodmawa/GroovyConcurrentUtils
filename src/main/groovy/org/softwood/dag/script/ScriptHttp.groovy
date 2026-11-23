package org.softwood.dag.script

import org.softwood.dag.task.HttpTaskSecurityConfig
import org.softwood.dag.task.TaskContext

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 1.4.0 (decision D2, level 2): what {@code allow 'http'} gives a restricted script, bound as
 * {@code http}.
 *
 * <p>A script still cannot name {@code URL} or an HTTP client. It makes requests through this
 * object, and every address is checked by the {@code HttpTask} guard (1.3.0, D3): public hosts are
 * allowed; private networks and cloud metadata are refused; a local address only when the graph
 * names it with {@code allowLocal 'host:port'}. A refused address is a {@link SecurityException}.</p>
 *
 * <p>Redirects are not followed: a 3xx response is returned as it is, with its {@code location}
 * header, and a script that follows it makes a new request, which is checked again.</p>
 *
 * <p>Each call returns a map: {@code status} (int), {@code body} (text), {@code headers}
 * (lower-case name to the first value).</p>
 *
 * <p>1.4.1: a script reaches only {@code get}, {@code post}, {@code put} and {@code delete}
 * ({@link ScriptCapability}). Before 1.4.1 a script could add a local service to the allowance
 * this object holds, through Groovy property syntax, and then call it.</p>
 */
class ScriptHttp implements ScriptCapability {

    private static final Set<String> OFFERED = Collections.unmodifiableSet(
            new LinkedHashSet<String>(['get', 'post', 'put', 'delete']))

    @Override
    String capabilityName() { 'http' }

    @Override
    Set<String> offeredMethods() { OFFERED }

    private static final Duration TIMEOUT = Duration.ofSeconds(30)

    private final HttpTaskSecurityConfig guard = HttpTaskSecurityConfig.standard()
    private final Collection<String> allowLocal
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(TIMEOUT)
            .build()

    ScriptHttp(TaskContext ctx) {
        this.allowLocal = Collections.unmodifiableList(ctx?.httpAllowLocal != null ? new ArrayList<String>(ctx.httpAllowLocal) : new ArrayList<String>())
    }

    Map<String, Object> get(String url, Map<String, Object> headers = [:]) {
        return send('GET', url, null, headers)
    }

    Map<String, Object> post(String url, String body, Map<String, Object> headers = [:]) {
        return send('POST', url, body, headers)
    }

    Map<String, Object> put(String url, String body, Map<String, Object> headers = [:]) {
        return send('PUT', url, body, headers)
    }

    Map<String, Object> delete(String url, Map<String, Object> headers = [:]) {
        return send('DELETE', url, null, headers)
    }

    private Map<String, Object> send(String method, String url, String body, Map<String, Object> headers) {
        if (!url) throw new IllegalArgumentException('http: a URL is required')
        URI uri = new URI(url)
        guard.checkRequest(uri, allowLocal)

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
        headers?.each { name, value -> request.header(name.toString(), String.valueOf(value)) }
        HttpRequest.BodyPublisher publisher = body != null ?
                HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody()
        request.method(method, publisher)

        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        Map<String, Object> found = [:]
        response.headers().map().each { String name, List<String> values ->
            if (values) found[name.toLowerCase()] = values[0]
        }
        return [status: response.statusCode(), body: response.body(), headers: found] as Map<String, Object>
    }
}
