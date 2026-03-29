plugins {
    `java-library`
}

dependencies {
    api(libs.slf4jApi)
    implementation("org.springframework:spring-tx:6.1.14")

    compileOnly(libs.jakartaValidationApi)
    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)

    testImplementation(libs.junitJupiter)
}
