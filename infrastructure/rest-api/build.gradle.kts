import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    `java-library`
    alias(libs.plugins.openapiGenerator)
}

dependencies {
    implementation(platform(libs.springBom))
    implementation(project(":application"))
    implementation(libs.springWeb)
    implementation(libs.springContext)
    implementation(libs.jakartaValidationApi)
    implementation(libs.jacksonDatabind)
    implementation(libs.openapiJacksonNullable)
    implementation(libs.swaggerAnnotations)
    implementation(libs.mapstruct)

    compileOnly(libs.lombok)
    compileOnly(libs.jakartaAnnotationApi)
    compileOnly(libs.jakartaServletApi)

    annotationProcessor(libs.mapstructProcessor)
    annotationProcessor(libs.lombok)
}

val openApiGeneratedDir = layout.buildDirectory.dir("generated/openapi")

tasks.named<GenerateTask>("openApiGenerate") {
    generatorName.set("spring")
    inputSpec.set("$projectDir/src/main/resources/openapi/openapi.yaml")
    outputDir.set(openApiGeneratedDir.get().asFile.absolutePath)
    apiPackage.set("com.company.service.restapi.generated.api")
    modelPackage.set("com.company.service.restapi.generated.model")
    modelNameSuffix.set("WebDto")
    configOptions.set(
        mapOf(
            "interfaceOnly" to "true",
            "useSpringBoot3" to "true",
            "dateLibrary" to "java8",
            "useTags" to "true",
            "skipDefaultInterface" to "true",
            "useResponseEntity" to "false"
        )
    )
}

sourceSets {
    main {
        java {
            srcDir(openApiGeneratedDir.map { it.dir("src/main/java") })
        }
    }
}

tasks.named("compileJava") {
    dependsOn("openApiGenerate")
}
