# Cantus
<img align="right" src="https://muppetmindset.files.wordpress.com/2014/06/cantus.png?w=300&h=274">

Cantus is a service for integration with docker registry.

The component is named after Cantus the Minstrel from the TV-show Fraggle Rock (http://muppet.wikia.com/wiki/Cantus_the_Minstrel).

To be able to run and make calls to Cantus you have to define variables in the file .spring-boot-devtools.properties located in the home folder.

The variables needed are:
- cantus.docker.urlsallowed = localhost, test (whitelist for docker registry urls)
- cantus.docker.internal.urls = localhost (Cantus adds token to header if internal and uses https if it is not internal)

 ## Setup
 
 In order to use this project you must set repositories in your `~/.gradle/init.gradle` file
 
     allprojects {
         ext.repos= {
             mavenCentral()
             jcenter()
         }
         repositories repos
         buildscript {
          repositories repos
         }
     }

We use a local repository for distributionUrl in our gradle-wrapper.properties, you need to change it to a public repo in order to use the gradlew command. `../gradle/wrapper/gradle-wrapper.properties`

    <...>
    distributionUrl=https\://services.gradle.org/distributions/gradle-<version>-bin.zip
    <...>
