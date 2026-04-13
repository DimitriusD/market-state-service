plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation("org.springframework.kafka:spring-kafka")
    implementation(libs.schemas)
    implementation(libs.kafkaAvroSerializer)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
}
