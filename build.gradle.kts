import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension

group = "com.trading"
version = "0.1.0-SNAPSHOT"

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven {
            url = uri("https://packages.confluent.io/maven/")
        }
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

tasks.wrapper {
    gradleVersion = "9.2.1"
    distributionType = Wrapper.DistributionType.BIN
}
