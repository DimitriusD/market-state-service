plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    java
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infrastructure:rest-api"))
    implementation(project(":infrastructure:jdbc-storage-adapter"))
    implementation(project(":infrastructure:event-adapter"))
    implementation(libs.springBootStarterWeb)
    implementation("org.springframework.kafka:spring-kafka")
    implementation(libs.springBootStarterActuator)
    implementation(libs.springBootStarterValidation)
    implementation(libs.springBootStarterDataJdbc)
    implementation(libs.flywayCore)
    implementation(libs.flywayDatabasePostgresql)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstructProcessor)

    testImplementation(libs.springBootStarterTest)
    testImplementation(libs.springBootStarterDataJdbc)
    testImplementation(platform(libs.testcontainersBom))
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgres)

    testRuntimeOnly(libs.junitPlatformLauncher)
}
