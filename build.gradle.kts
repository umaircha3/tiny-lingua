/*
 * Copyright © 2018-today Peter M. Stahl pemistahl@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.adarshr.gradle.testlogger.theme.ThemeType
import com.github.jengelman.gradle.plugins.shadow.tasks.ConfigureShadowRelocation
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import ru.vyarus.gradle.plugin.python.task.PythonTask
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale

val linguaTaskGroup: String by project
val linguaGroupId: String by project
val linguaArtifactId: String by project
val projectVersion: String by project
// Version of the upstream Lingua project on which this project is based
val upstreamProjectVersion: String by project
val linguaName: String by project
val linguaDescription: String by project
val linguaLicenseName: String by project
val linguaLicenseUrl: String by project
val linguaWebsiteUrl: String by project
val linguaScmConnection: String by project
val linguaScmDeveloperConnection: String by project
val linguaScmUrl: String by project
val linguaSupportedDetectors: String by project
val linguaSupportedLanguages: String by project
val linguaMainClass: String by project
val linguaCsvHeader: String by project

val compileTestKotlin: KotlinCompile by tasks

group = linguaGroupId
version = "$projectVersion-L$upstreamProjectVersion"
description = linguaDescription

plugins {
    kotlin("jvm") version libs.versions.kotlinPlugin.get()
    id("org.jlleitschuh.gradle.ktlint") version "10.3.0"
    id("com.adarshr.test-logger") version "3.2.0"
    id("com.asarkar.gradle.build-time-tracker") version "3.0.1"
    id("org.jetbrains.dokka") version "1.7.20"
    id("ru.vyarus.use-python") version "2.3.0"
    id("org.moditect.gradleplugin") version "1.0.0-rc3"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("io.github.gradle-nexus.publish-plugin") version "1.1.0"
    `maven-publish`
    signing
    jacoco
}

jacoco.toolVersion = "0.8.8"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get()))
    }
}

val targetJdkVersion = libs.versions.targetJdk.get()
tasks.withType<KotlinCompile> {
    kotlinOptions {
        // TODO: Does not work yet due to https://youtrack.jetbrains.com/issue/KT-52823
        // @Suppress("SuspiciousCollectionReassignment")
        // freeCompilerArgs += listOf("-Xjdk-release=$targetJdkVersion")
        jvmTarget = targetJdkVersion
    }
}

sourceSets {
    main {
        resources {
            exclude("training-data/**")
        }
    }
    create("accuracyReport") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

val accuracyReportImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}

configurations["accuracyReportRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

tasks.withType<Test> {
    useJUnitPlatform { failFast = false }
}

tasks.test {
    maxParallelForks = 1
}

// Suppress warnings about incubating test suites feature
@Suppress("UnstableApiUsage")
testing {
    suites {
        // Separate test suite for module testing
        val testJavaModule by registering(JvmTestSuite::class) {
            dependencies {
                implementation(project)
            }
        }
    }
}

tasks.check {
    @Suppress("UnstableApiUsage")
    dependsOn(testing.suites.named("testJavaModule"))
}

tasks.jacocoTestReport {
    dependsOn("test")
    reports {
        xml.isEnabled = true
        csv.isEnabled = false
        html.isEnabled = true
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude("**/app/**")
                }
            }
        )
    )
}

tasks.register<Test>("accuracyReport") {
    group = linguaTaskGroup
    description = "Runs Lingua on provided test data, and writes detection accuracy reports for each language."
    testClassesDirs = sourceSets["accuracyReport"].output.classesDirs
    classpath = sourceSets["accuracyReport"].runtimeClasspath

    val allowedDetectors = linguaSupportedDetectors.split(',')
    val detectors = if (project.hasProperty("detectors"))
        project.property("detectors").toString().split(Regex("\\s*,\\s*"))
    else allowedDetectors

    detectors.filterNot { it in allowedDetectors }.forEach {
        throw GradleException(
            """
            detector '$it' does not exist
            supported detectors: ${allowedDetectors.joinToString(
                ", "
            )}
            """.trimIndent()
        )
    }

    val allowedLanguages = linguaSupportedLanguages.split(',')
    val languages = if (project.hasProperty("languages"))
        project.property("languages").toString().split(Regex("\\s*,\\s*"))
    else allowedLanguages

    languages.filterNot { it in allowedLanguages }.forEach {
        throw GradleException("language '$it' is not supported")
    }

    val availableCpuCores = Runtime.getRuntime().availableProcessors()
    val cpuCoresRepr = if (project.hasProperty("cpuCores"))
        project.property("cpuCores").toString()
    else "1"

    val cpuCores = try {
        cpuCoresRepr.toInt()
    } catch (e: NumberFormatException) {
        throw GradleException("'$cpuCoresRepr' is not a valid value for argument -PcpuCores")
    }

    if (cpuCores !in 1..availableCpuCores) {
        throw GradleException(
            """
            $cpuCores cpu cores are not supported
            minimum: 1
            maximum: $availableCpuCores
            """.trimIndent()
        )
    }

    maxHeapSize = "4096m"
    maxParallelForks = cpuCores
    reports.html.isEnabled = false
    reports.junitXml.isEnabled = false

    testlogger {
        theme = ThemeType.STANDARD_PARALLEL
        showPassed = false
        showSkipped = false
    }

    filter {
        detectors.forEach { detector ->
            languages.forEach { language ->
                includeTestsMatching(
                    "com.github.pemistahl.lingua.report" +
                        ".${detector.toLowerCase(Locale.ROOT)}.${language}DetectionAccuracyReport"
                )
            }
        }
    }
}

tasks.register("writeAggregatedAccuracyReport") {
    group = linguaTaskGroup
    description = "Creates a table from all accuracy detection reports and writes it to a CSV file."

    doLast {
        val accuracyReportsDirectoryName = "accuracy-reports"
        val accuracyReportsDirectory = file(accuracyReportsDirectoryName)
        if (!accuracyReportsDirectory.exists()) {
            throw GradleException("directory '$accuracyReportsDirectoryName' does not exist")
        }

        val detectors = linguaSupportedDetectors.split(',')
        val languages = linguaSupportedLanguages.split(',')

        val csvFile = file("$accuracyReportsDirectoryName/aggregated-accuracy-values.csv")
        val stringToSplitAt = ">> Exact values:"

        if (csvFile.exists()) csvFile.delete()
        csvFile.createNewFile()
        csvFile.appendText(linguaCsvHeader)
        csvFile.appendText("\n")

        for (language in languages) {
            csvFile.appendText(language)

            for (detector in detectors) {
                val languageReportFileName =
                    "$accuracyReportsDirectoryName/${detector.toLowerCase(Locale.ROOT)}/$language.txt"
                val languageReportFile = file(languageReportFileName)
                val sliceLength = if (detector == "Lingua") (1..8) else (1..4)

                if (languageReportFile.exists()) {
                    for (line in languageReportFile.readLines()) {
                        if (line.startsWith(stringToSplitAt)) {
                            val accuracyValues = line
                                .split(stringToSplitAt)[1]
                                .split(' ')
                                .slice(sliceLength)
                                .joinToString(",")
                            csvFile.appendText(",")
                            csvFile.appendText(accuracyValues)
                        }
                    }
                } else {
                    if (detector == "Lingua") {
                        csvFile.appendText(",NaN,NaN,NaN,NaN,NaN,NaN,NaN,NaN")
                    } else {
                        csvFile.appendText(",NaN,NaN,NaN,NaN")
                    }
                }
            }

            csvFile.appendText("\n")
        }

        println("file 'aggregated-accuracy-values.csv' written successfully")
    }
}

tasks.register<PythonTask>("drawAccuracyPlots") {
    dependsOn("writeAggregatedAccuracyReport")
    group = linguaTaskGroup
    description = "Draws plots showing the results of the accuracy detection reports."
    command = "src/python-scripts/draw_accuracy_plots.py"
}

tasks.register<PythonTask>("writeAccuracyTable") {
    dependsOn("writeAggregatedAccuracyReport")
    group = linguaTaskGroup
    description = "Creates HTML table from all accuracy detection results and writes it to a markdown file."
    command = "src/python-scripts/write_accuracy_table.py"
}

tasks.addMainModuleInfo {
    // Create Multi-Release JAR with Java 9 as lowest version
    jvmVersion.set("9")
    // Overwrite the output JAR file (if any) from a previous Gradle execution
    overwriteExistingFiles.set(true)
    module {
        moduleInfoFile = File("$projectDir/src/main/java-9/module-info.java")
    }

    // Manually specify input; otherwise task seems to be erroneously considered UP-TO-DATE
    // despite the JAR having changed, see https://github.com/moditect/moditect-gradle-plugin/pull/17
    inputs.file(mainModule.get().inputJar)
}
// Workaround to avoid circular dependencies between tasks, see https://github.com/moditect/moditect-gradle-plugin/issues/14
project.afterEvaluate {
    val compileJavaTask = tasks.compileJava.get()
    compileJavaTask.setDependsOn(compileJavaTask.dependsOn - tasks.addDependenciesModuleInfo.get())
}

tasks.withType<DokkaTask>().configureEach {
    dokkaSourceSets.configureEach {
        jdkVersion.set(11) // link against Java 11 documentation
        reportUndocumented.set(false)
        perPackageOption {
            matchingRegex.set(".*\\.(app|internal).*")
            suppress.set(true)
        }
    }
}

tasks.register<Jar>("dokkaJavadocJar") {
    dependsOn("dokkaJavadoc")
    group = "Build"
    description = "Assembles a jar archive containing Javadoc documentation."
    classifier = "javadoc"
    from("$buildDir/dokka/javadoc")
}
tasks.register<Jar>("dokkaHtmlJar") {
    dependsOn("dokkaHtml")
    group = "Build"
    description = "Assembles a jar archive containing Dokka HTML documentation."
    classifier = "dokka-html"
    from("$buildDir/dokka/html")
}

tasks.register<Jar>("sourcesJar") {
    group = "Build"
    description = "Assembles a jar archive containing the main source code."
    classifier = "sources"
    from("src/main/kotlin")
}

tasks.register<ConfigureShadowRelocation>("relocateDependencies") {
    group = "shadow"
    description = "Specifies the ShadowJar task for which to relocate the dependencies."
    target = tasks["jarWithDependencies"] as ShadowJar
}

tasks.register<ShadowJar>("jarWithDependencies") {
    dependsOn("relocateDependencies")
    group = "Build"
    description = "Assembles a jar archive containing the main classes and all external dependencies."
    classifier = "with-dependencies"
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest { attributes("Main-Class" to linguaMainClass) }
}

tasks.register<JavaExec>("runLinguaOnConsole") {
    group = linguaTaskGroup
    description = "Starts a REPL (read-evaluate-print loop) to try Lingua on the command line."
    main = linguaMainClass
    standardInput = System.`in`
    classpath = sourceSets["main"].runtimeClasspath
}

val lingua by configurations.creating {
    // Prevent projects depending on lingua from seeing and using this configuration
    isCanBeConsumed = false
    isVisible = false
    isTransitive = false
}

@Suppress("PropertyName")
val modelOutputDir_ = buildDir.resolve("generated").resolve("language-models")
val createLanguageModels by tasks.registering(GenerateLanguageModelsTask::class) {
    linguaArtifact.set(lingua.singleFile)
    modelOutputDir.set(modelOutputDir_)

    finalizedBy(checkLanguageModelsChecksum)
}
sourceSets.main.get().output.dir(mutableMapOf<String, Any>("builtBy" to createLanguageModels), modelOutputDir_)

// Check whether generated models match expected checksum; this is done mainly to verify that model
// generation is deterministic
val expectedModelsChecksum = "40b984db2677430e3a341796eaecab1cfee605dafd99d6fc5d56c731803e23fb"
// Note: This is a separate task to not cause model generation task to fail, which would require regenerating
// models a second time when checksum becomes outdated
val checkLanguageModelsChecksum by tasks.registering {
    doLast {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val startDir = modelOutputDir_.toPath()
        Files.walk(startDir).use { files ->
            // Sort files because the iteration order is important for checksum creation
            files.filter(Files::isRegularFile).sorted(
                Comparator { a, b ->
                    // Create path strings in OS independent way
                    fun Path.createPathString(): String {
                        return startDir.relativize(this).iterator().asSequence().map(Path::toString).joinToString("/")
                    }

                    val pathA = a.createPathString()
                    val pathB = b.createPathString()
                    return@Comparator pathA.compareTo(pathB)
                }
            ).forEach {
                messageDigest.update(Files.readAllBytes(it))
            }
        }

        val actualChecksum = messageDigest.digest().joinToString("") {
            it.toInt().and(0xFF).toString(16).padStart(2, '0')
        }

        if (actualChecksum != expectedModelsChecksum) {
            throw Exception(
                """
                Language model checksums differ:
                  Expected: $expectedModelsChecksum
                  Actual:   $actualChecksum
                """.trimIndent()
            )
        }
    }
}

dependencies {
    lingua("com.github.pemistahl:lingua:$upstreamProjectVersion")

    implementation(kotlin("stdlib"))
    implementation(libs.fastutil)

    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
    testImplementation("org.assertj:assertj-core:3.23.1")
    testImplementation("io.mockk:mockk:1.12.5")

    accuracyReportImplementation("com.optimaize.languagedetector:language-detector:0.6")
    accuracyReportImplementation("org.apache.opennlp:opennlp-tools:1.9.4")
    accuracyReportImplementation("org.apache.tika:tika-core:2.3.0")
    accuracyReportImplementation("org.apache.tika:tika-langdetect-optimaize:2.3.0")
    accuracyReportImplementation("org.slf4j:slf4j-nop:1.7.36")
}

python {
    pip("matplotlib:3.5.2")
    pip("seaborn:0.11.2")
    pip("pandas:1.4.2")
    pip("numpy:1.22.0")
}

publishing {
    publications {
        create<MavenPublication>("lingua") {
            groupId = linguaGroupId
            artifactId = linguaArtifactId
            version = version

            from(components["kotlin"])

            artifact(tasks["sourcesJar"])
            artifact(tasks["jarWithDependencies"])
            artifact(tasks["dokkaJavadocJar"])
            artifact(tasks["dokkaHtmlJar"])

            pom {
                name.set(linguaName)
                description.set(linguaDescription)
                url.set(linguaWebsiteUrl)

                licenses {
                    license {
                        name.set(linguaLicenseName)
                        url.set(linguaLicenseUrl)
                    }
                }
                scm {
                    connection.set(linguaScmConnection)
                    developerConnection.set(linguaScmDeveloperConnection)
                    url.set(linguaScmUrl)
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

signing {
    sign(publishing.publications["lingua"])
}

repositories {
    mavenCentral()
}

// TODO: Signing is temporarily disabled
tasks.withType<Sign> {
    enabled = false
}
