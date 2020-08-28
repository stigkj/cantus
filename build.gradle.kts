plugins {
    id("org.springframework.cloud.contract")

    id("org.jetbrains.kotlin.jvm") version "1.3.72"
    id("org.jetbrains.kotlin.plugin.spring") version "1.3.72"

    id("org.jlleitschuh.gradle.ktlint") version "9.3.0"
    id("org.sonarqube") version "3.0"

    id("org.springframework.boot") version "2.3.3.RELEASE"

    id("com.gorylenko.gradle-git-properties") version "2.2.2"
    id("com.github.ben-manes.versions") version "0.29.0"
    id("se.patrikerdes.use-latest-versions") version "0.2.14"

    id("no.skatteetaten.gradle.aurora") version "3.6.6"
}

dependencies {

    implementation("uk.q3c.rest:hal-kotlin:0.5.4.0.db32476")

    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.0.2.RELEASE")
    implementation("io.projectreactor.addons:reactor-extra:3.3.3.RELEASE")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:1.3.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.9")

    // Testing
    testImplementation("no.skatteetaten.aurora:mockmvc-extensions-kotlin:1.1.4")
    testImplementation("io.mockk:mockk:1.10.0")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.22")
    testImplementation("com.ninja-squad:springmockk:2.0.3")

    // Spring
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
