export MAVEN_OPTS="-Xms1G -Xmx2G -XX:PermSize=1G -javaagent:/home/olej/Downloads/springloaded-1.2.0.RELEASE.jar -noverify"
mvn clean install -Dmodule.log.level=DEBUG -Pamp-to-war
