plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation(libs.springBootStarterDataJdbc)
    implementation(libs.flywayCore)
    implementation(libs.jacksonDatabind)
    implementation(libs.mapstruct)
    implementation(libs.postgresql)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
    annotationProcessor(libs.mapstructProcessor)

    testImplementation(libs.springBootStarterTest)
    testImplementation(libs.springBootStarterDataJdbc)
    testImplementation(platform(libs.testcontainersBom))
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgres)
    testImplementation(libs.flywayDatabasePostgresql)

    testRuntimeOnly(libs.junitPlatformLauncher)
}
