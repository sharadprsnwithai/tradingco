plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    application
}

application {
    mainClass = "com.trading.bot.broker.TradingBotApplication"
}

dependencies {
    implementation(project(":notifications"))
    runtimeOnly(project(":strategy"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("dev.samstevens.totp:totp:1.7.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

