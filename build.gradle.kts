plugins {
    java
}

group = "com.solidandshot"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "Luminol"
        url = uri("https://repo.bacteriawa.com/repository/maven-public/")
    }
}

dependencies {
    compileOnly("me.earthme.luminol:luminol-api:26.2.build.727-stable")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("LightningCrowbar")
}
