// ⑥ 치지직 데스크톱 클라이언트: 배포 서버(/api/broadcasts)의 방송 목록을 보고 녹화 MP4를 재생한다.
//   실행:          .\gradlew.bat -p client run --args="--server=http://127.0.0.1:8080"
//   패키지(현재 OS): .\gradlew.bat -p client packageAppImage   → build/jpackage/chzzk-client (Java 런타임 포함)
//   Windows ZIP:    .\gradlew.bat -p client packageWindowsZip  → build/dist/chzzk-client-windows-x64.zip
//   macOS DMG:      ./gradlew -p client packageMacDmg          → build/dist/chzzk-client-macos-<arch>.dmg (macOS에서만)
// jpackage는 실행하는 OS용 패키지만 만든다. 그래서 OS별 패키지는 각 OS에서(또는 GitHub Actions의 OS별 러너에서) 만든다.
import org.gradle.internal.os.OperatingSystem

plugins {
	java
	application
}

group = "com.programmers"
version = "1.0.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

val javafxVersion = "25.0.1"
val currentOs: OperatingSystem = OperatingSystem.current()
val arm64 = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
// OpenJFX는 OS·아키텍처별 jar(네이티브 코드 포함)를 받는다.
val javafxPlatform = when {
	currentOs.isWindows -> "win"
	currentOs.isMacOsX -> if (arm64) "mac-aarch64" else "mac"
	else -> if (arm64) "linux-aarch64" else "linux"
}

dependencies {
	for (module in listOf("base", "graphics", "controls", "media")) {
		implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
	}
	// 목록 API(JSON) 읽기용 작은 라이브러리(의존성 없음)
	implementation("com.fasterxml.jackson.jr:jackson-jr-objects:2.18.2")

	testImplementation(platform("org.junit:junit-bom:5.11.4"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
	mainClass = "com.programmers.chzzk.client.Launcher"
}

tasks.test {
	useJUnitPlatform()
}

tasks.jar {
	archiveFileName = "chzzk-client.jar"
	manifest {
		attributes("Main-Class" to "com.programmers.chzzk.client.Launcher")
	}
}

val jdkHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }
val jpackageInput = layout.buildDirectory.dir("jpackage-input")
val jpackageOutput = layout.buildDirectory.dir("jpackage")
val distDir = layout.buildDirectory.dir("dist")
val archLabel = if (arm64) "arm64" else "x64"

// 앱 jar와 의존 jar(JavaFX 포함)를 jpackage 입력 폴더 하나에 모은다.
val prepareJpackageInput = tasks.register<Sync>("prepareJpackageInput") {
	from(tasks.jar)
	from(configurations.runtimeClasspath)
	into(jpackageInput)
}

fun jpackageArgs(type: String, dest: File): List<String> = listOf(
	"--type", type,
	"--name", "chzzk-client",
	"--app-version", project.version.toString(),
	"--vendor", "chzzk",
	"--input", jpackageInput.get().asFile.absolutePath,
	"--main-jar", "chzzk-client.jar",
	"--main-class", "com.programmers.chzzk.client.Launcher",
	// 앱에 넣을 Java 런타임(jlink). JavaFX는 클래스패스의 jar로 들어간다.
	"--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.xml,jdk.unsupported,jdk.crypto.ec",
	"--jlink-options", "--strip-debug --no-header-files --no-man-pages",
	"--dest", dest.absolutePath,
)

// jpackage는 출력 폴더가 이미 있으면 실패한다. 실행 파일이 읽기 전용이라 Gradle의 Delete로 지운다.
val cleanAppImage = tasks.register<Delete>("cleanAppImage") {
	delete(jpackageOutput)
}

val packageAppImage = tasks.register<Exec>("packageAppImage") {
	group = "distribution"
	description = "현재 OS용 jpackage app-image(Java 런타임 포함)를 만든다."
	dependsOn(prepareJpackageInput, cleanAppImage)
	val output = jpackageOutput.get().asFile
	executable = jdkHome.get().resolve("bin/jpackage").absolutePath
	args(jpackageArgs("app-image", output))
}

tasks.register<Zip>("packageWindowsZip") {
	group = "distribution"
	description = "Windows app-image를 ZIP 하나로 묶는다(압축을 풀고 chzzk-client.exe 실행)."
	dependsOn(packageAppImage)
	onlyIf { currentOs.isWindows }
	from(jpackageOutput)
	archiveFileName = "chzzk-client-windows-$archLabel.zip"
	destinationDirectory = distDir
}

tasks.register<Exec>("packageMacDmg") {
	group = "distribution"
	description = "macOS DMG를 만든다(macOS에서만 동작)."
	dependsOn(prepareJpackageInput)
	onlyIf { currentOs.isMacOsX }
	val output = distDir.get().asFile
	executable = jdkHome.get().resolve("bin/jpackage").absolutePath
	args(jpackageArgs("dmg", output))
	doLast {
		output.listFiles { file -> file.name.endsWith(".dmg") && !file.name.contains("macos-") }?.forEach {
			it.renameTo(output.resolve("chzzk-client-macos-$archLabel.dmg"))
		}
	}
}
