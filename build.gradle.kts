plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.tradingbot"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot & Reactive WebFlux
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Project Reactor & Reactive Extensions
    implementation("io.projectreactor:reactor-core")
    implementation("io.projectreactor.addons:reactor-extra")
    implementation("io.projectreactor.netty:reactor-netty")

    // Resilience4j Reactive (CircuitBreaker, RateLimiter, Bulkhead, Retry)
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-reactor:2.2.0")

    // R2DBC & Database Drivers
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    runtimeOnly("org.postgresql:r2dbc-postgresql:1.0.7.RELEASE")
    runtimeOnly("org.postgresql:postgresql:42.7.4")
    runtimeOnly("io.r2dbc:r2dbc-h2:1.0.0.RELEASE")

    // JSON Processing
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // TOTP & Cryptography (for headless auth & Shoonya SHA-256)
    implementation("com.warrenstrange:googleauth:1.5.0")
    implementation("commons-codec:commons-codec:1.17.1")

    // SQLite JDBC for disk-backed instrument master cache
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")

    // Zerodha Kite Connect SDK (KiteTicker WebSocket for real market data)
    implementation("com.zerodhatech.kiteconnect:kiteconnect:3.5.0")

    // TA-Lib (Technical Analysis Library - Java wrapper)
    implementation("com.tictactec:ta-lib:0.4.0")

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.register<JavaExec>("backtest") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.BacktestRunner")
}

tasks.register<JavaExec>("shoonyaBacktest") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.ShoonyaBacktestRunner")
}

tasks.register<JavaExec>("findTokens") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.ShoonyaTokenFinder")
}

tasks.register<JavaExec>("backtestLvr") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.LowestVolumeReversalBacktestRunner")
}

tasks.register<JavaExec>("backtestVwap") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.VwapBacktestRunner")
}

tasks.register<JavaExec>("backtestIronFly") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.IronFlyBacktestRunner")
}

tasks.register<JavaExec>("backtestStIntraday") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.tradingbot.backtest.IntradayTrendMomentumBacktestRunner")
}
