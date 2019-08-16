/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the 'License'); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  groovy
  java
  `java-gradle-plugin`
}

buildDir = file("build")

group = "org.apache.lucene.gradle"

apply {
  from(file("common/configure-repositories.gradle"))
}

dependencies {
  implementation(gradleApi())
  implementation(localGroovy())
  
  implementation("org.eclipse.jgit:org.eclipse.jgit:4.6.0.201612231935-r")
  implementation("de.thetaphi:forbiddenapis:2.6")
  implementation("com.ibm.icu:icu4j:62.1")
  implementation("org.apache.rat:apache-rat:0.11")
  implementation("junit:junit:4.12")
}

val rat by configurations.creating
val junit4 by configurations.creating
val javacc by configurations.creating
val jflex by configurations.creating {
  extendsFrom(configurations["implementation"])
}

dependencies {
  jflex("de.jflex:jflex:1.7.0")
  rat("org.apache.rat:apache-rat:0.11")
  junit4("com.carrotsearch.randomizedtesting:junit4-ant")
  javacc("net.java.dev.javacc:javacc:5.0")
}

sourceSets {
  create("buildTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output

    java.srcDir("src/buildTest/java")
    resources.srcDir("src/buildTest/resources")
  }
}

tasks.withType(JavaCompile::class) {
  options.encoding = "UTF-8"
  sourceCompatibility = "11"
  targetCompatibility = "11"
}

configurations["buildTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["buildTestRuntime"].extendsFrom(configurations.testImplementation.get())
configurations["buildTestRuntimeOnly"].extendsFrom(configurations.testImplementation.get())

task<Test>("buildTest") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Runs the build tests (if using a unix env and docker is available). Ideally, run pristineClean first."

  maxHeapSize = "1024m"

  testClassesDirs = sourceSets["buildTest"].output.classesDirs
  classpath = sourceSets["buildTest"].runtimeClasspath

  outputs.upToDateWhen { false }
  mustRunAfter(tasks.test)
  dependsOn(project.rootProject.tasks["clean"])
}
