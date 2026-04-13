plugins {
    `java-library`
}

dependencies {
    api(libs.slf4jApi)

    compileOnly(libs.jakartaValidationApi)
    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)

    testImplementation(libs.junitJupiter)

    testRuntimeOnly(libs.junitPlatformLauncher)
}
