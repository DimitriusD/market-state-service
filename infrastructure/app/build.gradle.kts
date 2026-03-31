plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    java
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infrastructure:kafka-adapter"))
    implementation(project(":infrastructure:binance-snapshot-adapter"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation(libs.springBootStarterActuator)
    implementation(libs.springWeb)
    implementation(libs.jacksonDatabind)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstructProcessor)

    testImplementation(libs.springBootStarterTest)

    testRuntimeOnly(libs.junitPlatformLauncher)
}
