plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation(libs.springWeb)
    implementation(libs.jacksonDatabind)

    compileOnly(libs.lombok)

    annotationProcessor(libs.lombok)
}
