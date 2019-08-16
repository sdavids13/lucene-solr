import org.apache.lucene.gradle.MissingDeps
import org.apache.lucene.gradle.PartOfDist

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  `java-library`
  `maven-publish`
}

apply<org.apache.lucene.gradle.PartOfDist>()

dependencies {

  api ("org.apache.zookeeper:zookeeper")
  api("org.apache.zookeeper:zookeeper-jute")
  
  runtimeOnly("org.slf4j:log4j-over-slf4j") // bridge for deps that use log4j12 directly
  runtimeOnly("org.slf4j:jcl-over-slf4j") // bridge for jcl
  runtimeOnly("io.netty:netty-all") // used by zookeeper
  
  implementation("org.slf4j:slf4j-api")
  implementation("org.apache.httpcomponents:httpclient")
  implementation("org.apache.httpcomponents:httpmime")
  implementation("org.apache.httpcomponents:httpcore")
  implementation("commons-io:commons-io")
  implementation("org.apache.commons:commons-math3")
  implementation("org.eclipse.jetty.http2:http2-client")
  implementation("org.eclipse.jetty.http2:http2-http-client-transport")
  implementation("org.eclipse.jetty:jetty-util")
  implementation("org.eclipse.jetty:jetty-http")
  
  testImplementation("org.eclipse.jetty:jetty-servlet")
  testImplementation("io.dropwizard.metrics:metrics-core")
  testImplementation("org.restlet.jee:org.restlet")
  testImplementation("org.restlet.jee:org.restlet.ext.servlet")
  testImplementation("org.eclipse.jetty:jetty-webapp")
  testImplementation("org.eclipse.jetty:jetty-server")
  testImplementation("org.eclipse.jetty:jetty-xml")
  testImplementation("commons-collections:commons-collections")
  testImplementation("com.google.guava:guava")
  testImplementation("org.apache.commons:commons-compress")
  testImplementation("org.mockito:mockito-core")
  testImplementation("net.bytebuddy:byte-buddy")
  testImplementation("org.objenesis:objenesis")
  testImplementation("org.apache.commons:commons-lang3")
  testImplementation("javax.servlet:javax.servlet-api")
  
  testImplementation(project(":solr:solr-core"))
  testImplementation(project(":lucene:lucene-core"))
  testImplementation(project(":solr:solr-test-framework"))
  testImplementation(project(":lucene:lucene-test-framework"))
  testImplementation(project(":lucene:analysis:lucene-analyzers-common"))
  testImplementation(project(":solr:example:solr-example-DIH"))
}

tasks.named<MissingDeps>("missingDeps") {
  foundInClassExclude( "io\\.netty\\.handler\\.ssl\\..*") // zookeeper brings netty-all and ssl stuff we don"t use
  foundInClassExclude("io\\.netty\\.util\\.internal\\.logging\\.Log4J2Logger.*") // netty dependency on log4j2
  
  // annotations, mostly from zk
  classExclude("org\\.apache\\.yetus\\.audience\\.InterfaceAudience.*")
  classExclude("javax\\.annotation\\..*")
  classExclude("com\\.google\\.errorprone\\.annotations\\..*")
  
  // we are a lib and don"t distribute logging impl - dont export these excludes to other modules
  classExclude(false, "org.slf4j.impl.StaticLoggerBinder")
  classExclude(false, "org.slf4j.impl.StaticMDCBinder")
  classExclude(false, "org.slf4j.impl.StaticMarkerBinder")
}
