#!/usr/bin/bash

export JAVA_HOME="C:\Program Files\Java\jdk1.8.0_231"

export PATH="/c/Program Files/Apache/Maven/apache-maven-3.5.4/bin:$PATH"

mvn -v

cd preload
mvn install

cd ..
mvn clean install -DskipTests=true
