package ua.edu.ukma.springers.voltstore.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param timeoutDelay how long the mock waits before reporting a timeout for the "lost answer" card
 */
@ConfigurationProperties(prefix = "payment.gateway.mock")
public record MockGatewayProperties(@DefaultValue("2s") Duration timeoutDelay) {
}
