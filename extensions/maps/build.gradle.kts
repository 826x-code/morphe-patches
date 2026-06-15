import com.android.build.api.dsl.ApplicationExtension

dependencies {
    // Pulls in the shared GmsCore extension (app/morphe/extension/shared/...).
    compileOnly(project(":extensions:shared:library"))
    compileOnly(libs.morphe.extensions.library)
}

configure<ApplicationExtension> {
    compileSdk = 36
    defaultConfig {
        minSdk = 28
    }
}
