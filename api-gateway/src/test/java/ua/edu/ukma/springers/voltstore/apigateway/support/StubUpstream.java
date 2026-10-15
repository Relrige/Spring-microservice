package ua.edu.ukma.springers.voltstore.apigateway.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal upstream service for gateway tests: records every request it receives and answers with a configurable
 * response (by default {@code 200 stub:<name>}).
 */
public final class StubUpstream {

    /** A request as the upstream saw it. Header names are lower-case; each maps to all of its values. */
    public record Received(String method, String uri, String body, Map<String, List<String>> headers) {

        public List<String> header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }

    private record Reply(int status, String contentType, String body, Duration delay) {
    }

    private final String name;
    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Reply reply;

    public StubUpstream(String name) {
        this.name = name;
        try {
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body,
                    copy(exchange.getRequestHeaders())));
            Reply current = reply;
            try {
                if (!current.delay().isZero()) {
                    Thread.sleep(current.delay());
                }
                byte[] response = current.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", current.contentType());
                exchange.sendResponseHeaders(current.status(), response.length == 0 ? -1 : response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // the gateway already gave up on this request (timeout test)
            } finally {
                exchange.close();
            }
        });
        reset();
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        received.clear();
        reply = new Reply(200, "text/plain", "stub:" + name, Duration.ZERO);
    }

    public void replyWith(int status, String contentType, String body) {
        reply = new Reply(status, contentType, body, Duration.ZERO);
    }

    public void replyAfter(Duration delay) {
        reply = new Reply(200, "text/plain", "stub:" + name, delay);
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public Received single() {
        assertThat(received).as("requests received by " + name).hasSize(1);
        return received.getFirst();
    }

    private static Map<String, List<String>> copy(Headers headers) {
        Map<String, List<String>> result = new TreeMap<>();
        headers.forEach((key, values) -> result.put(key.toLowerCase(Locale.ROOT), List.copyOf(values)));
        return result;
    }
}
