package ua.edu.ukma.springers.voltstore.notification.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Mailpit (the SMTP server of Compose and Kubernetes, spec D4) with helpers for its REST API, so tests check the
 * emails that really arrived. Same image tag as docker-compose.yml and k8s/notification.
 */
public class MailpitContainer extends GenericContainer<MailpitContainer> {
    public static final String IMAGE = "axllent/mailpit:v1.27.10";
    public static final int SMTP_PORT = 1025;
    public static final int API_PORT = 8025;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Starts Mailpit in a Spring test context and points {@code spring.mail.*} at it. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        MailpitContainer mailpitContainer() {
            return new MailpitContainer();
        }

        @Bean
        DynamicPropertyRegistrar mailpitProperties(MailpitContainer mailpit) {
            return registry -> {
                registry.add("spring.mail.host", mailpit::getHost);
                registry.add("spring.mail.port", mailpit::smtpPort);
            };
        }
    }

    public record Message(String id, String from, List<String> to, String subject) {
    }

    public MailpitContainer() {
        super(DockerImageName.parse(IMAGE));
        withExposedPorts(SMTP_PORT, API_PORT);
        waitingFor(Wait.forHttp("/livez").forPort(API_PORT));
    }

    public int smtpPort() {
        return getMappedPort(SMTP_PORT);
    }

    /** Every message in the mailbox, newest first. */
    public List<Message> messages() {
        JsonNode root = get("/api/v1/messages?limit=500");
        List<Message> messages = new ArrayList<>();
        for (JsonNode message : root.path("messages")) {
            List<String> to = new ArrayList<>();
            message.path("To").forEach(address -> to.add(address.path("Address").asString()));
            messages.add(new Message(message.path("ID").asString(), message.path("From").path("Address").asString(),
                    to, message.path("Subject").asString()));
        }
        return messages;
    }

    public List<Message> messagesTo(String address) {
        return messages().stream().filter(message -> message.to().contains(address)).toList();
    }

    /** The plain-text body of one message, as received, with SMTP's CRLF line ends turned back into LF. */
    public String text(String id) {
        return get("/api/v1/message/" + id).path("Text").asString().replace("\r\n", "\n");
    }

    /** The sender as received, including the display name. */
    public String fromName(String id) {
        return get("/api/v1/message/" + id).path("From").path("Name").asString();
    }

    public void clear() {
        send(HttpRequest.newBuilder(api("/api/v1/messages")).DELETE().build());
    }

    private JsonNode get(String path) {
        return JSON.readTree(send(HttpRequest.newBuilder(api(path)).GET().build()));
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("Mailpit answered " + response.statusCode() + " to " + request.uri());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private URI api(String path) {
        return URI.create("http://" + getHost() + ":" + getMappedPort(API_PORT) + path);
    }
}
