FROM frolvlad/alpine-oraclejdk8
WORKDIR /web
ADD target/WebSSH.jar ./app.jar
EXPOSE 8080
ENTRYPOINT [ "sh", "-c", "java -jar /web/app.jar" ]
CMD ["-Xmx512M","-Xms256M","-Xmn192M","-XX:MaxMetaspaceSize=192M","-XX:MetaspaceSize=192M"]
