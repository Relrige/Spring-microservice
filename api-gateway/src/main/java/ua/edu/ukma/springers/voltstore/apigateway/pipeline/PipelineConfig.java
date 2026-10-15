package ua.edu.ukma.springers.voltstore.apigateway.pipeline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PipelineConfig {

    @Bean
    TrustBoundaryFilterFunctions.FilterSupplier trustBoundaryFilterSupplier() {
        return new TrustBoundaryFilterFunctions.FilterSupplier();
    }
}
