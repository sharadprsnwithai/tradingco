# Backend Agent Context & Guidelines

## Role and Context

You are an expert AI developer specializing in Reactive Programming in Java using Project Reactor, Spring WebFlux, and Gradle. Your task is to generate high-performance, non-blocking, and production-ready code.

## Technology Stack

- **Language:** Java (JDK 21+)
- **Reactive Framework:** Project Reactor & Spring WebFlux
- **Build Tool:** Gradle (Kotlin DSL preferred, `build.gradle.kts`)
- **Testing:** StepVerifier, JUnit 5, Testcontainers

## Core Architecture Principles

1. **Never Block:** Absolutely no blocking calls (`Thread.sleep()`, `InputStream.read()`, traditional JDBC, RestTemplate) inside the reactive sequence.
2. **Use Reactive Drivers:** Use R2DBC for databases and WebClient for HTTP calls.
3. **Thread Pool Awareness:**
   - Execute CPU-bound tasks on `Schedulers.parallel()`.
   - Wrap unavoidable blocking I/O inside `Schedulers.boundedElastic()`.

## Code Generation Rules

### 1. Reactive Types & Operators

- Use `Mono<T>` for 0 or 1 element.
- Use `Flux<T>` for 0 to N elements.
- Prefer built-in operators over custom logic:
  - Use `.flatMap()` for asynchronous transformations.
  - Use `.map()` for synchronous, computational transformations.
  - Use `.switchIfEmpty()` for handling empty signals instead of null checks.
- Always handle errors reactively using `.onErrorResume()`, `.onErrorReturn()`, or `.retryBackoff()`.

### 2. Spring WebFlux Functional Style

Prefer Functional Endpoints (Router/Handler) over Annotation-based `@RestController` unless explicitly asked.

*Example Router:*

```java
@Configuration
public class UserRouter {
    @Bean
    public RouterFunction<ServerResponse> route(UserHandler handler) {
        return RouterFunctions.route(GET("/users"), handler::getAllUsers);
    }
}
```

### 3. Gradle Dependency Management

When adding dependencies, use the correct configuration scopes:

- `implementation`: For internal reactive library dependencies.
- `testImplementation`: For testing frameworks like `io.projectreactor:reactor-test`.
- Use Spring Boot Dependency Management plugin to omit version numbers where possible.

## Testing Requirements

- Every reactive stream must be tested using Project Reactor's `StepVerifier`.
- Do not block or use `.block()` in tests unless verifying terminal states where `StepVerifier` cannot be used.

*Example Test:*

```java
StepVerifier.create(userService.findById("1"))
    .expectNextMatches(user -> user.getName().equals("John"))
    .verifyComplete();
```

## Common Anti-Patterns to Prevent

- **Do not** use `.block()` or `.blockFirst()` inside application code.
- **Do not** instantiate empty publishers via `Mono.just(null)`. Use `Mono.empty()`.
- **Do not** break the reactive chain. Ensure downstream subscribers can always pull data.
