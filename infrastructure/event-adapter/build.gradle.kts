plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation(libs.jacksonDatabind)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
}
