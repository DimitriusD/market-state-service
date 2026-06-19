plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation(libs.springWeb)
    implementation(libs.jacksonDatabind)
    implementation(libs.resilience4jCircuitBreaker)
    implementation(libs.resilience4jRateLimiter)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)

    testImplementation(libs.junitJupiter)

    testRuntimeOnly(libs.junitPlatformLauncher)
}
