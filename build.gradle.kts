plugins {
    `java-library`
}

group = "com.jellyfinvc"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.maxhenkel.de/repository/public")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    expand("version" to project.version)
}

tasks.jar {
    archiveBaseName.set("JellyfinVoiceChat")
    archiveClassifier.set("")
}
