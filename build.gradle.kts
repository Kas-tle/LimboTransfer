plugins {
    `java-library`
}


repositories {
    maven { url = uri("https://repo.loohpjames.com/repository") }
}

dependencies {
    compileOnly(libs.limbo)
}


java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}