import com.diffplug.gradle.spotless.SpotlessExtension
import com.github.spotbugs.snom.SpotBugsExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.PmdExtension

plugins {
    base
    id("com.diffplug.spotless") version "8.10.4"
    id("org.sonarqube") version "7.5.0.8588"
    id("com.github.spotbugs") version "6.5.13" apply false
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

spotless {
    java {
        // Root has no java plugin; no sources to format here, subprojects cover the Java code.
        target("src/*/java/**/*.java")
        googleJavaFormat()
    }
}

// SonarQube is dormant: configured only from env, `sonar` skips cleanly without it.
val sonarHostUrl: String? = System.getenv("SONAR_HOST_URL")
val sonarToken: String? = System.getenv("SONAR_TOKEN")

sonar {
    properties {
        if (!sonarHostUrl.isNullOrBlank()) {
            property("sonar.host.url", sonarHostUrl)
        }
        if (!sonarToken.isNullOrBlank()) {
            property("sonar.token", sonarToken)
        }
    }
}

tasks.named("sonar") {
    onlyIf("SONAR_HOST_URL is not set") { !sonarHostUrl.isNullOrBlank() }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<SpotlessExtension> {
        java {
            googleJavaFormat()
        }
    }

    plugins.withType<JavaPlugin> {
        apply(plugin = "pmd")
        apply(plugin = "checkstyle")
        apply(plugin = "com.github.spotbugs")
        apply(plugin = "jacoco")

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        configure<PmdExtension> {
            ruleSetFiles = rootProject.files("config/quality/pmd-ruleset.xml")
            ruleSets = listOf()
        }

        configure<CheckstyleExtension> {
            configFile = rootProject.file("config/quality/checkstyle.xml")
        }

        configure<SpotBugsExtension> {
            excludeFilter = rootProject.file("config/quality/spotbugs-exclude.xml")
        }

        dependencies {
            "compileOnly"("org.projectlombok:lombok:1.18.48")
            "annotationProcessor"("org.projectlombok:lombok:1.18.48")
            "testImplementation"("org.junit.jupiter:junit-jupiter:6.0.3")
            "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:6.0.3")
        }

        // Quality gate scope: SpotBugs analyzes main classes only (spec §11).
        tasks.matching { it.name == "spotbugsTest" }.configureEach {
            enabled = false
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
