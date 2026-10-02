plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

group = "com.ramiart"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(enforcedPlatform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.10.0"))
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<JavaExec>("localDatabase") {
    group = "application"
    description = "Starts an isolated PostgreSQL 17 database with current migrations and seed data"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.ramiart.admin.dev.LocalDatabaseLauncher"
    workingDir = rootProject.projectDir.parentFile
}
