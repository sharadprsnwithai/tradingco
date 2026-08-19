package com.tradingbot.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.*;

/**
 * Functional WebFlux Routing configuration for Telemetry REST and SSE endpoints.
 */
@Configuration
public class TelemetryRouter {

    /**
     * Defines the functional routing table for all Telemetry REST and SSE endpoints.
     * Maps HTTP methods and paths to TelemetryHandler methods under /api/telemetry.
     *
     * @param handler the telemetry handler providing endpoint implementations
     * @return the router function binding all telemetry routes
     */
    @Bean
    public RouterFunction<ServerResponse> telemetryRoutes(TelemetryHandler handler) {
        return RouterFunctions.route()
            .path("/api/telemetry", builder -> builder
                .GET("/summary", accept(MediaType.APPLICATION_JSON), handler::getSummary)
                .GET("/positions", accept(MediaType.APPLICATION_JSON), handler::getPositions)
                .GET("/orders", accept(MediaType.APPLICATION_JSON), handler::getOrders)
                .GET("/risk", accept(MediaType.APPLICATION_JSON), handler::getRiskLogs)
                .GET("/stream", handler::streamTelemetry)
                .POST("/kill-switch/strategy/{id}", handler::killStrategy)
                .POST("/kill-switch/broker/{id}", handler::freezeBroker)
                .POST("/kill-switch/panic", handler::triggerGlobalPanic)
                .POST("/kill-switch/panic/reset", handler::resetGlobalPanic)
                .POST("/strategy/{id}/toggle", handler::toggleStrategy)
                .POST("/paper-trading/toggle", handler::togglePaperTrading)
            )
            .build();
    }
}
